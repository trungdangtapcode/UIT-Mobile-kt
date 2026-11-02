# Course Knowledge Maps

Issue [#76](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues/76) implements
source-backed concept maps in Courses. Work is on `feat/knowledge-maps`, stacked
on [PDF reader #75](https://github.com/trungdangtapcode/UIT-Mobile-kt/pull/75).
The feature creates editable maps; algorithm simulators remain a follow-up.

## Use

1. Open **Courses → Knowledge maps**, or the same action in a selected course.
   Choose an owned course with saved materials. Opening the feature reads the
   local snapshot; it does not refresh, authenticate or contact UIT.
2. Inspect and explicitly check activity descriptions, verified downloaded text/
   notebook/PDF excerpts, or add pasted text. Nothing is checked automatically.
   Supply a focus question and choose English or Vietnamese independently of the
   app interface language. File previews can be prefixes; inspect their contents.
3. Configure the shared Study AI connection if needed. Direct OpenAI uses the
   personal API key entered on the phone and needs no hosted backend. The optional
   gateway supports the same map contract. Explicitly tap **Send selection and
   create map**; selecting, configuring and reading do not generate model calls.
4. Explore the selected concept and its incoming/outgoing relationships. Arrows
   retain direction and relation labels form readable propositions. Tap a neighbor
   or the horizontally scrollable concept index to refocus. The complete
   relationship list provides another accessible route without zooming.
5. **Inspect evidence** opens an original selected source copy and its quote.
   A relationship opens its meaning, citations and edit controls. Edit concept
   labels/definitions or relation labels/citations, then explicitly **Save reviewed
   map**. Missing, invented or formatting-stripped quotations prevent applying edits.
6. Reopen saved maps and evidence offline. Deleting a map requires confirmation
   and removes only that map/source copies. Discarding an unsaved draft also asks
   for confirmation. Failed saves retain the exact payload for explicit retry.

The node index, neighbor buttons, relation actions and dialogs use native Compose
semantics and existing technical shapes. Decorative connectors have no separate
TalkBack role. This is a readable phone concept neighborhood with an equivalent
relationship list, not a desktop graph editor or a whole-course force layout.

## Evidence and source versions

Maps retain the selected `StudySource` copies and a SHA-256 version over their IDs,
titles and exact text. Generation and source preview use only these copies. A
matching quote establishes occurrence, not logical entailment or pedagogical
correctness. Inspect every definition and relation before saving; generated maps
are drafts, not a university assessment or mastery estimate.

The current source contract does not carry a trustworthy PDF page/cell location.
Evidence therefore opens the saved excerpt, not an invented page in the PDF
reader. Source copies are historical, even if a downloaded file later changes or
is removed. Live document synchronization, page-aware navigation and a complete
untruncated document index remain ingestion/search follow-ups. Saved course
metadata changes during selection cancel generation and require source reselection;
byte changes at a stable URL are discovered on explicit local reload.

Downloaded inputs are captured through bounded streams and hashed again against
cache checksums before extraction, preventing a replacement between cache lookup
and reading. The loader reuses existing notebook/text/basic PDF extraction:
notebook outputs, metadata and attachments are excluded, and cells never execute.
Corrupt, unsupported or missing files are skipped without hiding usable sources.

## Contract and validation

`StudyAiRepository.knowledge` uses `mode=knowledge` through the shared fixed direct
Responses endpoint or optional authenticated `POST /v1/study` gateway. Both request
strict structured JSON, `store: false`, no tools, no redirects and no automatic
retry/model fallback. Model refusal/incomplete output and malformed results fail
closed. `store: false` is a request setting, not a zero-retention guarantee.

| Boundary | Implemented rule |
| --- | --- |
| Scope | One owned course; 1–5 explicitly checked sources, 12,000 characters/source and 36,000 total |
| Focus / output language | Nonblank focus up to 400 characters; English or Vietnamese |
| Graph | 2–12 distinct concepts, 1–20 directed relationships, one connected component |
| IDs and labels | Bounded ASCII IDs; unique concept IDs/normalized labels and relationship IDs; no duplicate relation triples, self loops or dangling endpoints |
| Content | Title up to 160, concept label/relation up to 100, definition up to 800 characters |
| Evidence | Every definition/relation has 1–3 known-source quotes, 6–500 characters after whitespace normalization; Markdown, punctuation, code and LaTeX remain literal |
| Insufficient evidence | Explanatory message and empty concept/relation arrays; no partial map |
| Cycles | General relation cycles are allowed; no prerequisite DAG or causal validity is inferred |
| Source catalog | Up to 30 saved module descriptions and 24 distinct downloaded files, at most 8 MiB/file; catalog capped at 60 entries |
| Local library | Up to 12 maps and 1,000,000 serialized UTF-16 code units per owner bucket |

Input/response validation does not prove that a quote supports a proposition.
Instructions specifically prohibit inventing relations or expanding definitions
from memory; the live evaluation below demonstrates why review remains necessary.
No arbitrary HTML, SVG, Mermaid, JavaScript or generated code is executed. Diagram
positions and connectors are application-owned Compose rendering.

## Local persistence and account removal

`LocalKnowledgeRepository` uses the independent `knowledge` storage namespace and
`uit.knowledge.v1` Android Keystore AES-GCM alias. Private source text, model output,
focus question and labels are not added to saved-instance-state bundles or backups.
The Activity-owned ViewModel survives configuration changes; unsaved drafts do not
survive process death. Saved records include original source copies and model/time
provenance. Scope is validated before accepting results and writing records.

Edits use optimistic revisions. An uncertain committed write can retry the exact
expected/payload pair without duplicating a map; further editing is disabled until
that save resolves. Stale writes/deletes cannot overwrite a newer revision.
Per-owner decode failures do not expose another owner or overwrite corrupted data.

Account removal clears the active workspace/dialog copies and removes its encrypted
bucket through the existing cleanup path. Repository writes check ownership before
and after I/O and purge late writes under `NonCancellable`. Other owners survive.
The feature has no university-write, login, messaging or submission capability.
Only selected titles/text, focus and requested language go to the configured AI
processor; account/course IDs, profile data and university credentials are omitted.
User-selected/pasted text can itself be sensitive; inspect before sending.

## Verification and live model evidence

Deterministic coverage includes graph topology/quote bounds, direct/gateway payload
parity, captured live results through the Android validator, encrypted-library
serialization/revisions/retry/isolation, notebook/cache replacement handling,
ViewModel lifecycle and actual production Compose phone flows. The Python suite
checks schema/instruction parity and gateway validation. Normal CI uses no API key.

The native `KnowledgeNativeTest` exercises production Compose/VM, real Android
Keystore persistence, offline restoration, local account cleanup and a phone
capture, with synthetic sources and a deterministic AI adapter. Final CI/native
results and the APK are linked on the PR after the head is verified.

The explicit live run used the owner's configured `gpt-6-luna` API access with
**authored public fixture text only**. No UIT account was accessed. Three calls
per prompt version tested English, Vietnamese and an out-of-source nuclear-fusion
question. No automatic retries were used. The initial Vietnamese definition
expanded FIFO from memory beyond its selected source; the instructions were
strengthened and all three cases were rerun. The final English/Vietnamese maps
passed structural/quote validation and the unrelated question returned
`insufficient_evidence`. That is not a representative accuracy benchmark.

- [Initial synthetic outputs and recorded limitation](evaluations/knowledge-maps/gpt-6-luna-initial.json).
- [Final synthetic requests, outputs and timings](evaluations/knowledge-maps/gpt-6-luna-synthetic.json).

The assistant inspected these small examples; independent human educational
review, real-course quality evaluation and learning-outcome measurement remain
open. No physical-phone compatibility approval is claimed from host/emulator tests.

## Inspected phone gallery

All images render the production Compose screen/VM with authored fixture concepts
and sources. They are not generated UI mockups or real university material.
Vietnamese captures use the Vietnamese interface at 1.3× font scale; the graph in
those deterministic fixtures intentionally remains English to test independent
content/interface languages.

| Step | Capture |
| --- | --- |
| Entry / source selection | [390dp library](design/previews/knowledge-library-390-phone.png) · [Explicit source selection](design/previews/knowledge-selection-390-phone.png) |
| Explore | [Initial map](design/previews/knowledge-map-390-phone.png) · [BFS neighborhood with both directions](design/previews/knowledge-bfs-neighborhood-390-phone.png) |
| Inspect / edit | [Evidence](design/previews/knowledge-evidence-390-phone.png) · [Concept editor](design/previews/knowledge-edit-390-phone.png) |
| Save / reopen | [Saved library](design/previews/knowledge-saved-library-390-phone.png) · [Restored edited map](design/previews/knowledge-restored-390-phone.png) |
| Recovery / deletion | [360dp retained failed save](design/previews/knowledge-failed-save-360-phone.png) · [Delete confirmation](design/previews/knowledge-delete-360-phone.png) |
| Vietnamese / larger text | [412dp map](design/previews/knowledge-map-412-vietnamese-phone.png) · [Relationship editor](design/previews/knowledge-relation-412-vietnamese-phone.png) · [Source copy](design/previews/knowledge-evidence-412-vietnamese-phone.png) |

## Reproduce deterministic checks

```sh
python3 -m unittest discover -s server
JAVA_HOME=/path/to/jdk21 ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest check :app:testDebugUnitTest
JAVA_HOME=/path/to/jdk21 ./gradlew :app:testDebugUnitTest --tests '*KnowledgeDesignTest' -PrecordUi=true
```

Run connected instrumentation on an Android device/emulator for the native test.
The rootless SSH host does not provide a physical device; normal GitHub Android CI
runs instrumentation. Live output files are checked offline without repeating paid
calls. See the [design intent](design/KNOWLEDGE-MAPS.md) and
[original proposal](research/KNOWLEDGE-VISUALIZATION.md).
