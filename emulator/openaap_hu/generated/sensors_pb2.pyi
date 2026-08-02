from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class SensorKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SENSOR_UNSET: _ClassVar[SensorKind]
    SENSOR_POSITION: _ClassVar[SensorKind]
    SENSOR_HEADING: _ClassVar[SensorKind]
    SENSOR_ROAD_SPEED: _ClassVar[SensorKind]
    SENSOR_ENGINE_SPEED: _ClassVar[SensorKind]
    SENSOR_DISTANCE: _ClassVar[SensorKind]
    SENSOR_FUEL: _ClassVar[SensorKind]
    SENSOR_PARK_BRAKE: _ClassVar[SensorKind]
    SENSOR_TRANSMISSION: _ClassVar[SensorKind]
    SENSOR_NIGHT_MODE: _ClassVar[SensorKind]
    SENSOR_CABIN: _ClassVar[SensorKind]
    SENSOR_CLIMATE: _ClassVar[SensorKind]
    SENSOR_DRIVE_RESTRICTION: _ClassVar[SensorKind]
    SENSOR_WHEEL_TICKS: _ClassVar[SensorKind]
    SENSOR_OCCUPANCY: _ClassVar[SensorKind]
    SENSOR_APERTURE: _ClassVar[SensorKind]
    SENSOR_LAMPS: _ClassVar[SensorKind]
    SENSOR_TYRE_PRESSURE: _ClassVar[SensorKind]
    SENSOR_ACCELEROMETER: _ClassVar[SensorKind]
    SENSOR_GYROSCOPE: _ClassVar[SensorKind]
    SENSOR_SATELLITES: _ClassVar[SensorKind]
SENSOR_UNSET: SensorKind
SENSOR_POSITION: SensorKind
SENSOR_HEADING: SensorKind
SENSOR_ROAD_SPEED: SensorKind
SENSOR_ENGINE_SPEED: SensorKind
SENSOR_DISTANCE: SensorKind
SENSOR_FUEL: SensorKind
SENSOR_PARK_BRAKE: SensorKind
SENSOR_TRANSMISSION: SensorKind
SENSOR_NIGHT_MODE: SensorKind
SENSOR_CABIN: SensorKind
SENSOR_CLIMATE: SensorKind
SENSOR_DRIVE_RESTRICTION: SensorKind
SENSOR_WHEEL_TICKS: SensorKind
SENSOR_OCCUPANCY: SensorKind
SENSOR_APERTURE: SensorKind
SENSOR_LAMPS: SensorKind
SENSOR_TYRE_PRESSURE: SensorKind
SENSOR_ACCELEROMETER: SensorKind
SENSOR_GYROSCOPE: SensorKind
SENSOR_SATELLITES: SensorKind

class SensorFeedRequest(_message.Message):
    __slots__ = ("kind", "min_period_ns")
    KIND_FIELD_NUMBER: _ClassVar[int]
    MIN_PERIOD_NS_FIELD_NUMBER: _ClassVar[int]
    kind: SensorKind
    min_period_ns: int
    def __init__(self, kind: _Optional[_Union[SensorKind, str]] = ..., min_period_ns: _Optional[int] = ...) -> None: ...

class SensorFeedReply(_message.Message):
    __slots__ = ("outcome",)
    OUTCOME_FIELD_NUMBER: _ClassVar[int]
    outcome: int
    def __init__(self, outcome: _Optional[int] = ...) -> None: ...

class RoadSpeedReading(_message.Message):
    __slots__ = ("speed_cm_s", "cruise_engaged")
    SPEED_CM_S_FIELD_NUMBER: _ClassVar[int]
    CRUISE_ENGAGED_FIELD_NUMBER: _ClassVar[int]
    speed_cm_s: int
    cruise_engaged: bool
    def __init__(self, speed_cm_s: _Optional[int] = ..., cruise_engaged: _Optional[bool] = ...) -> None: ...

