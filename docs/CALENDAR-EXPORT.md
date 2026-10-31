# Calendar file export

Open **Schedule → Export calendar file**, choose an inclusive date range of one to
90 days, select event types, review the preview, and tap **Save .ics file**. Android's
native document picker lets the student choose a local or cloud-backed destination.
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
calendar app will import a file directly. UIT Mobile does not select a calendar,
insert events into Android's Calendar Provider or keep copies synchronized.

The file is unencrypted and contains personal schedule information, even though
`CLASS:PRIVATE` requests private treatment by a calendar client. That property is
not access control or encryption. The document picker controls the destination;
its provider controls subsequent storage and sharing. No extra export file is
kept in UIT Mobile's private storage.

## Lifecycle and errors

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

Domain tests cover finite recurrence, cancellation, make-up meetings, exact UTC
boundaries, stable identifiers, type/range limits, unknown durations and escaped
Unicode content. Native Compose tests cover 360/390/412dp phones, larger text,
explicit save, invalid/empty selections and Back. Flow tests exercise file output,
picker cancellation, stale/oversized refreshed data, interrupted state and an
unavailable document provider using synthetic fixtures.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :core:domain:test \
  :app:testDebugUnitTest --tests '*CalendarExport*' -PrecordUi=true
JAVA_HOME=/path/to/jdk21 ./gradlew :app:assembleDebug \
  :app:assembleDebugAndroidTest check :app:testDebugUnitTest
```

A synthetic `.ics` fixture is generated at
`core/domain/build/test-artifacts/calendar-export.ics`. An independent Python
`icalendar` parser accepted its occurrences, UTC timestamps, cancellation status and
unique identifiers. Parsing is not an end-user calendar import test. Import into
an actual calendar client and a physical phone document-picker run remain manual
validation before closing [issue #14](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues/14).

The storage workflow follows Android's
[Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files).
