# Copyright 2026 The openaap authors.
# Licensed under the Apache License, Version 2.0.

"""Session tests, driven by a scripted phone.

The phone here is deliberately dumb and deliberately controllable. It automates
only the two exchanges that have to happen before anything else can be tested --
the version response and the TLS handshake -- and leaves every other message to
the test, so that "phone sends discovery before auth complete" is one line
rather than a subclass.

It shares the emulator's framing and TLS code, which is fine and is not the
claim being tested here: framing is cross-validated against the golden vectors
in ``testdata/frame-vectors.json``, and the cross-language check is the harness
running the Kotlin phone against this emulator over a socket. What these tests
assert is that the *session* enforces the rules the specification states, which
is the property that makes the emulator useful as an oracle rather than as
another permissive head unit.
"""

from __future__ import annotations

import os
import socket
import ssl
import struct
import sys
import threading
import time

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from openaap_hu import pki, wire  # noqa: E402
from openaap_hu.framing import (  # noqa: E402
    FLAG_CONTROL,
    FLAG_ENCRYPTED,
    FLAG_FIRST,
    FLAG_LAST,
    FrameDecoder,
    MessageAssembler,
    encode,
    encode_prefragmented,
)
from openaap_hu.generated import control_pb2, input_pb2, media_pb2, sensors_pb2  # noqa: E402
from openaap_hu.profile import (  # noqa: E402
    CONVENTIONAL_CHANNEL_IDS,
    BUTTON_HOME,
    ChannelIdAllocator,
    generic_profile,
    mib2_profile,
)
from openaap_hu.session import (  # noqa: E402
    FRAGMENT_SIZE,
    HeadUnitSession,
    Phase,
    ProtocolViolation,
    SessionError,
    describe_annex_b,
    format_trace_event,
)
from openaap_hu.tls import TrustPolicy  # noqa: E402
from openaap_hu.transport import TcpHeadUnitServer, run_session  # noqa: E402
from openaap_hu.wire import ServiceKind  # noqa: E402


class ScriptedPhone:
    """A phone that answers the two mandatory exchanges and nothing else."""

    def __init__(self, protocol_version: tuple[int, int] = (1, 6)) -> None:
        self.protocol_version = protocol_version
        self.credential = pki.self_signed("scripted phone")

        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        cert_path, key_path = self.credential.write_temp_files()
        context.load_cert_chain(cert_path, key_path)
        # The phone's own judgement of the head unit's certificate is a separate
        # question from the one under test here, and Python's server side cannot
        # request a certificate without also verifying it.
        context.verify_mode = ssl.CERT_NONE
        context.maximum_version = ssl.TLSVersion.TLSv1_2
        self._incoming = ssl.MemoryBIO()
        self._outgoing = ssl.MemoryBIO()
        self._tls = context.wrap_bio(self._incoming, self._outgoing, server_side=True)
        self.handshake_complete = False

        self._decoder = FrameDecoder()
        self._assembler = MessageAssembler()
        self._outbox: list[bytes] = []
        self.received: list[tuple[int, int, bytes]] = []
        self.auth_complete_seen = False
        self.channel_ids: dict[str, int] = {}

        # Knobs the tests use to misbehave on purpose.
        self.answer_version = True
        self.answer_ping = True
        self.version_status_override: int | None = None
        self.version_minor_override: int | None = None

    # -------------------------------------------------------------- plumbing

    def feed(self, data: bytes) -> None:
        self._decoder.feed(data)
        for frame in self._decoder.drain():
            if frame.is_encrypted:
                body = self._unwrap(frame.payload)
            else:
                body = frame.payload
            message = self._assembler.accept(frame, body)
            if message is None:
                continue
            message_id, payload = wire.split_message(message.payload)
            self.received.append((message.channel, message_id, payload))
            self._react(message.channel, message_id, payload)

    def drain(self) -> bytes:
        data = b"".join(self._outbox)
        self._outbox.clear()
        return data

    def _react(self, channel: int, message_id: int, body: bytes) -> None:
        if channel != wire.CONTROL_CHANNEL:
            return
        if message_id == wire.CONTROL_VERSION_REQUEST and self.answer_version:
            request = wire.decode_version_request(body)
            minor = (
                self.version_minor_override
                if self.version_minor_override is not None
                else min(request.minor, self.protocol_version[1])
            )
            status = (
                self.version_status_override
                if self.version_status_override is not None
                else wire.VersionResponse.VERSIONS_MATCH
            )
            self.send(
                wire.CONTROL_CHANNEL,
                wire.CONTROL_VERSION_RESPONSE,
                wire.encode_version_response(request.major, minor, status),
                encrypted=False,
            )
        elif message_id == wire.CONTROL_TLS_HANDSHAKE:
            self._advance_handshake(body)
        elif message_id == wire.CONTROL_AUTH_COMPLETE:
            self.auth_complete_seen = True
        elif message_id == wire.CONTROL_DISCOVERY_RESPONSE:
            announcement = control_pb2.ProfileAnnouncement()
            announcement.ParseFromString(body)
            self.announcement = announcement
        elif message_id == wire.CONTROL_HEARTBEAT_REQUEST and self.answer_ping:
            request = control_pb2.HeartbeatRequest()
            request.ParseFromString(body)
            self.send(
                wire.CONTROL_CHANNEL,
                wire.CONTROL_HEARTBEAT_REPLY,
                control_pb2.HeartbeatReply(stamp_ns=request.stamp_ns).SerializeToString(),
            )

    def _advance_handshake(self, records: bytes) -> None:
        if records:
            self._incoming.write(records)
        if not self.handshake_complete:
            try:
                self._tls.do_handshake()
            except ssl.SSLWantReadError:
                pass
            else:
                self.handshake_complete = True
        outbound = self._outgoing.read()
        if outbound:
            self.send(
                wire.CONTROL_CHANNEL, wire.CONTROL_TLS_HANDSHAKE, outbound, encrypted=False
            )

    def _unwrap(self, ciphertext: bytes) -> bytes:
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

    # --------------------------------------------------------------- sending

    def send(
        self,
        channel: int,
        message_id: int,
        body: bytes = b"",
        *,
        encrypted: bool = True,
        control: bool | None = None,
        fragment_size: int = FRAGMENT_SIZE,
    ) -> None:
        """Send one message. ``control`` overrides the flag, to break the rule."""
        payload = wire.encode_message(message_id, body)
        wants_control = (
            wire.expects_control_flag(channel, message_id) if control is None else control
        )
        flags = FLAG_CONTROL if wants_control else 0
        if encrypted:
            fragments = [
                payload[offset : offset + fragment_size]
                for offset in range(0, len(payload), fragment_size)
            ] or [b""]
            frames = encode_prefragmented(
                channel,
                flags | FLAG_ENCRYPTED,
                [self._wrap(fragment) for fragment in fragments],
                len(payload),
            )
        else:
            frames = encode(channel, flags, payload, fragment_size)
        self._outbox.extend(frames)

    def send_raw_ciphertext(self, channel: int, blob: bytes) -> None:
        """Set the ciphertext flag on bytes that are not ciphertext."""
        self._outbox.append(encode(channel, FLAG_ENCRYPTED, blob)[0])

    def emit_fragment(
        self,
        channel: int,
        plaintext: bytes,
        *,
        first: bool,
        last: bool,
        total_length: int,
        control: bool = False,
    ) -> None:
        """Frame one fragment, encrypting it at the moment it is transmitted.

        The header is built by hand because the encoder helpers deliberately
        derive the fragmentation bits from a whole message, and the point of
        this method is to emit fragments out of that rhythm -- interleaving
        another channel's traffic between them.
        """
        flags = FLAG_ENCRYPTED | (FLAG_CONTROL if control else 0)
        if first:
            flags |= FLAG_FIRST
        if last:
            flags |= FLAG_LAST
        ciphertext = self._wrap(plaintext)
        header = struct.pack("!BBH", channel, flags, len(ciphertext))
        if first and not last:
            header += struct.pack("!I", total_length)
        self._outbox.append(header + ciphertext)

    def _wrap(self, plaintext: bytes) -> bytes:
        self._tls.write(plaintext)
        return self._outgoing.read()

    # ------------------------------------------------------------ convenience

    def message_ids(self, channel: int | None = None) -> list[int]:
        return [
            message_id
            for received_channel, message_id, _ in self.received
            if channel is None or received_channel == channel
        ]

    def last_body(self, message_id: int) -> bytes:
        for _, received_id, body in reversed(self.received):
            if received_id == message_id:
                return body
        raise AssertionError(f"phone never received 0x{message_id:04x}")


