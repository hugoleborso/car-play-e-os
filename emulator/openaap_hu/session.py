# Copyright 2026 The openaap authors.
# Licensed under the Apache License, Version 2.0.

"""The head unit's half of an AAP session.

This is the piece that turns the framing, TLS and protobuf layers into a peer
the phone can actually talk to: it sends the version request, drives the TLS
handshake through control messages, issues the go signal, publishes its channel
map, answers channel and stream setup, and pushes input, sensor and microphone
traffic back the other way.

Two properties matter more than completeness.

**It is a judge, not a partner.** Every open-source head unit is permissive --
they disable peer verification, ignore ordering, and accept whatever framing
arrives. That makes them useless as test oracles: a phone that is wrong in a way
they tolerate passes, and then fails in a car with no logs and no error message.
So every rule this module can check, it checks, and a broken rule raises
``ProtocolViolation`` naming the rule rather than being absorbed. Anything the
specification leaves genuinely open is recorded as a warning instead, because
failing on an ambiguity would make the emulator a worse oracle, not a better
one.

**It never touches the transport.** The session consumes bytes through ``feed``
and produces them through ``drain_outbound``, so the same object runs over TCP,
over a USB bulk pair, or over a list in a test with no I/O at all. That is also
what makes the credit window checkable: the moment bytes are handed to a
transport is the moment the phone could have seen them, and that is exactly when
media credit is released.

Written from ``docs/01-aap-wire-format.md`` alone, sharing no code and no
assumptions with the Kotlin phone side -- see the note at the top of
``framing.py`` for why that independence is the whole point.
"""

from __future__ import annotations

import enum
import logging
import ssl
import time
from dataclasses import dataclass, field
from typing import Callable, Optional, Sequence

from google.protobuf import text_format
from google.protobuf.message import DecodeError
from google.protobuf.message import Message as ProtoMessage

from . import framing, wire
from .framing import (
    FLAG_CONTROL,
    FLAG_ENCRYPTED,
    FrameDecoder,
    FrameFormatError,
    MessageAssembler,
    describe_flags,
    encode,
    encode_prefragmented,
)
from .generated import control_pb2, descriptors_pb2, input_pb2, media_pb2, sensors_pb2
from .pki import Credential
from .profile import (
    DIAL_SCROLL,
    FPS_BY_CADENCE,
    PIXELS_BY_GEOMETRY,
    ChannelIdAllocator,
    ChannelMap,
    ChannelSpec,
    HeadUnitProfile,
)
from .tls import HeadUnitTls, TrustPolicy
from .wire import ServiceKind

log = logging.getLogger(__name__)

# The specification's maximum plaintext per frame. Senders split *plaintext* at
# this boundary and encrypt each fragment separately, so this is the size the
# message is cut at, not the size of the resulting frame body.
FRAGMENT_SIZE = 0x4000

# The `outcome` fields on channel-open, binding, sensor-start and microphone
# replies are plain int32 in a schema the specification never pins, and it never
# says what a successful outcome looks like either. Zero is this implementation's
# choice; a phone must not key behaviour off the value. See docs/08-emulator.md.
OUTCOME_OK = 0
OUTCOME_REFUSED = 1

DEFAULT_PROTOCOL_VERSION = (1, 6)


class ProtocolViolation(Exception):
    """The phone broke a rule the specification states.

    ``rule`` is a stable short name so a test can assert on which rule broke
    without matching prose, and so a trace can be grepped.
    """

    def __init__(self, rule: str, detail: str) -> None:
        super().__init__(f"{rule}: {detail}")
        self.rule = rule
        self.detail = detail


class SessionError(Exception):
    """The emulator was asked to do something the session is not in shape for.

    Distinct from ``ProtocolViolation`` on purpose: this one is our bug or the
    operator's, not the phone's.
    """


class Phase(enum.Enum):
    """Where the session has got to, in the order the specification lists."""

    NEW = "new"
    VERSION = "version"  # version request sent, response outstanding
    HANDSHAKE = "handshake"  # TLS records in flight on message 0x0003
    AUTHORISED = "authorised"  # handshake settled, auth complete not yet sent
    DISCOVERY = "discovery"  # auth complete sent, discovery request outstanding
    ACTIVE = "active"  # channel map published; channels open and stream
    CLOSING = "closing"  # shutdown request sent, reply outstanding
    CLOSED = "closed"


@dataclass
class ChannelState:
    """Per-channel bookkeeping for one advertised service."""

    channel_id: int
    spec: ChannelSpec

    opened: bool = False
    setup_done: bool = False
    granted_format_index: int = 0
    started: bool = False
    session_tag: int = 0
    # Video only: the phone may not start the stream until the head unit has
    # granted the screen.
    focus_granted: bool = False
    sensors_subscribed: set[int] = field(default_factory=set)
    microphone_open: bool = False

    # Credit accounting for media flowing phone -> head unit.
    max_unacked: int = 1
    outstanding: int = 0
    received: int = 0
    acknowledged: int = 0
    last_stamp_us: int = 0

    # Credit accounting for media flowing head unit -> phone (microphone only).
    sent: int = 0
    sent_unacked: int = 0

    @property
    def kind(self) -> ServiceKind:
        return self.spec.kind


@dataclass(frozen=True)
class TraceEvent:
    """One message crossing the boundary, already decoded.

    The trace is the primary debugging tool for the phone side, so this carries
    decoded contents rather than hex: a wrong enum or a missing field is
    supposed to be visible by reading, not by decoding by hand.
    """

    at: float
    outbound: bool
    channel: int
    channel_label: str
    flags: int
    message_id: int
    message_name: str
    summary: str
    wire_bytes: int
    phase: str


TraceSink = Callable[[TraceEvent], None]


def format_trace_event(event: TraceEvent) -> str:
    """One aligned line per message. Columns are fixed so eyes can scan them."""
    arrow = "HU  -> phone" if event.outbound else "phone -> HU "
    return (
        f"{event.at:8.3f} {arrow} {event.channel_label:<18} "
        f"{describe_flags(event.flags):<22} {event.wire_bytes:>7}B  "
        f"0x{event.message_id:04x} {event.message_name:<24} {event.summary}"
    )


# --- readable renderings of the three payload shapes that are not protobuf ---

_TLS_CONTENT_TYPES = {
    20: "ChangeCipherSpec",
    21: "Alert",
    22: "Handshake",
    23: "ApplicationData",
}

_TLS_HANDSHAKE_TYPES = {
    0: "HelloRequest",
    1: "ClientHello",
    2: "ServerHello",
    4: "NewSessionTicket",
    11: "Certificate",
    12: "ServerKeyExchange",
    13: "CertificateRequest",
    14: "ServerHelloDone",
    15: "CertificateVerify",
    16: "ClientKeyExchange",
    20: "Finished",
}


def describe_tls_records(blob: bytes, opaque_after_ccs: bool = True) -> tuple[str, bool]:
    """Name the TLS records in a handshake message body.

    A handshake that fails on real hardware fails somewhere specific -- at the
    certificate request, at the certificate, at the key exchange -- and a trace
    that says "0x0003, 1417 bytes" cannot tell you which. Returns the rendering
    and whether a ChangeCipherSpec was seen, because every handshake record
    after that one is ciphertext and its type byte is no longer a type byte.
    """
    parts: list[str] = []
    offset = 0
    encrypted = False
    while offset + 5 <= len(blob):
        content_type = blob[offset]
        length = int.from_bytes(blob[offset + 3 : offset + 5], "big")
        label = _TLS_CONTENT_TYPES.get(content_type, f"record-type-{content_type}")
        if content_type == 20:
            encrypted = True
        elif content_type == 22 and offset + 5 < len(blob):
            if encrypted and opaque_after_ccs:
                label = "Handshake(encrypted)"
            else:
                handshake_type = blob[offset + 5]
                label = _TLS_HANDSHAKE_TYPES.get(handshake_type, f"Handshake-{handshake_type}")
        parts.append(f"{label} {length}B")
        offset += 5 + length
    if offset != len(blob):
        parts.append(f"+{len(blob) - offset}B not a whole record")
    return ", ".join(parts) if parts else "empty", encrypted


