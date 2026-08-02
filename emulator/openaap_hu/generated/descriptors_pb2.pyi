import media_pb2 as _media_pb2
import sensors_pb2 as _sensors_pb2
import input_pb2 as _input_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class SensorSlot(_message.Message):
    __slots__ = ("kind",)
    KIND_FIELD_NUMBER: _ClassVar[int]
    kind: _sensors_pb2.SensorKind
    def __init__(self, kind: _Optional[_Union[_sensors_pb2.SensorKind, str]] = ...) -> None: ...

class SensorSourceInfo(_message.Message):
    __slots__ = ("slot",)
    SLOT_FIELD_NUMBER: _ClassVar[int]
    slot: _containers.RepeatedCompositeFieldContainer[SensorSlot]
    def __init__(self, slot: _Optional[_Iterable[_Union[SensorSlot, _Mapping]]] = ...) -> None: ...

class MediaSinkInfo(_message.Message):
    __slots__ = ("stream", "lane", "buffered_messages", "sound_format", "picture_format", "sink_delay_ms")
    STREAM_FIELD_NUMBER: _ClassVar[int]
    LANE_FIELD_NUMBER: _ClassVar[int]
    BUFFERED_MESSAGES_FIELD_NUMBER: _ClassVar[int]
    SOUND_FORMAT_FIELD_NUMBER: _ClassVar[int]
    PICTURE_FORMAT_FIELD_NUMBER: _ClassVar[int]
    SINK_DELAY_MS_FIELD_NUMBER: _ClassVar[int]
    stream: _media_pb2.StreamKind
    lane: _media_pb2.SoundLane
    buffered_messages: int
    sound_format: _containers.RepeatedCompositeFieldContainer[_media_pb2.SoundFormat]
    picture_format: _containers.RepeatedCompositeFieldContainer[_media_pb2.PictureFormat]
    sink_delay_ms: int
    def __init__(self, stream: _Optional[_Union[_media_pb2.StreamKind, str]] = ..., lane: _Optional[_Union[_media_pb2.SoundLane, str]] = ..., buffered_messages: _Optional[int] = ..., sound_format: _Optional[_Iterable[_Union[_media_pb2.SoundFormat, _Mapping]]] = ..., picture_format: _Optional[_Iterable[_Union[_media_pb2.PictureFormat, _Mapping]]] = ..., sink_delay_ms: _Optional[int] = ...) -> None: ...

class MediaSourceInfo(_message.Message):
    __slots__ = ("stream", "sound_format", "gain_control")
    STREAM_FIELD_NUMBER: _ClassVar[int]
    SOUND_FORMAT_FIELD_NUMBER: _ClassVar[int]
    GAIN_CONTROL_FIELD_NUMBER: _ClassVar[int]
    stream: _media_pb2.StreamKind
    sound_format: _media_pb2.SoundFormat
    gain_control: bool
    def __init__(self, stream: _Optional[_Union[_media_pb2.StreamKind, str]] = ..., sound_format: _Optional[_Union[_media_pb2.SoundFormat, _Mapping]] = ..., gain_control: _Optional[bool] = ...) -> None: ...

class InputSourceInfo(_message.Message):
    __slots__ = ("code", "touch_surface", "has_dial")
    CODE_FIELD_NUMBER: _ClassVar[int]
    TOUCH_SURFACE_FIELD_NUMBER: _ClassVar[int]
    HAS_DIAL_FIELD_NUMBER: _ClassVar[int]
    code: _containers.RepeatedScalarFieldContainer[int]
    touch_surface: _input_pb2.TouchSurfaceInfo
    has_dial: bool
    def __init__(self, code: _Optional[_Iterable[int]] = ..., touch_surface: _Optional[_Union[_input_pb2.TouchSurfaceInfo, _Mapping]] = ..., has_dial: _Optional[bool] = ...) -> None: ...

class BluetoothInfo(_message.Message):
    __slots__ = ("adapter_address", "pairing_method")
    ADAPTER_ADDRESS_FIELD_NUMBER: _ClassVar[int]
    PAIRING_METHOD_FIELD_NUMBER: _ClassVar[int]
    adapter_address: str
    pairing_method: _containers.RepeatedScalarFieldContainer[int]
    def __init__(self, adapter_address: _Optional[str] = ..., pairing_method: _Optional[_Iterable[int]] = ...) -> None: ...