def make_session(**overrides) -> HeadUnitSession:
    settings = dict(
        trust_policy=TrustPolicy.LENIENT,
        seed=1234,
        ping_interval=1000.0,  # off unless a test asks for it
    )
    settings.update(overrides)
    profile = settings.pop("profile", None) or mib2_profile()
    return HeadUnitSession(profile, pki.self_signed("emulated head unit"), **settings)


def pump(session: HeadUnitSession, phone: ScriptedPhone, rounds: int = 60) -> None:
    """Shuttle bytes until neither side has anything left to say."""
    for _ in range(rounds):
        outbound = session.drain_outbound()
        if outbound:
            phone.feed(outbound)
        inbound = phone.drain()
        if inbound:
            session.feed(inbound)
        if not outbound and not inbound:
            return
    raise AssertionError(f"session did not settle in {rounds} rounds")


def bring_up(session: HeadUnitSession, phone: ScriptedPhone) -> None:
    """Version, handshake, auth complete, discovery -- up to a published map."""
    session.start()
    pump(session, phone)
    assert phone.auth_complete_seen, "the head unit never issued the go signal"
    phone.send(
        wire.CONTROL_CHANNEL,
        wire.CONTROL_DISCOVERY_REQUEST,
        control_pb2.ProfileQuery(phone_label="scripted phone").SerializeToString(),
    )
    pump(session, phone)
    for entry in phone.announcement.channel:
        phone.channel_ids[_entry_service(entry)] = entry.channel_id


def _entry_service(entry) -> str:
    if entry.HasField("media_sink"):
        if entry.media_sink.stream == media_pb2.STREAM_PICTURE:
            return "video"
        return {
            media_pb2.LANE_PROGRAM: "media-audio",
            media_pb2.LANE_GUIDANCE: "speech-audio",
            media_pb2.LANE_ALERT: "system-audio",
        }[entry.media_sink.lane]
    if entry.HasField("media_source"):
        return "microphone"
    if entry.HasField("input_source"):
        return "input"
    if entry.HasField("sensor_source"):
        return "sensors"
    if entry.HasField("bluetooth"):
        return "bluetooth"
    if entry.HasField("phone_status"):
        return "phone-status"
    if entry.HasField("navigation_status"):
        return "navigation"
    if entry.HasField("generic_notification"):
        return "notifications"
    raise AssertionError("channel entry populated no sub-descriptor")