class NightModeReading(_message.Message):
    __slots__ = ("night",)
    NIGHT_FIELD_NUMBER: _ClassVar[int]
    night: bool
    def __init__(self, night: _Optional[bool] = ...) -> None: ...

class TransmissionReading(_message.Message):
    __slots__ = ("gear",)
    GEAR_FIELD_NUMBER: _ClassVar[int]
    gear: int
    def __init__(self, gear: _Optional[int] = ...) -> None: ...

class DriveRestrictionReading(_message.Message):
    __slots__ = ("restriction_mask",)
    RESTRICTION_MASK_FIELD_NUMBER: _ClassVar[int]
    restriction_mask: int
    def __init__(self, restriction_mask: _Optional[int] = ...) -> None: ...

class PositionReading(_message.Message):
    __slots__ = ("stamp_ms", "latitude_e7", "longitude_e7", "accuracy_mm", "altitude_mm", "bearing_e6", "speed_mm_s")
    STAMP_MS_FIELD_NUMBER: _ClassVar[int]
    LATITUDE_E7_FIELD_NUMBER: _ClassVar[int]
    LONGITUDE_E7_FIELD_NUMBER: _ClassVar[int]
    ACCURACY_MM_FIELD_NUMBER: _ClassVar[int]
    ALTITUDE_MM_FIELD_NUMBER: _ClassVar[int]
    BEARING_E6_FIELD_NUMBER: _ClassVar[int]
    SPEED_MM_S_FIELD_NUMBER: _ClassVar[int]
    stamp_ms: int
    latitude_e7: int
    longitude_e7: int
    accuracy_mm: int
    altitude_mm: int
    bearing_e6: int
    speed_mm_s: int
    def __init__(self, stamp_ms: _Optional[int] = ..., latitude_e7: _Optional[int] = ..., longitude_e7: _Optional[int] = ..., accuracy_mm: _Optional[int] = ..., altitude_mm: _Optional[int] = ..., bearing_e6: _Optional[int] = ..., speed_mm_s: _Optional[int] = ...) -> None: ...

class ParkBrakeReading(_message.Message):
    __slots__ = ("engaged",)
    ENGAGED_FIELD_NUMBER: _ClassVar[int]
    engaged: bool
    def __init__(self, engaged: _Optional[bool] = ...) -> None: ...

class SensorReadingNotice(_message.Message):
    __slots__ = ("road_speed", "night_mode", "transmission", "drive_restriction", "position", "park_brake")
    ROAD_SPEED_FIELD_NUMBER: _ClassVar[int]
    NIGHT_MODE_FIELD_NUMBER: _ClassVar[int]
    TRANSMISSION_FIELD_NUMBER: _ClassVar[int]
    DRIVE_RESTRICTION_FIELD_NUMBER: _ClassVar[int]
    POSITION_FIELD_NUMBER: _ClassVar[int]
    PARK_BRAKE_FIELD_NUMBER: _ClassVar[int]
    road_speed: _containers.RepeatedCompositeFieldContainer[RoadSpeedReading]
    night_mode: _containers.RepeatedCompositeFieldContainer[NightModeReading]
    transmission: _containers.RepeatedCompositeFieldContainer[TransmissionReading]
    drive_restriction: _containers.RepeatedCompositeFieldContainer[DriveRestrictionReading]
    position: _containers.RepeatedCompositeFieldContainer[PositionReading]
    park_brake: _containers.RepeatedCompositeFieldContainer[ParkBrakeReading]
    def __init__(self, road_speed: _Optional[_Iterable[_Union[RoadSpeedReading, _Mapping]]] = ..., night_mode: _Optional[_Iterable[_Union[NightModeReading, _Mapping]]] = ..., transmission: _Optional[_Iterable[_Union[TransmissionReading, _Mapping]]] = ..., drive_restriction: _Optional[_Iterable[_Union[DriveRestrictionReading, _Mapping]]] = ..., position: _Optional[_Iterable[_Union[PositionReading, _Mapping]]] = ..., park_brake: _Optional[_Iterable[_Union[ParkBrakeReading, _Mapping]]] = ...) -> None: ...
