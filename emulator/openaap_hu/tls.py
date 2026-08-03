# Copyright 2026 The openaap authors.
# Licensed under the Apache License, Version 2.0.

"""TLS driven by hand, head-unit side.

AAP does not run over a TLS socket: it carries TLS records as the payload of
control messages and then marks per-message payloads as ciphertext with a frame
flag. Python's ``ssl`` module supports exactly this through ``wrap_bio``, which
gives a TLS state machine fed from memory buffers instead of a file descriptor.

This mirrors the Kotlin ``AapTlsEngine`` on the phone side. The two never share
code, so a mismatch in how either drives its TLS stack shows up as a failed
handshake in the emulator rather than in a car.
"""

from __future__ import annotations

import logging
import ssl
from dataclasses import dataclass, field
from typing import Optional

from .pki import Credential, describe_certificate

log = logging.getLogger(__name__)


class TrustPolicy:
    """How strictly the emulated head unit inspects the phone's certificate.

    These are the three hypotheses about real hardware that the project needs
    to distinguish, made runnable:

    ``lenient``
        Requests a certificate and accepts whatever arrives. If real head units
        behave this way, a clean-room phone side works with a self-signed
        certificate and the project's central problem evaporates.

    ``strict``
        Pins a CA. A phone whose certificate chains elsewhere is rejected at the
        handshake. This is the pessimistic hypothesis and the one that would
        make a clean-room phone side impossible without vendor key material.

    ``none``
        Never asks for a client certificate at all. Included because it is
        cheap to test and would be the happiest possible outcome.
    """

    LENIENT = "lenient"
    STRICT = "strict"
    NONE = "none"
    ALL = (LENIENT, STRICT, NONE)


@dataclass
class HandshakeOutcome:
    """What the head unit observed, whether or not the handshake succeeded."""

    completed: bool = False
    error: Optional[str] = None
    protocol: Optional[str] = None
    cipher: Optional[str] = None
    peer_certificate: Optional[str] = None
    peer_certificate_presented: bool = False
    rounds: int = 0
    log: list[str] = field(default_factory=list)

    def summary(self) -> str:
        if self.completed:
            return (
                f"handshake OK after {self.rounds} rounds: {self.protocol} / {self.cipher}; "
                f"phone certificate: {self.peer_certificate}"
            )
        return (
            f"handshake FAILED after {self.rounds} rounds: {self.error}; "
            f"phone certificate presented: {self.peer_certificate_presented}"
        )