def open_channel(session: HeadUnitSession, phone: ScriptedPhone, service: str) -> int:
    channel = phone.channel_ids[service]
    phone.send(
        channel,
        wire.CONTROL_CHANNEL_JOIN_REQUEST,
        control_pb2.ChannelJoinRequest(priority=0, channel_id=channel).SerializeToString(),
    )
    pump(session, phone)
    return channel


def start_stream(
    session: HeadUnitSession, phone: ScriptedPhone, service: str, session_tag: int = 7
) -> int:
    channel = open_channel(session, phone, service)
    phone.send(
        channel,
        wire.STREAM_SETUP_REQUEST,
        media_pb2.StreamSetupRequest(format_index=0).SerializeToString(),
    )
    pump(session, phone)
    phone.send(
        channel,
        wire.STREAM_START_NOTICE,
        media_pb2.StreamStartNotice(session_tag=session_tag).SerializeToString(),
    )
    pump(session, phone)
    return channel


def send_media(
    phone: ScriptedPhone, channel: int, payload: bytes, stamp: int | None = 1_000
) -> None:
    if stamp is None:
        phone.send(channel, wire.MEDIA_BARE, payload)
    else:
        phone.send(channel, wire.MEDIA_WITH_STAMP, stamp.to_bytes(8, "big") + payload)


# --------------------------------------------------------------- happy path


def test_full_bring_up_to_media_flowing_and_acknowledged():
    session = make_session()
    phone = ScriptedPhone()

    bring_up(session, phone)
    assert session.phase is Phase.ACTIVE
    assert phone.announcement.maker == "Volkswagen"

    channel = start_stream(session, phone, "video")

    # The specification orders the video channel: setup response, then the head
    # unit's focus indication, and only then may the phone start.
    ordering = phone.message_ids(channel)
    assert ordering.index(wire.STREAM_SETUP_REPLY) < ordering.index(wire.SCREEN_FOCUS_NOTICE)

    reply = media_pb2.StreamSetupReply()
    reply.ParseFromString(phone.last_body(wire.STREAM_SETUP_REPLY))
    assert reply.outcome == media_pb2.SETUP_ACCEPTED
    assert reply.max_unacked == 1
    assert list(reply.granted_format_index) == [0]

    keyframe = b"\x00\x00\x00\x01\x67sps\x00\x00\x00\x01\x68pps\x00\x00\x00\x01\x65idr"
    for index in range(4):
        send_media(phone, channel, keyframe, stamp=1000 + index)
        pump(session, phone)

    acks = [
        body for received_channel, message_id, body in phone.received
        if received_channel == channel and message_id == wire.MEDIA_CONSUMED_NOTICE
    ]
    assert len(acks) == 4
    last = media_pb2.MediaConsumedNotice()
    last.ParseFromString(acks[-1])
    assert last.session_tag == 7
    assert list(last.released) == [4]
    assert last.stamp_us == 1003
    assert not session.violations


def test_audio_channel_starts_without_waiting_for_focus():
    # The mirror of the video rule: audio has no focus step and starting
    # straight after the setup response must not be treated as an error.
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)

    channel = start_stream(session, phone, "media-audio", session_tag=3)
    assert wire.SCREEN_FOCUS_NOTICE not in phone.message_ids(channel)

    send_media(phone, channel, b"\x00\x01" * 480)
    pump(session, phone)
    assert wire.MEDIA_CONSUMED_NOTICE in phone.message_ids(channel)
    assert not session.violations


# ---------------------------------------------------------------- violations


def test_discovery_before_auth_complete_is_rejected():
    # A head unit that gates the go signal on something slow -- a certificate
    # check, say -- is what exposes a phone that does not wait for it.
    session = make_session(auto_auth_complete=False)
    phone = ScriptedPhone()
    session.start()
    pump(session, phone)

    assert session.phase is Phase.AUTHORISED
    assert not phone.auth_complete_seen

    phone.send(
        wire.CONTROL_CHANNEL,
        wire.CONTROL_DISCOVERY_REQUEST,
        control_pb2.ProfileQuery().SerializeToString(),
    )
    with pytest.raises(ProtocolViolation) as caught:
        pump(session, phone)

    assert caught.value.rule == "discovery-before-auth-complete"
    assert "auth complete" in caught.value.detail


def test_control_flag_omitted_on_a_service_channel_is_rejected():
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)

    channel = phone.channel_ids["video"]
    phone.send(
        channel,
        wire.CONTROL_CHANNEL_JOIN_REQUEST,
        control_pb2.ChannelJoinRequest(channel_id=channel).SerializeToString(),
        control=False,
    )
    with pytest.raises(ProtocolViolation) as caught:
        pump(session, phone)

    assert caught.value.rule == "control-flag"
    assert "must set the control flag" in caught.value.detail


def test_control_flag_set_on_a_media_message_is_rejected():
    # The 0x0000/0x0001 carve-out: media ids are below 2 and are service
    # messages despite their low numbers, so the bit must stay clear.
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)
    channel = start_stream(session, phone, "video")

    phone.send(channel, wire.MEDIA_BARE, b"frame", control=True)
    with pytest.raises(ProtocolViolation) as caught:
        pump(session, phone)

    assert caught.value.rule == "control-flag"
    assert "must not set the control flag" in caught.value.detail


