# AI practice evaluation and phone evidence

This evidence covers issue #50 on the stacked `feat/ai-practice-sets` branch.
All accounts, lessons, progress and model outputs used for testing are synthetic.
There was no real UIT login or university account operation for this feature.

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
The fake account repository also rejects account/session writes. These guards
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
