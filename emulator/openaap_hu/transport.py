# Copyright 2026 The openaap authors.
# Licensed under the Apache License, Version 2.0.

"""Carrying an AAP session over something.

The session state machine deliberately knows nothing about sockets: it eats
bytes through ``feed`` and produces them through ``drain_outbound``. This module
supplies the other half -- a byte pipe and the loop that shuttles between the
two -- and keeps them apart for a concrete reason. The specification describes
three transports for the same byte stream: TCP on port 5288 for the wireless
case, a USB bulk endpoint pair with no additional framing, and Bluetooth RFCOMM
for the wireless bootstrap. Only the first is implemented here, but a USB
version is a ``Link`` with ``read`` and ``write`` over a file descriptor and
nothing else; if the loop lived inside the session, adding it would mean
rewriting the session.

Exactly one phone connects at a time. That is not a simplification -- a head
unit has one screen and one cable, and a server that accepted a second
connection would be modelling something that does not exist.
"""

from __future__ import annotations

import logging
import selectors
import socket
import ssl
import time
from typing import Callable, Optional, Protocol

from .generated import control_pb2
from .session import HeadUnitSession, Phase, ProtocolViolation, SessionError

log = logging.getLogger(__name__)

# The conventional wireless port. Wired USB has no port at all.
DEFAULT_PORT = 5288

# The USB accessory path caps a single bulk transfer at 16384 bytes, so reading
# in the same unit keeps the two transports behaving alike under fragmentation.
READ_SIZE = 16384


class Link(Protocol):
    """A bidirectional byte pipe to one phone.

    Deliberately minimal: everything a USB bulk pair can also offer.
    """

    def read(self, timeout: float) -> Optional[bytes]:
        """Return bytes, ``b""`` when the timeout expired, or None at EOF."""

    def write(self, data: bytes) -> None: ...

    def close(self) -> None: ...

    @property
    def peer(self) -> str: ...


class SocketLink:
    """A ``Link`` over a connected TCP socket."""

    def __init__(self, connection: socket.socket, peer: str) -> None:
        self._connection = connection
        self._peer = peer
        self._selector = selectors.DefaultSelector()
        self._selector.register(connection, selectors.EVENT_READ)
        self._closed = False

    @property
    def peer(self) -> str:
        return self._peer

    def read(self, timeout: float) -> Optional[bytes]:
        if self._closed:
            return None
        if not self._selector.select(timeout):
            return b""
        chunk = self._connection.recv(READ_SIZE)
        if not chunk:
            return None
        return chunk

    def write(self, data: bytes) -> None:
        if data and not self._closed:
            self._connection.sendall(data)

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self._selector.close()
        try:
            self._connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self._connection.close()


class SessionOutcome:
    """Why a session ended, for the CLI's exit code and closing report."""

    def __init__(self, session: HeadUnitSession) -> None:
        self.session = session
        self.violation: Optional[ProtocolViolation] = None
        self.error: Optional[Exception] = None
        self.disconnected = False

    @property
    def clean(self) -> bool:
        return self.violation is None and self.error is None

    def summary(self) -> str:
        if self.violation is not None:
            return f"session failed: [{self.violation.rule}] {self.violation.detail}"
        if self.error is not None:
            return f"session failed: {self.error!r}"
        if self.disconnected:
            return f"phone disconnected in phase {self.session.phase.value}"
        return f"session closed cleanly by the {self.session.closed_by or 'head unit'}"


def run_session(
    link: Link,
    session: HeadUnitSession,
    *,
    poll_interval: float = 0.2,
    idle_timeout: Optional[float] = None,
    on_ready: Optional[Callable[[HeadUnitSession], None]] = None,
) -> SessionOutcome:
    """Shuttle bytes between a link and a session until one of them stops.

    Ordering inside the loop matters. Outbound bytes are flushed *after* every
    feed and again after every tick, because the session releases media credit
    when its outbound buffer is drained -- that being the first moment the phone
    could have seen an acknowledgement. Flushing lazily would let a phone that
    overruns its window look compliant.
    """
    outcome = SessionOutcome(session)
    ready_fired = False
    last_activity = time.monotonic()

    try:
        session.start()
        link.write(session.drain_outbound())

        while session.phase is not Phase.CLOSED:
            chunk = link.read(poll_interval)
            if chunk is None:
                outcome.disconnected = True
                break
            if chunk:
                last_activity = time.monotonic()
                session.feed(chunk)
                link.write(session.drain_outbound())

            session.tick()
            link.write(session.drain_outbound())

            if not ready_fired and on_ready is not None and session.phase is Phase.ACTIVE:
                ready_fired = True
                on_ready(session)
                link.write(session.drain_outbound())

            if idle_timeout is not None and time.monotonic() - last_activity > idle_timeout:
                log.warning("no traffic from the phone for %.0fs; closing", idle_timeout)
                session.request_shutdown()
                link.write(session.drain_outbound())
                break

    except ProtocolViolation as violation:
        outcome.violation = violation
        # Tell the phone why. A bare disconnect is what every existing head unit
        # does and is precisely what makes them impossible to develop against.
        try:
            session.request_shutdown(cause=control_pb2.SHUTDOWN_PROTOCOL_ERROR)
            link.write(session.drain_outbound())
        except (SessionError, OSError):
            pass
    except (OSError, ssl.SSLError, SessionError) as error:
        # A socket that died, a TLS record layer that desynchronised, or the
        # emulator asked to do something impossible. None is the phone's fault,
        # so none becomes a violation -- but all three end the session.
        outcome.error = error
    finally:
        link.close()

    return outcome


class TcpHeadUnitServer:
    """Listens for the one phone that connects, the way a wireless unit does.

    On wireless the head unit is the TCP *server* and the phone dials out --
    the inverse of the TLS roles, where the head unit is the client. Getting
    those two the wrong way round is the first thing to check when nothing
    connects.
    """

    def __init__(self, host: str = "0.0.0.0", port: int = DEFAULT_PORT) -> None:
        self.host = host
        self.port = port
        self._listener: Optional[socket.socket] = None

    def __enter__(self) -> "TcpHeadUnitServer":
        self.open()
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def open(self) -> None:
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind((self.host, self.port))
        listener.listen(1)
        self._listener = listener
        # Port 0 asks the kernel to choose, which is how tests avoid colliding.
        self.port = listener.getsockname()[1]
        log.info("listening for a phone on %s:%d", self.host, self.port)

    def accept(self, timeout: Optional[float] = None) -> SocketLink:
        if self._listener is None:
            raise SessionError("accept() before open()")
        self._listener.settimeout(timeout)
        connection, address = self._listener.accept()
        connection.settimeout(None)
        # Media messages are small and latency-sensitive; Nagle would coalesce
        # an acknowledgement with whatever comes next and inflate the measured
        # round trip for no benefit.
        connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        peer = f"{address[0]}:{address[1]}"
        log.info("phone connected from %s", peer)
        return SocketLink(connection, peer)

    def close(self) -> None:
        if self._listener is not None:
            self._listener.close()
            self._listener = None