def test_control_flag_set_on_channel_zero_is_rejected():
    session = make_session()
    phone = ScriptedPhone()
    session.start()
    pump(session, phone)

    phone.send(
        wire.CONTROL_CHANNEL,
        wire.CONTROL_HEARTBEAT_REPLY,
        control_pb2.HeartbeatReply(stamp_ns=1).SerializeToString(),
        encrypted=False,
        control=True,
    )
    with pytest.raises(ProtocolViolation) as caught:
        pump(session, phone)

    assert caught.value.rule == "control-flag"
    assert "must not set the control flag" in caught.value.detail
    assert "channel 0" in caught.value.detail


def test_ciphertext_before_the_handshake_completes_is_rejected():
    session = make_session()
    phone = ScriptedPhone()
    session.start()
    # Answer the version request but nothing else, then jump straight to
    # encrypted framing while the handshake is still outstanding.
    outbound = session.drain_outbound()
    phone.feed(outbound)
    session.feed(phone.drain())
    assert session.phase is Phase.HANDSHAKE

    phone.send_raw_ciphertext(wire.CONTROL_CHANNEL, b"not really ciphertext")
    with pytest.raises(ProtocolViolation) as caught:
        session.feed(phone.drain())

    assert caught.value.rule == "ciphertext-before-handshake"
    assert "plaintext" in caught.value.detail


def test_credit_window_overrun_is_rejected():
    session = make_session(max_unacked=1)
    phone = ScriptedPhone()
    bring_up(session, phone)
    channel = start_stream(session, phone, "video")

    # Two media messages written before the head unit's acknowledgement could
    # possibly have reached the phone: a strict send-one-wait-for-one window is
    # exactly what forbids this.
    send_media(phone, channel, b"first")
    send_media(phone, channel, b"second")
    with pytest.raises(ProtocolViolation) as caught:
        pump(session, phone)

    assert caught.value.rule == "credit-window-overrun"
    assert "window of 1" in caught.value.detail


def test_a_wider_credit_window_permits_more_in_flight():
    session = make_session(max_unacked=3)
    phone = ScriptedPhone()
    bring_up(session, phone)
    channel = start_stream(session, phone, "video")

    reply = media_pb2.StreamSetupReply()
    reply.ParseFromString(phone.last_body(wire.STREAM_SETUP_REPLY))
    assert reply.max_unacked == 3

    for index in range(3):
        send_media(phone, channel, b"frame %d" % index)
    pump(session, phone)
    assert not session.violations

    for index in range(4):
        send_media(phone, channel, b"frame %d" % index)
    with pytest.raises(ProtocolViolation) as caught:
        pump(session, phone)
    assert caught.value.rule == "credit-window-overrun"


def test_opening_a_channel_that_was_never_advertised_is_rejected():
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)

    unadvertised = max(phone.channel_ids.values()) + 1
    assert unadvertised not in phone.channel_ids.values()
    phone.send(
        unadvertised,
        wire.CONTROL_CHANNEL_JOIN_REQUEST,
        control_pb2.ChannelJoinRequest(channel_id=unadvertised).SerializeToString(),
    )
    with pytest.raises(ProtocolViolation) as caught:
        pump(session, phone)

    assert caught.value.rule == "unadvertised-channel"
    assert "never advertised" in caught.value.detail


def test_media_before_the_start_indication_is_rejected():
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)

    channel = open_channel(session, phone, "video")
    phone.send(
        channel,
        wire.STREAM_SETUP_REQUEST,
        media_pb2.StreamSetupRequest(format_index=0).SerializeToString(),
    )
    pump(session, phone)

    send_media(phone, channel, b"a frame nobody asked for")
    with pytest.raises(ProtocolViolation) as caught:
        pump(session, phone)

    assert caught.value.rule == "media-before-start"
    assert "start indication" in caught.value.detail


def test_starting_video_before_the_focus_indication_is_rejected():
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)
    channel = open_channel(session, phone, "video")

    # Send setup and start together, so start is processed before the phone has
    # had any chance to read the focus indication.
    phone.send(
        channel,
        wire.STREAM_SETUP_REQUEST,
        media_pb2.StreamSetupRequest(format_index=0).SerializeToString(),
    )
    phone.send(
        channel,
        wire.STREAM_START_NOTICE,
        media_pb2.StreamStartNotice(session_tag=1).SerializeToString(),
    )
    with pytest.raises(ProtocolViolation) as caught:
        pump(session, phone)

    assert caught.value.rule == "start-before-video-focus"


def test_plaintext_after_auth_complete_is_rejected_except_ping():
    session = make_session()
    phone = ScriptedPhone()
    session.start()
    pump(session, phone)
    assert phone.auth_complete_seen

    phone.send(
        wire.CONTROL_CHANNEL,
        wire.CONTROL_DISCOVERY_REQUEST,
        control_pb2.ProfileQuery().SerializeToString(),
        encrypted=False,
    )
    with pytest.raises(ProtocolViolation) as caught:
        pump(session, phone)

    assert caught.value.rule == "plaintext-after-auth-complete"


