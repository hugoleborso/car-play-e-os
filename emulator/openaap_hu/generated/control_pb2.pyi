import descriptors_pb2 as _descriptors_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class LinkKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    LINK_UNSET: _ClassVar[LinkKind]
    LINK_WIRED: _ClassVar[LinkKind]
    LINK_WIRELESS: _ClassVar[LinkKind]

class ShutdownCause(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SHUTDOWN_UNSET: _ClassVar[ShutdownCause]
    SHUTDOWN_USER: _ClassVar[ShutdownCause]
    SHUTDOWN_DEVICE_OFF: _ClassVar[ShutdownCause]
    SHUTDOWN_PROTOCOL_ERROR: _ClassVar[ShutdownCause]

class SoundFocusWant(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SOUND_WANT_UNSET: _ClassVar[SoundFocusWant]
    SOUND_WANT_HOLD: _ClassVar[SoundFocusWant]
    SOUND_WANT_HOLD_BRIEF: _ClassVar[SoundFocusWant]
    SOUND_WANT_HOLD_DUCKED: _ClassVar[SoundFocusWant]
    SOUND_WANT_RELEASE: _ClassVar[SoundFocusWant]

class SoundFocusState(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SOUND_STATE_UNSET: _ClassVar[SoundFocusState]
    SOUND_STATE_HELD: _ClassVar[SoundFocusState]
    SOUND_STATE_HELD_BRIEF: _ClassVar[SoundFocusState]
    SOUND_STATE_RELEASED: _ClassVar[SoundFocusState]
    SOUND_STATE_PREEMPTED: _ClassVar[SoundFocusState]
    SOUND_STATE_DUCKED: _ClassVar[SoundFocusState]

class RouteFocusWant(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    ROUTE_WANT_UNSET: _ClassVar[RouteFocusWant]
    ROUTE_WANT_HOLD: _ClassVar[RouteFocusWant]
    ROUTE_WANT_RELEASE: _ClassVar[RouteFocusWant]
LINK_UNSET: LinkKind
LINK_WIRED: LinkKind
LINK_WIRELESS: LinkKind
SHUTDOWN_UNSET: ShutdownCause
SHUTDOWN_USER: ShutdownCause
SHUTDOWN_DEVICE_OFF: ShutdownCause
SHUTDOWN_PROTOCOL_ERROR: ShutdownCause
SOUND_WANT_UNSET: SoundFocusWant
SOUND_WANT_HOLD: SoundFocusWant
SOUND_WANT_HOLD_BRIEF: SoundFocusWant
SOUND_WANT_HOLD_DUCKED: SoundFocusWant
SOUND_WANT_RELEASE: SoundFocusWant
SOUND_STATE_UNSET: SoundFocusState
SOUND_STATE_HELD: SoundFocusState
SOUND_STATE_HELD_BRIEF: SoundFocusState
SOUND_STATE_RELEASED: SoundFocusState
SOUND_STATE_PREEMPTED: SoundFocusState
SOUND_STATE_DUCKED: SoundFocusState
ROUTE_WANT_UNSET: RouteFocusWant
ROUTE_WANT_HOLD: RouteFocusWant
ROUTE_WANT_RELEASE: RouteFocusWant

class AuthCompleteNotice(_message.Message):
    __slots__ = ("outcome",)
    OUTCOME_FIELD_NUMBER: _ClassVar[int]
    outcome: int
    def __init__(self, outcome: _Optional[int] = ...) -> None: ...

class ProfileQuery(_message.Message):
    __slots__ = ("phone_label",)
    PHONE_LABEL_FIELD_NUMBER: _ClassVar[int]
    phone_label: str
    def __init__(self, phone_label: _Optional[str] = ...) -> None: ...

class ProfileAnnouncement(_message.Message):
    __slots__ = ("channel", "unit_label", "maker", "model", "model_year", "vehicle_id", "left_hand_drive", "software_build", "software_version", "plays_native_media", "display_label", "link")
    CHANNEL_FIELD_NUMBER: _ClassVar[int]
    UNIT_LABEL_FIELD_NUMBER: _ClassVar[int]
    MAKER_FIELD_NUMBER: _ClassVar[int]
    MODEL_FIELD_NUMBER: _ClassVar[int]
    MODEL_YEAR_FIELD_NUMBER: _ClassVar[int]
    VEHICLE_ID_FIELD_NUMBER: _ClassVar[int]
    LEFT_HAND_DRIVE_FIELD_NUMBER: _ClassVar[int]
    SOFTWARE_BUILD_FIELD_NUMBER: _ClassVar[int]
    SOFTWARE_VERSION_FIELD_NUMBER: _ClassVar[int]
    PLAYS_NATIVE_MEDIA_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_LABEL_FIELD_NUMBER: _ClassVar[int]
    LINK_FIELD_NUMBER: _ClassVar[int]
    channel: _containers.RepeatedCompositeFieldContainer[_descriptors_pb2.ChannelEntry]
    unit_label: str
    maker: str
    model: str
    model_year: str
    vehicle_id: str
    left_hand_drive: bool
    software_build: str
    software_version: str
    plays_native_media: bool
    display_label: str
    link: LinkKind
    def __init__(self, channel: _Optional[_Iterable[_Union[_descriptors_pb2.ChannelEntry, _Mapping]]] = ..., unit_label: _Optional[str] = ..., maker: _Optional[str] = ..., model: _Optional[str] = ..., model_year: _Optional[str] = ..., vehicle_id: _Optional[str] = ..., left_hand_drive: _Optional[bool] = ..., software_build: _Optional[str] = ..., software_version: _Optional[str] = ..., plays_native_media: _Optional[bool] = ..., display_label: _Optional[str] = ..., link: _Optional[_Union[LinkKind, str]] = ...) -> None: ...

class ChannelJoinRequest(_message.Message):
    __slots__ = ("priority", "channel_id")
    PRIORITY_FIELD_NUMBER: _ClassVar[int]
    CHANNEL_ID_FIELD_NUMBER: _ClassVar[int]
    priority: int
    channel_id: int
    def __init__(self, priority: _Optional[int] = ..., channel_id: _Optional[int] = ...) -> None: ...

class ChannelJoinReply(_message.Message):
    __slots__ = ("outcome", "channel_id")
    OUTCOME_FIELD_NUMBER: _ClassVar[int]
    CHANNEL_ID_FIELD_NUMBER: _ClassVar[int]
    outcome: int
    channel_id: int
    def __init__(self, outcome: _Optional[int] = ..., channel_id: _Optional[int] = ...) -> None: ...

class HeartbeatRequest(_message.Message):
    __slots__ = ("stamp_ns", "filler")
    STAMP_NS_FIELD_NUMBER: _ClassVar[int]
    FILLER_FIELD_NUMBER: _ClassVar[int]
    stamp_ns: int
    filler: bytes
    def __init__(self, stamp_ns: _Optional[int] = ..., filler: _Optional[bytes] = ...) -> None: ...

class HeartbeatReply(_message.Message):
    __slots__ = ("stamp_ns", "filler")
    STAMP_NS_FIELD_NUMBER: _ClassVar[int]
    FILLER_FIELD_NUMBER: _ClassVar[int]
    stamp_ns: int
    filler: bytes
    def __init__(self, stamp_ns: _Optional[int] = ..., filler: _Optional[bytes] = ...) -> None: ...

class RouteFocusRequest(_message.Message):
    __slots__ = ("wanted",)
    WANTED_FIELD_NUMBER: _ClassVar[int]
    wanted: RouteFocusWant
    def __init__(self, wanted: _Optional[_Union[RouteFocusWant, str]] = ...) -> None: ...

class RouteFocusReply(_message.Message):
    __slots__ = ("granted",)
    GRANTED_FIELD_NUMBER: _ClassVar[int]
    granted: RouteFocusWant
    def __init__(self, granted: _Optional[_Union[RouteFocusWant, str]] = ...) -> None: ...

class TeardownRequest(_message.Message):
    __slots__ = ("cause",)
    CAUSE_FIELD_NUMBER: _ClassVar[int]
    cause: ShutdownCause
    def __init__(self, cause: _Optional[_Union[ShutdownCause, str]] = ...) -> None: ...

class TeardownReply(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class VoiceSessionNotice(_message.Message):
    __slots__ = ("phase",)
    PHASE_FIELD_NUMBER: _ClassVar[int]
    phase: int
    def __init__(self, phase: _Optional[int] = ...) -> None: ...

class SoundFocusRequest(_message.Message):
    __slots__ = ("wanted",)
    WANTED_FIELD_NUMBER: _ClassVar[int]
    wanted: SoundFocusWant
    def __init__(self, wanted: _Optional[_Union[SoundFocusWant, str]] = ...) -> None: ...

class SoundFocusReply(_message.Message):
    __slots__ = ("state", "unprompted")
    STATE_FIELD_NUMBER: _ClassVar[int]
    UNPROMPTED_FIELD_NUMBER: _ClassVar[int]
    state: SoundFocusState
    unprompted: bool
    def __init__(self, state: _Optional[_Union[SoundFocusState, str]] = ..., unprompted: _Optional[bool] = ...) -> None: ...
