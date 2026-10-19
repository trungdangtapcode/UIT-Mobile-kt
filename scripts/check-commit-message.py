#!/usr/bin/env python3
"""Validate this repository's Conventional Commit message contract."""
from pathlib import Path
import re
import sys

TYPES = "feat|fix|refactor|perf|test|docs|build|ci|chore|style|revert"
HEADER = re.compile(rf"^({TYPES})(\([a-z0-9]+(?:[.-][a-z0-9]+)*\))?!?: [^\s].*$")


def validate(message: str) -> list[str]:
    lines = [line.rstrip() for line in message.splitlines() if not line.startswith("#")]
    while lines and not lines[-1]:
        lines.pop()
    issues = []
    if not lines or not HEADER.fullmatch(lines[0]):
        issues.append("Use type(scope): summary, for example feat(auth): add UIT SSO")
    if lines and len(lines[0]) > 72:
        issues.append("Keep the commit header at 72 characters or fewer")
    if lines and lines[0].endswith("."):
        issues.append("Do not end the commit header with a period")
    if len(lines) > 1 and lines[1]:
        issues.append("Separate the header and body with a blank line")
    return issues


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: check-commit-message.py COMMIT_MESSAGE_FILE", file=sys.stderr)
        return 2
    issues = validate(Path(sys.argv[1]).read_text(encoding="utf-8"))
    for issue in issues:
        print(issue, file=sys.stderr)
    return int(bool(issues))


if __name__ == "__main__":
    raise SystemExit(main())