def test_a_strict_head_unit_refuses_the_phone_and_relays_the_alert():
    # The pessimistic hypothesis about real hardware, made runnable: a unit that
    # pins a CA the phone cannot chain to. The alert matters as much as the
    # refusal -- it is the difference between a phone that can report "bad
    # certificate" and one that only sees the connection go quiet.
    authority = pki.Authority("pinned vendor authority")
    session = HeadUnitSession(
        mib2_profile(),
        authority.issue("emulated head unit"),
        trust_policy=TrustPolicy.STRICT,
        trusted_ca_pem=authority.certificate_pem,
        seed=1,
        ping_interval=1000.0,
    )
    phone = ScriptedPhone()
    session.start()

    with pytest.raises(ProtocolViolation) as caught:
        pump(session, phone)

    assert caught.value.rule == "tls-handshake-failed"
    assert "strict" in caught.value.detail
    handshake_out = [
        event
        for event in session.trace_log
        if event.outbound and event.message_id == wire.CONTROL_TLS_HANDSHAKE
    ]
    assert "Alert" in handshake_out[-1].summary
    assert not session.handshake_outcome.completed
    assert "certificate verify failed" in session.handshake_outcome.error


def test_version_mismatch_is_rejected():
    session = make_session()
    phone = ScriptedPhone()
    phone.version_status_override = wire.VersionResponse.VERSIONS_DIFFER
    session.start()

    with pytest.raises(ProtocolViolation) as caught:
        pump(session, phone)

    assert caught.value.rule == "version-mismatch"
    assert session.peer_version is not None


def test_a_phone_claiming_a_higher_minor_than_offered_is_rejected():
    session = make_session(protocol_version=(1, 3))
    phone = ScriptedPhone()
    phone.version_minor_override = 6
    session.start()

    with pytest.raises(ProtocolViolation) as caught:
        pump(session, phone)

    assert caught.value.rule == "version-mismatch"
    assert "above the 3 offered" in caught.value.detail


def test_traffic_on_a_channel_before_it_is_opened_is_rejected():
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)

    channel = phone.channel_ids["video"]
    phone.send(
        channel,
        wire.STREAM_SETUP_REQUEST,
        media_pb2.StreamSetupRequest(format_index=0).SerializeToString(),
    )
    with pytest.raises(ProtocolViolation) as caught:
        pump(session, phone)

    assert caught.value.rule == "channel-not-open"


def test_a_ping_response_with_the_wrong_stamp_is_rejected():
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)
    session.send_ping()

    phone.send(
        wire.CONTROL_CHANNEL,
        wire.CONTROL_HEARTBEAT_REPLY,
        control_pb2.HeartbeatReply(stamp_ns=999).SerializeToString(),
    )
    with pytest.raises(ProtocolViolation) as caught:
        pump(session, phone)

    assert caught.value.rule == "ping-stamp"


# ------------------------------------------------------------- channel ids


def test_channel_ids_are_assigned_by_the_head_unit_not_the_convention():
    session = make_session(seed=7)
    phone = ScriptedPhone()
    bring_up(session, phone)

    # The whole point of the scrambled allocator: a phone that hardcoded the
    # conventional table fails here rather than in a car.
    for service, channel_id in phone.channel_ids.items():
        assert channel_id != 0
        conventional = CONVENTIONAL_CHANNEL_IDS[ServiceKind(service)]
        assert channel_id != conventional, f"{service} landed on its conventional id"
    assert len(set(phone.channel_ids.values())) == len(phone.channel_ids)


def test_the_seed_makes_the_assignment_reproducible():
    first = make_session(seed=99).channel_ids
    second = make_session(seed=99).channel_ids
    third = make_session(seed=100).channel_ids

    assert first == second
    assert first != third


def test_the_conventional_strategy_reproduces_the_sanity_check_table():
    session = make_session(channel_strategy=ChannelIdAllocator.CONVENTIONAL)
    assert session.channel_ids[ServiceKind.VIDEO] == 3
    assert session.channel_ids[ServiceKind.INPUT] == 1


def test_the_generic_profile_advertises_every_descriptor_shape():
    session = make_session(profile=generic_profile())
    phone = ScriptedPhone()
    bring_up(session, phone)

    assert set(phone.channel_ids) >= {
        "input",
        "sensors",
        "video",
        "media-audio",
        "speech-audio",
        "system-audio",
        "microphone",
        "bluetooth",
        "phone-status",
        "notifications",
        "navigation",
    }


# ------------------------------------------------------------ fragmentation


def test_a_fragmented_encrypted_message_reassembles():
    session = make_session(max_unacked=1)
    phone = ScriptedPhone()
    bring_up(session, phone)
    channel = start_stream(session, phone, "video")

    # Comfortably over the 0x4000 plaintext fragment size, which is what every
    # keyframe looks like. The phone splits plaintext and encrypts each fragment
    # separately, so the frame lengths sum to more than the announced total.
    keyframe = b"\x00\x00\x00\x01\x65" + bytes(random_ish(70_000))
    send_media(phone, channel, keyframe, stamp=4242)
    pump(session, phone)

    ack = media_pb2.MediaConsumedNotice()
    ack.ParseFromString(phone.last_body(wire.MEDIA_CONSUMED_NOTICE))
    assert ack.stamp_us == 4242
    assert list(ack.released) == [1]
    assert not session.violations

    inbound = [event for event in session.trace_log if not event.outbound]
    media_event = [event for event in inbound if event.message_id == wire.MEDIA_WITH_STAMP][-1]
    # The message really did fragment: the opening frame carries FIRST without
    # LAST, which is also what makes its header eight bytes rather than four.
    assert media_event.flags & FLAG_FIRST and not media_event.flags & FLAG_LAST
    # The announced total counts plaintext for the whole message, so the
    # reassembled payload matches it exactly even though the per-frame lengths
    # counted ciphertext and sum to more.
    assert media_event.wire_bytes == len(keyframe) + 8 + 2