class HeadUnitTls:
    """The TLS client half of an AAP session.

    The head unit dials the TLS session; the phone answers and is the side
    holding the certificate under scrutiny.
    """

    def __init__(
        self,
        credential: Credential,
        trust_policy: str = TrustPolicy.LENIENT,
        trusted_ca_pem: Optional[bytes] = None,
        maximum_version: Optional[str] = None,
    ) -> None:
        if trust_policy not in TrustPolicy.ALL:
            raise ValueError(f"unknown trust policy: {trust_policy}")
        self.trust_policy = trust_policy
        self.outcome = HandshakeOutcome()

        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        # The phone's certificate carries no hostname that means anything on a
        # USB cable, so hostname checking is always off; whether the *chain* is
        # checked is the interesting variable and is set by the trust policy.
        context.check_hostname = False

        if trust_policy == TrustPolicy.STRICT:
            if not trusted_ca_pem:
                raise ValueError("strict trust policy requires a pinned CA")
            context.verify_mode = ssl.CERT_REQUIRED
            context.load_verify_locations(cadata=trusted_ca_pem.decode("ascii"))
        else:
            context.verify_mode = ssl.CERT_NONE

        if trust_policy != TrustPolicy.NONE:
            cert_path, key_path = credential.write_temp_files()
            context.load_cert_chain(cert_path, key_path)

        if maximum_version == "TLSv1.2":
            # MIB2 hardware is from 2017 and predates TLS 1.3 by years.
            context.maximum_version = ssl.TLSVersion.TLSv1_2
            context.minimum_version = ssl.TLSVersion.TLSv1_2

        self._incoming = ssl.MemoryBIO()
        self._outgoing = ssl.MemoryBIO()
        self._tls = context.wrap_bio(self._incoming, self._outgoing, server_side=False)
        self._closed = False

    @property
    def handshake_complete(self) -> bool:
        return self.outcome.completed

    def begin(self) -> bytes:
        """Produce the ClientHello."""
        return self._pump()

    def handshake(self, inbound: bytes) -> bytes:
        """Feed one message of handshake ciphertext, return what to send back.

        Remains callable after the handshake completes: a TLS 1.3 server emits
        a NewSessionTicket once it has seen the client's Finished, and those
        records share the sequence space with application data. Dropping one
        desynchronises the record layer and surfaces much later as an
        inexplicable decryption failure.
        """
        if inbound:
            self._incoming.write(inbound)
        self.outcome.rounds += 1
        if not self.outcome.completed:
            return self._pump()
        # Post-handshake message: consume it, expect no application data.
        leftover = self._read_application_data()
        if leftover:
            raise ssl.SSLError(
                f"phone sent {len(leftover)} bytes of application data on the handshake path"
            )
        return self._drain_outgoing()

    def wrap(self, plaintext: bytes) -> bytes:
        """Encrypt application data into TLS records."""
        if not self.outcome.completed:
            raise RuntimeError("wrap before handshake completed")
        self._tls.write(plaintext)
        return self._drain_outgoing()

    def unwrap(self, ciphertext: bytes) -> bytes:
        """Decrypt TLS records; returns b"" when only a partial record arrived."""
        if not self.outcome.completed:
            raise RuntimeError("unwrap before handshake completed")
        if ciphertext:
            self._incoming.write(ciphertext)
        return self._read_application_data()

    def close(self) -> bytes:
        """Emit close_notify so the phone sees a clean shutdown."""
        if self._closed:
            return b""
        self._closed = True
        try:
            self._tls.unwrap()
        except (ssl.SSLError, ssl.SSLWantReadError, OSError):
            pass
        return self._drain_outgoing()

    def _pump(self) -> bytes:
        """Advance the handshake as far as the buffered input allows."""
        try:
            self._tls.do_handshake()
        except ssl.SSLWantReadError:
            # Normal: the flight is incomplete and the peer must speak next.
            return self._drain_outgoing()
        except ssl.SSLError as error:
            # Capture the outgoing alert before reporting, so callers can relay
            # it to the phone and the phone learns why it was rejected instead
            # of seeing a bare disconnect.
            self.outcome.error = str(error)
            self.outcome.log.append(f"handshake error: {error}")
            log.warning("TLS handshake failed: %s", error)
            self._drain_outgoing()
            raise

        self.outcome.completed = True
        self.outcome.protocol = self._tls.version()
        cipher = self._tls.cipher()
        self.outcome.cipher = cipher[0] if cipher else None
        peer = self._tls.getpeercert(binary_form=True)
        self.outcome.peer_certificate_presented = peer is not None
        self.outcome.peer_certificate = describe_certificate(peer)
        self.outcome.log.append(
            f"handshake complete: {self.outcome.protocol} / {self.outcome.cipher}"
        )
        log.info("TLS handshake complete: %s", self.outcome.summary())
        return self._drain_outgoing()

    def _read_application_data(self) -> bytes:
        chunks = []
        while True:
            try:
                chunk = self._tls.read(16384)
            except (ssl.SSLWantReadError, ssl.SSLWantWriteError):
                break
            except ssl.SSLZeroReturnError:
                break
            if not chunk:
                break
            chunks.append(chunk)
        return b"".join(chunks)

    def _drain_outgoing(self) -> bytes:
        return self._outgoing.read()
