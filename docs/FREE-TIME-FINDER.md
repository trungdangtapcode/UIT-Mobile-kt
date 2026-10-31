# Free-time finder

Open **Schedule → Find free time**. Choose a UIT date, a same-day search window,
a minimum duration and a travel/break buffer. The app calculates candidate gaps
from saved timetable meetings, make-up classes, exams, timed calendar events and
optional personal blocks. Tap **Inspect interval** to see its neighboring buffered
commitments or search-window boundaries. Inspecting a gap creates no event.

This feature runs entirely on the Android client. It needs no backend, AI key,
calendar permission, remote calendar write or room-availability service.

## Time rules

- All dates, labels and search inputs use `Asia/Ho_Chi_Minh` (GMT+07).
- The search window stays within one date; the end must follow the start.
- Minimum duration accepts 10–720 minutes; the buffer accepts 0–120 minutes.
- Each commitment reserves the buffer before and after its occupied interval.
  Overlapping, nested and touching intervals are combined before gaps are found.
- Gaps round inward to whole minutes so displayed intervals never offer an
  occupied fractional minute. The minimum applies after rounding.
- Timetable recurrence follows the shared weekly/fortnightly rules. Full
  cancellations remove the affected meeting. Partial cancellations preserve the
  periods that still meet; multiple notices combine at period granularity.
- Multi-day calendar events can start before the selected date. Adjacent-date
  commitments can affect the window through their travel buffers.
- An exam or timed event without a valid end reserves the rest of its own UIT
  day, followed by the buffer. This is an explicit conservative planning rule,
  not a reported duration. The screen identifies these assumptions. Check the
  actual event before relying on gaps around an unknown end.
- Deadlines remain separate reminders and do not automatically occupy study time.
  Add a personal block when preparation time should be reserved.

The commitments list shows the clipped buffered intervals used by the calculation,
not an altered version of official lesson times. Cached versions of one occurrence
with different ends are considered together rather than silently using the shorter
version. No result is a guarantee of personal or room availability.

## Personal planning drafts

**Add personal block** accepts a title and start/end times on the currently entered
date. Add is explicit; invalid values keep the form open with an error. Up to ten
blocks can be added and individually removed. Blocks may remain listed while
switching the search date so their date stays visible and they can be removed.

Drafts and settings live in the schedule ViewModel's in-memory planner. They survive
configuration changes while the activity and planner are retained. Explicitly
closing the planner resets them; opening it again begins from the schedule's
selected date. Process death removes drafts. They are not written into university
services, AI prompts, calendar files, app preferences or backup storage.

The actual connected-account ID set scopes a planning session. Adding/removing
an account clears drafts, including accounts that have no cached courses. The
session reset also dismisses an open personal-block form and interval inspection,
so unsaved form text cannot carry over to a different account context. An Add
callback from an obsolete form session is rejected before any draft is changed. A normal
snapshot refresh with unchanged connected accounts retains personal blocks.

## Missing data and limits

The app reads saved data and does not trigger a background sync from this screen.
An unavailable saved schedule is shown differently from an empty synchronized day.
If only personal blocks exist, a notice states that university commitments are not
included. A synced empty day can return the whole search window as a candidate.
Invalid inputs or unsupported cached timestamps produce an error instead of gaps.
Refresh the schedule when classes or changes may be missing.

The first version is a single-day planning tool. It does not persist personal
appointments, create reminders, search friends' calendars, find rooms, schedule
meetings or infer an exam's real finish time.

## Verification

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :core:domain:test \
  :app:testDebugUnitTest --tests '*FreeTime*' -PrecordUi=true
JAVA_HOME=/path/to/jdk21 ./gradlew :app:assembleDebug \
  :app:assembleDebugAndroidTest check :app:testDebugUnitTest
```

Synthetic tests cover overlapping/nested events, duplicate cached versions,
fortnightly meetings, full/partial cancellations, make-up classes, multi-day and
adjacent-date events, unknown end times, inward rounding and buffer/minimum bounds.
Planner tests cover explicit drafts, block limits, date/time validation and actual
account removal without courses. Actual Compose renders and interactions cover
360/390/412dp phones, larger text, interval inspection, invalid settings, absent
saved data, draft validation/removal and resetting an open form on account changes.

This implements the client-side scope of
[Free-time finder #11](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues/11).