def test_a_zero_length_last_fragment_is_accepted():
    # Some senders split at ">= fragment size", so a message that is an exact
    # multiple ends with an empty LAST frame. The specification says accept it.
    session = make_session(max_unacked=1)
    phone = ScriptedPhone()
    bring_up(session, phone)
    channel = start_stream(session, phone, "video")

    payload = bytes(random_ish(FRAGMENT_SIZE - 10))
    fragments = [payload[:FRAGMENT_SIZE - 10], b""]
    frames = encode_prefragmented(
        channel,
        FLAG_ENCRYPTED,
        [phone._wrap(wire.encode_message(wire.MEDIA_BARE, fragments[0])), phone._wrap(b"")],
        len(payload) + 2,
    )
    phone._outbox.extend(frames)
    pump(session, phone)

    assert not session.violations
    assert wire.MEDIA_CONSUMED_NOTICE in phone.message_ids(channel)


def test_a_fragmented_message_survives_interleaved_traffic_on_another_channel():
    # Reassembly is per channel, so a sensor request landing between two video
    # fragments must not splice the two payloads together. The one constraint
    # the specification does not spell out is that both channels share a single
    # TLS record sequence, so the sender has to encrypt in the order it
    # transmits -- encrypting the whole video message first and then slotting an
    # earlier-transmitted sensor record in between produces records the receiver
    # cannot decrypt at all.
    session = make_session(max_unacked=1)
    phone = ScriptedPhone()
    bring_up(session, phone)
    video = start_stream(session, phone, "video")
    sensors = open_channel(session, phone, "sensors")

    payload = wire.encode_message(wire.MEDIA_BARE, bytes(random_ish(40_000)))
    fragments = [
        payload[offset : offset + FRAGMENT_SIZE]
        for offset in range(0, len(payload), FRAGMENT_SIZE)
    ]
    assert len(fragments) == 3

    phone.emit_fragment(video, fragments[0], first=True, last=False, total_length=len(payload))
    phone.send(
        sensors,
        wire.SENSOR_FEED_REQUEST,
        sensors_pb2.SensorFeedRequest(kind=sensors_pb2.SENSOR_NIGHT_MODE).SerializeToString(),
    )
    phone.emit_fragment(video, fragments[1], first=False, last=False, total_length=len(payload))
    phone.emit_fragment(video, fragments[2], first=False, last=True, total_length=len(payload))
    pump(session, phone)

    assert not session.violations
    assert wire.SENSOR_FEED_REPLY in phone.message_ids(sensors)
    assert wire.MEDIA_CONSUMED_NOTICE in phone.message_ids(video)


# -------------------------------------------------------------------- ping


def test_ping_round_trips_and_is_timed():
    clock = FakeClock()
    session = make_session(ping_interval=5.0, clock=clock)
    phone = ScriptedPhone()
    bring_up(session, phone)

    session.tick()  # first tick sends immediately
    clock.advance(0.02)
    pump(session, phone)

    assert wire.CONTROL_HEARTBEAT_REQUEST in phone.message_ids(wire.CONTROL_CHANNEL)
    assert len(session.round_trip_samples) == 1
    assert session.round_trip_samples[0] == pytest.approx(0.02, abs=1e-6)

    # Not yet due again.
    session.tick()
    assert session.drain_outbound() == b""

    clock.advance(6.0)
    session.tick()
    assert session.drain_outbound() != b""


def test_an_unanswered_ping_times_out():
    clock = FakeClock()
    session = make_session(ping_interval=1.0, ping_timeout=4.0, clock=clock)
    phone = ScriptedPhone()
    bring_up(session, phone)

    session.tick()
    session.drain_outbound()  # never delivered to the phone
    clock.advance(5.0)
    with pytest.raises(ProtocolViolation) as caught:
        session.tick()

    assert caught.value.rule == "ping-timeout"


def test_a_plaintext_ping_response_is_tolerated():
    # The one documented inconsistency: implementations disagree on whether ping
    # travels plaintext, and both occur in the field. Everything else after auth
    # complete must be encrypted, and this is the only carve-out.
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)

    session.send_ping()
    pump(session, phone)
    assert len(session.round_trip_samples) == 1

    phone.answer_ping = False
    session.send_ping()
    pump(session, phone)
    stamp = _last_ping_stamp(phone)
    phone.send(
        wire.CONTROL_CHANNEL,
        wire.CONTROL_HEARTBEAT_REPLY,
        control_pb2.HeartbeatReply(stamp_ns=stamp).SerializeToString(),
        encrypted=False,
    )
    pump(session, phone)

    assert not session.violations
    assert len(session.round_trip_samples) == 2


def _last_ping_stamp(phone: ScriptedPhone) -> int:
    request = control_pb2.HeartbeatRequest()
    request.ParseFromString(phone.last_body(wire.CONTROL_HEARTBEAT_REQUEST))
    return request.stamp_ns


# ------------------------------------------------------------- head-unit side


