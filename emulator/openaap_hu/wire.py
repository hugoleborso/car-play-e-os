# Copyright 2026 The openaap authors.
# Licensed under the Apache License, Version 2.0.

"""Message ids, the control-flag rule, and the two non-protobuf codecs.

Everything in this module is dictated by the wire format rather than chosen,
which is why it is separated from ``session.py``: the session is policy, this
is the parts a conforming implementation has no freedom about.
"""

from __future__ import annotations

import enum
import struct
from dataclasses import dataclass
from typing import Optional

# Control namespace. Ids 0x0009 and 0x000a are unaccounted for in every public
# source; they are named here only so the trace can say "unaccounted" rather
# than "unknown", and they are logged and ignored.
CONTROL_VERSION_REQUEST = 0x0001
CONTROL_VERSION_RESPONSE = 0x0002
CONTROL_TLS_HANDSHAKE = 0x0003
CONTROL_AUTH_COMPLETE = 0x0004
CONTROL_DISCOVERY_REQUEST = 0x0005
CONTROL_DISCOVERY_RESPONSE = 0x0006
CONTROL_CHANNEL_JOIN_REQUEST = 0x0007
CONTROL_CHANNEL_JOIN_REPLY = 0x0008
CONTROL_UNACCOUNTED_9 = 0x0009
CONTROL_UNACCOUNTED_A = 0x000A
CONTROL_HEARTBEAT_REQUEST = 0x000B
CONTROL_HEARTBEAT_REPLY = 0x000C
CONTROL_ROUTE_FOCUS_REQUEST = 0x000D
CONTROL_ROUTE_FOCUS_REPLY = 0x000E
CONTROL_TEARDOWN_REQUEST = 0x000F
CONTROL_TEARDOWN_REPLY = 0x0010
CONTROL_VOICE_SESSION = 0x0011
CONTROL_SOUND_FOCUS_REQUEST = 0x0012
CONTROL_SOUND_FOCUS_REPLY = 0x0013

# A/V channel namespace.
MEDIA_WITH_STAMP = 0x0000
MEDIA_BARE = 0x0001
STREAM_SETUP_REQUEST = 0x8000
STREAM_START_NOTICE = 0x8001
STREAM_STOP_NOTICE = 0x8002
STREAM_SETUP_REPLY = 0x8003
MEDIA_CONSUMED_NOTICE = 0x8004
MICROPHONE_OPEN_REQUEST = 0x8005
MICROPHONE_OPEN_REPLY = 0x8006
SCREEN_FOCUS_REQUEST = 0x8007
SCREEN_FOCUS_NOTICE = 0x8008

# Input channel namespace.
INPUT_REPORT_NOTICE = 0x8001
INPUT_BINDING_REQUEST = 0x8002
INPUT_BINDING_REPLY = 0x8003

# Sensor channel namespace.
SENSOR_FEED_REQUEST = 0x8001
SENSOR_FEED_REPLY = 0x8002
SENSOR_READING_NOTICE = 0x8003

CONTROL_CHANNEL = 0
SERVICE_NAMESPACE_FLOOR = 0x8000

_CONTROL_NAMES = {
    CONTROL_VERSION_REQUEST: "version-request",
    CONTROL_VERSION_RESPONSE: "version-response",
    CONTROL_TLS_HANDSHAKE: "tls-handshake",
    CONTROL_AUTH_COMPLETE: "auth-complete",
    CONTROL_DISCOVERY_REQUEST: "discovery-request",
    CONTROL_DISCOVERY_RESPONSE: "discovery-response",
    CONTROL_CHANNEL_JOIN_REQUEST: "channel-open-request",
    CONTROL_CHANNEL_JOIN_REPLY: "channel-open-response",
    CONTROL_UNACCOUNTED_9: "unaccounted-0x0009",
    CONTROL_UNACCOUNTED_A: "unaccounted-0x000a",
    CONTROL_HEARTBEAT_REQUEST: "ping-request",
    CONTROL_HEARTBEAT_REPLY: "ping-response",
    CONTROL_ROUTE_FOCUS_REQUEST: "navigation-focus-request",
    CONTROL_ROUTE_FOCUS_REPLY: "navigation-focus-response",
    CONTROL_TEARDOWN_REQUEST: "shutdown-request",
    CONTROL_TEARDOWN_REPLY: "shutdown-response",
    CONTROL_VOICE_SESSION: "voice-session-request",
    CONTROL_SOUND_FOCUS_REQUEST: "audio-focus-request",
    CONTROL_SOUND_FOCUS_REPLY: "audio-focus-response",
}

_STREAM_NAMES = {
    MEDIA_WITH_STAMP: "media-with-timestamp",
    MEDIA_BARE: "media",
    STREAM_SETUP_REQUEST: "setup-request",
    STREAM_START_NOTICE: "start-indication",
    STREAM_STOP_NOTICE: "stop-indication",
    STREAM_SETUP_REPLY: "setup-response",
    MEDIA_CONSUMED_NOTICE: "media-ack",
    MICROPHONE_OPEN_REQUEST: "microphone-open-request",
    MICROPHONE_OPEN_REPLY: "microphone-open-response",
    SCREEN_FOCUS_REQUEST: "video-focus-request",
    SCREEN_FOCUS_NOTICE: "video-focus-indication",
}

