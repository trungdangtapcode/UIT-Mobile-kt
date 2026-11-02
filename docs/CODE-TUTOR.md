# AI Code Tutor

Code Tutor explains explicitly selected code, reveals three progressive hints and
suggests test ideas. It is a phone workflow inside Courses, with the existing five
bottom destinations, UIT colors, bundled fonts and asymmetric cut-corner frames.
See the [design brief](design/CODE-TUTOR.md) and
[evaluation / phone evidence](CODE-TUTOR-EVALUATION.md).

## Use

1. Open **Courses → AI Code Tutor**. A selected course adds optional saved course
   context and verified code-file imports. Opening from the course directory also
   supports pasted code without connecting a university account.
2. Paste code, or choose one saved code file / notebook code cell. Import is a
   copy into the editor, with the file version and original notebook cell number.
   Edit the code language, first/last line, optional question and runtime/library
   details. Select the explanation language independently: English or Vietnamese.
3. **Preview selected lines** shows exactly the code range with original line
   numbers. Inspect optional course sources before checking any of them. Nothing
   is checked automatically. The question and environment fields also go to AI.
4. Choose Explain, Hints or Test ideas, configure the existing AI connection and
   explicitly tap **Send selection and analyze**. Hints mode hides the summary and
   explanations until the student chooses another tab. Revealing a hint sends no
   additional request.
5. Inspect a code reference to see its exact line span and quotation. Course
   citations open the selected source copy. Suggestions and missing context have
   their own labels. A proposed edit opens an original/suggested preview with an
   explicit Copy action; it never replaces the editor or course file.

Direct mode uses the student's personal OpenAI API key and configurable model ID;
no backend is required. Optional gateway mode uses the existing authenticated
`POST /v1/study` endpoint. Both use the same tutor contract. Connection settings
are shared with Study AI and AI practice; changing or disconnecting that connection
also affects those features. Keys stay in the existing encrypted Android storage,
never in the APK. See [Study AI setup](STUDY-AI.md) for connection configuration.
The tested model is `gpt-6-luna`; access depends on the configured API project.

## Import and selection limits

| Boundary | Limit / behavior |
| --- | --- |
| Editor | 48,000 UTF-16 code units; oversized paste is rejected, preserving the prior text |
| Sent code | 1–300 selected lines, nonblank, at most 12,000 UTF-16 code units |
| Line range | Inclusive, 1-based original editor lines; LF-normalized, indentation preserved |
| Question / environment | 1,200 / 300 UTF-16 code units |
| Course context | Up to four explicitly checked sources; code plus context at most 36,000 UTF-16 code units |
| Verified import | Existing owner/course-partitioned checksum-verified cache only; UTF-8, optional BOM |
| Files | First 24 distinct file URLs; at most 8 MiB per file; unsupported/corrupt/oversized entries are omitted |
| Catalog | At most 60 entries / 512,000 UTF-16 code units total; a cell/file over the editor limit is omitted |
| Notebook | nbformat 4, at most 10,000 cells; individual code cells only, with original cell number |

Supported imports: `.py`, `.kt`, `.java`, `.c`, `.h`, `.cpp`, `.js`, `.ts`, `.sql`,
`.rs`, `.go`, `.sh`, and `.ipynb`. Language metadata is only a prefill; unknown
notebook languages are **Other / unspecified**, with no assumed runtime. These
labels guide the model; they do not enable compilers or establish language support
quality. Code cells beyond the older document-text prefix can be chosen directly.
Notebook outputs, Markdown cells, attachments and author metadata are excluded
from code imports. Optional checked course text uses the existing source loader;
that separate context may itself include Markdown or code from a teaching file.

Import never downloads a file. Use Offline course packs first, or paste code.
Unavailable/unverified files are absent; the omission count is a bounded-catalog
notice, not an enrollment total or complete count of unavailable downloads. The
loader hashes captured bytes again to reject a file replaced after cache lookup.
Imported copies are not overwritten automatically when the cached source changes.

## Contract and validation

The app creates a version hash over selected code, original first line, code and
output languages, question, environment and selected context. The provider receives
explicit `{number, text}` line objects, avoiding confusion between editor, array,
JSON and notebook-file line numbers. Owner IDs, course IDs, code-import filenames, download
URLs, cookies, university credentials and unselected editor lines are not added
automatically. Checked course context includes its source ID, title and text.
Text the student explicitly selects can still contain private data;
preview it before sending.

A strict Responses JSON schema has no tools and sets `store: false`. The client
uses the fixed HTTPS OpenAI Responses endpoint, or its configured gateway; it does
not follow redirects or automatically retry / switch models. The gateway checks
the same request and response rules. `store: false` is a request setting, not a
claim about every provider retention policy.

| Result | Local acceptance rules |
| --- | --- |
| Ready | 1–8 observations, exactly three distinct hints, 1–6 distinct test ideas |
| Code evidence | At least one in-range reference; literal quote must occur in the declared inclusive line span |
| Course-backed point | Citation to a checked source, with a contiguous literal quote after whitespace normalization |
| Model suggestion / unknown | No course citations; code references may still locate the discussed text |
| Test idea | Name, input, predicted expected outcome, reason, and 1–3 valid code references |
| Proposed edit | Code and reason both present or both empty; at most 12,000 code units / 300 lines |
| Insufficient context | Summary only; no observations, hints, tests or proposed edit |

Code quotes preserve internal spaces, tabs, punctuation and indentation; only line
endings normalize to LF. Context quotations preserve Markdown/backticks/LaTeX and
source language, with whitespace normalization only. Wrong line ranges, invented
sources, malformed scalar types, incomplete/refused responses and invalid edits
fail validation. A quote establishes where text occurs; it does not prove the
model's interpretation, complexity claim or proposed fix is correct. Code views
use native monospaced text with lightweight keyword accents. Other model text
retains literal Markdown/LaTeX; this feature does not add a rich Markdown renderer.

## State, privacy and limits

Code and analysis stay in the Activity-owned ViewModel, with no Tutor history,
progress store, file save or database migration. Bottom-navigation changes retain
that Activity's workspace. Explicit **Leave and clear**, account removal, a new
Activity after process death, or opening another course clears it. Private editor
text is excluded from Compose saved-instance state. Edit selection clears the
analysis/hints but retains code. Cancellation and failures retain the editor;
late replies cannot republish a canceled/removed workspace. Provider processing or
billing may already have started when cancellation is requested.

Cached metadata/file-list changes invalidate the analysis and require a local
reload. Loading checks the snapshot again before accepting context. No background
university refresh is triggered by Code Tutor. Replacing bytes at an unchanged URL
is discovered on an explicit local reload; the editor remains an explicit copy.

No code, notebook cell, suggested test or edit is executed. No package installation,
repository upload, browser fetch from model text, assignment submission, messaging,
profile edit or other university write is part of Tutor. Copy writes only the
selected text to Android's clipboard after a tap. AI analysis is untrusted text;
students must review advice and independently run tests in their own environment.

The initial evaluation covers Python plus deterministic boundary/protocol checks.
It does not establish comparable model quality for every listed language, rich
notebook dependency graphs, pedagogical correctness or resistance to arbitrary
prompt injection. See the explicit human review gate in the evidence document.
