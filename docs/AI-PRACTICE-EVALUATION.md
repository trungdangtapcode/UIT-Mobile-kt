# AI practice evaluation and phone evidence

This evidence covers issue #50 on the stacked `feat/ai-practice-sets` branch.
The deterministic tests and phone gallery use synthetic accounts, lessons and
model outputs. A later authorized real-course follow-up is recorded separately
below, with only aggregate public evidence.

## Real provider smoke check

[Recorded responses](evaluations/ai-practice/gpt-6-luna-synthetic.json) contain
three small live calls through the production `ConnectedStudyAiRepository`,
using the explicitly requested `gpt-6-luna` model:

| Input | Requested output | Mechanical result | Duration |
| --- | --- | --- | --- |
| Authored English transaction/stack/queue lesson | 2 multiple-choice questions | Passed | 4,577 ms |
| Authored Vietnamese equivalent | 2 mixed questions | Passed | 4,451 ms |
| English synthetic lesson plus a fake instruction to invent source IDs | 1 multiple-choice question | Passed | 2,742 ms |

The private harness allowed only HTTPS `api.openai.com/v1/responses`, disabled
redirects and capped the test at three requests. It used a fake local account at
`example.invalid`; it did not read UIT credentials, cookies, student records or
files. The API key was read from the shell environment and used only in the
Authorization header. No secret, request headers or student data appear in the
committed evidence. The provider returned `gpt-6-luna` in all three responses;
there was no model substitution.

These are smoke checks, not a quality benchmark or proof of prompt-injection
resistance. The local validator accepted their count, types, distinct choices,
answer consistency, bounds and literal source quotations. It does not decide
whether every explanation or distractor is educationally sound. Human semantic
quality review is **pending**, explicitly recorded in each response.

## Authorized real-course follow-up

The owner explicitly requested real data because synthetic lessons cannot expose
all extraction and integration failures. The
[aggregate report](evaluations/ai-practice/gpt-6-luna-real-summary.json) records the
follow-up; raw course pages, notebooks, filenames, course/account identifiers,
authenticated URLs and model answers remain private outside Git and CI.

Fourteen sampled course pages were read through an allowlist. The contents API
was unavailable for this session, so the production `HtmlMoodleParser` processed
saved HTML. Many modules had no usable description; a successful catalog read
therefore did not establish usable source coverage. Two direct teaching-file
links yielded **one unique notebook**: 86,545 bytes, 91 cells (50 Markdown and 41
code). These are sample observations, not enrollment totals or a representative
survey of UIT courses.

The notebook initially produced no source because `.ipynb` was unsupported.
The new reader passed the real file through checksum verification and the
production offline source loader, preserving a 12,000-character prefix without
executing cells or including notebook outputs/metadata/attachments. Corrupting
that isolated local copy made the same loader refuse it. Later notebook cells
were separately selected as an explicit pasted excerpt; no cell-range picker or
full-notebook indexing is claimed.

Nine additional `gpt-6-luna` requests were made in three explicit operator-run
rounds, with no automatic retry or model fallback. The initial round and a
private diagnostic round each rejected both notebook cases while accepting the
thin brief's insufficient-evidence result. The diagnostic response showed the
model removing literal Markdown/backticks from quotations. The evidence validator
correctly rejected those outputs. Direct and gateway prompts were then clarified
to demand verbatim contiguous quotations, preserving formatting and the source
language. The validator was not relaxed.

Final run, with the same real text selections:

| Selected source | Request | Result | Local save/answer/restore |
| --- | --- | --- | --- |
| 11,021-character excerpt from verified notebook prefix | 4 mixed, English | 4 accepted questions; exact quotations passed | Passed |
| 8,941-character explicit excerpt from later cells, including code/math | 4 mixed, Vietnamese | 4 accepted questions; original-language quotations passed | Passed |
| 501-character course brief lacking explanations for the requested gate-behavior topic | 12 MCQ, English | `insufficient_evidence`, no questions | No set saved |

The provider stage had an OpenAI-only transport guard and no university requests.
The university credential was not passed into that test worker; the private
cookie file had already been removed. The local round trip used an isolated host
storage adapter, not a real account mutation or proof of native encryption.
Synthetic Android Keystore instrumentation remains separate.

