# UIT Mobile phone previews

UIT Mobile is a native Android phone app. Current design and validation target
portrait phones at **360dp, 390dp and 412dp**, with dark mode and larger text.
See [the phone scope](MOBILE.md) and [the current design rules](../../.interface-design/system.md).

The interface keeps UIT blue, the original transparent emblem, IBM Plex fonts,
a subtle campus photograph and the requested asymmetric cut-corner frames.
Main panels have three 8dp cuts and a larger 28dp physical bottom-right cut.
[Asset provenance](ASSETS.md) and [frame geometry](CUT-CORNERS.md) are documented separately.

## Phone sizes

| Compact phone · 360 × 800dp | Main phone · 390 × 844dp | Larger phone · 412 × 915dp |
| --- | --- | --- |
| [Today](previews/dashboard-360-phone.png) | [Today](previews/dashboard-phone.png) | [Today](previews/dashboard-412-phone.png) |

Fresh installations enable dark appearance and device colors. The device-color
preview below uses the test device's palette; actual colors depend on the user's
wallpaper on Android 12+. Older Android versions use the UIT dark palette. Saved
appearance choices remain unchanged.

![Default dark appearance with device colors on a 390dp phone](previews/dashboard-default-device-colors-phone.png)

## Phone screens

| Screen | Preview |
| --- | --- |
| Class schedule | [Day](previews/schedule-phone.png) · [Week agenda](previews/schedule-week-phone.png) · [Month](previews/schedule-month-phone.png) |
| Calendar export | [360dp](previews/calendar-export-360-phone.png) · [390dp](previews/calendar-export-390-phone.png) · [Preview](previews/calendar-export-preview-390-phone.png) · [412dp larger text](previews/calendar-export-412-large-text-phone.png) |
| Courses | [Course list](previews/courses-phone.png) · [Materials](previews/course-materials-phone.png) |
| Study AI (synthetic results) | [Assignment sources · 390dp](previews/ai-assignment-390-phone.png) · [Evidence · 390dp](previews/ai-evidence-390-phone.png) · [Question setup · 360dp](previews/ai-question-360-phone.png) · [Direct connection · 360dp](previews/ai-connection-direct-360-phone.png) · [Direct connection · 412dp larger text](previews/ai-connection-direct-412-large-text-phone.png) · [Optional gateway · 390dp](previews/ai-connection-gateway-390-phone.png) · [Checklist · 412dp larger text](previews/ai-checklist-412-large-text-phone.png) |
| Academic record | [Overview](previews/academic-phone.png) · [Semester selection](previews/academic-semester-phone.png) · [GPA planner](previews/academic-planner-phone.png) |
| Tuition | [390dp](previews/tuition-390-phone.png) · [360dp](previews/tuition-360-phone.png) · [Charge details](previews/tuition-details-phone.png) · [Offline](previews/tuition-offline-phone.png) · [Larger text](previews/tuition-360-large-text.png) |
| Student procedures | [Request history](previews/procedures-390-phone.png) |
| Student profile | [390dp](previews/profile-390-phone.png) · [Academic](previews/profile-academic-phone.png) · [Insurance](previews/profile-insurance-phone.png) · [360dp larger text](previews/profile-360-large-text.png) · [412dp dark family view](previews/profile-family-412-dark-phone.png) |
| Profile scans | [Documents](previews/profile-documents-390-phone.png) · [Image viewer](previews/profile-scan-390-phone.png) · [412dp dark](previews/profile-scan-412-dark-phone.png) · [360dp larger-text error](previews/profile-scan-360-error-phone.png) |
| Home screen widgets | [Next class · 360dp phone](previews/widget-next-360-phone.png) · [Today · 390dp phone](previews/widget-today-390-phone.png) · [412dp device colors](previews/widget-today-412-device-colors-phone.png) · [360dp larger text](previews/widget-today-360-large-text-phone.png) · [Disconnected](previews/widget-disconnected-360-phone.png) · [Pin chooser](previews/widget-picker-phone.png) |
| Graduation | [412dp dark](previews/graduation-412-dark-phone.png) · [Assessment conditions](previews/graduation-conditions-phone.png) |
| Inbox | [Deadlines](previews/focus-phone.png) · [Conversation list](previews/inbox-phone.png) · [Chat](previews/inbox-conversation-phone.png) |
| Appearance | [Dark phone](previews/dashboard-dark.png) · [Compact dark phone](previews/dashboard-dark-360-phone.png) · [Larger text](previews/dashboard-compact-large-text.png) |
| Data states | [Sign-in](previews/dashboard-connect-phone.png) · [Canceled class](previews/dashboard-canceled-phone.png) · [Empty day](previews/dashboard-empty-day-phone.png) · [Overdue work](previews/dashboard-overdue-phone.png) |

These are the actual Compose components rendered with Robolectric native graphics
and Roborazzi. Names, grades, courses and messages are synthetic test fixtures;
production screens read repository data. No credentials are needed to render them.
Screenshots and passing tests do not constitute visual approval.

Widget images inflate and draw the actual Android RemoteViews, with a 16dp outer
gutter representing their placement on a phone. They are host-side renders, not
screenshots of an installed launcher. The widget pin chooser uses actual Compose.

## Render on a rootless server

An Android SDK and JDK are required; an emulator, KVM and root are not:

```bash
cd /home/nguyentnt/PDWAIA/kl/uit-mobile
JAVA_HOME=/tmp/uit-jdk ./gradlew :app:testDebugUnitTest \
  --tests '*WorkspaceDesignTest*' -PrecordUi=true
```

Current PNGs are written directly to `docs/design/previews/`. The JDK path reflects
the current temporary installation; use your persistent JDK when needed.

```bash
./gradlew :app:assembleDebug check
```

The suite checks phone navigation, date selection, course search, materials,
calendar selection, transcript/planner interactions, submission filters and
conversation navigation/drafts. Screenshots are review artifacts, not pixel-diff
assertions. The rendering suite uses the debug-only test host Activity.

This is static host rendering, not hot reload or a live device session. See
[Debugging over SSH](../DEBUGGING.md) for device debugging and Live Edit distinctions.

Earlier dashboard and agenda notes are historical. They do not define the current
product scope or preview targets.
