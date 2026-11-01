#!/usr/bin/env python3
"""Require public sign-in screenshots copied by AGP before device teardown."""
from pathlib import Path
import shutil
import struct


def main() -> None:
    source = Path("app/build/outputs/connected_android_test_additional_output")
    destination = Path("build/auth-smoke")
    destination.mkdir(parents=True, exist_ok=True)
    for name in ("moodle-login.png", "sso-login.png"):
        matches = list(source.rglob(name))
        if len(matches) != 1:
            raise SystemExit(f"Expected exactly one collected {name}; found {len(matches)}")
        data = matches[0].read_bytes()
        if len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR" or 0 in struct.unpack(">II", data[16:24]):
            raise SystemExit(f"Invalid PNG screenshot: {name}")
        shutil.copyfile(matches[0], destination / name)
        print(f"Collected {name}: {len(data)} bytes")


if __name__ == "__main__":
    main()
