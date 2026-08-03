#!/usr/bin/env python3
# Copyright 2026 The openaap authors.
# Licensed under the Apache License, Version 2.0.

"""Run the head-unit emulator and print what the phone said.

The trace this prints is the point of the program. A phone that fails against a
real car gets a blank screen and no logs; against this it gets an aligned line
per message with the channel, the frame flags, the byte count and the decoded
body, and a named rule when something is wrong. Anything that makes that trace
easier to read is worth the code.

    emulator/run_emulator.py --profile mib2 --trust-policy lenient
"""

from __future__ import annotations

import argparse
import logging
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])

from openaap_hu import pki  # noqa: E402
from openaap_hu.profile import PROFILES, ChannelIdAllocator  # noqa: E402
from openaap_hu.session import (  # noqa: E402
    HeadUnitSession,
    TraceEvent,
    format_trace_event,
)
from openaap_hu.tls import TrustPolicy  # noqa: E402
from openaap_hu.transport import DEFAULT_PORT, TcpHeadUnitServer, run_session  # noqa: E402

log = logging.getLogger("openaap.emulator")

# ANSI, applied only to a terminal. A trace piped into a file or a CI log stays
# plain, because half the value of it is being greppable.
_RESET = "\033[0m"
_DIM = "\033[2m"
_OUT = "\033[36m"  # head unit -> phone
_IN = "\033[33m"  # phone -> head unit


def _parse_version(text: str) -> tuple[int, int]:
    try:
        major, minor = (int(part) for part in text.split(".", 1))
    except ValueError:
        raise argparse.ArgumentTypeError(
            f"protocol version must look like 1.6, got {text!r}"
        ) from None
    if not 0 <= major <= 0xFFFF or not 0 <= minor <= 0xFFFF:
        raise argparse.ArgumentTypeError("both halves of the version are uint16")
    return major, minor


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="run_emulator.py",
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--port",
        type=int,
        default=DEFAULT_PORT,
        help=f"TCP port to listen on; 0 lets the kernel choose (default {DEFAULT_PORT})",
    )
    parser.add_argument("--host", default="0.0.0.0", help="address to bind (default 0.0.0.0)")
    parser.add_argument(
        "--trust-policy",
        choices=TrustPolicy.ALL,
        default=TrustPolicy.LENIENT,
        help=(
            "how hard the head unit looks at the phone's certificate: "
            "lenient asks and accepts anything, strict pins a CA the phone cannot "
            "chain to, none never asks (default lenient)"
        ),
    )
    parser.add_argument(
        "--profile",
        choices=sorted(PROFILES),
        default="mib2",
        help="which head unit to impersonate (default mib2)",
    )
    parser.add_argument(
        "--max-unacked",
        type=int,
        default=None,
        help="credit window advertised in every setup response; overrides the profile",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=None,
        help="seed for the channel id allocator, so a failing run is reproducible",
    )
    parser.add_argument(
        "--channel-ids",
        choices=ChannelIdAllocator.ALL,
        default=ChannelIdAllocator.SCRAMBLED,
        help=(
            "scrambled hands out ids that are deliberately not the conventional ones, "
            "which fails a phone that hardcoded them; conventional is for comparing "
            "against other head units (default scrambled)"
        ),
    )
    parser.add_argument(
        "--protocol-version",
        type=_parse_version,
        default="1.6",
        help="version offered in the version request, as major.minor (default 1.6)",
    )
    parser.add_argument(
        "--log-level",
        choices=("debug", "info", "warning", "error"),
        default="info",
        help="verbosity of everything that is not the message trace (default info)",
    )
    parser.add_argument(
        "--ping-interval",
        type=float,
        default=5.0,
        help="seconds between ping requests (default 5)",
    )
    parser.add_argument(
        "--idle-timeout",
        type=float,
        default=None,
        help="close the session after this many seconds without inbound traffic",
    )
    parser.add_argument(
        "--defer-auth-complete",
        action="store_true",
        help=(
            "do not send auth complete automatically; models a head unit that gates the "
            "go signal on something slow, and reproduces a phone that races ahead of it"
        ),
    )
    parser.add_argument(
        "--no-auto-ack",
        action="store_true",
        help="never acknowledge media, to see how the phone behaves once its credit runs out",
    )
    parser.add_argument(
        "--once",
        action="store_true",
        help="exit after the first phone disconnects instead of listening again",
    )
    return parser