def test_input_and_sensor_events_are_injected_on_their_own_channels():
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)
    input_channel = open_channel(session, phone, "input")
    sensor_channel = open_channel(session, phone, "sensors")

    phone.send(
        sensor_channel,
        wire.SENSOR_FEED_REQUEST,
        sensors_pb2.SensorFeedRequest(kind=sensors_pb2.SENSOR_ROAD_SPEED).SerializeToString(),
    )
    pump(session, phone)

    session.inject_touch(400, 240, input_pb2.CONTACT_DOWN)
    session.inject_button(BUTTON_HOME, pressed=True)
    session.inject_sensor(road_speed_cm_s=1350)
    pump(session, phone)

    reports = [
        body for channel, message_id, body in phone.received
        if channel == input_channel and message_id == wire.INPUT_REPORT_NOTICE
    ]
    assert len(reports) == 2
    touch = input_pb2.InputReportNotice()
    touch.ParseFromString(reports[0])
    assert touch.touch.contact[0].x == 400
    assert touch.touch.contact[0].y == 240
    assert touch.touch.action == input_pb2.CONTACT_DOWN
    button = input_pb2.InputReportNotice()
    button.ParseFromString(reports[1])
    assert button.buttons.button[0].code == BUTTON_HOME
    assert button.buttons.button[0].pressed

    reading = sensors_pb2.SensorReadingNotice()
    reading.ParseFromString(phone.last_body(wire.SENSOR_READING_NOTICE))
    assert reading.road_speed[0].speed_cm_s == 1350


def test_an_unsubscribed_sensor_reading_is_withheld_rather_than_volunteered():
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)
    open_channel(session, phone, "sensors")

    session.inject_sensor(night=True)
    pump(session, phone)

    assert wire.SENSOR_READING_NOTICE not in phone.message_ids()
    assert any("not subscribed" in warning for warning in session.warnings)


def test_injecting_before_the_channel_is_open_is_an_operator_error():
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)

    with pytest.raises(SessionError) as caught:
        session.inject_touch(10, 10)
    assert "has not opened" in str(caught.value)


def test_touch_outside_the_advertised_surface_is_refused():
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)
    open_channel(session, phone, "input")

    with pytest.raises(SessionError) as caught:
        session.inject_touch(1600, 240)
    assert "800x480" in str(caught.value)


def test_the_microphone_channel_runs_the_other_way():
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)

    channel = open_channel(session, phone, "microphone")
    phone.send(
        channel,
        wire.STREAM_SETUP_REQUEST,
        media_pb2.StreamSetupRequest(format_index=0).SerializeToString(),
    )
    phone.send(
        channel,
        wire.MICROPHONE_OPEN_REQUEST,
        media_pb2.MicrophoneOpenRequest(open=True).SerializeToString(),
    )
    pump(session, phone)

    reply = media_pb2.MicrophoneOpenReply()
    reply.ParseFromString(phone.last_body(wire.MICROPHONE_OPEN_REPLY))
    assert reply.open

    session.send_microphone_audio(b"\x01\x02" * 160)
    pump(session, phone)
    assert wire.MEDIA_WITH_STAMP in phone.message_ids(channel)

    # Window of one: a second message before the phone acknowledges is refused
    # locally rather than put on the wire.
    with pytest.raises(SessionError):
        session.send_microphone_audio(b"\x03\x04" * 160)

    phone.send(
        channel,
        wire.MEDIA_CONSUMED_NOTICE,
        media_pb2.MediaConsumedNotice(released=[1]).SerializeToString(),
    )
    pump(session, phone)
    session.send_microphone_audio(b"\x03\x04" * 160)
    pump(session, phone)
    assert not session.violations


def test_media_sent_on_the_microphone_channel_is_rejected():
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)
    channel = open_channel(session, phone, "microphone")
    phone.send(
        channel,
        wire.STREAM_SETUP_REQUEST,
        media_pb2.StreamSetupRequest(format_index=0).SerializeToString(),
    )
    phone.send(
        channel,
        wire.STREAM_START_NOTICE,
        media_pb2.StreamStartNotice(session_tag=2).SerializeToString(),
    )
    pump(session, phone)

    send_media(phone, channel, b"\x00" * 32)
    with pytest.raises(ProtocolViolation) as caught:
        pump(session, phone)
    assert caught.value.rule == "wrong-direction"


def test_manual_acknowledgement_holds_the_phone_at_its_window():
    session = make_session(max_unacked=1, auto_acknowledge_media=False)
    phone = ScriptedPhone()
    bring_up(session, phone)
    channel = start_stream(session, phone, "video")

    send_media(phone, channel, b"frame")
    pump(session, phone)
    assert wire.MEDIA_CONSUMED_NOTICE not in phone.message_ids(channel)

    session.acknowledge_media(channel)
    pump(session, phone)
    assert wire.MEDIA_CONSUMED_NOTICE in phone.message_ids(channel)

    send_media(phone, channel, b"frame")
    pump(session, phone)
    assert not session.violations


# ---------------------------------------------------------------- shutdown


def test_shutdown_from_the_phone_is_answered_and_closes_the_session():
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)

    phone.send(
        wire.CONTROL_CHANNEL,
        wire.CONTROL_TEARDOWN_REQUEST,
        control_pb2.TeardownRequest(cause=control_pb2.SHUTDOWN_USER).SerializeToString(),
    )
    pump(session, phone)

    assert wire.CONTROL_TEARDOWN_REPLY in phone.message_ids(wire.CONTROL_CHANNEL)
    assert session.phase is Phase.CLOSED
    assert session.closed_by == "phone"
    assert not session.violations


