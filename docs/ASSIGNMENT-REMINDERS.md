# Unsubmitted assignment reminders

The app checks Moodle assignments whose effective due date is in the next seven
days and groups verified pending work into one notification per Moodle account.
Healthy account scans and reminder attempts are at least eight hours apart by
their durable checkpoint. No AI connection or hosted notification backend is used.

## Selection and submission verification

The dedicated worker reads the current account's enrolled courses and calendar,
plus assignment definitions/submission status where their AJAX methods are
available, through the existing origin-scoped Moodle transport. Its source facade
does not expose uploads, save/finalize submission, messaging, Forum writes or
mark-read operations. Moodle's AJAX reads
use POST; request method alone does not identify a mutation.

For each active, visible course:

1. Match assignments to that exact course/account. Deduplicate assignment IDs
   within the course; equal IDs in different accounts remain separate.
2. Use the fresh, account-specific calendar `due` event when present. A `close`
   event never replaces the due date. Conflicting due events are unresolved and
   skipped. Calendar months covering the entire look-ahead window are fetched,
   including a month/year boundary.
3. Require `now < effectiveDue <= now + 7 days`. Missing/past deadlines and
   assignments not yet open are excluded. A passed course-level cutoff excludes
   a task without an authoritative personal due override; a personal extension
   is not discarded solely because the general cutoff has passed.
4. Read submission status for each eligible assignment. Explicit `new`, `draft`
   and `reopened` states can qualify, but locked/disabled attempts are suppressed.
   A submitted individual or team record suppresses personal reminders. A missing
   attempt qualifies only when explicit enabled/unlocked/edit-or-submit capability
   flags establish that the student can start; arbitrary missing/unrecognized
   responses stay unknown. An explicit submission extension is rechecked against
   the look-ahead window before including the task. If assignment/status AJAX
   methods are unavailable, a separate fallback accepts only a newly fetched
   assignment `due` event with `actionable=true` and an exact same-origin
   `action=editsubmission` action URL. Missing actions, grading actions, close
   events, ambiguous dates and unrecognized routes are not pending evidence.
   Each reminder item records whether its basis is submission status or a fresh
   calendar action. The action URL is parsed only, never opened.
5. Recheck the deadline window and the captured session before notifying. Removing
   or replacing the account session invalidates the read batch.