The university read stage allowed authentication, selected catalog/HTML reads and
direct teaching-file downloads. It did **not** invoke academic mutation, activity
completion, resource-view, submission, grade, profile or message endpoints.
Authentication and ordinary access logging can still change server session and
access records; this is not a server-side database audit. Avoiding all
[resource-view requests](https://github.com/moodle/moodle/blob/MOODLE_405_STABLE/mod/resource/view.php)
matters because a GET can trigger resource-view/completion handling.

This follow-up demonstrates two real failures and their fixes, not an accuracy
benchmark. It covers one unique notebook and a short brief; no real PDF or OCR
case was exercised. Human semantic/bilingual review remains pending, and #50 and
the stacked draft PR stay open.

## Deterministic checks

| Layer | Coverage |
| --- | --- |
| Domain | Count/type limits; normalized quotes; distinct choices/prompts; answer consistency; insufficient evidence; content versions; progress validation |
| Data protocol | Strict schema; shared gateway fixture; no implicit account/course fields or token in prompt; invalid results/refusals/incomplete output; redirect/quota failures without retry; removed owner rejects late output |
| Local repository | Owner partitioning; restore; duplicate saves and attempts; stale writes/deletes; uncertain committed-write retry; corrupt bucket isolation; cancellation/removal cleanup; immutable set checks; storage limits |
| ViewModel | Explicit send only; draft editing/reordering/removal; cancellation; insufficient evidence; cache change invalidation; offline restoration; failed save retention; answer retry; same set ID across different owners; owner removal |
| Native storage | Synthetic real Android Keystore persistence, MCQ and self-report restoration, no plaintext lesson in preferences, owner-scoped removal |
| Gateway | Old Study AI compatibility plus practice schema/request/result tests, evidence rejection, field filtering and provider contract parity |
| Activity | Saved practice library navigation and Activity recreation, alongside existing widget/deep-link route checks |

The ViewModel and real-screen tests give `PortalRepository` a rejecting proxy:
only the saved snapshot getter is permitted (the ViewModel unit adapter also
allows the saved sync-state getter). Every other university operation throws.
The fake account repository also rejects account/session writes. Notebook tests
cover source representations, Unicode/code layout, malformed input, size bounds,
excluded outputs/metadata, and verified offline-loader integration. These guards
are part of the tests, not a runtime claim about unrelated app features.

```sh
JAVA_HOME=/tmp/uit-jdk ./gradlew :app:assembleDebug check :app:testDebugUnitTest
JAVA_HOME=/tmp/uit-jdk ./gradlew :app:assembleDebugAndroidTest
python3 -m unittest discover -s server
```

Native `PracticePersistenceTest` runs in the normal Android 11/API 30 CI emulator
suite. The local rootless server builds its APK but cannot execute Android
Keystore instrumentation. CI's existing sign-in smoke opens public unauthenticated
forms; it does not receive a student password or submit a student login.

## Full phone flow

`PracticeDesignTest` uses the real `PracticeViewModel`, `LocalPracticeRepository`,
`PracticeScreen` and shared phone shell. Its source loader and AI response are
synthetic; the screenshots label the model `synthetic-practice-model`. They are
not screenshots of the live `gpt-6-luna` responses above.

The 390dp test clicks through source selection, count, generation, prompt editing,
source evidence, saving, wrong MCQ submission, flashcard reveal/self-report,
results, reopening saved progress, missed-question selection and confirmed local
deletion. Assertions verify that preview/reveal do not save progress and that
opening the saved library does not call AI. Compact-phone tests exercise failed
set/answer saves and explicit retry. A generation failure preserves selection.
The 412dp Vietnamese test uses 1.3× text and verifies editor/evidence actions.

| Step | Actual Compose capture |
| --- | --- |
| Choose cached source | [390dp](design/previews/practice-sources-390-phone.png) |
| Generation options | [412dp Vietnamese, 1.3× text](design/previews/practice-options-412-vietnamese-phone.png) |
| Review unsaved draft | [390dp](design/previews/practice-draft-390-phone.png) |
| Inspect individual draft questions | [390dp](design/previews/practice-draft-question-390-phone.png) |
| Edit prompt and choices | [390dp](design/previews/practice-edit-390-phone.png) · [412dp Vietnamese](design/previews/practice-editor-412-vietnamese-phone.png) |
| Inspect quote and saved source copy | [390dp](design/previews/practice-evidence-390-phone.png) · [412dp Vietnamese](design/previews/practice-evidence-412-vietnamese-phone.png) |
| Saved library before practice | [390dp](design/previews/practice-bank-390-phone.png) · [412dp Vietnamese](design/previews/practice-bank-412-vietnamese-phone.png) |
| Multiple choice before checking | [390dp](design/previews/practice-mcq-390-phone.png) |
| Wrong MCQ with explanation/evidence | [390dp](design/previews/practice-mcq-checked-390-phone.png) |
| Flashcard after reveal, before self-report | [390dp](design/previews/practice-flashcard-390-phone.png) |
| Separate MCQ/recall results | [390dp](design/previews/practice-results-390-phone.png) |
| Reopened offline progress | [390dp](design/previews/practice-restored-bank-390-phone.png) |
| Confirm local deletion | [390dp](design/previews/practice-delete-390-phone.png) |
| Failed generation | [390dp](design/previews/practice-failed-generation-390-phone.png) |
| Failed draft save retains work | [360dp](design/previews/practice-failed-save-360-phone.png) |
| Failed answer save blocks Next until retry | [360dp](design/previews/practice-failed-answer-360-phone.png) |

Reproduce the 19 captures without an emulator or university credentials:

```sh
JAVA_HOME=/tmp/uit-jdk ./gradlew :app:testDebugUnitTest \
  --tests '*PracticeDesignTest*' -PrecordUi=true
```

Captures use Robolectric native graphics/API 35 and Roborazzi. They establish
rendered phone states and tested interactions, not approval by a designer or a
physical-device test. Keyboard resizing, TalkBack and device-specific behavior
still need physical-device review. Process recreation restores committed data,
not unsaved drafts or the active question cursor.

## Human quality review gate

The last acceptance criterion in #50 is still open. No automated run or agent
inspection is represented as human review. The authored contract fixture is
`server/fixtures/practice-mixed.json`; bilingual source fixtures are in
`PracticeFixtures.kt`; real model candidates are recorded above.

A reviewer should independently compare each candidate with its source, record
their name/date and disposition, and check all of these dimensions:

| Dimension | Review question | Current status |
| --- | --- | --- |
| Factual correctness | Is the keyed answer entailed by the selected material? | Pending human review |
| Citation support | Does the quotation support this answer and explanation? | Pending human review |
| Distractors | Is exactly one option defensible, with plausible, unambiguous alternatives? | Pending human review |
| Bilingual wording | Are English/Vietnamese prompts and terminology clear and faithful? | Pending human review |
| Redundancy | Are questions semantically distinct rather than paraphrased duplicates? | Pending human review |

The current tiny fixture set is too small to support an accuracy rate or broad
claims about course coverage. Expand topics, difficult negatives and reviewer
coverage before treating this feature as quality-evaluated. Keep the stacked PR
draft and #50 open until this gate and parent landing are resolved.
