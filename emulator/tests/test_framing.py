# Copyright 2026 The openaap authors.
# Licensed under the Apache License, Version 2.0.

"""Framing tests, including the shared golden vectors.

The vector file is the contract between this implementation and the Kotlin one.
Both suites read it; if either drifts, one of them goes red.
"""

from __future__ import annotations

import json
import os
import random
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from openaap_hu.framing import (  # noqa: E402
    FLAG_CONTROL,
    FLAG_ENCRYPTED,
    FrameDecoder,
    FrameFormatError,
    MessageAssembler,
    encode,
)

VECTORS_PATH = os.path.join(
    os.path.dirname(__file__), "..", "..", "testdata", "frame-vectors.json"
)

with open(VECTORS_PATH, encoding="utf-8") as handle:
    VECTORS = json.load(handle)


@pytest.mark.parametrize("vector", VECTORS["vectors"], ids=lambda v: v["name"])
def test_encoder_matches_golden_vector(vector):
    frames = encode(
        vector["channel"],
        vector["flags"],
        bytes.fromhex(vector["payload"]),
        vector["fragmentSize"],
    )
    assert [frame.hex() for frame in frames] == vector["frames"]


@pytest.mark.parametrize("vector", VECTORS["vectors"], ids=lambda v: v["name"])
def test_decoder_round_trips_golden_vector(vector):
    decoder = FrameDecoder()
    assembler = MessageAssembler()
    assembled = []
    for wire in vector["frames"]:
        decoder.feed(bytes.fromhex(wire))
        for frame in decoder.drain():
            message = assembler.accept(frame)
            if message is not None:
                assembled.append(message)

    assert len(assembled) == 1
    assert assembled[0].channel == vector["channel"]
    assert assembled[0].payload == bytes.fromhex(vector["payload"])
    assert decoder.buffered == 0


@pytest.mark.parametrize(
    "vector", VECTORS["assemblyVectors"], ids=lambda v: v["name"]
)
def test_assembly_vector(vector):
    decoder = FrameDecoder()
    assembler = MessageAssembler()
    assembled = []
    for wire in vector["frames"]:
        decoder.feed(bytes.fromhex(wire))
        for frame in decoder.drain():
            message = assembler.accept(frame)
            if message is not None:
                assembled.append(message)

    assert len(assembled) == len(vector["expected"])
    for actual, expected in zip(assembled, vector["expected"]):
        assert actual.channel == expected["channel"]
        assert actual.payload == bytes.fromhex(expected["payload"])


@pytest.mark.parametrize("vector", VECTORS["rejectVectors"], ids=lambda v: v["name"])
def test_malformed_input_is_rejected(vector):
    decoder = FrameDecoder()
    assembler = MessageAssembler()
    with pytest.raises(FrameFormatError):
        for wire in vector["frames"]:
            decoder.feed(bytes.fromhex(wire))
            for frame in decoder.drain():
                assembler.accept(frame)


def test_decoder_tolerates_reads_that_split_frames():
    payload = random.Random(11).randbytes(5000)
    wire = b"".join(encode(4, FLAG_CONTROL, payload, fragment_size=700))

    decoder = FrameDecoder()
    assembler = MessageAssembler()
    assembled = None

    rng = random.Random(12)
    offset = 0
    while offset < len(wire):
        chunk = min(rng.randint(1, 37), len(wire) - offset)
        decoder.feed(wire[offset : offset + chunk])
        offset += chunk
        for frame in decoder.drain():
            message = assembler.accept(frame)
            if message is not None:
                assembled = message

    assert assembled is not None
    assert assembled.payload == payload
    assert assembled.is_control


def test_large_payload_fragments_and_reassembles():
    # A 1080p keyframe is comfortably past the fragment size and is the case
    # that actually matters in a car.
    payload = random.Random(13).randbytes(400_000)
    frames = encode(9, FLAG_ENCRYPTED, payload)
    assert len(frames) > 1

    decoder = FrameDecoder()
    assembler = MessageAssembler()
    assembled = None
    for frame in frames:
        decoder.feed(frame)
        for parsed in decoder.drain():
            message = assembler.accept(parsed)
            if message is not None:
                assembled = message

    assert assembled is not None
    assert assembled.payload == payload
    assert assembled.is_encrypted
