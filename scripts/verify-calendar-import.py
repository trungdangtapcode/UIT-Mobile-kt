#!/usr/bin/env python3
"""Import the generated synthetic calendar into an isolated khal calendar.

Requires khal in PATH and icalendar in this Python environment. No existing khal
configuration, calendar database, university account or cloud service is used.
"""
import datetime
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from zoneinfo import ZoneInfo

from icalendar import Calendar

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "core/domain/build/test-artifacts/calendar-export.ics"
client = shutil.which("khal")
if client is None:
    raise SystemExit("Install khal in an isolated Python environment and add its bin directory to PATH.")
raw = SOURCE.read_bytes()
events = Calendar.from_ical(raw).walk("VEVENT")
assert events and len({str(event["UID"]) for event in events}) == len(events)
zone = ZoneInfo("Asia/Ho_Chi_Minh")
starts = [event.decoded("DTSTART").astimezone(zone) for event in events]
with tempfile.TemporaryDirectory(prefix="uit-calendar-import-") as temp:
    root = Path(temp)
    calendar = root / "calendar"
    calendar.mkdir()
    config = root / "config"
    config.write_text(f"""[calendars]
[[export]]
path = {calendar}
[default]
default_calendar = export
[locale]
local_timezone = Asia/Ho_Chi_Minh
default_timezone = Asia/Ho_Chi_Minh
timeformat = %H:%M
dateformat = %Y-%m-%d
longdateformat = %Y-%m-%d
datetimeformat = %Y-%m-%d %H:%M
longdatetimeformat = %Y-%m-%d %H:%M
[sqlite]
path = {root / 'calendar.db'}
""", encoding="utf-8")

    def run(*args):
        return subprocess.run([client, "-c", str(config), "--no-color", *args],
                              check=True, capture_output=True, text=True, timeout=30).stdout

    run("import", "--batch", "-a", "export", str(SOURCE))
    assert len(list(calendar.glob("*.ics"))) == len(events)
    # Reimport the same UIDs into this disposable calendar, never a user's calendar.
    run("import", "--batch", "-a", "export", str(SOURCE))
    assert len(list(calendar.glob("*.ics"))) == len(events)
    displayed = run("list", "--format", "{uid}|{start-long}|{status}",
                    min(starts).date().isoformat(),
                    (max(starts).date() + datetime.timedelta(days=1)).isoformat())
    for event, start in zip(events, starts):
        expected = f"{event['UID']}|{start:%Y-%m-%d %H:%M}|{event.get('STATUS', '')}"
        assert expected in displayed, f"Imported occurrence did not match: {event['UID']}"
    imported = [Calendar.from_ical(path.read_bytes()).walk("VEVENT")[0] for path in calendar.glob("*.ics")]
    assert {str(event["UID"]) for event in imported} == {str(event["UID"]) for event in events}
    evidence = {
        "verified_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "client": subprocess.check_output([client, "--version"], text=True).strip(),
        "source": str(SOURCE.relative_to(ROOT)),
        "source_sha256": hashlib.sha256(raw).hexdigest(),
        "data": "Synthetic domain-test fixture; no university account or network requests",
        "timezone": str(zone),
        "imported_occurrences": len(events),
        "checks": ["CLI client import succeeds", "Reimport retains stable UIDs without duplicates in khal",
                   "Displayed Vietnam dates and times match source", "Cancellation status survives import",
                   "Finite make-up and fortnightly occurrences survive import"],
        "limits": "Terminal calendar client on Linux, not Google/Outlook or Android import UI. Device FileProvider test is separate.",
    }
(ROOT / "docs/research/evidence/calendar-file-import.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
print(f"PASS: {len(events)} synthetic occurrences imported and reimported in an isolated khal calendar; no existing account/calendar touched.")