_INPUT_NAMES = {
    INPUT_REPORT_NOTICE: "input-event",
    INPUT_BINDING_REQUEST: "binding-request",
    INPUT_BINDING_REPLY: "binding-response",
}

_SENSOR_NAMES = {
    SENSOR_FEED_REQUEST: "sensor-start-request",
    SENSOR_FEED_REPLY: "sensor-start-response",
    SENSOR_READING_NOTICE: "sensor-event",
}


class ServiceKind(enum.Enum):
    """What a channel carries, independent of the id it was given."""

    CONTROL = "control"
    INPUT = "input"
    SENSORS = "sensors"
    VIDEO = "video"
    MEDIA_AUDIO = "media-audio"
    SPEECH_AUDIO = "speech-audio"
    SYSTEM_AUDIO = "system-audio"
    MICROPHONE = "microphone"
    BLUETOOTH = "bluetooth"
    PHONE_STATUS = "phone-status"
    NOTIFICATIONS = "notifications"
    NAVIGATION = "navigation"

    @property
    def is_audio_sink(self) -> bool:
        return self in (
            ServiceKind.MEDIA_AUDIO,
            ServiceKind.SPEECH_AUDIO,
            ServiceKind.SYSTEM_AUDIO,
        )

    @property
    def is_media(self) -> bool:
        """Channels that run the A/V setup handshake and carry media bytes."""
        return self is ServiceKind.VIDEO or self.is_audio_sink or self is ServiceKind.MICROPHONE


def message_name(kind: Optional[ServiceKind], message_id: int) -> str:
    """A readable name for a message id, which depends on its channel."""
    if kind in (None, ServiceKind.CONTROL) or message_id < SERVICE_NAMESPACE_FLOOR:
        # Below 0x8000 on a service channel the id still comes from the control
        # namespace -- except the two media ids, which is what the control-flag
        # rule below is really encoding.
        if message_id in (MEDIA_WITH_STAMP, MEDIA_BARE) and kind is not None:
            return _STREAM_NAMES[message_id]
        return _CONTROL_NAMES.get(message_id, f"control-0x{message_id:04x}")
    if kind is ServiceKind.INPUT:
        return _INPUT_NAMES.get(message_id, f"input-0x{message_id:04x}")
    if kind is ServiceKind.SENSORS:
        return _SENSOR_NAMES.get(message_id, f"sensor-0x{message_id:04x}")
    if kind is not None and kind.is_media:
        return _STREAM_NAMES.get(message_id, f"stream-0x{message_id:04x}")
    return f"0x{message_id:04x}"


def expects_control_flag(channel: int, message_id: int) -> bool:
    """Whether frame bit 2 must be set for this (channel, id) pair.

    The bit does not mean "channel 0". It means "this message's id comes from
    the control namespace even though it is addressed to a service channel", so
    all three of the following must hold:

    - the channel is not 0
    - the id is >= 2, which carves out the two media indications 0x0000 and
      0x0001, service messages despite their low numbers
    - the id is < 0x8000, above which the id is a service message

    Channel 0 never sets it. Getting this wrong is survivable in one direction
    and silently corrupting in the other, so the emulator checks both.
    """
    return channel != CONTROL_CHANNEL and 2 <= message_id < SERVICE_NAMESPACE_FLOOR


@dataclass(frozen=True)
class VersionRequest:
    major: int
    minor: int


@dataclass(frozen=True)
class VersionResponse:
    major: int
    minor: int
    status: int

    VERSIONS_MATCH = 0
    VERSIONS_DIFFER = 0xFFFF

    @property
    def matched(self) -> bool:
        return self.status == VersionResponse.VERSIONS_MATCH


def encode_version_request(major: int, minor: int) -> bytes:
    return struct.pack("!HH", major, minor)


def decode_version_request(body: bytes) -> VersionRequest:
    if len(body) != 4:
        raise ValueError(f"version request is 4 bytes, got {len(body)}")
    return VersionRequest(*struct.unpack("!HH", body))


def encode_version_response(major: int, minor: int, status: int) -> bytes:
    return struct.pack("!HHH", major, minor, status)


def decode_version_response(body: bytes) -> VersionResponse:
    if len(body) != 6:
        raise ValueError(f"version response is 6 bytes, got {len(body)}")
    return VersionResponse(*struct.unpack("!HHH", body))


def encode_message(message_id: int, body: bytes) -> bytes:
    """Prefix a body with its big-endian id, forming a message payload."""
    return struct.pack("!H", message_id) + body


def split_message(payload: bytes) -> tuple[int, bytes]:
    """Peel the id off a reassembled payload."""
    if len(payload) < 2:
        raise ValueError(f"payload of {len(payload)} bytes carries no message id")
    return struct.unpack_from("!H", payload, 0)[0], payload[2:]


def decode_media(message_id: int, body: bytes) -> tuple[Optional[int], bytes]:
    """Split a media payload into its optional timestamp and its bytes.

    The timestamp is 8 big-endian bytes of microseconds on a monotonic media
    clock, present only on id 0x0000.
    """
    if message_id == MEDIA_WITH_STAMP:
        if len(body) < 8:
            raise ValueError(f"timestamped media carries {len(body)} bytes, needs at least 8")
        return struct.unpack_from("!Q", body, 0)[0], body[8:]
    return None, body
