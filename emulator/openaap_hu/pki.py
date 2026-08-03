# Copyright 2026 The openaap authors.
# Licensed under the Apache License, Version 2.0.

"""Throwaway PKI for the emulator.

Certificates are generated at run time and never written to the repository.
None of this material is, or derives from, key material belonging to Google or
to any head-unit vendor -- the project's clean-room rule.

The emulator's trust policy is a knob rather than a constant, because the
question this project has to answer is precisely "how strict is a real head
unit?". Running the emulator in ``strict`` mode reproduces the pessimistic
case (a unit that pins a CA the phone cannot obtain a certificate from) and in
``lenient`` mode the optimistic one (a unit that asks for a certificate and
never checks it).
"""

from __future__ import annotations

import datetime
import tempfile
from dataclasses import dataclass
from typing import Optional

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from cryptography.x509.oid import NameOID


@dataclass
class Credential:
    """A certificate chain and its private key, in PEM form."""

    name: str
    certificate_pem: bytes
    key_pem: bytes
    chain_pem: bytes

    def write_temp_files(self) -> tuple[str, str]:
        """Materialise as files, which is the only form ``ssl`` accepts.

        Python's ssl module has no API to load a certificate from memory, so
        the emulator writes to a temporary file with the default 0600 mode and
        lets the process lifetime bound its existence.
        """
        cert_file = tempfile.NamedTemporaryFile(suffix=".pem", delete=False)
        cert_file.write(self.chain_pem)
        cert_file.flush()
        key_file = tempfile.NamedTemporaryFile(suffix=".pem", delete=False)
        key_file.write(self.key_pem)
        key_file.flush()
        return cert_file.name, key_file.name


class Authority:
    """A generated certificate authority, held in memory."""

    def __init__(self, common_name: str = "openaap emulator CA", key_type: str = "rsa2048") -> None:
        self._key = _generate_key(key_type)
        subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, common_name)])
        now = datetime.datetime.now(datetime.timezone.utc)
        self.certificate = (
            x509.CertificateBuilder()
            .subject_name(subject)
            .issuer_name(subject)
            .public_key(self._key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=365))
            .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
            .add_extension(
                x509.KeyUsage(
                    digital_signature=False,
                    content_commitment=False,
                    key_encipherment=False,
                    data_encipherment=False,
                    key_agreement=False,
                    key_cert_sign=True,
                    crl_sign=True,
                    encipher_only=False,
                    decipher_only=False,
                ),
                critical=True,
            )
            .sign(self._key, hashes.SHA256())
        )

    @property
    def certificate_pem(self) -> bytes:
        return self.certificate.public_bytes(serialization.Encoding.PEM)

    def issue(self, common_name: str, key_type: str = "rsa2048", validity_days: int = 30) -> Credential:
        key = _generate_key(key_type)
        now = datetime.datetime.now(datetime.timezone.utc)
        certificate = (
            x509.CertificateBuilder()
            .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, common_name)]))
            .issuer_name(self.certificate.subject)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            # Backdate: head units routinely boot with a wrong clock, and a
            # notBefore in the future is a classic unexplained handshake
            # failure in a car.
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=validity_days))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(
                x509.ExtendedKeyUsage(
                    [x509.oid.ExtendedKeyUsageOID.SERVER_AUTH, x509.oid.ExtendedKeyUsageOID.CLIENT_AUTH]
                ),
                critical=False,
            )
            .sign(self._key, hashes.SHA256())
        )
        certificate_pem = certificate.public_bytes(serialization.Encoding.PEM)
        return Credential(
            name=common_name,
            certificate_pem=certificate_pem,
            key_pem=key.private_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PrivateFormat.PKCS8,
                encryption_algorithm=serialization.NoEncryption(),
            ),
            chain_pem=certificate_pem + self.certificate_pem,
        )


def self_signed(common_name: str, key_type: str = "rsa2048", validity_days: int = 30) -> Credential:
    """A standalone certificate with no CA above it."""
    key = _generate_key(key_type)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, common_name)])
    now = datetime.datetime.now(datetime.timezone.utc)
    certificate = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=validity_days))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(
            x509.ExtendedKeyUsage(
                [x509.oid.ExtendedKeyUsageOID.SERVER_AUTH, x509.oid.ExtendedKeyUsageOID.CLIENT_AUTH]
            ),
            critical=False,
        )
        .sign(key, hashes.SHA256())
    )
    certificate_pem = certificate.public_bytes(serialization.Encoding.PEM)
    return Credential(
        name=common_name,
        certificate_pem=certificate_pem,
        key_pem=key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        ),
        chain_pem=certificate_pem,
    )


def _generate_key(key_type: str):
    if key_type == "rsa2048":
        return rsa.generate_private_key(public_exponent=65537, key_size=2048)
    if key_type == "rsa4096":
        return rsa.generate_private_key(public_exponent=65537, key_size=4096)
    if key_type == "ec256":
        return ec.generate_private_key(ec.SECP256R1())
    raise ValueError(f"unknown key type: {key_type}")


def describe_certificate(der: Optional[bytes]) -> str:
    """One-line summary of a peer certificate, for diagnostics."""
    if not der:
        return "<none presented>"
    certificate = x509.load_der_x509_certificate(der)
    return (
        f"subject={certificate.subject.rfc4514_string()} "
        f"issuer={certificate.issuer.rfc4514_string()} "
        f"serial={certificate.serial_number:x} "
        f"not_after={certificate.not_valid_after_utc.isoformat()}"
    )
