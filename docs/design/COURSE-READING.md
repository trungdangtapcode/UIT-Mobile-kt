# Course reading phone direction

The student resumes a downloaded lecture PDF between classes and marks a page or
records a question for later study. Reading the actual page is the focal task;
notes are secondary and stay one tap away. Reuse the UIT phone shell, five bottom
destinations, Plex Sans/Mono typography and existing asymmetric cut-corner frames.

- Domain: lecture pages, page margins, reading position, page bookmarks, note pins,
  source versions, verified downloads and annotation sidecars.
- Color world: white lecture paper, navy reading canvas, UIT blue controls, gray
  page numbers and amber unsaved/error notices.
- Signature: a full-width original PDF page with page-linked numbered note pins,
  paired with a compact page index and a version-aware local reading record.
- Avoid: desktop split-pane editors, generic chat/summaries above the document,
  and decorative terminal widgets. Use a single reading surface and native dialogs.

## Component intent before implementation

Intent: resume reading and capture a specific question without losing context.
Hierarchy: PDF page first; compact navigation/bookmark controls; details, notes and
export secondary. The document keeps its original rectangular paper edges.
Palette: existing UIT theme for chrome, white PDF paper for source fidelity.
Depth/surfaces: quiet navy canvas and shared outlined cut-corner panels/dialogs;
no shadows, new gradients or illustration behind the reading area.
Typography: original PDF typography inside the page; Plex Sans for note text and
controls, Mono for page/version metadata. Do not shrink native touch targets.
Spacing: 4dp base, 16dp phone gutters and panel padding, 8dp related-action gaps.

## Components and states

- Library: verified saved PDF choices and separate saved reading records, including
  records whose original file/version is no longer available. Inspect/export/delete
  those notes without attaching them to a new PDF version.
- Reader: compact file heading, page navigation, bookmark toggle, dominant page
  canvas, pinch/double-tap/buttons for zoom and bounded pan. Persist page and
  normalized viewport position. Keep Back available during loading or failure.
- Notes: page-linked text annotations with an optional tap-selected pin, numbered
  markers, editable text and explicit save/delete. No OCR, text-selection highlight
  extraction, freehand drawing or embedding annotations into PDF bytes is claimed.
- Export: preview the record and export JSON through Android CreateDocument only
  after a tap. Include document name/version, reading position, page bookmarks and
  notes; omit account identifiers, source URLs and credentials.
- Recovery: retain failed note drafts, expose unsaved position/save retry and reject
  stale writes. Distinguish inaccessible cache, unsupported/locked PDF, local-store
  failure, rendering failure, missing old version and removed owner.

Render actual Compose phones at 360/390/412dp, including dark mode and Vietnamese
1.3× text. Use authored synthetic PDFs/notes in public evidence. Native Android
instrumentation must exercise PdfRenderer and encrypted reading-state restoration.
