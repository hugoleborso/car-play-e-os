# Copyright 2026 The openaap authors.
# Licensed under the Apache License, Version 2.0.

"""What the emulated head unit claims to be, and how it numbers its channels.

Two things live here that the specification deliberately leaves open. The first
is the head unit's self-description -- name, make, model, year -- which is free
text a phone may key behaviour off but must not depend on. The second is more
interesting: channel ids.

Only channel 0 is fixed. Every other id is chosen by the head unit and
published in the service discovery response, so a phone must build its map at
run time. Hardcoding the conventional ids works against most open-source head
units and then fails in a real car, which is the worst possible failure
schedule -- it passes every test you own. So the default allocator here
deliberately hands out ids that are *not* the conventional ones, and a phone
that hardcoded them fails immediately and loudly instead. The allocation is
seeded, so a failing run is reproducible.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from typing import Optional, Sequence

from .generated import media_pb2
from .wire import ServiceKind

# The conventional assignment, per the specification's own sanity-check table.
# Present so the emulator can reproduce a permissive head unit on request --
# never as a default.
CONVENTIONAL_CHANNEL_IDS: dict[ServiceKind, int] = {
    ServiceKind.CONTROL: 0,
    ServiceKind.INPUT: 1,
    ServiceKind.SENSORS: 2,
    ServiceKind.VIDEO: 3,
    ServiceKind.MEDIA_AUDIO: 4,
    ServiceKind.SPEECH_AUDIO: 5,
    ServiceKind.SYSTEM_AUDIO: 6,
    ServiceKind.MICROPHONE: 7,
    ServiceKind.BLUETOOTH: 8,
    ServiceKind.PHONE_STATUS: 9,
    ServiceKind.NOTIFICATIONS: 10,
    ServiceKind.NAVIGATION: 11,
}

# Scrambled ids are drawn from here: high enough that no conventional id can
# fall out by chance, low enough to stay inside the single byte the header
# gives the channel field.
SCRAMBLED_ID_POOL = range(0x20, 0x80)

# Button codes are not specified anywhere in the wire-format document, so these
# are the emulator's own numbering. A phone must learn them from the input
# source descriptor rather than assuming; that is exactly the property this
# numbering exists to test.
BUTTON_HOME = 0x101
BUTTON_BACK = 0x102
BUTTON_PHONE = 0x103
BUTTON_MEDIA = 0x104
BUTTON_NAVIGATION = 0x105
BUTTON_VOICE = 0x106
BUTTON_NEXT = 0x107
BUTTON_PREVIOUS = 0x108
DIAL_SCROLL = 0x201

DEFAULT_BUTTONS = (
    BUTTON_HOME,
    BUTTON_BACK,
    BUTTON_PHONE,
    BUTTON_MEDIA,
    BUTTON_NAVIGATION,
    BUTTON_VOICE,
    BUTTON_NEXT,
    BUTTON_PREVIOUS,
)

GEOMETRY_BY_PIXELS = {
    (800, 480): media_pb2.GEOMETRY_800_480,
    (1280, 720): media_pb2.GEOMETRY_1280_720,
    (1920, 1080): media_pb2.GEOMETRY_1920_1080,
}
PIXELS_BY_GEOMETRY = {value: key for key, value in GEOMETRY_BY_PIXELS.items()}
CADENCE_BY_FPS = {30: media_pb2.CADENCE_30, 60: media_pb2.CADENCE_60}
FPS_BY_CADENCE = {value: key for key, value in CADENCE_BY_FPS.items()}


def geometry_for(text: str) -> int:
    """Map a ``800x480`` style argument onto the enum index the protocol uses."""
    try:
        width, height = (int(part) for part in text.lower().split("x", 1))
    except ValueError:
        raise ValueError(f"resolution must look like 800x480, got {text!r}") from None
    if (width, height) not in GEOMETRY_BY_PIXELS:
        offered = ", ".join(f"{w}x{h}" for w, h in GEOMETRY_BY_PIXELS)
        raise ValueError(
            f"{text} has no enum index in the protocol; resolutions travel as "
            f"indices, not pixel counts. Known: {offered}"
        )
    return GEOMETRY_BY_PIXELS[(width, height)]


@dataclass(frozen=True)
class SoundSpec:
    sample_rate: int
    sample_bits: int = 16
    lanes: int = 1


@dataclass(frozen=True)
class PictureSpec:
    geometry: int
    cadence: int
    density_dpi: int = 160
    pad_left: int = 0
    pad_top: int = 0
    decoder_delay_ms: int = 0

    def describe(self) -> str:
        width, height = PIXELS_BY_GEOMETRY.get(self.geometry, (0, 0))
        return f"{width}x{height}@{FPS_BY_CADENCE.get(self.cadence, 0)}"


@dataclass(frozen=True)
class TouchSpec:
    width_px: int
    height_px: int
    multi_contact: bool = False


@dataclass(frozen=True)
class ChannelSpec:
    """One entry of the service discovery response, before it gets an id."""

    kind: ServiceKind
    picture: Sequence[PictureSpec] = ()
    sound: Sequence[SoundSpec] = ()
    lane: int = media_pb2.LANE_UNSET
    sensors: Sequence[int] = ()
    buttons: Sequence[int] = ()
    touch: Optional[TouchSpec] = None
    has_dial: bool = False
    buffered_messages: int = 4
    sink_delay_ms: int = 0


@dataclass(frozen=True)
class HeadUnitProfile:
    """Everything the head unit says about itself."""

    name: str
    make: str
    model: str
    year: str
    channels: Sequence[ChannelSpec]
    link_wireless: bool = False
    software_build: str = "openaap-emulator"
    software_version: str = "1.0"
    plays_native_media: bool = True
    left_hand_drive: bool = True
    vehicle_id: str = "OPENAAP-EMULATOR"
    max_unacked: int = 1
    tls_max_version: Optional[str] = "TLSv1.2"

    def channel(self, kind: ServiceKind) -> Optional[ChannelSpec]:
        for spec in self.channels:
            if spec.kind is kind:
                return spec
        return None


class ChannelIdAllocator:
    """Hands out the channel ids announced in the discovery response.

    ``scrambled`` is the default on purpose: see the module docstring.
    """

    SCRAMBLED = "scrambled"
    CONVENTIONAL = "conventional"
    ALL = (SCRAMBLED, CONVENTIONAL)

    def __init__(self, strategy: str = SCRAMBLED, seed: Optional[int] = None) -> None:
        if strategy not in ChannelIdAllocator.ALL:
            raise ValueError(f"unknown channel id strategy: {strategy}")
        self.strategy = strategy
        self.seed = seed

    def assign(self, kinds: Sequence[ServiceKind]) -> dict[ServiceKind, int]:
        service_kinds = [kind for kind in kinds if kind is not ServiceKind.CONTROL]
        if len(set(service_kinds)) != len(service_kinds):
            raise ValueError("a profile may not list the same service twice")

        if self.strategy == ChannelIdAllocator.CONVENTIONAL:
            return {kind: CONVENTIONAL_CHANNEL_IDS[kind] for kind in service_kinds}

        pool = list(SCRAMBLED_ID_POOL)
        if len(service_kinds) > len(pool):
            raise ValueError("more services than there are ids to give them")
        random.Random(self.seed).shuffle(pool)
        return {kind: pool[index] for index, kind in enumerate(service_kinds)}


# The four sound configurations the protocol standardises. Anything else is a
# negotiation the emulator has no evidence for.
SOUND_MEDIA = SoundSpec(sample_rate=48000, sample_bits=16, lanes=2)
SOUND_SPEECH = SoundSpec(sample_rate=16000, sample_bits=16, lanes=1)
SOUND_SYSTEM = SoundSpec(sample_rate=16000, sample_bits=16, lanes=1)
SOUND_MICROPHONE = SoundSpec(sample_rate=16000, sample_bits=16, lanes=1)

# Sensor kinds a car of this vintage actually publishes.
_MIB2_SENSORS = (3, 9, 12, 7)  # road speed, night mode, drive restriction, park brake


def mib2_profile(
    geometry: int = media_pb2.GEOMETRY_800_480,
    cadence: int = media_pb2.CADENCE_30,
) -> HeadUnitProfile:
    """A 2017 MIB2, as closely as the available documents allow.

    Modelled from written evidence:

    - 800x480 at 30 fps. Both are enum indices 1, the lowest the protocol
      defines, which is what a screen of that vintage and size gets.
    - Wired only. ``docs/01-aap-wire-format.md`` states outright that a 2017
      MIB2 has no wireless bootstrap, so the link kind is WIRED and no
      Bluetooth-based bootstrap is offered.
    - TLS 1.2 with no 1.3 fallback: the hardware predates 1.3 by years, and the
      specification says offering 1.3 breaks head units.
    - Sound configurations are the four the specification standardises.
    - A credit window of 1. Open-source head units advertise 1, and a 2017
      infotainment SoC is not the place to assume more buffering than that.

    Guessed, and marked as such so nobody mistakes it for evidence:

    - The button set and their codes. Nothing in the wire-format document
      assigns key codes, so these are invented; a phone must read them from the
      descriptor.
    - Single-contact touch. MIB2 panels of this generation are resistive, but
      no document consulted for this project says so in as many words.
    - Sensor list, decoder delay, buffer depths, and the software version
      strings. All plausible, none observed.
    - The absence of the phone-status, notification and navigation-status
      channels. A real car may well offer them; they are left out so the
      generic profile and this one differ in channel *set* as well as id, which
      is the case a phone is most likely to have got wrong.
    """
    return HeadUnitProfile(
        name="MIB2 emulator",
        make="Volkswagen",
        model="MIB2 Composition Media",
        year="2017",
        link_wireless=False,
        max_unacked=1,
        tls_max_version="TLSv1.2",
        software_build="MIB2-STD-2017",
        software_version="0.9.0",
        channels=(
            ChannelSpec(
                kind=ServiceKind.INPUT,
                buttons=DEFAULT_BUTTONS,
                touch=TouchSpec(800, 480, multi_contact=False),
                has_dial=False,
            ),
            ChannelSpec(kind=ServiceKind.SENSORS, sensors=_MIB2_SENSORS),
            ChannelSpec(
                kind=ServiceKind.VIDEO,
                picture=(PictureSpec(geometry, cadence, density_dpi=160, decoder_delay_ms=30),),
                buffered_messages=2,
            ),
            ChannelSpec(
                kind=ServiceKind.MEDIA_AUDIO,
                sound=(SOUND_MEDIA,),
                lane=media_pb2.LANE_PROGRAM,
                buffered_messages=4,
                sink_delay_ms=40,
            ),
            ChannelSpec(
                kind=ServiceKind.SPEECH_AUDIO,
                sound=(SOUND_SPEECH,),
                lane=media_pb2.LANE_GUIDANCE,
                buffered_messages=4,
            ),
            ChannelSpec(
                kind=ServiceKind.SYSTEM_AUDIO,
                sound=(SOUND_SYSTEM,),
                lane=media_pb2.LANE_ALERT,
                buffered_messages=4,
            ),
            ChannelSpec(kind=ServiceKind.MICROPHONE, sound=(SOUND_MICROPHONE,)),
            ChannelSpec(kind=ServiceKind.BLUETOOTH),
        ),
    )


def generic_profile(
    geometry: int = media_pb2.GEOMETRY_1280_720,
    cadence: int = media_pb2.CADENCE_60,
) -> HeadUnitProfile:
    """A maximal head unit: every channel the descriptor can express.

    Useful as the opposite pole from ``mib2``. Nothing here claims to match
    real hardware; it exists so the phone's discovery handling is exercised
    against every sub-descriptor field number, including the sparse ones.
    """
    return HeadUnitProfile(
        name="openaap generic head unit",
        make="openaap",
        model="reference",
        year="2026",
        link_wireless=True,
        max_unacked=4,
        tls_max_version="TLSv1.2",
        channels=(
            ChannelSpec(
                kind=ServiceKind.INPUT,
                buttons=DEFAULT_BUTTONS,
                touch=TouchSpec(1280, 720, multi_contact=True),
                has_dial=True,
            ),
            ChannelSpec(kind=ServiceKind.SENSORS, sensors=(1, 3, 7, 9, 12)),
            ChannelSpec(
                kind=ServiceKind.VIDEO,
                picture=(
                    PictureSpec(geometry, cadence, density_dpi=200),
                    PictureSpec(media_pb2.GEOMETRY_800_480, media_pb2.CADENCE_30),
                ),
                buffered_messages=8,
            ),
            ChannelSpec(
                kind=ServiceKind.MEDIA_AUDIO,
                sound=(SOUND_MEDIA,),
                lane=media_pb2.LANE_PROGRAM,
                buffered_messages=8,
            ),
            ChannelSpec(
                kind=ServiceKind.SPEECH_AUDIO,
                sound=(SOUND_SPEECH,),
                lane=media_pb2.LANE_GUIDANCE,
            ),
            ChannelSpec(
                kind=ServiceKind.SYSTEM_AUDIO,
                sound=(SOUND_SYSTEM,),
                lane=media_pb2.LANE_ALERT,
            ),
            ChannelSpec(kind=ServiceKind.MICROPHONE, sound=(SOUND_MICROPHONE,)),
            ChannelSpec(kind=ServiceKind.BLUETOOTH),
            ChannelSpec(kind=ServiceKind.PHONE_STATUS),
            ChannelSpec(kind=ServiceKind.NOTIFICATIONS),
            ChannelSpec(kind=ServiceKind.NAVIGATION),
        ),
    )


PROFILES = {"mib2": mib2_profile, "generic": generic_profile}


@dataclass
class ChannelMap:
    """The runtime map the session builds and the trace formats against."""

    by_id: dict[int, ChannelSpec] = field(default_factory=dict)
    id_by_kind: dict[ServiceKind, int] = field(default_factory=dict)

    def kind_of(self, channel: int) -> Optional[ServiceKind]:
        if channel == 0:
            return ServiceKind.CONTROL
        spec = self.by_id.get(channel)
        return spec.kind if spec else None

    def label(self, channel: int) -> str:
        kind = self.kind_of(channel)
        return f"{channel}/{kind.value}" if kind else f"{channel}/unannounced"
