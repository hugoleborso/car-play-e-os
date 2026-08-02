from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ContactAction(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CONTACT_DOWN: _ClassVar[ContactAction]
    CONTACT_UP: _ClassVar[ContactAction]
    CONTACT_MOVE: _ClassVar[ContactAction]

class SurfaceKind(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SURFACE_UNSET: _ClassVar[SurfaceKind]
    SURFACE_SINGLE_CONTACT: _ClassVar[SurfaceKind]
    SURFACE_MULTI_CONTACT: _ClassVar[SurfaceKind]
CONTACT_DOWN: ContactAction
CONTACT_UP: ContactAction
CONTACT_MOVE: ContactAction
SURFACE_UNSET: SurfaceKind
SURFACE_SINGLE_CONTACT: SurfaceKind
SURFACE_MULTI_CONTACT: SurfaceKind

class TouchSurfaceInfo(_message.Message):
    __slots__ = ("width_px", "height_px", "kind")
    WIDTH_PX_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_PX_FIELD_NUMBER: _ClassVar[int]
    KIND_FIELD_NUMBER: _ClassVar[int]
    width_px: int
    height_px: int
    kind: SurfaceKind
    def __init__(self, width_px: _Optional[int] = ..., height_px: _Optional[int] = ..., kind: _Optional[_Union[SurfaceKind, str]] = ...) -> None: ...

class TouchContact(_message.Message):
    __slots__ = ("x", "y", "contact_id")
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    CONTACT_ID_FIELD_NUMBER: _ClassVar[int]
    x: int
    y: int
    contact_id: int
    def __init__(self, x: _Optional[int] = ..., y: _Optional[int] = ..., contact_id: _Optional[int] = ...) -> None: ...

class TouchReport(_message.Message):
    __slots__ = ("contact", "action", "action_index")
    CONTACT_FIELD_NUMBER: _ClassVar[int]
    ACTION_FIELD_NUMBER: _ClassVar[int]
    ACTION_INDEX_FIELD_NUMBER: _ClassVar[int]
    contact: _containers.RepeatedCompositeFieldContainer[TouchContact]
    action: ContactAction
    action_index: int
    def __init__(self, contact: _Optional[_Iterable[_Union[TouchContact, _Mapping]]] = ..., action: _Optional[_Union[ContactAction, str]] = ..., action_index: _Optional[int] = ...) -> None: ...

class ButtonEvent(_message.Message):
    __slots__ = ("code", "pressed", "repeated_press")
    CODE_FIELD_NUMBER: _ClassVar[int]
    PRESSED_FIELD_NUMBER: _ClassVar[int]
    REPEATED_PRESS_FIELD_NUMBER: _ClassVar[int]
    code: int
    pressed: bool
    repeated_press: bool
    def __init__(self, code: _Optional[int] = ..., pressed: _Optional[bool] = ..., repeated_press: _Optional[bool] = ...) -> None: ...

class DialEvent(_message.Message):
    __slots__ = ("code", "detents")
    CODE_FIELD_NUMBER: _ClassVar[int]
    DETENTS_FIELD_NUMBER: _ClassVar[int]
    code: int
    detents: int
    def __init__(self, code: _Optional[int] = ..., detents: _Optional[int] = ...) -> None: ...

class ButtonReport(_message.Message):
    __slots__ = ("button", "dial")
    BUTTON_FIELD_NUMBER: _ClassVar[int]
    DIAL_FIELD_NUMBER: _ClassVar[int]
    button: _containers.RepeatedCompositeFieldContainer[ButtonEvent]
    dial: _containers.RepeatedCompositeFieldContainer[DialEvent]
    def __init__(self, button: _Optional[_Iterable[_Union[ButtonEvent, _Mapping]]] = ..., dial: _Optional[_Iterable[_Union[DialEvent, _Mapping]]] = ...) -> None: ...

class InputReportNotice(_message.Message):
    __slots__ = ("stamp_ns", "display_index", "touch", "buttons")
    STAMP_NS_FIELD_NUMBER: _ClassVar[int]
    DISPLAY_INDEX_FIELD_NUMBER: _ClassVar[int]
    TOUCH_FIELD_NUMBER: _ClassVar[int]
    BUTTONS_FIELD_NUMBER: _ClassVar[int]
    stamp_ns: int
    display_index: int
    touch: TouchReport
    buttons: ButtonReport
    def __init__(self, stamp_ns: _Optional[int] = ..., display_index: _Optional[int] = ..., touch: _Optional[_Union[TouchReport, _Mapping]] = ..., buttons: _Optional[_Union[ButtonReport, _Mapping]] = ...) -> None: ...

class BindingRequest(_message.Message):
    __slots__ = ("code",)
    CODE_FIELD_NUMBER: _ClassVar[int]
    code: _containers.RepeatedScalarFieldContainer[int]
    def __init__(self, code: _Optional[_Iterable[int]] = ...) -> None: ...

class BindingReply(_message.Message):
    __slots__ = ("outcome",)
    OUTCOME_FIELD_NUMBER: _ClassVar[int]
    outcome: int
    def __init__(self, outcome: _Optional[int] = ...) -> None: ...
