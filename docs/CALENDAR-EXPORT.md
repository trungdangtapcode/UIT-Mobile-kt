# Calendar file export

Open **Schedule → Export calendar file**, choose an inclusive date range of one to
90 days, select event types, and uncheck individual occurrences in the preview.
Use **Select all** or **Clear selection** to change the whole visible selection.
Changing the date range or event types resets individual exclusions.
Tap **Save .ics file** or **Share .ics file**; both use the same reviewed selection. Android's
native document picker lets the student choose a local or cloud-backed destination.
Share opens the native Android Sharesheet with a `.ics` attachment. Nothing is
sent until the student chooses a destination app and completes its flow. Opening
the Sharesheet does not prove delivery or calendar import.
No backend, calendar permission, university write or automatic upload is required.
A selected cloud document provider may upload the file as part of its own storage.

The initial range starts at the selected schedule date and includes seven days.
The 7-day and 30-day shortcuts restart from that date. Classes include regular,
make-up and canceled meetings; exams, deadlines and other calendar events can be
selected separately. Empty or invalid selections cannot be exported.

## Contents and dates

The file includes selected event titles, start/end times when known, class codes,
rooms, event kinds and the saved snapshot timestamp. Preview dates use Vietnam
civil time (`Asia/Ho_Chi_Minh`, GMT+07). The file encodes timestamps in UTC with `Z`,
so importing applications can convert them to their configured time zone.

The export uses the shared unified schedule rules, including fortnightly meetings,
date-specific cancellation notices, make-up classes, exams and Moodle deadline
overrides. Recurring schedules are expanded into individual occurrences within the
selected range. This avoids inventing an indefinite recurrence beyond cached data.
Canceled occurrences have `STATUS:CANCELLED` and are transparent; an importing
client may hide them. Deadlines are also transparent. Unknown exam/event end times
omit `DTEND` and are labeled as unknown rather than receiving an invented duration.

Event identifiers are deterministic opaque UUIDs derived from the existing event
key. Overlapping exports and title/status changes preserve the identifier of an
occurrence. Separate meeting dates have separate keys. Raw account IDs are not
included as calendar fields. Text escaping, CRLF content lines and UTF-8 folding
at 75 octets follow [RFC 5545](https://datatracker.ietf.org/doc/html/rfc5545).
No invitation method, attendees, organizer, alarm or executable attachment is added.

## Copies and importing

This is a finite `.ics` snapshot, not a calendar subscription. Refreshing UIT Mobile,
removing an account or changing a university class does not update an exported or
imported copy. Stable UIDs do not guarantee that every client deduplicates repeated
imports. Use a separate destination calendar for testing; inspect its dates,
time zone and canceled meetings before relying on it.

Choose a client that supports `.ics` import. Support varies by app/version. Google's
[documented Google Calendar import workflow](https://support.google.com/calendar/answer/37118?hl=en)
uses its computer interface; this feature does not promise that every Android
calendar app will import a file directly. You can also tap **Add to device
calendar** on any previewed event to open the phone's calendar insert screen for
that occurrence without granting `WRITE_CALENDAR` permission. UIT Mobile does not
keep exported copies synchronized automatically.

The file is unencrypted and contains personal schedule information, even though
`CLASS:PRIVATE` requests private treatment by a calendar client. That property is
not access control or encryption. The document picker controls the destination;
its provider controls subsequent storage and sharing. Save writes directly to the selected document without an extra local export copy.
Share creates a private cache copy under `calendar-shares/`, with an opaque random
filename, and exposes only its `content://` URI using a narrow FileProvider path
and a temporary read grant. It grants no write access. Completed share copies are
not deleted immediately because the chosen app may read them asynchronously.
On app startup or the next share, copies at least an hour old and excess copies
are removed (at most eight after a new share). This is opportunistic cleanup,
not a background expiry timer. A recipient may retain its own copy indefinitely.
Failed or canceled preparation deletes its temporary file. Share files are bounded
to 8 MiB; there is no exported-calendar directory or broad external-storage grant.

## Lifecycle and errors

Changing the set of connected accounts invalidates an outstanding export even
when the schedule entries happen to be identical. Exclusions apply to stable
occurrence keys, so omitted events cannot reappear in Save or Share.

The reviewed plan stays in the schedule ViewModel while the native picker is open,
including configuration changes. Returning from a canceled picker writes no events.
If selected entries changed while the picker was open, the user must review them
again. A refresh that changes only the saved timestamp does not invalidate unchanged
events. A missing pending plan after process death shows an error and asks for a
new save. Android may already have created an empty destination document in that
case. Storage failure or process termination during writing can leave a partial
file at the chosen destination; retry to a fresh destination and remove an
incomplete file with the document provider.

## Verification

Validation for this increment: full Gradle `check`, debug APK build and Android
instrumentation compilation passed. The focused suites contain 26 passing tests:
8 domain, 9 flow, 7 Compose and 2 FileProvider/cache tests. Instrumentation runtime
results are reported separately in PR CI.

Domain tests cover finite recurrence, cancellation, make-up meetings, exact UTC
boundaries, stable identifiers, type/range limits, unknown durations and escaped
Unicode content. Native Compose tests cover 360/390/412dp phones, larger text,
explicit save, invalid/empty selections and Back. Flow tests exercise file output,
picker cancellation, stale/oversized refreshed data, interrupted state and an
unavailable document provider using synthetic fixtures.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :core:domain:test \
  :app:testDebugUnitTest --tests '*CalendarExport*' --tests '*CalendarFileSharingTest' -PrecordUi=true
JAVA_HOME=/path/to/jdk21 ./gradlew :app:assembleDebug \
  :app:assembleDebugAndroidTest check :app:testDebugUnitTest
```

A synthetic `.ics` fixture is generated at
`core/domain/build/test-artifacts/calendar-export.ics`. The actual
[khal calendar client](https://khal.readthedocs.io/en/latest/usage.html) imported
and reimported it in an isolated local calendar. Its displayed Vietnam dates/times,
UIDs, cancellation and finite recurrence occurrences matched the source; reimport
did not duplicate events in khal 0.14.1. See [import evidence](research/evidence/calendar-file-import.json).
This is a Linux terminal calendar client, not Google Calendar, Outlook or Android
import UI. Physical phone document-picker/import validation remains manual before closing [issue #14](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues/14).

Reproduce the client acceptance without touching existing calendars:

```sh
python3 -m venv /tmp/uit-calendar-import
/tmp/uit-calendar-import/bin/pip install 'khal==0.14.1'
# First generate the fixture with :core:domain:test.
PATH=/tmp/uit-calendar-import/bin:$PATH /tmp/uit-calendar-import/bin/python scripts/verify-calendar-import.py
```

The script creates and deletes its own temporary calendar, configuration and
SQLite database. It exports only aggregate evidence, using synthetic schedule data.
It never reads the normal khal configuration or connects to university services.
`CalendarFileExportNativeTest` additionally exercises the actual Android
FileProvider read-back with synthetic events; host compilation is not device
runtime acceptance. Compose captures use clearly synthetic fixture schedules,
with 360/390/412dp and larger text. English and Vietnamese validation, storage and
share messages use locale resources; no server exception text is shown as UI copy.

The storage workflow follows Android's
[Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files).

Sharing follows Android's [Sharesheet contract](https://developer.android.com/develop/ui/compose/sharing/send)
and [secure file sharing guidance](https://developer.android.com/training/secure-file-sharing/share-file).
The feature uses ACTION_SEND, text/calendar, EXTRA_STREAM, ClipData and the read URI
grant. It does not choose or contact a recipient on the student's behalf.