def test_shutdown_from_the_head_unit_closes_on_the_reply():
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)

    session.request_shutdown(cause=control_pb2.SHUTDOWN_DEVICE_OFF)
    pump(session, phone)
    assert session.phase is Phase.CLOSING

    request = control_pb2.TeardownRequest()
    request.ParseFromString(phone.last_body(wire.CONTROL_TEARDOWN_REQUEST))
    assert request.cause == control_pb2.SHUTDOWN_DEVICE_OFF

    phone.send(
        wire.CONTROL_CHANNEL,
        wire.CONTROL_TEARDOWN_REPLY,
        control_pb2.TeardownReply().SerializeToString(),
    )
    pump(session, phone)
    assert session.phase is Phase.CLOSED
    assert session.closed_by == "head unit"


# ------------------------------------------------------------------- trace


def test_the_trace_names_every_message_with_channel_flags_and_contents():
    session = make_session()
    phone = ScriptedPhone()
    bring_up(session, phone)
    channel = start_stream(session, phone, "video")
    send_media(
        phone, channel, b"\x00\x00\x00\x01\x67sps\x00\x00\x00\x01\x65idr", stamp=12345
    )
    pump(session, phone)

    lines = [format_trace_event(event) for event in session.trace_log]

    assert any("version-request" in line and "offering 1.6" in line for line in lines)
    # The handshake flight is named record by record, which is what tells you
    # where a failing handshake stopped.
    assert any("tls-handshake" in line and "ClientHello" in line for line in lines)
    assert any("tls-handshake" in line and "ServerKeyExchange" in line for line in lines)
    # Once ChangeCipherSpec has gone past, the handshake body is ciphertext and
    # the trace must say so rather than invent a message name from it.
    assert any("Handshake(encrypted)" in line for line in lines)
    assert any(
        "discovery-response" in line and f"{channel}/video" in line for line in lines
    )

    media_line = next(line for line in lines if "media-with-timestamp" in line)
    assert "stamp=12345us" in media_line
    assert "SPS" in media_line and "IDR" in media_line
    assert f"{channel}/video" in media_line
    assert "BULK|ENCRYPTED" in media_line

    # Channel 0 never sets the control flag; a control-namespace message on a
    # service channel always does. Both must be visible in the flags column.
    version_line = next(line for line in lines if "version-request" in line)
    assert "CONTROL" not in version_line
    join_line = next(line for line in lines if "channel-open-response" in line)
    assert "BULK|CONTROL|ENCRYPTED" in join_line


def test_annex_b_rendering_names_parameter_sets_and_slices():
    stream = b"\x00\x00\x00\x01\x67a\x00\x00\x00\x01\x68b\x00\x00\x01\x65c\x00\x00\x01\x41d"
    assert describe_annex_b(stream) == "SPS,PPS,IDR,slice"
    assert describe_annex_b(b"no start code here") == "no start code"


# ------------------------------------------------------------------ transport


def test_the_session_runs_over_a_real_socket():
    # The transport is separate from the session precisely so a USB link can
    # replace it, which only holds if the session never reaches for a socket.
    # This is the check that the seam is where it claims to be.
    server = TcpHeadUnitServer("127.0.0.1", 0)
    server.open()
    session = make_session()
    outcome: list = []

    def serve() -> None:
        link = server.accept(timeout=10.0)
        outcome.append(run_session(link, session, poll_interval=0.05, idle_timeout=5.0))

    worker = threading.Thread(target=serve, daemon=True)
    worker.start()

    phone = ScriptedPhone()
    connection = socket.create_connection(("127.0.0.1", server.port), timeout=10.0)
    try:
        _exchange(connection, phone, until=lambda: phone.auth_complete_seen)
        phone.send(
            wire.CONTROL_CHANNEL,
            wire.CONTROL_DISCOVERY_REQUEST,
            control_pb2.ProfileQuery(phone_label="socket phone").SerializeToString(),
        )
        _exchange(connection, phone, until=lambda: hasattr(phone, "announcement"))
        phone.send(
            wire.CONTROL_CHANNEL,
            wire.CONTROL_TEARDOWN_REQUEST,
            control_pb2.TeardownRequest(cause=control_pb2.SHUTDOWN_USER).SerializeToString(),
        )
        _exchange(
            connection,
            phone,
            until=lambda: wire.CONTROL_TEARDOWN_REPLY in phone.message_ids(),
        )
    finally:
        connection.close()
        worker.join(timeout=10.0)
        server.close()

    assert not worker.is_alive()
    assert len(phone.announcement.channel) == len(mib2_profile().channels)
    assert outcome and outcome[0].clean
    assert session.phase is Phase.CLOSED
    assert session.closed_by == "phone"


def _exchange(connection, phone: ScriptedPhone, until, timeout: float = 10.0) -> None:
    """Write whatever the phone owes, then read until a condition holds."""
    deadline = time.monotonic() + timeout
    pending = phone.drain()
    if pending:
        connection.sendall(pending)
    while not until():
        if time.monotonic() > deadline:
            raise AssertionError("timed out waiting for the head unit")
        connection.settimeout(max(0.05, deadline - time.monotonic()))
        chunk = connection.recv(16384)
        assert chunk, "head unit closed the connection early"
        phone.feed(chunk)
        pending = phone.drain()
        if pending:
            connection.sendall(pending)


# ------------------------------------------------------------------ helpers


class FakeClock:
    """A monotonic clock the tests move by hand."""

    def __init__(self, start: float = 1000.0) -> None:
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def random_ish(count: int) -> bytes:
    """Deterministic filler that does not compress to nothing."""
    return bytes((index * 37 + 11) & 0xFF for index in range(count))