class NavigationStatusInfo(_message.Message):
    __slots__ = ("min_interval_ms", "status_lines", "line_columns")
    MIN_INTERVAL_MS_FIELD_NUMBER: _ClassVar[int]
    STATUS_LINES_FIELD_NUMBER: _ClassVar[int]
    LINE_COLUMNS_FIELD_NUMBER: _ClassVar[int]
    min_interval_ms: int
    status_lines: int
    line_columns: int
    def __init__(self, min_interval_ms: _Optional[int] = ..., status_lines: _Optional[int] = ..., line_columns: _Optional[int] = ...) -> None: ...

class PhoneStatusInfo(_message.Message):
    __slots__ = ("call_control", "signal_strength")
    CALL_CONTROL_FIELD_NUMBER: _ClassVar[int]
    SIGNAL_STRENGTH_FIELD_NUMBER: _ClassVar[int]
    call_control: bool
    signal_strength: bool
    def __init__(self, call_control: _Optional[bool] = ..., signal_strength: _Optional[bool] = ...) -> None: ...

class VendorExtensionInfo(_message.Message):
    __slots__ = ("vendor", "capability", "payload")
    VENDOR_FIELD_NUMBER: _ClassVar[int]
    CAPABILITY_FIELD_NUMBER: _ClassVar[int]
    PAYLOAD_FIELD_NUMBER: _ClassVar[int]
    vendor: str
    capability: _containers.RepeatedScalarFieldContainer[str]
    payload: bytes
    def __init__(self, vendor: _Optional[str] = ..., capability: _Optional[_Iterable[str]] = ..., payload: _Optional[bytes] = ...) -> None: ...

class GenericNotificationInfo(_message.Message):
    __slots__ = ("source",)
    SOURCE_FIELD_NUMBER: _ClassVar[int]
    source: str
    def __init__(self, source: _Optional[str] = ...) -> None: ...

class ChannelEntry(_message.Message):
    __slots__ = ("channel_id", "sensor_source", "media_sink", "input_source", "media_source", "bluetooth", "navigation_status", "phone_status", "vendor_extension", "generic_notification")
    CHANNEL_ID_FIELD_NUMBER: _ClassVar[int]
    SENSOR_SOURCE_FIELD_NUMBER: _ClassVar[int]
    MEDIA_SINK_FIELD_NUMBER: _ClassVar[int]
    INPUT_SOURCE_FIELD_NUMBER: _ClassVar[int]
    MEDIA_SOURCE_FIELD_NUMBER: _ClassVar[int]
    BLUETOOTH_FIELD_NUMBER: _ClassVar[int]
    NAVIGATION_STATUS_FIELD_NUMBER: _ClassVar[int]
    PHONE_STATUS_FIELD_NUMBER: _ClassVar[int]
    VENDOR_EXTENSION_FIELD_NUMBER: _ClassVar[int]
    GENERIC_NOTIFICATION_FIELD_NUMBER: _ClassVar[int]
    channel_id: int
    sensor_source: SensorSourceInfo
    media_sink: MediaSinkInfo
    input_source: InputSourceInfo
    media_source: MediaSourceInfo
    bluetooth: BluetoothInfo
    navigation_status: NavigationStatusInfo
    phone_status: PhoneStatusInfo
    vendor_extension: VendorExtensionInfo
    generic_notification: GenericNotificationInfo
    def __init__(self, channel_id: _Optional[int] = ..., sensor_source: _Optional[_Union[SensorSourceInfo, _Mapping]] = ..., media_sink: _Optional[_Union[MediaSinkInfo, _Mapping]] = ..., input_source: _Optional[_Union[InputSourceInfo, _Mapping]] = ..., media_source: _Optional[_Union[MediaSourceInfo, _Mapping]] = ..., bluetooth: _Optional[_Union[BluetoothInfo, _Mapping]] = ..., navigation_status: _Optional[_Union[NavigationStatusInfo, _Mapping]] = ..., phone_status: _Optional[_Union[PhoneStatusInfo, _Mapping]] = ..., vendor_extension: _Optional[_Union[VendorExtensionInfo, _Mapping]] = ..., generic_notification: _Optional[_Union[GenericNotificationInfo, _Mapping]] = ...) -> None: ...
