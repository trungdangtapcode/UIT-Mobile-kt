# Code Tutor phone direction

The student is reading lab code between classes or trying to understand a failing
exercise on a phone. The primary task is to inspect a selected range, understand
its behavior and choose one next step. Preserve the established UIT palette,
IBM Plex typography and asymmetric cut-corner frames; use the existing native
phone shell with five bottom destinations.

- Domain: notebook cells, line ranges, preconditions, invariants, lab instructions,
  edge cases, compiler/toolchain assumptions and proposed assertions.
- Color world: UIT blue, navy laboratory screens, white source text, gray editor
  gutters, amber unverified notices. Status colors communicate meaning, not decoration.
- Signature: a numbered source pane paired with exact line-reference actions.
  Opening a reference reveals the original selected lines and their quotation.
- Rejected defaults: a generic chatbot transcript, decorative terminal telemetry,
  and a desktop editor/sidebar. Use a single phone column and task-specific views.

## Shared component intent

Intent: inspect code and learn before changing it; calm, precise and technical.
Hierarchy: the selected numbered code leads, followed by one explicit Analyze
action. Results separate Explain, Hints and Test ideas. Suggested edits stay behind
an explicit preview and copy action, with no Apply or Run control.
Palette/depth/surfaces: existing dark UIT surfaces and Panel borders, no new shadows,
background illustration or accent palette. Keep 8dp/28dp panel cuts.
Typography: IBM Plex Sans for explanations, Mono for code/line numbers. Preserve
indentation with horizontally scrollable source text rather than shrinking it.
Spacing: 4dp base, 16dp phone gutters/panel padding, 8dp related-control gaps.

## Component behavior

- Editor: explicit code language, selected start/end lines, output language and
  optional question/toolchain. Preview exactly the range sent, not the whole editor.
- Verified imports: choose an individual downloaded code file or notebook code cell;
  preserve its version and show omitted/unsupported input coverage. Never run cells.
- Context: optional, unchecked saved course text with a source-inspection action.
- Results: source version/model, separately labeled code evidence, course evidence,
  model suggestions and unknowns. No semantic-truth or executed-test badge.
- Hints: three ordered steps, revealed only by explicit taps. Revealing a generated
  hint sends no new request. Reset revealed steps when the selection changes.
- Test ideas: show input, predicted/unknown outcome and rationale, always Not run.
- Proposed edit: inspect original and suggested text; copying never changes the
  editor, repository or university records.
- Evidence dialogs: bounded scroll body, visible Close, exact source text/line span.
  Use native dialog focus and Back; no embedded browser or external asset loading.

Review the real Compose screens at 360/390/412dp, dark appearance and Vietnamese
1.3× text. Include invalid selection, unconfigured/loading/failure/cancel states,
reference inspection, progressive hints and proposed-edit preview. Public captures
use synthetic code; authorized real-code validation retains private content outside Git.
