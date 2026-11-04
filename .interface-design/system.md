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
- Fresh installations enable dark appearance and device colors by default.
  Android 12+ uses the wallpaper-derived dark palette; older versions use the UIT
  dark palette. Existing saved appearance choices win.
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
portrait stay in screen memory. Uploaded documents expose a View scan action;
missing images disable that action. Open images in a full-screen native dialog
with a Back button and document title above an image fitted inside a quiet canvas.
Keep zoom, fit and gesture hints below the image, with native touch targets. The
image remains the focal content and keeps its original rectangular edges. Support
pinch, double-tap and bounded panning. Viewer errors retain Back and Retry controls;
closing or switching accounts clears image state without creating a file.

Home screen widgets: use real launcher RemoteViews in the app's saved appearance,
sharing the Material color resolver and bundled fonts. Keep a small emblem/date
masthead, then the class name, exact teaching time, class code and room. Next class
is compact; Today adds remaining rows and the closest deadline as space allows.
Larger fonts reduce the row count. The saved snapshot time and GMT+07 remain visible.
Keep the same 8dp small cuts and 28dp physical bottom-right cut in the native frame.
No campus hero, counters or decorative charts inside widgets. Disconnected widgets
offer sign-in; missing submission status stays explicit. Class taps focus their
schedule date; deadline taps focus the appropriate queue. The pin chooser uses
native controls and includes manual launcher instructions.

Schedule planning: Find free time stays inside the schedule's single phone column
with the five bottom destinations. Pair same-day start/end fields, then minimum
and buffer fields in the shared panel; keep the date full width. Candidate panels
lead with exact time bounds and minutes, with an Inspect interval action exposing
neighboring buffered commitments. Keep the commitments and deadline lists below
results. A compact cut-corner personal-block dialog scrolls its form and retains
Add/Cancel actions; validation stays in the form. Unknown end times and missing
saved data remain explicit. Account-session changes dismiss the form and clear
personal planning drafts. These are local planning aids, not room bookings.

Code Tutor: keep the numbered selected-code pane as the anchor inside Courses.
Use a monospaced gutter and code, horizontal scroll for long lines and bounded
vertical scroll. Keep Explain/Hints/Test ideas in horizontal cut-corner controls.
Distinguish exact code evidence, checked course context, model suggestions and
unknowns using text labels. Short evidence dialogs wrap content; long proposals
scroll with Copy/Close actions retained. Preserve physical bottom-right cuts and
native controls. No chat bubbles, execution buttons or automatic edit application.

Course PDF reading: the original rectangular page dominates a quiet navy canvas.
Keep native previous/page-index/next/bookmark controls compact; secondary notes and
bookmarks use horizontal cut-corner tabs in the same phone column. Fit-page leaves
parent scrolling available; enlarged pages pan within bounds. Numbered 48dp note
pins map to normalized page coordinates. Reuse bounded native dialogs for editing
and export; retain explicit Save/Delete and confirm record deletion. Old-version
notes remain separate from the current PDF. No desktop sidebar or automatic AI
processing belongs in this reading flow.

## References and evidence

- [Research and baseline audit](../docs/design/AI-SLOP-AUDIT.md)
- [UIT identity and asset provenance](../docs/design/ASSETS.md)
- [Renders and verification workflow](../docs/design/README.md)

References guide judgment. Do not copy a dashboard template and call a color or
font substitution a structural revision. Review 360/390/412dp phones, dark mode,
canceled/empty/overdue states and larger text when changing these patterns. Do not
generate desktop previews or use archived desktop images as the review baseline.

Course knowledge maps: a selected concept leads a one-column directed neighborhood.
Use 28dp connector gutters, native neighbor buttons, labeled incoming/outgoing
propositions and a horizontally scrollable concept index. Keep an equivalent
relationship list and source-copy dialogs. Definitions use bodyLarge; labels use
existing title styles. All controls keep TechnicalShapes and native touch targets.

Forum: the topic title remains the main focus. Keep public discovery controls
inside the single phone column: a native sort menu, a second equal-width period
menu for Top, and a separate searchable Tags tab. Controls use TechnicalShapes,
8dp spacing and 48dp touch targets. A selected tag has a visible clear action.
Do not present guest filters as account features. Post bodies use semantic inline
styles and nested blocks; concealed spoilers stay out of accessibility semantics.
Images open on a quiet fullscreen canvas with explicit zoom/fit/close controls.
Revision dialogs retain Changes/Before/After, source and close actions. Forum
review screenshots use actual production UIT responses with capture provenance.

University announcements: enter from Today or Inbox without adding a bottom tab.
Use a date-led, single-column list of cut-corner panels. Full titles lead; source,
unread/correction state and checked time remain explicit. Search and horizontally
scrollable filters precede the list. Group matching title/date cross-posts while
retaining each source action. Article detail has a compact title/source panel,
Save/Remind/Open original actions, then semantic native text and tables. Do not
place long permalink strings in the title panel. Source settings stay in the same
column. Reminder forms use a bounded, scrollable native dialog with explicit GMT+07
and no implied university deadline. All public preferences are device-local.


UIT Assistant: keep the existing five bottom destinations and enter from Today or
an announcement. A one-column workspace list leads with the student-chosen label
and selected sources. Chat keeps a compact context panel and a fixed bottom
composer. Right-aligned user messages use primaryContainer with the shared
8dp/28dp cut-corner panel and 88% column width; left-aligned assistant replies use
the reading canvas with a 28dp authentic emblem and compact role/model identity.
Paragraphs remain unboxed; only suggestions and unknowns have kind labels. Numbered
references map to a collapsed Sources control after each answer, alongside native
Copy and Checklist actions. The header source selector is one 48dp row, with
workspace management in an overflow menu. A 56dp cut-corner Send/Stop control sits
beside the 1–4 line composer. Streaming previews carry a visible unverified-draft
label and expose no citation or save actions. Follow new questions; let readers
scroll long answers with an explicit Latest message action. Source inspection shows the retained paragraph version in a
bounded scrollable dialog. Context, checklist review and connection forms reuse
TechnicalShapes and native touch targets. Source scope is public-only in the initial
release; configuration and Send remain explicit. Preserve Back to workspace and
native original-source navigation. Credentials stay outside saveable form state.
