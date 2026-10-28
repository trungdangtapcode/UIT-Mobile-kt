# Home screen widgets

UIT Mobile includes two native Android home screen widgets:

- **Next class**: the current or next class/exam, its time and room, and a short
  closest-deadline line when space permits. Its requested launcher size is 4 × 2 cells.
- **Today**: up to three remaining classes and the closest deadline. Its requested
  size is 4 × 3 cells; the number of visible rows adapts to height and font scale.
  If today's classes have finished, it can show the next meeting on another day.

Launcher grids determine the actual size. Both widgets can be resized. They keep
the UIT emblem, shared appearance settings and asymmetric cut-corner frame. The
background uses the Android background ID/outline opt-out supported by Launcher3
to preserve its authored shape. Other launchers may apply their own masks.

## Add a widget

In the app, open **Account icon → Settings → Home screen widgets**, choose a type,
and tap **Add to home screen**. The launcher asks you to confirm placement. Where
pinning is unsupported, use the manual path: long-press the home screen, choose
**Widgets → UIT Mobile**, then drag either widget onto the home screen.

Sign in and sync in the app to populate the widgets. Classes require cached Portal
or DAA timetable data; Moodle-only accounts can still supply deadlines. There is
no widget-specific university login or academic write operation.

Tapping a class opens Schedule on that date in Day mode with All events selected.
Tapping a known deadline opens the Upcoming or Overdue queue. An assignment whose
submission status is unknown opens its date in Schedule instead. Widget navigation
uses explicit, immutable PendingIntents and consumes the launch request after use.

## Data and updates

Widgets read the same combined, account-linked Room snapshot as the app. They
exclude canceled meetings, keep a current class until its end, and look up to 14
days ahead for meetings. They preserve fortnightly schedules and explicit exam
start times. Deadlines are selected from the next year of cached data, with known
overdue work used when there is no upcoming item. Confirmed submitted work is
excluded; assignment-only items say **Check status** rather than claiming they
still require submission. Unknown historical assignment status is not treated as
confirmed overdue work.

All dates and times use **Asia/Ho_Chi_Minh (GMT+07)**, including when the phone is
in another time zone. **Saved** shows the combined snapshot's saved timestamp,
not proof that each portal request succeeded or that the displayed data is live.
Disconnected and never-synced widgets display sign-in/sync actions, not empty
academic results.

The widget worker only reads cached data. The existing UIT sync worker owns
network refreshes. An application observer updates widgets after snapshot, account
or appearance changes. Widget additions/resizes, periodic cache work, clock/date
changes, package replacement and reboot can request another render. A unique
one-time job targets the next class boundary, deadline or UIT midnight; a periodic
15-minute job and the platform's 30-minute widget callback provide fallback updates.
These are best-effort jobs: Android battery restrictions and launcher behavior can
delay them. No exact-alarm permission or precise update guarantee is added.

Removing all widgets cancels the widget jobs and observer. Signing out renders a
disconnected state. Widgets do not add another profile/image cache, and they never
display student IDs, profile scans, family records or bank information.

## Verification

The domain suite covers current/finished/canceled/fortnightly meetings, exam times,
UIT day boundaries, submission-state handling and disconnected cache isolation.
Robolectric inflates and draws the actual RemoteViews, checks tap destinations,
font scaling and frame geometry, and verifies the Android 8 fallback. Production
Activity tests cover widget navigation using synthetic repository data. Compose
tests exercise the pin chooser and restoration of the schedule view/filter.

The [gallery](design/README.md) contains synthetic native widget renders at phone
widths. Host rendering does not establish placement, resize or background timing
on a physical launcher; those need ordinary device testing.

Implementation references: [Android widget providers](https://developer.android.com/develop/ui/views/appwidgets),
[background updates](https://developer.android.com/develop/ui/views/appwidgets/advanced),
[Android 12 dynamic colors](https://developer.android.com/develop/ui/views/appwidgets/enhance),
and [Launcher3 corner enforcement](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/src/com/android/launcher3/widget/RoundedCornerEnforcement.java).
