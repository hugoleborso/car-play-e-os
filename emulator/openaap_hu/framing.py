# Copyright 2026 The openaap authors.
# Licensed under the Apache License, Version 2.0.

"""AAP link-layer framing, head-unit side.

This is deliberately a second, independent implementation of the same wire
format the Kotlin ``:protocol`` module implements. Testing a protocol against a
peer that shares its codec proves very little: the two agree because they make
the same mistakes. Written separately, in another language, from the same
written specification, the emulator disagrees with the phone whenever one of
them has misread the spec -- which is the entire point of having it.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass, field
from typing import Iterator, Optional

# Flag bits in the second header byte.
FLAG_FIRST = 1 << 0
FLAG_LAST = 1 << 1
FLAG_CONTROL = 1 << 2
FLAG_ENCRYPTED = 1 << 3
FLAG_BULK = FLAG_FIRST | FLAG_LAST

BASE_HEADER_SIZE = 4
EXTENDED_HEADER_SIZE = 8
MAX_PAYLOAD = 0xFFFF
DEFAULT_FRAGMENT_SIZE = 16 * 1024


class FrameFormatError(Exception):
    """The peer sent bytes that are not a well-formed frame."""


def describe_flags(flags: int) -> str:
    parts = []
    fragmentation = flags & FLAG_BULK
    if fragmentation == FLAG_BULK:
        parts.append("BULK")
    elif fragmentation == FLAG_FIRST:
        parts.append("FIRST")
    elif fragmentation == FLAG_LAST:
        parts.append("LAST")
    else:
        parts.append("MIDDLE")
    if flags & FLAG_CONTROL:
        parts.append("CONTROL")
    if flags & FLAG_ENCRYPTED:
        parts.append("ENCRYPTED")
    return "|".join(parts)


@dataclass(frozen=True)
class Frame:
    channel: int
    flags: int
    payload: bytes
    total_length: Optional[int] = None

    @property
    def is_first(self) -> bool:
        return bool(self.flags & FLAG_FIRST)

    @property
    def is_last(self) -> bool:
        return bool(self.flags & FLAG_LAST)

    @property
    def is_control(self) -> bool:
        return bool(self.flags & FLAG_CONTROL)

    @property
    def is_encrypted(self) -> bool:
        return bool(self.flags & FLAG_ENCRYPTED)

    def __repr__(self) -> str:  # pragma: no cover - debugging aid
        return (
            f"Frame(channel={self.channel}, flags={describe_flags(self.flags)}, "
            f"payload={len(self.payload)}B)"
        )


def encode(
    channel: int,
    flags: int,
    payload: bytes,
    fragment_size: int = DEFAULT_FRAGMENT_SIZE,
) -> list[bytes]:
    """Serialise one logical message into one or more frames.

    ``flags`` carries only the semantic bits; fragmentation bits are derived.
    """
    if not 0 <= channel <= 0xFF:
        raise ValueError(f"channel out of range: {channel}")
    if not 1 <= fragment_size <= MAX_PAYLOAD:
        raise ValueError(f"bad fragment size: {fragment_size}")

    semantic = flags & (FLAG_CONTROL | FLAG_ENCRYPTED)

    if len(payload) <= fragment_size:
        return [_serialise(channel, semantic | FLAG_BULK, payload, None)]

    out: list[bytes] = []
    total = len(payload)
    offset = 0
    while offset < total:
        chunk = payload[offset : offset + fragment_size]
        first = offset == 0
        last = offset + len(chunk) >= total
        frame_flags = semantic
        if first:
            frame_flags |= FLAG_FIRST
        if last:
            frame_flags |= FLAG_LAST
        # Only the opening fragment announces the reassembled size.
        out.append(_serialise(channel, frame_flags, chunk, total if first else None))
        offset += len(chunk)
    return out


def _serialise(channel: int, flags: int, payload: bytes, total_length: Optional[int]) -> bytes:
    header = struct.pack("!BBH", channel, flags, len(payload))
    if total_length is not None:
        header += struct.pack("!I", total_length)
    return header + payload


class FrameDecoder:
    """Incremental parser fed by whatever the transport managed to read."""

    def __init__(self) -> None:
        self._buffer = bytearray()

    def feed(self, data: bytes) -> None:
        self._buffer += data

    @property
    def buffered(self) -> int:
        return len(self._buffer)

    def poll(self) -> Optional[Frame]:
        """Return the next whole frame, or None when more bytes are needed."""
        if len(self._buffer) < BASE_HEADER_SIZE:
            return None

        channel, flags, payload_length = struct.unpack_from("!BBH", self._buffer, 0)

        fragmented = bool(flags & FLAG_FIRST) and not (flags & FLAG_LAST)
        header_size = EXTENDED_HEADER_SIZE if fragmented else BASE_HEADER_SIZE
        if len(self._buffer) < header_size:
            return None

        total_length = None
        if fragmented:
            (total_length,) = struct.unpack_from("!I", self._buffer, 4)
            if total_length < payload_length:
                raise FrameFormatError(
                    f"total length {total_length} smaller than first fragment "
                    f"{payload_length} on channel {channel}"
                )

        frame_size = header_size + payload_length
        if len(self._buffer) < frame_size:
            return None

        payload = bytes(self._buffer[header_size:frame_size])
        del self._buffer[:frame_size]
        return Frame(channel, flags, payload, total_length)

    def drain(self) -> Iterator[Frame]:
        """Yield every frame currently available."""
        while True:
            frame = self.poll()
            if frame is None:
                return
            yield frame


@dataclass
class _Partial:
    total_length: int
    flags: int
    chunks: list[bytes] = field(default_factory=list)
    received: int = 0


@dataclass(frozen=True)
class Message:
    """A reassembled message, before the message-id prefix is stripped."""

    channel: int
    flags: int
    payload: bytes

    @property
    def is_control(self) -> bool:
        return bool(self.flags & FLAG_CONTROL)

    @property
    def is_encrypted(self) -> bool:
        return bool(self.flags & FLAG_ENCRYPTED)


class MessageAssembler:
    """Per-channel reassembly.

    Channels interleave: a fragmented video frame is routinely interrupted by
    ping and sensor traffic, so a single global reassembly buffer would splice
    unrelated payloads together.
    """

    def __init__(self, max_message_bytes: int = 8 * 1024 * 1024) -> None:
        self._partials: dict[int, _Partial] = {}
        self._max_message_bytes = max_message_bytes

    def accept(self, frame: Frame) -> Optional[Message]:
        channel = frame.channel

        if frame.is_first and frame.is_last:
            if self._partials.pop(channel, None) is not None:
                raise FrameFormatError(
                    f"channel {channel} sent an unfragmented message while a "
                    "fragmented one was open"
                )
            return Message(channel, frame.flags, frame.payload)

        if frame.is_first:
            if frame.total_length is None:
                raise FrameFormatError(f"first fragment on channel {channel} has no total length")
            if frame.total_length > self._max_message_bytes:
                raise FrameFormatError(
                    f"message of {frame.total_length} bytes on channel {channel} exceeds cap"
                )
            partial = _Partial(frame.total_length, frame.flags)
            partial.chunks.append(frame.payload)
            partial.received = len(frame.payload)
            self._partials[channel] = partial
            return None

        partial = self._partials.get(channel)
        if partial is None:
            raise FrameFormatError(
                f"continuation frame on channel {channel} with no open message"
            )

        semantic = FLAG_CONTROL | FLAG_ENCRYPTED
        if (frame.flags & semantic) != (partial.flags & semantic):
            raise FrameFormatError(
                f"fragment flags changed mid-message on channel {channel}: "
                f"{describe_flags(partial.flags)} -> {describe_flags(frame.flags)}"
            )

        partial.chunks.append(frame.payload)
        partial.received += len(frame.payload)
        if partial.received > partial.total_length:
            del self._partials[channel]
            raise FrameFormatError(
                f"channel {channel} overran its announced length: "
                f"{partial.received} > {partial.total_length}"
            )

        if not frame.is_last:
            return None

        del self._partials[channel]
        if partial.received != partial.total_length:
            raise FrameFormatError(
                f"channel {channel} ended short: {partial.received} of {partial.total_length}"
            )
        return Message(channel, partial.flags, b"".join(partial.chunks))

    def reset(self) -> None:
        self._partials.clear()
