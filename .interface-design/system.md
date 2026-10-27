# UIT student desk with cut-corner frames

## Product scope and intent

UIT Mobile is a native Android phone app. The user's explicit priority is MOBILE.
Design and review portrait phones at 360dp, 390dp and 412dp widths. No desktop
preview targets, sidebars, rails, master-detail panes or multi-column product flows.
Wider Android windows keep the same centered phone reading column, capped at 600dp.
The [phone gallery](../docs/design/README.md) and [mobile scope](../docs/design/MOBILE.md)
are current. Previous dashboard reviews are historical and are not approved designs.

A student opens the app while traveling to class: find a room, check a cancellation,
open Moodle materials and identify an approaching submission. The timetable comes
first. Academic records are available through the persistent bottom destination.
Preserve the user's asymmetric frames: three equal small cuts and a larger
physical bottom-right cut. Build success does not establish visual approval.

## Domain and expression

- Domain: teaching periods, class sections, lab rooms, cancellations, make-up
  classes, Moodle modules, DAA records, submission cutoffs, and campus buildings.
- Color world: UIT emblem blue, white university documents, navy ink, blue campus
  glazing, gray classroom signage, amber deadline notices.
- Signature: a date-led teaching-week index and aligned time/course/room rows.
  UIT identity comes from the original transparent emblem, real campus photograph,
  domain-specific course icons and imported academic structure.
- Avoid the previous defaults: image hero above the timetable; large course/class
  counters; cards nested in cards; decorative orbit/circuit fields; arbitrary
  deadline progress bars; uppercase command-center jargon.
- Futuristic character should come from precise interaction and information
  handling, not simulated telemetry or decoration across the reading area.

## Palette and typography

- Light background #F8FAFC, white reading surfaces, ink #102A43, UIT blue #0768B5.
- Dark background #111C28, surfaces #162330, text #E6F2FF, primary #65BEFF.
- Fresh installations start in light mode. Existing saved appearance choices win.
- Semantic states include text: canceled classes are labeled and struck through;
  current classes say In progress; submissions expose exact due dates and urgency.
- IBM Plex Sans 400/500/600 for content and controls. IBM Plex Mono for class codes,
  clock times, and the date index. Generic labels use Sans, not tiny uppercase mono.
- Body 13/20sp; row titles 15/22sp; section titles 19/26sp; page headings 28/36sp.
  Phone date headings use the normal title scale. Course names use the full card
  width; time and room share a metadata row underneath. Titles may wrap without truncation.

## Surface and spacing rules

Keep outlined frames around the main content groups. Use straight eight-edge
outlines with no curved corners. Shared panels use white/dark surface, a 1dp border
at 55% outline opacity, and 16dp interior padding. Rows inside a panel use quiet
separators. No heavy shadows.

Geometry is centralized in `TechnicalShapes.kt`, using `AbsoluteCutCornerShape`.
The large corner always stays at the physical bottom-right, including in RTL.
Three other corners have equal small cuts:

| Role | Three small cuts | Bottom-right cut |
| --- | --- | --- |
| Tile | 3dp | 8dp |
| Control | 4dp | 12dp |
| Field | 6dp | 16dp |
| Panel | 8dp | 28dp |
| Dialog | 8dp | 32dp |

Apply these to authored cards, navigation selections, buttons, filter tabs, fields,
and dialogs. Do not reintroduce rounded containers. Status dots and chart points
remain data marks; the official UIT emblem must retain its original geometry.

- Base spacing: 4dp. Phone gutter and section gaps: 16dp. Main frame padding: 16dp.
- Navigation: 56dp toolbar and native bottom navigation at every width. Controls
  retain native touch targets and semantics. Account/notification actions are
  reachable from the toolbar. Keyboard insets are consumed once by the shell;
  bottom navigation hides while the keyboard is visible.
- A single campus image occupies the first 180dp behind the masthead, faded into
  the page with 8dp blur where supported. It is decorative and has no text role.
  There are no canvas dots, orbital lines, or repeated image panels.
- The official emblem keeps its original paths, fill and proportions. No opaque
  backing or generated replacement.

## Component behavior

Today: a selectable Monday–Sunday index filters the actual unified schedule. The
selected date survives recreation. Rows expose time, course, code and room; the
current row gets a restrained blue tint and a course action. Canceled/unlinked
entries open a detail dialog. Submission deadlines appear once in their queue,
including overdue actionable work; no arbitrary urgency percentage is drawn. The timetable, submission queue and
material index retain their outlined cut-corner frames.
Assignment-only dates remain in the agenda when calendar submission status is absent.

Phones: timetable, closest submissions, schedule changes, course materials,
announcements and portal links. Empty days retain submissions. A disconnected
account gets a direct sign-in action. Native vertical scrolling handles the whole
page. Do not introduce desktop-style time/title/room columns inside narrow cards.

Courses: full-width file-like cards with code, title and category stacked. Existing
filters and material/discussion/submission behavior remain. Android Back returns
from a selected course. Messages use list → conversation → Back, never two panes.
Schedule starts in Day; Week is a date-grouped vertical agenda; Month is a phone-sized
calendar. Semester selection must be available on the phone.

Deadlines: date-indexed rows with exact due timestamp and textual status. Submitted,
upcoming and overdue filters use repository data. Academic: actual cumulative GPA,
semester values and transcript details; degree progress appears only when supplied.

Academic student services: keep the five bottom destinations. Inside Academic,
use a horizontally scrollable set of cut-corner filters for Records, Tuition,
Procedures and Graduation. Each view remains a single phone reading column.
Tuition selects one reported term before showing its exact remaining balance;
do not sum historical balances without verified accounting semantics. Request
history groups by service. Graduation presents the university's registration
status and condition list. Reuse Panel, native buttons and existing type/color
tokens. Show per-section saved times, cached-data notices and retry controls;
missing financial values are an em dash, never a fabricated zero. All new student
service controls navigate, expand or refresh reads; there are no submission,
payment, cancellation or upload controls.

Student profile: the toolbar account action opens the student's profile in the
same single phone column. Keep a compact identity panel with the student's name,
code, class, faculty, major and reported status. Use horizontally scrollable
section filters, then vertical label/value groups. Preserve readable wrapping,
native Back and the five bottom destinations. Refresh and account settings remain
available in the profile toolbar. Settings contains the existing account and
appearance sheet; each account can open its own profile. Missing values are
explicit and section failures remain visible. Profile details and the optional
portrait stay in screen memory; documents expose metadata without scan downloads.

## References and evidence

- [Research and baseline audit](../docs/design/AI-SLOP-AUDIT.md)
- [UIT identity and asset provenance](../docs/design/ASSETS.md)
- [Renders and verification workflow](../docs/design/README.md)

References guide judgment. Do not copy a dashboard template and call a color or
font substitution a structural revision. Review 360/390/412dp phones, dark mode,
canceled/empty/overdue states and larger text when changing these patterns. Do not
generate desktop previews or use archived desktop images as the review baseline.