def make_tracer(stream=None) -> "callable":
    """Return a trace sink that prints aligned, optionally coloured, lines."""
    handle = stream if stream is not None else sys.stdout
    coloured = hasattr(handle, "isatty") and handle.isatty()

    def emit(event: TraceEvent) -> None:
        line = format_trace_event(event)
        if coloured:
            line = f"{_OUT if event.outbound else _IN}{line}{_RESET}"
        print(line, file=handle, flush=True)

    return emit


def _banner(session: HeadUnitSession, arguments: argparse.Namespace) -> str:
    lines = [
        f"{session.profile.name} -- {session.profile.make} {session.profile.model} "
        f"{session.profile.year}",
        f"  trust policy   {arguments.trust_policy}",
        f"  channel ids    {arguments.channel_ids}"
        + (f", seed {arguments.seed}" if arguments.seed is not None else ""),
        f"  credit window  {session.max_unacked}",
        f"  offering       version {session.protocol_version[0]}.{session.protocol_version[1]}",
        "  channels       "
        + ", ".join(
            f"{channel_id}={kind.value}"
            for kind, channel_id in sorted(session.channel_ids.items(), key=lambda item: item[1])
        ),
    ]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    arguments = build_parser().parse_args(argv)
    if isinstance(arguments.protocol_version, str):
        arguments.protocol_version = _parse_version(arguments.protocol_version)

    logging.basicConfig(
        level=getattr(logging, arguments.log_level.upper()),
        format="%(levelname)-7s %(name)s: %(message)s",
    )

    # A pinned authority the phone cannot possibly chain to is the whole content
    # of the strict policy: it reproduces the pessimistic hypothesis about real
    # hardware, in which a clean-room phone cannot connect at all.
    if arguments.trust_policy == TrustPolicy.STRICT:
        authority = pki.Authority("openaap emulator pinned authority")
        credential = authority.issue("emulated head unit")
        trusted_ca_pem = authority.certificate_pem
    else:
        credential = pki.self_signed("emulated head unit")
        trusted_ca_pem = None

    profile = PROFILES[arguments.profile]()
    tracer = make_tracer()

    server = TcpHeadUnitServer(arguments.host, arguments.port)
    server.open()
    print(f"listening on {arguments.host}:{server.port}", file=sys.stderr, flush=True)

    exit_code = 0
    try:
        while True:
            link = server.accept()
            session = HeadUnitSession(
                profile,
                credential,
                trust_policy=arguments.trust_policy,
                trusted_ca_pem=trusted_ca_pem,
                protocol_version=arguments.protocol_version,
                max_unacked=arguments.max_unacked,
                channel_strategy=arguments.channel_ids,
                seed=arguments.seed,
                ping_interval=arguments.ping_interval,
                auto_auth_complete=not arguments.defer_auth_complete,
                auto_acknowledge_media=not arguments.no_auto_ack,
                trace=tracer,
            )
            print(f"{_DIM}{_banner(session, arguments)}{_RESET}", file=sys.stderr, flush=True)

            outcome = run_session(
                link, session, idle_timeout=arguments.idle_timeout
            )
            _report(session, outcome)
            if not outcome.clean:
                exit_code = 1
            if arguments.once:
                break
    except KeyboardInterrupt:
        print("interrupted", file=sys.stderr)
    finally:
        server.close()
    return exit_code


def _report(session: HeadUnitSession, outcome) -> None:
    print("", file=sys.stderr)
    print(outcome.summary(), file=sys.stderr)
    print(f"  messages traced   {len(session.trace_log)}", file=sys.stderr)
    print(f"  final phase       {session.phase.value}", file=sys.stderr)
    if session.round_trip_samples:
        average = sum(session.round_trip_samples) / len(session.round_trip_samples)
        print(
            f"  ping round trip   {average * 1000:.1f}ms over "
            f"{len(session.round_trip_samples)} samples",
            file=sys.stderr,
        )
    for warning in session.warnings:
        print(f"  warning: {warning}", file=sys.stderr)
    for violation in session.violations:
        print(f"  VIOLATION [{violation.rule}] {violation.detail}", file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
