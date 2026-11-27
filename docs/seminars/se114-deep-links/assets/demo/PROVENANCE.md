# UIT Mobile widget picker capture

The file `widget-picker-phone.png` is copied from
`docs/design/previews/widget-picker-phone.png` in the UIT Mobile repository.
It is the Roborazzi capture of `HomeWidgetsContent` recorded under
`home-widget-picker` in `WorkspaceDesignTest.kt`. The deck crops the empty
outer padding during placement. The image shows the original app component
and includes no student timetable or personal information. It is a preview
of the picker, not evidence of a phone interaction.

## Signed-out widget

`widget-signed-out-360.png` is the original 360 × 176 PNG from
`docs/design/previews/widget-disconnected-360-phone.png`
(SHA-256 `b89e40f4968511cd82599eb5afd4880e85bfee7aa20e1b57c54bd7a286389ca8`).
It was recorded by `ScheduleWidgetRendererTest.signOutReplacesCourseDataWithASignInAction`
using the production widget renderer and an empty disconnected state. It
shows no class or student data. Label it as a widget render, not a screenshot
of a phone or the result of a physical widget tap. The displayed day is a
fixture date, independent of the November 25/26 schedule test.

## November 25 and 26 schedule route

| Capture | Route delivered to the production `MainActivity` | SHA-256 |
| --- | --- | --- |
| `schedule-empty-2026-11-25.png` | `uit-mobile://widget/schedule?date=2026-11-25` on Activity creation | `ed9fb1a9135321996a5bc0be1f370e7f179dd10a007f38da20534c652a1f460a` |
| `schedule-empty-2026-11-26.png` | `uit-mobile://widget/schedule?date=2026-11-26` through `onNewIntent()` in the same Activity | `5ae17b9c9f12ca1d4f6bbe19fd9bd989b368d314c20bde98d3f548f22405699d` |

Both 390 × 844 PNGs are unchanged Roborazzi screen renders from a test of
the actual app classes, run in a temporary source copy using Robolectric
API 35. The test reused the injection harness in
`app/src/test/kotlin/vn/edu/uit/mobile/WidgetNavigationTest.kt`, then supplied
`PortalSnapshot()` and no linked accounts. It constructed each Intent
using the production `WidgetRoute` and asserted selection of the exact
day from the Compose semantics before capturing the screen. The run
reported one passing test with no skips or failures.

The images show the app selecting a date, with zero events and no student
data. They do not represent a Launcher interaction, APK install or
physical phone test. Use a caption identifying them as Robolectric
renders with empty test data. The calendar covers approximately
x = 16–374 and y = 354–565 in the originals.
