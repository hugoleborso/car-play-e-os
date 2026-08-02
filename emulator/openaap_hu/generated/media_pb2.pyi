from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class StreamKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    STREAM_UNSET: _ClassVar[StreamKind]
    STREAM_SOUND: _ClassVar[StreamKind]
    STREAM_PICTURE: _ClassVar[StreamKind]

class PictureGeometry(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    GEOMETRY_UNSET: _ClassVar[PictureGeometry]
    GEOMETRY_800_480: _ClassVar[PictureGeometry]
    GEOMETRY_1280_720: _ClassVar[PictureGeometry]
    GEOMETRY_1920_1080: _ClassVar[PictureGeometry]

class PictureCadence(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CADENCE_UNSET: _ClassVar[PictureCadence]
    CADENCE_30: _ClassVar[PictureCadence]
    CADENCE_60: _ClassVar[PictureCadence]

class SoundLane(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    LANE_UNSET: _ClassVar[SoundLane]
    LANE_PROGRAM: _ClassVar[SoundLane]
    LANE_GUIDANCE: _ClassVar[SoundLane]
    LANE_ALERT: _ClassVar[SoundLane]

class SetupOutcome(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SETUP_UNSET: _ClassVar[SetupOutcome]
    SETUP_ACCEPTED: _ClassVar[SetupOutcome]
    SETUP_REFUSED: _ClassVar[SetupOutcome]

class ScreenFocusState(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SCREEN_FOCUS_UNSET: _ClassVar[ScreenFocusState]
    SCREEN_FOCUS_PROJECTED: _ClassVar[ScreenFocusState]
    SCREEN_FOCUS_NATIVE: _ClassVar[ScreenFocusState]
STREAM_UNSET: StreamKind
STREAM_SOUND: StreamKind
STREAM_PICTURE: StreamKind
GEOMETRY_UNSET: PictureGeometry
GEOMETRY_800_480: PictureGeometry
GEOMETRY_1280_720: PictureGeometry
GEOMETRY_1920_1080: PictureGeometry
CADENCE_UNSET: PictureCadence
CADENCE_30: PictureCadence
CADENCE_60: PictureCadence
LANE_UNSET: SoundLane
LANE_PROGRAM: SoundLane
LANE_GUIDANCE: SoundLane
LANE_ALERT: SoundLane
SETUP_UNSET: SetupOutcome
SETUP_ACCEPTED: SetupOutcome
SETUP_REFUSED: SetupOutcome
SCREEN_FOCUS_UNSET: ScreenFocusState
SCREEN_FOCUS_PROJECTED: ScreenFocusState
SCREEN_FOCUS_NATIVE: ScreenFocusState

class PictureFormat(_message.Message):
    __slots__ = ("geometry", "cadence", "pad_left", "pad_top", "density_dpi", "decoder_delay_ms")
    GEOMETRY_FIELD_NUMBER: _ClassVar[int]
    CADENCE_FIELD_NUMBER: _ClassVar[int]
    PAD_LEFT_FIELD_NUMBER: _ClassVar[int]
    PAD_TOP_FIELD_NUMBER: _ClassVar[int]
    DENSITY_DPI_FIELD_NUMBER: _ClassVar[int]
    DECODER_DELAY_MS_FIELD_NUMBER: _ClassVar[int]
    geometry: PictureGeometry
    cadence: PictureCadence
    pad_left: int
    pad_top: int
    density_dpi: int
    decoder_delay_ms: int
    def __init__(self, geometry: _Optional[_Union[PictureGeometry, str]] = ..., cadence: _Optional[_Union[PictureCadence, str]] = ..., pad_left: _Optional[int] = ..., pad_top: _Optional[int] = ..., density_dpi: _Optional[int] = ..., decoder_delay_ms: _Optional[int] = ...) -> None: ...

class SoundFormat(_message.Message):
    __slots__ = ("sample_rate", "sample_bits", "lane_count")
    SAMPLE_RATE_FIELD_NUMBER: _ClassVar[int]
    SAMPLE_BITS_FIELD_NUMBER: _ClassVar[int]
    LANE_COUNT_FIELD_NUMBER: _ClassVar[int]
    sample_rate: int
    sample_bits: int
    lane_count: int
    def __init__(self, sample_rate: _Optional[int] = ..., sample_bits: _Optional[int] = ..., lane_count: _Optional[int] = ...) -> None: ...

class StreamSetupRequest(_message.Message):
    __slots__ = ("format_index",)
    FORMAT_INDEX_FIELD_NUMBER: _ClassVar[int]
    format_index: int
    def __init__(self, format_index: _Optional[int] = ...) -> None: ...

class StreamSetupReply(_message.Message):
    __slots__ = ("outcome", "max_unacked", "granted_format_index")
    OUTCOME_FIELD_NUMBER: _ClassVar[int]
    MAX_UNACKED_FIELD_NUMBER: _ClassVar[int]
    GRANTED_FORMAT_INDEX_FIELD_NUMBER: _ClassVar[int]
    outcome: SetupOutcome
    max_unacked: int
    granted_format_index: _containers.RepeatedScalarFieldContainer[int]
    def __init__(self, outcome: _Optional[_Union[SetupOutcome, str]] = ..., max_unacked: _Optional[int] = ..., granted_format_index: _Optional[_Iterable[int]] = ...) -> None: ...

class StreamStartNotice(_message.Message):
    __slots__ = ("session_tag", "target_gain")
    SESSION_TAG_FIELD_NUMBER: _ClassVar[int]
    TARGET_GAIN_FIELD_NUMBER: _ClassVar[int]
    session_tag: int
    target_gain: int
    def __init__(self, session_tag: _Optional[int] = ..., target_gain: _Optional[int] = ...) -> None: ...

class StreamStopNotice(_message.Message):
    __slots__ = ("session_tag",)
    SESSION_TAG_FIELD_NUMBER: _ClassVar[int]
    session_tag: int
    def __init__(self, session_tag: _Optional[int] = ...) -> None: ...

class MediaConsumedNotice(_message.Message):
    __slots__ = ("session_tag", "stamp_us", "released")
    SESSION_TAG_FIELD_NUMBER: _ClassVar[int]
    STAMP_US_FIELD_NUMBER: _ClassVar[int]
    RELEASED_FIELD_NUMBER: _ClassVar[int]
    session_tag: int
    stamp_us: int
    released: _containers.RepeatedScalarFieldContainer[int]
    def __init__(self, session_tag: _Optional[int] = ..., stamp_us: _Optional[int] = ..., released: _Optional[_Iterable[int]] = ...) -> None: ...

class ScreenFocusRequest(_message.Message):
    __slots__ = ("display_index", "wanted")
    DISPLAY_INDEX_FIELD_NUMBER: _ClassVar[int]
    WANTED_FIELD_NUMBER: _ClassVar[int]
    display_index: int
    wanted: ScreenFocusState
    def __init__(self, display_index: _Optional[int] = ..., wanted: _Optional[_Union[ScreenFocusState, str]] = ...) -> None: ...

class ScreenFocusNotice(_message.Message):
    __slots__ = ("state", "unprompted")
    STATE_FIELD_NUMBER: _ClassVar[int]
    UNPROMPTED_FIELD_NUMBER: _ClassVar[int]
    state: ScreenFocusState
    unprompted: bool
    def __init__(self, state: _Optional[_Union[ScreenFocusState, str]] = ..., unprompted: _Optional[bool] = ...) -> None: ...

class MicrophoneOpenRequest(_message.Message):
    __slots__ = ("open", "noise_suppression")
    OPEN_FIELD_NUMBER: _ClassVar[int]
    NOISE_SUPPRESSION_FIELD_NUMBER: _ClassVar[int]
    open: bool
    noise_suppression: bool
    def __init__(self, open: _Optional[bool] = ..., noise_suppression: _Optional[bool] = ...) -> None: ...

class MicrophoneOpenReply(_message.Message):
    __slots__ = ("outcome", "open")
    OUTCOME_FIELD_NUMBER: _ClassVar[int]
    OPEN_FIELD_NUMBER: _ClassVar[int]
    outcome: int
    open: bool
    def __init__(self, outcome: _Optional[int] = ..., open: _Optional[bool] = ...) -> None: ...