The [upstream Moodle 4.5 submission-status contract](https://github.com/moodle/moodle/blob/MOODLE_405_STABLE/mod/assign/externallib.php)
was inspected for optional individual/team records, capability flags and extension
metadata. This is upstream evidence, not a new authenticated observation of UIT's
server version. The production gateway fixture covers missing attempts, team
submission, disabled/locked states, numeric booleans and a later extension. An
HTML module-page fallback is disabled for background reads: opening a page with
`forceview=1` can have viewed/completion side effects and its parser historically
inferred a new submission from unrecognized text. The reminder facade uses only
AJAX reads. [Moodle's calendar action callback](https://github.com/moodle/moodle/blob/MOODLE_405_STABLE/mod/assign/lib.php)
suppresses submitted/nonparticipant work and exposes edit actions only when the
student can act; this supports the narrowly checked calendar fallback. The
[service declarations](https://github.com/moodle/moodle/blob/MOODLE_405_STABLE/mod/assign/db/services.php)
also show why a read function is not automatically callable over AJAX. No action,
view, edit, or completion URL is followed by this worker.

These are fresh reads, not a claim that an old calendar action flag proves an
assignment is still unsubmitted. Other than the documented unavailable-method
fallback, a read failure aborts that account's batch rather than sending a partially
verified reminder. Other linked accounts can still be
processed. Each account read is bounded by a two-minute timeout.

## Cadence and deduplication

`AssignmentReminderPolicy` defaults to a seven-day look-ahead and an eight-hour
interval. Its validated constructor parameters support policy tests and deliberate
code changes; this increment does not add a user-facing interval setting.

WorkManager runs a unique **15-minute local checkpoint tick**, with connected
network and battery-not-low constraints. A successful scan, including an empty
result, records its checkpoint; subsequent ticks perform no Moodle scan until
eight hours have elapsed. Thus the tick does not mean university requests or
notifications every 15 minutes.

An exact eight-hour periodic job could run slightly before the previous delivery
checkpoint becomes due, skip that pass and leave an approximately sixteen-hour
gap. Short checkpoint ticks avoid losing that entire period. Android still controls
when work actually runs: Doze, battery restrictions, no connection and force-stop
can delay the reminder. This is not an exact alarm or a guarantee of three alerts
per calendar day. See [Android's periodic-work guidance](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

The rolling comparison is `now - lastCheckpoint >= 8 hours`, not a calendar-day
or fixed clock-bucket comparison. Moving the clock backward does not enable an
immediate repeat. A successful empty scan is also throttled so accounts with no
pending tasks do not repeatedly query Moodle on every tick. A newly added task
or changed deadline may therefore wait until the next eligible account scan.

Delivery passes are serialized in process. The existing Room `delivered_alerts`
table holds one updatable checkpoint under an opaque hash of each account ID;
there is no new schema/table or unbounded record per tick/assignment. The
checkpoint survives app/process restarts and is deleted with that account's
snapshot. Notification tags use the same account identity, so updating one
account's alert cannot replace another's.

Before posting, the worker reserves its checkpoint durably. If storage fails,
nothing is posted. If posting then fails or the process is interrupted, the
reservation conservatively suppresses repeats for the interval; that can miss
one reminder, but avoids retry spam after an uncertain notification outcome.
Exactly-once delivery across Room and Android NotificationManager is not claimed.
An initially denied/muted notification permission consumes no checkpoint and
performs no university reads. A permission change during delivery can suppress
that reserved attempt until the next eligible interval.

Read failures remain eligible for retry. The worker uses a fifteen-minute
exponential retry backoff with at most two retries for a failing periodic pass;
later periodic ticks can try again. Previously reserved/delivered accounts remain
throttled while a different account retries.

The older 24-hour/1-hour deadline path excludes assignment-linked events and
assignment URLs, preventing a second reminder system from interrupting this
cadence. Other academic event reminders and grade updates retain their existing
behavior.

## Phone behavior and controls

- The dedicated **Unsubmitted assignment reminders** channel can be muted in
  Android notification settings. Existing app notification permission is required,
  including `POST_NOTIFICATIONS` on Android 13+; no permission prompt is initiated
  from a background worker. See [Android's notification permission contract](https://developer.android.com/develop/ui/views/notifications/notification-permission).
- A grouped alert shows up to five upcoming tasks with course labels and due
  times in `Asia/Ho_Chi_Minh`, plus a remaining count. Long/multiline names are
  bounded before constructing the system notification.
- Tapping opens the existing deadline inbox. This increment does not add a new
  assignment-specific navigation flow; that inbox uses the ordinary app snapshot
  and refresh path, while the reminder source independently verifies fresh status.
- Lock-screen public content is generic; course/task details use private
  notification visibility. Different accounts have separate notification tags.
- No further reminder is posted for a submitted task after the next successful
  status check. An existing grouped notification is canceled when a successful
  eligible scan finds no pending tasks. This is polling, not immediate remote
  submission-event delivery; the old notification can remain until that check or
  user dismissal. Account removal explicitly cancels its notification.
- Offline/expired/unsupported source responses never become invented pending
  tasks. A school-side change after the latest verified read cannot be detected
  instantaneously.

## Local verification

See the [deterministic verification record](research/evidence/assignment-reminders-verification.json)
for the local check result, 27 relevant tests (23 new), fixture boundaries and APK
checksum, and the [owner-authorized live follow-up](research/evidence/assignment-reminders-owner-live.json)
for actual UIT source and Android framework acceptance. The [preview APK](https://github.com/trungdangtapcode/UIT-Mobile-kt/releases/tag/v0.1.8-reminders-preview.1)
updates the existing UIT Mobile Preview package with the same signing certificate;
install in place to preserve its local accounts/data. Enable the reminder channel
and grant notification permission when testing on the phone.

The deterministic suite uses explicit synthetic fixtures and injected time. It
does not access university credentials or submit anything. The separately enabled
owner live test below uses actual UIT reads; neither suite sends alerts to the
owner's physical phone.

- Domain policy: seven-day and expiration boundaries, eight-hour threshold,
  clock rollback, explicit pending states, ownership, course visibility, personal
  due overrides versus close events, cutoff/extension handling and conflicts.
- Delivery: restart persistence through the ledger contract, concurrent passes,
  permission denial, account revocation, expiring/foreign results, cancellation,
  read failure isolation, empty-result cancellation, storage/post failures and
  empty-scan throttling.
- A simulated complete seven-day interval yields **21 grouped reminders** for
  continuously pending work at hours 0 through 160, then stops at the deadline.
  The simulation advances an injected clock; it is not a physical timing capture.
- A production Moodle gateway fixture verifies the permitted AJAX read methods,
  a calendar month boundary, fresh submission filtering and session invalidation.
  Separate unsupported-method cases verify calendar-action fallback and reject
  every module-page GET, submitted/nonactionable event, grading link and close
  event. These fixtures do not establish live UIT method admission.
- Room verifies checkpoint upsert, adapter recreation and account cleanup without
  deleting another account's state.
- Robolectric exercises the Android NotificationManager, runtime permission,
  muted channel, private/public content, bounded grouped text, stable account
  notification identities, deadline PendingIntent and WorkManager request policy.

Focused command, with JDK 21 and the local Android SDK configured:

```sh
./gradlew :core:domain:test --tests '*AssignmentReminderPolicyTest' --tests '*ScheduleTest' \
  :core:data:testDebugUnitTest --tests '*AssignmentReminder*' --tests '*OfflinePortalRepositoryTest' \
  :core:database:testDebugUnitTest \
  :app:testDebugUnitTest --tests '*AssignmentReminderNotificationTest' --tests '*AnnouncementNotificationTest'
```

### Actual owner-account follow-up

On 2026-11-07 at 23:04 UTC, the owner-authorized test authenticated through UIT
SSO and passed the resulting session to a separate JUnit process through stdin.
The password was used only for the official sign-in. The temporary Chromium
profile was removed; the JVM did not persist the university session. The live test
code is excluded from the APK and skips by default unless
`UIT_REMINDER_OWNER_LIVE=1` is explicitly set. Credentials are not command-line
arguments, provider context, committed fixtures or public evidence.

The exact production `MoodleAssignmentReminderSource` found pending work from the
real account. UIT accepted course-overview and monthly-calendar reads; assignment
definitions returned `servicenotavailable`. Consequently this run genuinely
exercised the fresh student calendar-action fallback, rather than substituting
fixture responses or claiming direct submission API support.

The same production delivery coordinator, Room ledger and
`AssignmentReminderNotifier` posted a grouped notification through Robolectric's
Android NotificationManager. Its private visibility and deadline navigation were
checked. An immediate second pass made no further university reads and did not
duplicate the alert. The authenticated HTTP interceptor allowed only the exact
Moodle AJAX endpoint and five read function names. No module page, action URL,
submission, message or completion request was allowed.

JUnit reported **OK (1 live test)**. The private item list remains outside the
repository; public evidence omits assignment names, course codes, identifiers,
record counts, cookies and session keys. This live follow-up changes the earlier
source-admission status: the calendar fallback is now verified against the owner's
actual UIT session. Physical Android scheduling remains separate.

No physical Android device was connected during implementation. Host tests cannot
establish Itel/OEM background execution, sound/vibration presentation or actual
eight-hour delivery under Doze. On a phone, install the preview, permit the reminder
channel, use a legitimate pending assignment and record actual arrival times,
source changes and navigation. During ordinary authorized coursework, observe whether a legitimate submission
stops later alerts. Do not create, submit or modify university records solely
for this acceptance test; use a separately authorized test environment for writes.

Do not dispatch or rerun CI for iterative verification; run relevant checks locally. No AI
API key or live subscriber inference is involved in this feature.
