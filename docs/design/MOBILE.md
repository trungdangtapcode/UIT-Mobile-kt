# Android phone scope

UIT Mobile is an Android phone application. The earlier desktop-oriented review
flow was outside the intended product scope. Current implementation and review
focus on portrait phones at 360 × 800dp, 390 × 844dp and 412 × 915dp, including dark
mode and 1.3× font scale.

## Current behavior

- The app has a compact UIT Mobile toolbar, one reading column and five bottom
  destinations. Wider Android windows keep this same flow rather than switching
  to a desktop sidebar or a second pane.
- Today uses the phone's width for course titles. Class code and status appear
  above the title; time and room appear together below it. There is no narrow
  desktop-style time/title/room table squeezed into the phone. The closest two
  actionable submissions follow the timetable, with View all for the full queue.
- Schedule starts in Day mode. Week mode groups events chronologically by date
  in the same vertical list; Month mode keeps a phone-sized calendar. Semester
  selection is available on the phone. The complete screen scrolls, including
  its controls, so larger text does not squeeze events into a small viewport.
- Courses use stacked file-like cards with full-width names and searchable data.
  Materials, submissions and academic records remain single-column flows.
- Messages open from the conversation list into a dedicated chat view. Back
  returns to the list. Android Back also returns from an opened course. The shell
  consumes keyboard insets and hides bottom navigation while the keyboard is open.
- The requested frames remain: three 8dp cuts and a 28dp bottom-right cut on main
  panels, with smaller versions for controls. UIT blue, the transparent original
  emblem and the quiet campus photograph are retained.

## Review artifacts

The [phone gallery](README.md) is the current review surface. The Compose suite
uses phone dimensions only. The old desktop PNGs are archived under
`previews/history/desktop/` and are not regenerated or presented as current targets.

Previews use synthetic fixtures and native Compose rendering on the rootless host.
They require neither credentials nor an emulator. They are static review images;
physical-device input and keyboard behavior still require device verification.

Validation completed: debug APK build and full `check` pass; all 24 phone UI
cases pass. The current gallery contains 22 renders at phone widths only
(360dp, 390dp and 412dp). Local documentation links are valid.
