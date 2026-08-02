#!/usr/bin/env python3
# Copyright 2026 The openaap authors.
# Licensed under the Apache License, Version 2.0.

"""Regenerate ``openaap_hu/generated`` from ``openaap_hu/proto``.

Generated protobuf code is checked in so that running the emulator needs only
``protobuf``, not a protoc toolchain. Checked-in generated code rots silently,
so ``--check`` regenerates into a temporary directory and diffs; the test suite
calls it, which turns "someone edited a .proto and forgot to rebuild" from a
mystery wire-format bug into a red test.

protoc emits flat ``import media_pb2`` statements, which only resolve if the
output directory happens to be on sys.path. Rather than mutate sys.path at
import time, the imports are rewritten to package-relative form here -- a
mechanical, easily audited transformation applied to generated code only.
"""

from __future__ import annotations

import argparse
import difflib
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
PROTO_DIR = HERE / "openaap_hu" / "proto"
GENERATED_DIR = HERE / "openaap_hu" / "generated"

# `import foo_pb2 as foo__pb2` at the start of a line, which is exactly what
# protoc writes for a sibling .proto and nothing else writes.
_FLAT_IMPORT = re.compile(r"^import (\w+_pb2)( as \w+)?$", re.MULTILINE)

_PACKAGE_HEADER = '''# Copyright 2026 The openaap authors.
# Licensed under the Apache License, Version 2.0.

"""Generated protobuf modules. Do not edit; run ``generate_protos.py``."""
'''


def _proto_files() -> list[pathlib.Path]:
    return sorted(PROTO_DIR.glob("*.proto"))


def _generate_into(destination: pathlib.Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    command = [
        sys.executable,
        "-m",
        "grpc_tools.protoc",
        f"--proto_path={PROTO_DIR}",
        f"--python_out={destination}",
        f"--pyi_out={destination}",
        *[str(path) for path in _proto_files()],
    ]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"protoc failed:\n{result.stdout}\n{result.stderr}")

    for path in sorted(destination.glob("*_pb2.py")):
        source = path.read_text(encoding="utf-8")
        rewritten = _FLAT_IMPORT.sub(r"from . import \1\2", source)
        if rewritten != source:
            path.write_text(rewritten, encoding="utf-8")

    (destination / "__init__.py").write_text(_PACKAGE_HEADER, encoding="utf-8")


def _snapshot(directory: pathlib.Path) -> dict[str, str]:
    return {
        path.name: path.read_text(encoding="utf-8")
        for path in sorted(directory.iterdir())
        if path.is_file() and path.suffix in (".py", ".pyi")
    }


def check() -> list[str]:
    """Return a human-readable list of drifts; empty means up to date."""
    with tempfile.TemporaryDirectory() as scratch:
        fresh = pathlib.Path(scratch) / "generated"
        _generate_into(fresh)
        expected = _snapshot(fresh)

    if not GENERATED_DIR.exists():
        return ["openaap_hu/generated does not exist"]
    actual = _snapshot(GENERATED_DIR)

    problems: list[str] = []
    for name in sorted(set(expected) | set(actual)):
        if name not in actual:
            problems.append(f"{name}: missing, .proto has been added")
            continue
        if name not in expected:
            problems.append(f"{name}: stale, no .proto produces it any more")
            continue
        if expected[name] != actual[name]:
            diff = "\n".join(
                list(
                    difflib.unified_diff(
                        actual[name].splitlines(),
                        expected[name].splitlines(),
                        fromfile=f"checked-in/{name}",
                        tofile=f"regenerated/{name}",
                        lineterm="",
                    )
                )[:40]
            )
            problems.append(f"{name}: out of date\n{diff}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail instead of writing if the checked-in output is stale",
    )
    arguments = parser.parse_args()

    if arguments.check:
        problems = check()
        for problem in problems:
            print(problem, file=sys.stderr)
        if problems:
            print("run emulator/generate_protos.py to refresh", file=sys.stderr)
            return 1
        print("generated protobuf code is up to date")
        return 0

    if GENERATED_DIR.exists():
        shutil.rmtree(GENERATED_DIR)
    _generate_into(GENERATED_DIR)
    print(f"wrote {len(_proto_files())} modules to {GENERATED_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
