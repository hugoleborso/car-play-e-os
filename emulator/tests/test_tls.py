# Copyright 2026 The openaap authors.
# Licensed under the Apache License, Version 2.0.

"""Trust-policy tests for the emulated head unit.

These encode the three hypotheses about how a real head unit treats the phone's
certificate. They are the whole reason the emulator exists: until someone runs
the phone side against physical hardware, the only honest position is that we
do not know which of these a 2017 MIB2 unit implements, so the phone side has
to work correctly under all three.
"""

from __future__ import annotations

import os
import ssl
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from openaap_hu import pki  # noqa: E402
from openaap_hu.tls import HeadUnitTls, TrustPolicy  # noqa: E402


class PhoneTls:
    """Minimal phone-side TLS server, standing in for the Kotlin engine.

    The real cross-language check is the end-to-end session test, which drives
    the actual Kotlin implementation over a socket. This exists so the trust
    policies can be exercised without spinning up a JVM.
    """

    def __init__(self, credential: pki.Credential, maximum_version: str | None = None) -> None:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        cert_path, key_path = credential.write_temp_files()
        context.load_cert_chain(cert_path, key_path)
        context.verify_mode = ssl.CERT_NONE
        if maximum_version == "TLSv1.2":
            context.maximum_version = ssl.TLSVersion.TLSv1_2
        self._incoming = ssl.MemoryBIO()
        self._outgoing = ssl.MemoryBIO()
        self._tls = context.wrap_bio(self._incoming, self._outgoing, server_side=True)
        self.complete = False

    def handshake(self, inbound: bytes) -> bytes:
        if inbound:
            self._incoming.write(inbound)
        if not self.complete:
            try:
                self._tls.do_handshake()
            except ssl.SSLWantReadError:
                return self._outgoing.read()
            self.complete = True
        return self._outgoing.read()

    def wrap(self, plaintext: bytes) -> bytes:
        self._tls.write(plaintext)
        return self._outgoing.read()

    def unwrap(self, ciphertext: bytes) -> bytes:
        if ciphertext:
            self._incoming.write(ciphertext)
        chunks = []
        while True:
            try:
                chunk = self._tls.read(16384)
            except (ssl.SSLWantReadError, ssl.SSLZeroReturnError):
                break
            if not chunk:
                break
            chunks.append(chunk)
        return b"".join(chunks)


def drive(phone: PhoneTls, head_unit: HeadUnitTls, max_rounds: int = 30) -> None:
    """Strict ping-pong that never discards a flight."""
    to_phone = head_unit.begin()
    to_head_unit = b""
    for _ in range(max_rounds):
        if to_phone:
            to_head_unit = phone.handshake(to_phone)
            to_phone = b""
        elif to_head_unit:
            to_phone = head_unit.handshake(to_head_unit)
            to_head_unit = b""
        else:
            return
    raise AssertionError(f"handshake did not settle in {max_rounds} rounds")


def test_lenient_head_unit_accepts_a_self_signed_phone_certificate():
    # The optimistic hypothesis. If real hardware behaves this way, a clean-room
    # phone side needs no Google certificate at all.
    phone = PhoneTls(pki.self_signed("openaap phone"))
    head_unit = HeadUnitTls(pki.self_signed("emulated head unit"), TrustPolicy.LENIENT)

    drive(phone, head_unit)

    assert head_unit.handshake_complete
    assert head_unit.outcome.peer_certificate_presented
    assert "openaap phone" in head_unit.outcome.peer_certificate


def test_strict_head_unit_rejects_a_certificate_from_another_authority():
    # The pessimistic hypothesis, and the wall the project is trying to locate.
    pinned = pki.Authority("pinned vendor authority")
    ours = pki.Authority("openaap test CA")

    phone = PhoneTls(ours.issue("openaap phone"))
    head_unit = HeadUnitTls(
        pinned.issue("head unit"),
        TrustPolicy.STRICT,
        trusted_ca_pem=pinned.certificate_pem,
    )

    with pytest.raises(ssl.SSLError):
        drive(phone, head_unit)
    assert not head_unit.handshake_complete
    assert head_unit.outcome.error


def test_strict_head_unit_accepts_a_certificate_from_the_pinned_authority():
    pinned = pki.Authority("pinned vendor authority")

    phone = PhoneTls(pinned.issue("openaap phone"))
    head_unit = HeadUnitTls(
        pinned.issue("head unit"),
        TrustPolicy.STRICT,
        trusted_ca_pem=pinned.certificate_pem,
    )

    drive(phone, head_unit)
    assert head_unit.handshake_complete


def test_head_unit_that_never_requests_a_certificate():
    phone = PhoneTls(pki.self_signed("openaap phone"))
    head_unit = HeadUnitTls(pki.self_signed("head unit"), TrustPolicy.NONE)

    drive(phone, head_unit)
    assert head_unit.handshake_complete


def test_application_data_round_trips_after_the_handshake():
    phone = PhoneTls(pki.self_signed("openaap phone"))
    head_unit = HeadUnitTls(pki.self_signed("head unit"), TrustPolicy.LENIENT)
    drive(phone, head_unit)

    request = b"service discovery request"
    assert phone.unwrap(head_unit.wrap(request)) == request

    # Larger than one TLS record, the shape every video frame has.
    keyframe = os.urandom(200_000)
    assert head_unit.unwrap(phone.wrap(keyframe)) == keyframe


def test_tls_1_2_only_head_unit_negotiates():
    phone = PhoneTls(pki.self_signed("openaap phone"))
    head_unit = HeadUnitTls(
        pki.self_signed("head unit"), TrustPolicy.LENIENT, maximum_version="TLSv1.2"
    )

    drive(phone, head_unit)
    assert head_unit.outcome.protocol == "TLSv1.2"


def test_partial_record_is_buffered_until_complete():
    phone = PhoneTls(pki.self_signed("openaap phone"))
    head_unit = HeadUnitTls(pki.self_signed("head unit"), TrustPolicy.LENIENT)
    drive(phone, head_unit)

    payload = os.urandom(4096)
    ciphertext = phone.wrap(payload)
    split = len(ciphertext) // 3

    assert head_unit.unwrap(ciphertext[:split]) == b""
    assert head_unit.unwrap(ciphertext[split:]) == payload