_NAL_UNIT_NAMES = {
    1: "slice",
    5: "IDR",
    6: "SEI",
    7: "SPS",
    8: "PPS",
    9: "AUD",
}


def describe_annex_b(blob: bytes, limit: int = 8) -> str:
    """List the H.264 NAL unit types in an Annex-B byte stream.

    Worth the few lines: "no SPS/PPS in the first second" and "no IDR after the
    focus indication" are the two ways video silently fails, and both are
    visible from the type list alone.
    """
    names: list[str] = []
    offset = 0
    end = len(blob)
    while offset + 3 < end and len(names) < limit:
        if blob[offset] == 0 and blob[offset + 1] == 0:
            if blob[offset + 2] == 1:
                start = offset + 3
            elif blob[offset + 2] == 0 and offset + 3 < end and blob[offset + 3] == 1:
                start = offset + 4
            else:
                offset += 1
                continue
            if start < end:
                unit_type = blob[start] & 0x1F
                names.append(_NAL_UNIT_NAMES.get(unit_type, f"nal-{unit_type}"))
            offset = start
        else:
            offset += 1
    if not names:
        return "no start code"
    suffix = ",..." if len(names) == limit else ""
    return ",".join(names) + suffix


def describe_pcm(blob: bytes, sample_bits: int, lanes: int) -> str:
    """Frame count and duration of a raw PCM buffer, which is all it carries."""
    if sample_bits <= 0 or lanes <= 0:
        return f"{len(blob)}B"
    frame_bytes = (sample_bits // 8) * lanes
    if frame_bytes == 0 or len(blob) % frame_bytes:
        return f"{len(blob)}B (not a whole number of {frame_bytes}B frames)"
    return f"{len(blob) // frame_bytes} frames"


def _one_line(message: ProtoMessage) -> str:
    rendered = text_format.MessageToString(message, as_one_line=True).strip()
    return rendered if rendered else "<empty>"


class HeadUnitSession:
    """The head-unit half of one AAP session, with no transport attached.

    Feed it bytes with ``feed``, take bytes from it with ``drain_outbound``,
    and call ``tick`` on a timer so pings go out. Everything else is either a
    reaction to the phone or an ``inject_*`` call from the operator.
    """

    def __init__(
        self,
        profile: HeadUnitProfile,
        credential: Credential,
        *,
        trust_policy: str = TrustPolicy.LENIENT,
        trusted_ca_pem: Optional[bytes] = None,
        protocol_version: tuple[int, int] = DEFAULT_PROTOCOL_VERSION,
        max_unacked: Optional[int] = None,
        channel_strategy: str = ChannelIdAllocator.SCRAMBLED,
        seed: Optional[int] = None,
        ping_interval: float = 5.0,
        ping_timeout: float = 15.0,
        auto_auth_complete: bool = True,
        auto_acknowledge_media: bool = True,
        trace: Optional[TraceSink] = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.profile = profile
        self.protocol_version = protocol_version
        self.max_unacked = max_unacked if max_unacked is not None else profile.max_unacked
        if self.max_unacked < 1:
            raise ValueError("a credit window of zero would stall every stream")
        self.ping_interval = ping_interval
        self.ping_timeout = ping_timeout
        # A head unit that waits before issuing the go signal is a real thing --
        # a slow certificate check is the obvious reason -- and deferring it is
        # also the only way to reproduce a phone that races ahead of it.
        self.auto_auth_complete = auto_auth_complete
        self.auto_acknowledge_media = auto_acknowledge_media

        self._clock = clock
        self._started_at = clock()
        self._trace = trace

        self._tls = HeadUnitTls(
            credential,
            trust_policy=trust_policy,
            trusted_ca_pem=trusted_ca_pem,
            maximum_version=profile.tls_max_version,
        )
        self._decoder = FrameDecoder()
        self._assembler = MessageAssembler()
        self._outbox: list[bytes] = []

        allocator = ChannelIdAllocator(channel_strategy, seed)
        self.channel_ids = allocator.assign([spec.kind for spec in profile.channels])
        self.channels = ChannelMap()
        self._states: dict[int, ChannelState] = {}
        for spec in profile.channels:
            channel_id = self.channel_ids[spec.kind]
            self.channels.by_id[channel_id] = spec
            self.channels.id_by_kind[spec.kind] = channel_id
            self._states[channel_id] = ChannelState(
                channel_id=channel_id, spec=spec, max_unacked=self.max_unacked
            )

        self.phase = Phase.NEW
        self.violations: list[ProtocolViolation] = []
        self.warnings: list[str] = []
        self.trace_log: list[TraceEvent] = []
        self.closed_by: Optional[str] = None
        self.peer_version: Optional[wire.VersionResponse] = None
        self.round_trip_samples: list[float] = []

        self._auth_complete_sent = False
        self._pending_release: list[tuple[int, int]] = []
        self._ping_stamp: Optional[int] = None
        self._ping_sent_at: Optional[float] = None
        self._last_ping_at: Optional[float] = None
        self._outbound_ccs_seen = False
        self._inbound_ccs_seen = False
        self._media_clock_origin = clock()

    # ---------------------------------------------------------------- driving

    def start(self) -> None:
        """Open the session by sending the version request."""
        if self.phase is not Phase.NEW:
            raise SessionError(f"start() called in phase {self.phase.value}")
        major, minor = self.protocol_version
        self._emit(
            wire.CONTROL_CHANNEL,
            wire.CONTROL_VERSION_REQUEST,
            wire.encode_version_request(major, minor),
            encrypted=False,
        )
        self.phase = Phase.VERSION

    def feed(self, data: bytes) -> None:
        """Consume bytes from the transport."""
        if not data:
            return
        self._decoder.feed(data)
        while True:
            try:
                frame = self._decoder.poll()
            except FrameFormatError as error:
                raise self._violate("framing", str(error)) from error
            if frame is None:
                return
            self._accept_frame(frame)

    def drain_outbound(self) -> bytes:
        """Take the bytes owed to the phone.

        Media credit is released here rather than when an acknowledgement is
        built, because this call is the moment the acknowledgement could
        actually have reached the phone. Releasing earlier would let a phone
        that sends its whole window in one write look compliant.
        """
        data = b"".join(self._outbox)
        self._outbox.clear()
        if data:
            for channel_id, count in self._pending_release:
                state = self._states.get(channel_id)
                if state is not None:
                    state.outstanding = max(0, state.outstanding - count)
            self._pending_release.clear()
        return data

    @property
    def pending_outbound(self) -> int:
        return sum(len(chunk) for chunk in self._outbox)

    def tick(self, now: Optional[float] = None) -> None:
        """Timer work: periodic ping, and the timeout on an unanswered one."""
        if self.phase not in (Phase.DISCOVERY, Phase.ACTIVE):
            return
        moment = self._clock() if now is None else now
        if self._ping_sent_at is not None:
            if moment - self._ping_sent_at > self.ping_timeout:
                raise self._violate(
                    "ping-timeout",
                    f"no ping response in {moment - self._ping_sent_at:.1f}s; the phone must "
                    f"echo message 0x000b as 0x000c",
                )
            return
        if self._last_ping_at is None or moment - self._last_ping_at >= self.ping_interval:
            self.send_ping(now=moment)

    # ------------------------------------------------------------- operations

    def send_auth_complete(self) -> None:
        """Issue the go signal: the last plaintext message of the session."""
        if not self._tls.handshake_complete:
            raise SessionError("auth complete before the TLS handshake settled")
        if self._auth_complete_sent:
            return
        notice = control_pb2.AuthCompleteNotice(outcome=OUTCOME_OK)
        self._emit(
            wire.CONTROL_CHANNEL,
            wire.CONTROL_AUTH_COMPLETE,
            notice.SerializeToString(),
            encrypted=False,
        )
        self._auth_complete_sent = True
        self.phase = Phase.DISCOVERY

    def send_ping(self, now: Optional[float] = None) -> None:
        """Send a ping request and start its round-trip timer."""
        moment = self._clock() if now is None else now
        stamp = int(moment * 1_000_000_000)
        request = control_pb2.HeartbeatRequest(stamp_ns=stamp)
        # The specification records that implementations disagree on whether
        # ping travels plaintext or encrypted and that both occur in the field.
        # We send encrypted, because the same document says everything after
        # auth complete is encrypted, and accept either direction inbound.
        self._emit(
            wire.CONTROL_CHANNEL,
            wire.CONTROL_HEARTBEAT_REQUEST,
            request.SerializeToString(),
        )
        self._ping_stamp = stamp
        self._ping_sent_at = moment
        self._last_ping_at = moment

    def inject_touch(
        self,
        x: int,
        y: int,
        action: int = input_pb2.CONTACT_DOWN,
        contact_id: int = 0,
    ) -> None:
        """Report a single touch contact on the input channel."""
        state = self._require_open(ServiceKind.INPUT)
        surface = state.spec.touch
        if surface is not None and not (0 <= x < surface.width_px and 0 <= y < surface.height_px):
            raise SessionError(
                f"({x},{y}) is outside the {surface.width_px}x{surface.height_px} surface "
                "this profile advertised"
            )
        notice = input_pb2.InputReportNotice(stamp_ns=self._media_clock_ns())
        report = notice.touch
        report.action = action
        report.action_index = 0
        contact = report.contact.add()
        contact.x = x
        contact.y = y
        contact.contact_id = contact_id
        self._emit(state.channel_id, wire.INPUT_REPORT_NOTICE, notice.SerializeToString())

    def inject_button(self, code: int, pressed: bool = True, repeated: bool = False) -> None:
        """Report a button press or release on the input channel."""
        state = self._require_open(ServiceKind.INPUT)
        if code not in state.spec.buttons and not (state.spec.has_dial and code == DIAL_SCROLL):
            raise SessionError(
                f"button 0x{code:x} is not in the set this profile advertised: "
                + ", ".join(f"0x{value:x}" for value in state.spec.buttons)
            )
        notice = input_pb2.InputReportNotice(stamp_ns=self._media_clock_ns())
        event = notice.buttons.button.add()
        event.code = code
        event.pressed = pressed
        event.repeated_press = repeated
        self._emit(state.channel_id, wire.INPUT_REPORT_NOTICE, notice.SerializeToString())

    def inject_dial(self, detents: int, code: int = DIAL_SCROLL) -> None:
        """Report rotary movement, which reports detents rather than press/release."""
        state = self._require_open(ServiceKind.INPUT)
        if not state.spec.has_dial:
            raise SessionError("this profile advertised no rotary controller")
        notice = input_pb2.InputReportNotice(stamp_ns=self._media_clock_ns())
        event = notice.buttons.dial.add()
        event.code = code
        event.detents = detents
        self._emit(state.channel_id, wire.INPUT_REPORT_NOTICE, notice.SerializeToString())

    def inject_sensor(
        self,
        *,
        road_speed_cm_s: Optional[int] = None,
        cruise_engaged: bool = False,
        night: Optional[bool] = None,
        park_brake: Optional[bool] = None,
        drive_restriction_mask: Optional[int] = None,
        gear: Optional[int] = None,
    ) -> None:
        """Publish one sensor reading burst.

        Only sensors the phone actually subscribed to are sent: a head unit
        that volunteers unsubscribed readings would let a phone that forgot to
        subscribe appear to work.
        """
        state = self._require_open(ServiceKind.SENSORS)
        notice = sensors_pb2.SensorReadingNotice()
        wanted: list[tuple[int, Callable[[], None]]] = []
        if road_speed_cm_s is not None:
            wanted.append(
                (
                    sensors_pb2.SENSOR_ROAD_SPEED,
                    lambda: notice.road_speed.add(
                        speed_cm_s=road_speed_cm_s, cruise_engaged=cruise_engaged
                    ),
                )
            )
        if night is not None:
            wanted.append((sensors_pb2.SENSOR_NIGHT_MODE, lambda: notice.night_mode.add(night=night)))
        if park_brake is not None:
            wanted.append(
                (sensors_pb2.SENSOR_PARK_BRAKE, lambda: notice.park_brake.add(engaged=park_brake))
            )
        if drive_restriction_mask is not None:
            wanted.append(
                (
                    sensors_pb2.SENSOR_DRIVE_RESTRICTION,
                    lambda: notice.drive_restriction.add(restriction_mask=drive_restriction_mask),
                )
            )
        if gear is not None:
            wanted.append((sensors_pb2.SENSOR_TRANSMISSION, lambda: notice.transmission.add(gear=gear)))
        if not wanted:
            raise SessionError("inject_sensor() was given no readings")

        emitted = 0
        for kind, populate in wanted:
            if kind not in state.sensors_subscribed:
                self._warn(
                    f"sensor {kind} was not subscribed by the phone; reading withheld",
                )
                continue
            populate()
            emitted += 1
        if emitted:
            self._emit(state.channel_id, wire.SENSOR_READING_NOTICE, notice.SerializeToString())

    def send_microphone_audio(self, pcm: bytes, timestamped: bool = True) -> None:
        """Push microphone PCM, the one stream that runs head unit -> phone."""
        state = self._require_open(ServiceKind.MICROPHONE)
        if not state.microphone_open:
            raise SessionError("microphone media before the phone opened the microphone")
        if state.sent_unacked >= self.max_unacked:
            raise SessionError(
                f"microphone credit exhausted: {state.sent_unacked} of {self.max_unacked} "
                "messages unacknowledged"
            )
        if timestamped:
            body = self._media_clock_us().to_bytes(8, "big") + pcm
            message_id = wire.MEDIA_WITH_STAMP
        else:
            body = pcm
            message_id = wire.MEDIA_BARE
        self._emit(state.channel_id, message_id, body)
        state.sent += 1
        state.sent_unacked += 1

    def acknowledge_media(self, channel_id: int) -> None:
        """Release credit by hand, for when ``auto_acknowledge_media`` is off."""
        state = self._states.get(channel_id)
        if state is None:
            raise SessionError(f"channel {channel_id} is not one this head unit advertised")
        if state.acknowledged >= state.received:
            raise SessionError(f"nothing outstanding to acknowledge on channel {channel_id}")
        self._acknowledge(state, list(range(state.acknowledged + 1, state.received + 1)))

    def request_video_focus(self, projected: bool = True) -> None:
        """Volunteer a screen-focus change, as a driver pressing radio would."""
        state = self._require_open(ServiceKind.VIDEO)
        self._send_video_focus(state, projected=projected, unprompted=True)

    def request_shutdown(
        self, cause: int = control_pb2.SHUTDOWN_USER
    ) -> None:
        """Ask the phone to shut down; the session closes on its reply."""
        if self.phase in (Phase.CLOSING, Phase.CLOSED):
            return
        request = control_pb2.TeardownRequest(cause=cause)
        self._emit(
            wire.CONTROL_CHANNEL,
            wire.CONTROL_TEARDOWN_REQUEST,
            request.SerializeToString(),
            encrypted=self._auth_complete_sent,
        )
        self.phase = Phase.CLOSING
        self.closed_by = "head unit"

    # ------------------------------------------------------------- inbound

    def _accept_frame(self, frame: framing.Frame) -> None:
        if frame.is_encrypted:
            if not self._tls.handshake_complete:
                raise self._violate(
                    "ciphertext-before-handshake",
                    f"frame on channel {frame.channel} sets the ciphertext flag, but the TLS "
                    "handshake has not settled; everything up to and including auth complete "
                    "is plaintext",
                )
            try:
                body = self._tls.unwrap(frame.payload)
            except ssl.SSLError as error:
                raise self._violate(
                    "undecryptable-frame",
                    f"channel {frame.channel}: {error}. Frames carry whole TLS records, so a "
                    "record that will not decrypt means the sender split ciphertext across "
                    "frames or encrypted before splitting",
                ) from error
            if frame.payload and not body and not frame.is_last:
                # Legal only as a zero-length plaintext fragment, which the
                # specification says senders emit when a message is an exact
                # multiple of the fragment size -- and that is always a LAST.
                self._warn(
                    f"channel {frame.channel}: {len(frame.payload)}B of ciphertext yielded no "
                    "plaintext on a non-final fragment; the record does not fit in one frame"
                )
        else:
            body = frame.payload

        try:
            message = self._assembler.accept(frame, body)
        except FrameFormatError as error:
            raise self._violate("framing", str(error)) from error
        if message is not None:
            self._dispatch(message)

    def _dispatch(self, message: framing.Message) -> None:
        try:
            message_id, body = wire.split_message(message.payload)
        except ValueError as error:
            raise self._violate("missing-message-id", str(error)) from error

        kind = self.channels.kind_of(message.channel)
        name = wire.message_name(kind, message_id)
        self._record(
            outbound=False,
            channel=message.channel,
            flags=message.flags,
            message_id=message_id,
            message_name=name,
            summary=self._describe(kind, message_id, body),
            wire_bytes=len(message.payload),
        )

        # --- rules that hold for every message, checked before any handling ---

        expected_control = wire.expects_control_flag(message.channel, message_id)
        if expected_control != message.is_control:
            raise self._violate(
                "control-flag",
                f"{name} (0x{message_id:04x}) on channel {message.channel} "
                f"{'must' if expected_control else 'must not'} set the control flag; the bit "
                "means the id comes from the control namespace, which holds when the channel "
                "is not 0 and 2 <= id < 0x8000",
            )

        if self._auth_complete_sent and not message.is_encrypted:
            # Ping is the documented exception: implementations disagree on
            # whether it travels plaintext and both occur in the field.
            if message_id != wire.CONTROL_HEARTBEAT_REPLY:
                raise self._violate(
                    "plaintext-after-auth-complete",
                    f"{name} arrived in plaintext; everything after auth complete is "
                    "encrypted, ping excepted",
                )

        if message.channel != wire.CONTROL_CHANNEL and kind is None:
            detail = f"channel {message.channel} was never advertised in the discovery response"
            if message_id == wire.CONTROL_CHANNEL_JOIN_REQUEST:
                detail = (
                    f"channel open request for channel {message.channel}, which was never "
                    "advertised; channel ids are chosen by the head unit and only those in "
                    "the discovery response exist"
                )
            known = ", ".join(str(value) for value in sorted(self.channels.by_id))
            raise self._violate("unadvertised-channel", f"{detail} (advertised: {known})")

        if message.channel == wire.CONTROL_CHANNEL:
            self._handle_control(message_id, body)
        else:
            self._handle_service(self._states[message.channel], message_id, body)

    def _handle_control(self, message_id: int, body: bytes) -> None:
        if message_id == wire.CONTROL_VERSION_RESPONSE:
            self._handle_version_response(body)
        elif message_id == wire.CONTROL_TLS_HANDSHAKE:
            self._handle_handshake(body)
        elif message_id == wire.CONTROL_DISCOVERY_REQUEST:
            self._handle_discovery_request(body)
        elif message_id == wire.CONTROL_HEARTBEAT_REPLY:
            self._handle_ping_response(body)
        elif message_id == wire.CONTROL_ROUTE_FOCUS_REQUEST:
            request = self._parse(control_pb2.RouteFocusRequest, body, "navigation focus request")
            reply = control_pb2.RouteFocusReply(granted=request.wanted)
            self._emit(
                wire.CONTROL_CHANNEL, wire.CONTROL_ROUTE_FOCUS_REPLY, reply.SerializeToString()
            )
        elif message_id == wire.CONTROL_SOUND_FOCUS_REQUEST:
            self._handle_audio_focus(body)
        elif message_id == wire.CONTROL_VOICE_SESSION:
            # The specification defines no reply for this one.
            self._parse(control_pb2.VoiceSessionNotice, body, "voice session request")
        elif message_id == wire.CONTROL_TEARDOWN_REQUEST:
            self._handle_shutdown_request(body)
        elif message_id == wire.CONTROL_TEARDOWN_REPLY:
            self.phase = Phase.CLOSED
        elif message_id in (wire.CONTROL_UNACCOUNTED_9, wire.CONTROL_UNACCOUNTED_A):
            # Unaccounted for in every public source. Log and ignore, as the
            # specification instructs -- rejecting them would be guessing.
            self._warn(
                f"message 0x{message_id:04x} is unaccounted for in every public source; "
                f"ignored ({len(body)}B)"
            )
        elif message_id in (
            wire.CONTROL_VERSION_REQUEST,
            wire.CONTROL_AUTH_COMPLETE,
            wire.CONTROL_DISCOVERY_RESPONSE,
            wire.CONTROL_CHANNEL_JOIN_REPLY,
            wire.CONTROL_HEARTBEAT_REQUEST,
        ):
            raise self._violate(
                "wrong-direction",
                f"{wire.message_name(ServiceKind.CONTROL, message_id)} is a head unit -> phone "
                "message and must never arrive from the phone",
            )
        else:
            self._warn(f"unknown control message 0x{message_id:04x} ignored ({len(body)}B)")

    # ----------------------------------------------------------- control steps

    def _handle_version_response(self, body: bytes) -> None:
        if self.phase is not Phase.VERSION:
            raise self._violate(
                "out-of-order",
                f"version response in phase {self.phase.value}; it answers the version request "
                "and nothing else",
            )
        try:
            response = wire.decode_version_response(body)
        except ValueError as error:
            raise self._violate("malformed-version-response", str(error)) from error
        self.peer_version = response

        major, minor = self.protocol_version
        if response.status == wire.VersionResponse.VERSIONS_DIFFER:
            raise self._violate(
                "version-mismatch",
                f"the phone reported no common version with {major}.{minor} "
                f"(it offered {response.major}.{response.minor})",
            )
        if not response.matched:
            raise self._violate(
                "version-status",
                f"status 0x{response.status:04x} is neither 0 (match) nor 0xffff (mismatch)",
            )
        if response.major != major:
            raise self._violate(
                "version-mismatch",
                f"the phone answered major {response.major} against the {major} offered but "
                "still reported a match",
            )
        if response.minor > minor:
            raise self._violate(
                "version-mismatch",
                f"the phone answered minor {response.minor}, above the {minor} offered; the "
                "response echoes the head unit's minor capped at what the phone supports",
            )

        self.phase = Phase.HANDSHAKE
        self._send_handshake(self._tls.begin())

    def _handle_handshake(self, body: bytes) -> None:
        if self.phase is not Phase.HANDSHAKE:
            raise self._violate(
                "out-of-order",
                f"TLS handshake message in phase {self.phase.value}; the handshake follows the "
                "version exchange and precedes auth complete",
            )
        try:
            outbound = self._tls.handshake(body)
        except ssl.SSLError as error:
            # Relay the alert first: a phone that learns why it was rejected can
            # be debugged, one that sees a bare disconnect cannot.
            pending = self._tls.outcome
            self._send_handshake(b"")
            raise self._violate(
                "tls-handshake-failed",
                f"{error}; phone presented a certificate: {pending.peer_certificate_presented}",
            ) from error

        self._send_handshake(outbound)
        if self._tls.handshake_complete:
            self.phase = Phase.AUTHORISED
            log.info("%s", self._tls.outcome.summary())
            if self.auto_auth_complete:
                self.send_auth_complete()

    def _handle_discovery_request(self, body: bytes) -> None:
        if not self._auth_complete_sent:
            raise self._violate(
                "discovery-before-auth-complete",
                "service discovery request arrived before auth complete was sent; auth "
                "complete is the head unit declaring the TLS session acceptable and is what "
                "gates the phone into discovery",
            )
        if self.phase is Phase.ACTIVE:
            self._warn("service discovery requested a second time; answering again")
        query = self._parse(control_pb2.ProfileQuery, body, "service discovery request")
        if query.HasField("phone_label"):
            log.info("phone identifies as %r", query.phone_label)

        announcement = self._build_announcement()
        self._emit(
            wire.CONTROL_CHANNEL,
            wire.CONTROL_DISCOVERY_RESPONSE,
            announcement.SerializeToString(),
        )
        self.phase = Phase.ACTIVE

    def _handle_ping_response(self, body: bytes) -> None:
        reply = self._parse(control_pb2.HeartbeatReply, body, "ping response")
        if self._ping_stamp is None:
            self._warn("ping response with no ping outstanding; ignored")
            return
        if reply.stamp_ns != self._ping_stamp:
            raise self._violate(
                "ping-stamp",
                f"ping response carried {reply.stamp_ns} against the {self._ping_stamp} sent; "
                "the stamp identifies which request is being answered and must come back "
                "unchanged",
            )
        if self._ping_sent_at is not None:
            self.round_trip_samples.append(self._clock() - self._ping_sent_at)
        self._ping_stamp = None
        self._ping_sent_at = None

    def _handle_audio_focus(self, body: bytes) -> None:
        request = self._parse(control_pb2.SoundFocusRequest, body, "audio focus request")
        granted = {
            control_pb2.SOUND_WANT_HOLD: control_pb2.SOUND_STATE_HELD,
            control_pb2.SOUND_WANT_HOLD_BRIEF: control_pb2.SOUND_STATE_HELD_BRIEF,
            control_pb2.SOUND_WANT_HOLD_DUCKED: control_pb2.SOUND_STATE_DUCKED,
            control_pb2.SOUND_WANT_RELEASE: control_pb2.SOUND_STATE_RELEASED,
        }.get(request.wanted, control_pb2.SOUND_STATE_UNSET)
        reply = control_pb2.SoundFocusReply(state=granted, unprompted=False)
        self._emit(
            wire.CONTROL_CHANNEL, wire.CONTROL_SOUND_FOCUS_REPLY, reply.SerializeToString()
        )

    def _handle_shutdown_request(self, body: bytes) -> None:
        request = self._parse(control_pb2.TeardownRequest, body, "shutdown request")
        log.info("phone asked to shut down, cause %s", request.cause)
        self._emit(
            wire.CONTROL_CHANNEL,
            wire.CONTROL_TEARDOWN_REPLY,
            control_pb2.TeardownReply().SerializeToString(),
            encrypted=self._auth_complete_sent,
        )
        self.phase = Phase.CLOSED
        self.closed_by = "phone"

    # ----------------------------------------------------------- service steps

    def _handle_service(self, state: ChannelState, message_id: int, body: bytes) -> None:
        # The service namespaces overlap: 0x8001 is a start indication on a
        # media channel, an input event on the input channel and a sensor start
        # request on the sensor channel. So the channel's service decides the
        # meaning of the id, and dispatch has to branch on the kind first.
        if message_id == wire.CONTROL_CHANNEL_JOIN_REQUEST:
            self._handle_channel_open(state, body)
            return

        if not state.opened:
            raise self._violate(
                "channel-not-open",
                f"{wire.message_name(state.kind, message_id)} on channel {state.channel_id} "
                f"({state.kind.value}) before the channel open handshake; the specification "
                "orders channel open before anything else on a channel",
            )

        if message_id in (wire.MEDIA_WITH_STAMP, wire.MEDIA_BARE):
            self._handle_media(state, message_id, body)
        elif message_id < wire.SERVICE_NAMESPACE_FLOOR:
            self._warn(
                f"control-namespace message 0x{message_id:04x} on {state.kind.value} channel "
                f"{state.channel_id}; only channel open is defined there, so it is ignored"
            )
        elif state.kind.is_media:
            self._handle_media_channel(state, message_id, body)
        elif state.kind is ServiceKind.INPUT:
            self._handle_input_channel(state, message_id, body)
        elif state.kind is ServiceKind.SENSORS:
            self._handle_sensor_channel(state, message_id, body)
        else:
            self._warn(
                f"message 0x{message_id:04x} on a {state.kind.value} channel has no handler; "
                f"ignored ({len(body)}B)"
            )

    def _handle_media_channel(self, state: ChannelState, message_id: int, body: bytes) -> None:
        if message_id == wire.STREAM_SETUP_REQUEST:
            self._handle_setup_request(state, body)
        elif message_id == wire.STREAM_START_NOTICE:
            self._handle_start_notice(state, body)
        elif message_id == wire.STREAM_STOP_NOTICE:
            notice = self._parse(media_pb2.StreamStopNotice, body, "stop indication")
            state.started = False
            state.outstanding = 0
            log.info("channel %s stopped (tag %d)", state.channel_id, notice.session_tag)
        elif message_id == wire.SCREEN_FOCUS_REQUEST and state.kind is ServiceKind.VIDEO:
            request = self._parse(media_pb2.ScreenFocusRequest, body, "video focus request")
            self._send_video_focus(
                state,
                projected=request.wanted != media_pb2.SCREEN_FOCUS_NATIVE,
                unprompted=False,
            )
        elif message_id == wire.MICROPHONE_OPEN_REQUEST and state.kind is ServiceKind.MICROPHONE:
            self._handle_microphone_open(state, body)
        elif message_id == wire.MEDIA_CONSUMED_NOTICE and state.kind is ServiceKind.MICROPHONE:
            notice = self._parse(media_pb2.MediaConsumedNotice, body, "microphone media ack")
            released = len(notice.released) or 1
            if released > state.sent_unacked:
                raise self._violate(
                    "over-acknowledgement",
                    f"the phone released {released} microphone messages against "
                    f"{state.sent_unacked} outstanding",
                )
            state.sent_unacked -= released
        else:
            self._warn(
                f"message 0x{message_id:04x} is not defined for a {state.kind.value} channel; "
                f"ignored ({len(body)}B)"
            )

    def _handle_input_channel(self, state: ChannelState, message_id: int, body: bytes) -> None:
        if message_id != wire.INPUT_BINDING_REQUEST:
            self._warn(
                f"message 0x{message_id:04x} is not defined for an input channel; ignored "
                f"({len(body)}B)"
            )
            return
        request = self._parse(input_pb2.BindingRequest, body, "input binding request")
        advertised = set(state.spec.buttons) | ({DIAL_SCROLL} if state.spec.has_dial else set())
        unknown = [code for code in request.code if code not in advertised]
        if unknown:
            # Not fatal: nothing in the specification assigns button codes at
            # all, so a phone binding an unknown one is guessing, not breaking
            # a rule. It is exactly the guess this profile exists to expose.
            self._warn(
                "phone bound button codes this head unit never advertised: "
                + ", ".join(f"0x{code:x}" for code in unknown)
            )
        reply = input_pb2.BindingReply(outcome=OUTCOME_OK)
        self._emit(state.channel_id, wire.INPUT_BINDING_REPLY, reply.SerializeToString())

    def _handle_sensor_channel(self, state: ChannelState, message_id: int, body: bytes) -> None:
        if message_id != wire.SENSOR_FEED_REQUEST:
            self._warn(
                f"message 0x{message_id:04x} is not defined for a sensor channel; ignored "
                f"({len(body)}B)"
            )
            return
        request = self._parse(sensors_pb2.SensorFeedRequest, body, "sensor start request")
        known = request.kind in state.spec.sensors
        if not known:
            self._warn(
                f"phone subscribed to sensor {request.kind}, which this head unit never "
                "advertised; refused"
            )
        else:
            state.sensors_subscribed.add(request.kind)
        reply = sensors_pb2.SensorFeedReply(outcome=OUTCOME_OK if known else OUTCOME_REFUSED)
        self._emit(state.channel_id, wire.SENSOR_FEED_REPLY, reply.SerializeToString())

    def _handle_channel_open(self, state: ChannelState, body: bytes) -> None:
        if self.phase is not Phase.ACTIVE:
            raise self._violate(
                "out-of-order",
                f"channel open request in phase {self.phase.value}; channels are opened from "
                "the map the discovery response publishes",
            )
        request = self._parse(control_pb2.ChannelJoinRequest, body, "channel open request")
        if request.HasField("channel_id") and request.channel_id != state.channel_id:
            raise self._violate(
                "channel-id-mismatch",
                f"channel open request carried by channel {state.channel_id} names channel "
                f"{request.channel_id}; the request is sent on the channel being opened",
            )
        if state.opened:
            self._warn(f"channel {state.channel_id} opened twice; answering again")
        state.opened = True
        reply = control_pb2.ChannelJoinReply(outcome=OUTCOME_OK, channel_id=state.channel_id)
        self._emit(state.channel_id, wire.CONTROL_CHANNEL_JOIN_REPLY, reply.SerializeToString())

    def _handle_setup_request(self, state: ChannelState, body: bytes) -> None:
        if not state.kind.is_media:
            raise self._violate(
                "setup-on-non-media-channel",
                f"stream setup on channel {state.channel_id}, which carries "
                f"{state.kind.value} and has no stream",
            )
        request = self._parse(media_pb2.StreamSetupRequest, body, "stream setup request")
        formats = state.spec.picture if state.kind is ServiceKind.VIDEO else state.spec.sound
        index = request.format_index if request.HasField("format_index") else 0
        if index >= len(formats):
            # Refused rather than fatal: refusing a format is a thing head units
            # do, and the specification says nothing about how a phone should
            # be punished for asking.
            self._warn(
                f"phone asked for format index {index} on channel {state.channel_id}, which "
                f"advertised {len(formats)}; setup refused"
            )
            reply = media_pb2.StreamSetupReply(
                outcome=media_pb2.SETUP_REFUSED, max_unacked=state.max_unacked
            )
            self._emit(state.channel_id, wire.STREAM_SETUP_REPLY, reply.SerializeToString())
            return

        state.setup_done = True
        state.granted_format_index = index
        reply = media_pb2.StreamSetupReply(
            outcome=media_pb2.SETUP_ACCEPTED,
            max_unacked=state.max_unacked,
            granted_format_index=[index],
        )
        self._emit(state.channel_id, wire.STREAM_SETUP_REPLY, reply.SerializeToString())

        if state.kind is ServiceKind.VIDEO:
            # The video channel has the extra step: the phone waits for the
            # focus indication before it may start.
            self._send_video_focus(state, projected=True, unprompted=True)

    def _handle_start_notice(self, state: ChannelState, body: bytes) -> None:
        if not state.setup_done:
            raise self._violate(
                "start-before-setup",
                f"start indication on channel {state.channel_id} before its setup response",
            )
        if state.kind is ServiceKind.VIDEO and not state.focus_granted:
            raise self._violate(
                "start-before-video-focus",
                f"start indication on video channel {state.channel_id} before the head unit's "
                "video focus indication; the video channel waits for focus, audio channels "
                "may start straight after their setup response",
            )
        notice = self._parse(media_pb2.StreamStartNotice, body, "start indication")
        state.started = True
        state.session_tag = notice.session_tag
        state.outstanding = 0
        state.received = 0
        state.acknowledged = 0

    def _handle_media(self, state: ChannelState, message_id: int, body: bytes) -> None:
        if state.kind is ServiceKind.MICROPHONE:
            raise self._violate(
                "wrong-direction",
                f"media on microphone channel {state.channel_id}; the microphone channel runs "
                "the other way -- the head unit sends and the phone acknowledges",
            )
        if not state.started:
            raise self._violate(
                "media-before-start",
                f"media on channel {state.channel_id} ({state.kind.value}) before its start "
                "indication; setup response, then start indication, then media",
            )
        try:
            stamp, payload = wire.decode_media(message_id, body)
        except ValueError as error:
            raise self._violate("malformed-media", str(error)) from error

        state.received += 1
        state.outstanding += 1
        if stamp is not None:
            state.last_stamp_us = stamp
        if state.outstanding > state.max_unacked:
            raise self._violate(
                "credit-window-overrun",
                f"channel {state.channel_id} has {state.outstanding} media messages "
                f"unacknowledged against the window of {state.max_unacked} this head unit "
                "advertised in its setup response",
            )
        if self.auto_acknowledge_media:
            self._acknowledge(state, [state.received])
        _ = payload  # The emulator consumes media; it does not decode it.

    def _handle_microphone_open(self, state: ChannelState, body: bytes) -> None:
        request = self._parse(media_pb2.MicrophoneOpenRequest, body, "microphone open request")
        if not state.setup_done:
            raise self._violate(
                "microphone-open-before-setup",
                f"microphone open on channel {state.channel_id} before its setup response",
            )
        state.microphone_open = bool(request.open)
        state.sent_unacked = 0
        reply = media_pb2.MicrophoneOpenReply(outcome=OUTCOME_OK, open=state.microphone_open)
        self._emit(state.channel_id, wire.MICROPHONE_OPEN_REPLY, reply.SerializeToString())

    def _acknowledge(self, state: ChannelState, released: Sequence[int]) -> None:
        notice = media_pb2.MediaConsumedNotice(
            session_tag=state.session_tag,
            stamp_us=state.last_stamp_us,
            released=list(released),
        )
        self._emit(state.channel_id, wire.MEDIA_CONSUMED_NOTICE, notice.SerializeToString())
        state.acknowledged = max(state.acknowledged, max(released))
        self._pending_release.append((state.channel_id, len(released)))

    def _send_video_focus(self, state: ChannelState, projected: bool, unprompted: bool) -> None:
        notice = media_pb2.ScreenFocusNotice(
            state=media_pb2.SCREEN_FOCUS_PROJECTED if projected else media_pb2.SCREEN_FOCUS_NATIVE,
            unprompted=unprompted,
        )
        self._emit(state.channel_id, wire.SCREEN_FOCUS_NOTICE, notice.SerializeToString())
        state.focus_granted = projected

    # ------------------------------------------------------------ discovery

    def _build_announcement(self) -> control_pb2.ProfileAnnouncement:
        announcement = control_pb2.ProfileAnnouncement(
            unit_label=self.profile.name,
            maker=self.profile.make,
            model=self.profile.model,
            model_year=self.profile.year,
            vehicle_id=self.profile.vehicle_id,
            left_hand_drive=self.profile.left_hand_drive,
            software_build=self.profile.software_build,
            software_version=self.profile.software_version,
            plays_native_media=self.profile.plays_native_media,
            display_label=self.profile.name,
            link=control_pb2.LINK_WIRELESS if self.profile.link_wireless else control_pb2.LINK_WIRED,
        )
        for spec in self.profile.channels:
            announcement.channel.append(
                _channel_entry(spec, self.channel_ids[spec.kind], self.max_unacked)
            )
        return announcement

    # ---------------------------------------------------------------- plumbing

    def _emit(
        self,
        channel: int,
        message_id: int,
        body: bytes,
        *,
        encrypted: bool = True,
    ) -> None:
        payload = wire.encode_message(message_id, body)
        flags = FLAG_CONTROL if wire.expects_control_flag(channel, message_id) else 0

        if encrypted:
            if not self._tls.handshake_complete:
                raise SessionError(
                    f"0x{message_id:04x} would be sent encrypted before the handshake settled"
                )
            # Split the plaintext, then encrypt each fragment separately. The
            # announced total counts plaintext, and the per-frame length counts
            # the ciphertext of that frame, so these two numbers are not
            # comparable and the split cannot happen after encryption.
            fragments = [
                payload[offset : offset + FRAGMENT_SIZE]
                for offset in range(0, len(payload), FRAGMENT_SIZE)
            ] or [b""]
            frames = encode_prefragmented(
                channel,
                flags | FLAG_ENCRYPTED,
                [self._tls.wrap(fragment) for fragment in fragments],
                len(payload),
            )
        else:
            frames = encode(channel, flags, payload, FRAGMENT_SIZE)

        self._outbox.extend(frames)
        kind = self.channels.kind_of(channel)
        summary = self._describe(kind, message_id, body, outbound=True)
        if len(frames) > 1:
            summary = f"{summary}  [{len(frames)} fragments]"
        self._record(
            outbound=True,
            channel=channel,
            # The flags of the opening frame, so the trace shows what actually
            # went on the wire rather than the semantic bits alone.
            flags=frames[0][1],
            message_id=message_id,
            message_name=wire.message_name(kind, message_id),
            summary=summary,
            wire_bytes=sum(len(frame) for frame in frames),
        )

    def _send_handshake(self, records: bytes) -> None:
        if not records:
            return
        self._emit(wire.CONTROL_CHANNEL, wire.CONTROL_TLS_HANDSHAKE, records, encrypted=False)

    def _record(
        self,
        *,
        outbound: bool,
        channel: int,
        flags: int,
        message_id: int,
        message_name: str,
        summary: str,
        wire_bytes: int,
    ) -> None:
        event = TraceEvent(
            at=self._clock() - self._started_at,
            outbound=outbound,
            channel=channel,
            channel_label=self.channels.label(channel),
            flags=flags,
            message_id=message_id,
            message_name=message_name,
            summary=summary,
            wire_bytes=wire_bytes,
            phase=self.phase.value,
        )
        self.trace_log.append(event)
        if self._trace is not None:
            self._trace(event)

    def _violate(self, rule: str, detail: str) -> ProtocolViolation:
        violation = ProtocolViolation(rule, detail)
        self.violations.append(violation)
        log.error("protocol violation [%s] %s", rule, detail)
        return violation

    def _warn(self, detail: str) -> None:
        self.warnings.append(detail)
        log.warning("%s", detail)

    def _parse(self, message_type, body: bytes, label: str):
        message = message_type()
        try:
            message.ParseFromString(body)
        except (DecodeError, ValueError) as error:
            raise self._violate(
                "malformed-body", f"{label} is not valid protobuf: {error}"
            ) from error
        return message

    def _require_open(self, kind: ServiceKind) -> ChannelState:
        channel_id = self.channels.id_by_kind.get(kind)
        if channel_id is None:
            raise SessionError(f"this profile has no {kind.value} channel")
        state = self._states[channel_id]
        if not state.opened:
            raise SessionError(
                f"the phone has not opened the {kind.value} channel (id {channel_id}) yet"
            )
        return state

    def _media_clock_ns(self) -> int:
        return int((self._clock() - self._media_clock_origin) * 1_000_000_000)

    def _media_clock_us(self) -> int:
        return int((self._clock() - self._media_clock_origin) * 1_000_000)

    # ---------------------------------------------------------------- tracing

    def _describe(
        self, kind: Optional[ServiceKind], message_id: int, body: bytes, outbound: bool = False
    ) -> str:
        """Render a message body as one readable line."""
        try:
            return self._describe_inner(kind, message_id, body, outbound)
        except Exception as error:  # pragma: no cover - the trace must never throw
            return f"<undecodable {len(body)}B: {error}>"

    def _describe_inner(
        self, kind: Optional[ServiceKind], message_id: int, body: bytes, outbound: bool
    ) -> str:
        if kind in (None, ServiceKind.CONTROL):
            if message_id == wire.CONTROL_VERSION_REQUEST:
                request = wire.decode_version_request(body)
                return f"offering {request.major}.{request.minor}"
            if message_id == wire.CONTROL_VERSION_RESPONSE:
                response = wire.decode_version_response(body)
                verdict = "match" if response.matched else f"status 0x{response.status:04x}"
                return f"{response.major}.{response.minor}, {verdict}"
            if message_id == wire.CONTROL_TLS_HANDSHAKE:
                seen = self._outbound_ccs_seen if outbound else self._inbound_ccs_seen
                rendering, ccs = describe_tls_records(body, opaque_after_ccs=seen)
                if ccs or seen:
                    if outbound:
                        self._outbound_ccs_seen = True
                    else:
                        self._inbound_ccs_seen = True
                return rendering
            return self._describe_control_protobuf(message_id, body)

        if message_id in (wire.MEDIA_WITH_STAMP, wire.MEDIA_BARE):
            stamp, payload = wire.decode_media(message_id, body)
            head = f"stamp={stamp}us " if stamp is not None else ""
            if kind is ServiceKind.VIDEO:
                return f"{head}{len(payload)}B [{describe_annex_b(payload)}]"
            channel_id = self.channels.id_by_kind.get(kind)
            spec = self._states[channel_id].spec if channel_id in self._states else None
            sound = spec.sound[0] if spec and spec.sound else None
            shape = (
                describe_pcm(payload, sound.sample_bits, sound.lanes) if sound else f"{len(payload)}B"
            )
            return f"{head}{len(payload)}B [{shape}]"

        return self._describe_service_protobuf(kind, message_id, body)

    def _describe_control_protobuf(self, message_id: int, body: bytes) -> str:
        table = {
            wire.CONTROL_AUTH_COMPLETE: control_pb2.AuthCompleteNotice,
            wire.CONTROL_DISCOVERY_REQUEST: control_pb2.ProfileQuery,
            wire.CONTROL_DISCOVERY_RESPONSE: control_pb2.ProfileAnnouncement,
            wire.CONTROL_CHANNEL_JOIN_REQUEST: control_pb2.ChannelJoinRequest,
            wire.CONTROL_CHANNEL_JOIN_REPLY: control_pb2.ChannelJoinReply,
            wire.CONTROL_HEARTBEAT_REQUEST: control_pb2.HeartbeatRequest,
            wire.CONTROL_HEARTBEAT_REPLY: control_pb2.HeartbeatReply,
            wire.CONTROL_ROUTE_FOCUS_REQUEST: control_pb2.RouteFocusRequest,
            wire.CONTROL_ROUTE_FOCUS_REPLY: control_pb2.RouteFocusReply,
            wire.CONTROL_TEARDOWN_REQUEST: control_pb2.TeardownRequest,
            wire.CONTROL_TEARDOWN_REPLY: control_pb2.TeardownReply,
            wire.CONTROL_VOICE_SESSION: control_pb2.VoiceSessionNotice,
            wire.CONTROL_SOUND_FOCUS_REQUEST: control_pb2.SoundFocusRequest,
            wire.CONTROL_SOUND_FOCUS_REPLY: control_pb2.SoundFocusReply,
        }
        message_type = table.get(message_id)
        if message_type is None:
            return f"{len(body)}B, no schema"
        message = message_type()
        message.ParseFromString(body)
        if message_id == wire.CONTROL_DISCOVERY_RESPONSE:
            # The full announcement is far too long for one line, and its
            # channel map is the only part anyone reads.
            entries = " ".join(
                f"{entry.channel_id}/{_entry_kind(entry)}" for entry in message.channel
            )
            return f"{len(message.channel)} channels: {entries}"
        return _one_line(message)

    def _describe_service_protobuf(
        self, kind: ServiceKind, message_id: int, body: bytes
    ) -> str:
        if message_id < wire.SERVICE_NAMESPACE_FLOOR:
            return self._describe_control_protobuf(message_id, body)

        # Service namespaces overlap -- 0x8001 is three different messages
        # depending on the channel -- so the table is chosen by kind first.
        table: dict[int, type] = {}
        if kind.is_media:
            table = {
                wire.STREAM_SETUP_REQUEST: media_pb2.StreamSetupRequest,
                wire.STREAM_SETUP_REPLY: media_pb2.StreamSetupReply,
                wire.STREAM_START_NOTICE: media_pb2.StreamStartNotice,
                wire.STREAM_STOP_NOTICE: media_pb2.StreamStopNotice,
                wire.MEDIA_CONSUMED_NOTICE: media_pb2.MediaConsumedNotice,
                wire.MICROPHONE_OPEN_REQUEST: media_pb2.MicrophoneOpenRequest,
                wire.MICROPHONE_OPEN_REPLY: media_pb2.MicrophoneOpenReply,
                wire.SCREEN_FOCUS_REQUEST: media_pb2.ScreenFocusRequest,
                wire.SCREEN_FOCUS_NOTICE: media_pb2.ScreenFocusNotice,
            }
        elif kind is ServiceKind.INPUT:
            table = {
                wire.INPUT_REPORT_NOTICE: input_pb2.InputReportNotice,
                wire.INPUT_BINDING_REQUEST: input_pb2.BindingRequest,
                wire.INPUT_BINDING_REPLY: input_pb2.BindingReply,
            }
        elif kind is ServiceKind.SENSORS:
            table = {
                wire.SENSOR_FEED_REQUEST: sensors_pb2.SensorFeedRequest,
                wire.SENSOR_FEED_REPLY: sensors_pb2.SensorFeedReply,
                wire.SENSOR_READING_NOTICE: sensors_pb2.SensorReadingNotice,
            }
        message_type = table.get(message_id)
        if message_type is None:
            return f"{len(body)}B, no schema for a {kind.value} channel"
        message = message_type()
        message.ParseFromString(body)
        return _one_line(message)


# --------------------------------------------------------------------- helpers


def _entry_kind(entry: descriptors_pb2.ChannelEntry) -> str:
    """Name a channel entry by which sub-descriptor it populated.

    Which field number is set *is* the service identity -- the numbers are the
    protocol, the names are not -- so this reads the descriptor the same way a
    phone has to.
    """
    if entry.HasField("sensor_source"):
        return "sensors"
    if entry.HasField("media_sink"):
        if entry.media_sink.stream == media_pb2.STREAM_PICTURE:
            return "video"
        lane = {
            media_pb2.LANE_PROGRAM: "media-audio",
            media_pb2.LANE_GUIDANCE: "speech-audio",
            media_pb2.LANE_ALERT: "system-audio",
        }
        return lane.get(entry.media_sink.lane, "audio")
    if entry.HasField("input_source"):
        return "input"
    if entry.HasField("media_source"):
        return "microphone"
    if entry.HasField("bluetooth"):
        return "bluetooth"
    if entry.HasField("navigation_status"):
        return "navigation"
    if entry.HasField("phone_status"):
        return "phone-status"
    if entry.HasField("vendor_extension"):
        return "vendor"
    if entry.HasField("generic_notification"):
        return "notifications"
    return "empty"


def _channel_entry(
    spec: ChannelSpec, channel_id: int, max_unacked: int
) -> descriptors_pb2.ChannelEntry:
    """Build one discovery entry: an id plus exactly one sub-descriptor."""
    entry = descriptors_pb2.ChannelEntry(channel_id=channel_id)
    kind = spec.kind

    if kind is ServiceKind.SENSORS:
        for sensor in spec.sensors:
            entry.sensor_source.slot.add(kind=sensor)
    elif kind is ServiceKind.INPUT:
        entry.input_source.code.extend(spec.buttons)
        if spec.has_dial:
            entry.input_source.code.append(DIAL_SCROLL)
        entry.input_source.has_dial = spec.has_dial
        if spec.touch is not None:
            surface = entry.input_source.touch_surface
            surface.width_px = spec.touch.width_px
            surface.height_px = spec.touch.height_px
            surface.kind = (
                input_pb2.SURFACE_MULTI_CONTACT
                if spec.touch.multi_contact
                else input_pb2.SURFACE_SINGLE_CONTACT
            )
    elif kind is ServiceKind.VIDEO:
        sink = entry.media_sink
        sink.stream = media_pb2.STREAM_PICTURE
        sink.buffered_messages = spec.buffered_messages
        sink.sink_delay_ms = spec.sink_delay_ms
        for picture in spec.picture:
            fmt = sink.picture_format.add()
            fmt.geometry = picture.geometry
            fmt.cadence = picture.cadence
            fmt.pad_left = picture.pad_left
            fmt.pad_top = picture.pad_top
            fmt.density_dpi = picture.density_dpi
            fmt.decoder_delay_ms = picture.decoder_delay_ms
    elif kind.is_audio_sink:
        sink = entry.media_sink
        sink.stream = media_pb2.STREAM_SOUND
        sink.lane = spec.lane
        sink.buffered_messages = spec.buffered_messages
        sink.sink_delay_ms = spec.sink_delay_ms
        for sound in spec.sound:
            fmt = sink.sound_format.add()
            fmt.sample_rate = sound.sample_rate
            fmt.sample_bits = sound.sample_bits
            fmt.lane_count = sound.lanes
    elif kind is ServiceKind.MICROPHONE:
        source = entry.media_source
        source.stream = media_pb2.STREAM_SOUND
        source.gain_control = False
        if spec.sound:
            source.sound_format.sample_rate = spec.sound[0].sample_rate
            source.sound_format.sample_bits = spec.sound[0].sample_bits
            source.sound_format.lane_count = spec.sound[0].lanes
    elif kind is ServiceKind.BLUETOOTH:
        entry.bluetooth.adapter_address = "00:00:5E:00:53:00"
        entry.bluetooth.pairing_method.extend([1, 2])
    elif kind is ServiceKind.PHONE_STATUS:
        entry.phone_status.call_control = True
        entry.phone_status.signal_strength = True
    elif kind is ServiceKind.NOTIFICATIONS:
        entry.generic_notification.source = "openaap"
    elif kind is ServiceKind.NAVIGATION:
        entry.navigation_status.min_interval_ms = 1000
        entry.navigation_status.status_lines = 3
        entry.navigation_status.line_columns = 40
    else:  # pragma: no cover - ServiceKind.CONTROL is never advertised
        raise SessionError(f"no descriptor shape for {kind.value}")

    _ = max_unacked  # advisory buffering lives on the sink; the window is in setup
    return entry


def describe_picture_format(fmt: media_pb2.PictureFormat) -> str:
    """Human-readable geometry, for the trace and the CLI banner."""
    width, height = PIXELS_BY_GEOMETRY.get(fmt.geometry, (0, 0))
    return f"{width}x{height}@{FPS_BY_CADENCE.get(fmt.cadence, 0)}"
