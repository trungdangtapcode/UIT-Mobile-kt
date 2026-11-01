#!/usr/bin/env python3
"""Fail when user-facing UI text is hardcoded instead of using string resources.

The check scans Kotlin production sources and reports `Text("...")` calls whose
argument is a literal. Data templates (`"${...}"`), constant identifiers used for
internal matching, and codes written in capitals are ignored.

Usage: python3 scripts/check-hardcoded-ui-strings.py
"""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
TEXT_CALL = re.compile(r'\bText\(\s*"')

# Values used as map keys, filter identifiers or proper names. They are matched
# internally and never shown to the reader, or they must not be translated.
ALLOWED_LITERALS = {
    "All events",
    "Classes",
    "Exams",
    "Deadlines",
    "Notices",
    "Materials",
    "Assignments",
    "Upcoming",
    "Submitted",
    "Overdue",
    "Day",
    "Week",
    "Month",
    "English",
    "Vietnamese",
    "PERSONAL",
    "Moodle",
    "UIT",
}

CODE_LIKE = re.compile(r"^[A-Z0-9 ._:/\-–—×·]+$")


def ui_literals(path: Path):
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        match = TEXT_CALL.search(line)
        if not match:
            continue
        remainder = line[match.end() :]
        if remainder.startswith("${"):
            continue
        literal = remainder.split('"', 1)[0]
        if not literal or "$" in literal:
            continue
        if literal in ALLOWED_LITERALS or CODE_LIKE.match(literal):
            continue
        yield number, literal


def main() -> int:
    failures = []
    for path in sorted(ROOT.rglob("*.kt")):
        parts = path.parts
        if "build" in parts or "src" not in parts or "main" not in parts:
            continue
        for number, literal in ui_literals(path):
            failures.append(f"{path.relative_to(ROOT)}:{number}: {literal[:70]}")

    if failures:
        print("Hardcoded UI text found; move it into res/values and res/values-vi:")
        for failure in failures:
            print(f"  {failure}")
        return 1

    print("No hardcoded UI text found")
    return 0


if __name__ == "__main__":
    sys.exit(main())
