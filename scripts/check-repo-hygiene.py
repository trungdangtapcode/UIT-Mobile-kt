#!/usr/bin/env python3
"""Check the Git index without printing sensitive file contents."""
from pathlib import PurePosixPath
import re
import subprocess
import sys

FORBIDDEN_DIRS = {".gradle", ".kotlin", ".idea", ".vscode", ".cxx", ".externalNativeBuild", "build", "node_modules", "__pycache__", ".venv"}
FORBIDDEN_SUFFIXES = {".apk", ".aab", ".ap_", ".idsig", ".dex", ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".hprof", ".heapdump", ".log", ".pyc"}
SECRETS = re.compile(rb"(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)")


def main() -> int:
    paths = subprocess.check_output(["git", "ls-files", "-z"]).decode().split("\0")
    failures = []
    count = 0
    total = 0
    for name in filter(None, paths):
        path = PurePosixPath(name)
        local_env = path.name == ".env" or path.name.startswith(".env.") and path.name not in {".env.example", ".env.sample"}
        if any(part in FORBIDDEN_DIRS for part in path.parts) or path.suffix in FORBIDDEN_SUFFIXES or path.name in {"local.properties", "keystore.properties", "signing.properties", "credentials.json"} or path.name.startswith("service-account") and path.suffix == ".json" or local_env:
            failures.append(f"Local/generated/sensitive path is staged: {name}")
            continue
        data = subprocess.check_output(["git", "show", f":{name}"])
        count += 1
        total += len(data)
        if len(data) > 5 * 1024 * 1024:
            failures.append(f"File exceeds the 5 MiB source/review asset limit: {name}")
        if b"\0" not in data[:8192] and SECRETS.search(data):
            failures.append(f"Possible credential or private key in staged content: {name}")
    for failure in failures:
        print(failure, file=sys.stderr)
    if not failures:
        print(f"Index hygiene passed: {count} files, {total:,} bytes")
    return int(bool(failures))


if __name__ == "__main__":
    raise SystemExit(main())
