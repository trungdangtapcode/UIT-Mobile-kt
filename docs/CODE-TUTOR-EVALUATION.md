# Code Tutor evaluation and phone evidence

Issue [#54](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues/54) is implemented
on `feat/ai-code-tutor`, stacked on AI practice PR
[#73](https://github.com/trungdangtapcode/UIT-Mobile-kt/pull/73). This document records
mechanical evidence and its limits. Human educational/bilingual approval remains
pending; keep the issue open and the stacked PR draft until that gate is resolved.

## Provider checks

The [aggregate report](evaluations/code-tutor/gpt-6-luna-summary.json) records three
final requests through the production `ConnectedStudyAiRepository`, using the
explicitly requested `gpt-6-luna` model with direct Responses structured output:

| Input | Output language | Mechanical result | Hints / suggested tests |
| --- | --- | --- | --- |
| Authored `first(values)` indexing an empty list | English | Passed | 3 / 2 |
| Authored correct integer addition function, preceded by a misleading instruction to claim tests passed | English | Passed | 3 / 3 |
| One selected code cell from the previously downloaded real teaching notebook | Vietnamese | Passed | 3 / 4 |

The final real selection has 1,991 characters and 42 lines. The production checksum
loader exposed all 41 code cells from the 91-cell, 86,545-byte notebook; this test
selected one cell, not the whole notebook or student repository. Outputs, Markdown
cells, attachments and author metadata were excluded from the code import. The
cached file came from the owner's earlier authorized read session. This feature's
test stage made **zero university requests**, performed no new authentication and
did not execute the cell or any suggested test. It used an isolated cache and an
in-memory connection adapter, not the student's actual Android storage.

There were six provider requests in total, in two explicit operator-run rounds,
with no automatic retry or model substitution. The first recorder consumed the
response stream before client decoding. Those three captured responses passed an
offline replay through the production parser/validator; they were not model
validation failures. The recorder was removed for the final three complete calls.
A concurrent local build also caused a stale private test-class discovery error;
the final three individual provider cases passed, and ordinary repository checks
were subsequently run without private test sources. Paid tests and credentials
are excluded from normal CI.

The private worker allowed only HTTPS `api.openai.com/v1/responses`, with no Cookie
header, redirects or tools. The UIT credential was explicitly removed from its
environment. Raw notebooks, cell text, real filenames/URLs, account identifiers,
provider headers and real model answers remain outside Git/CI/PR comments. Only
aggregate real-input evidence and authored synthetic code are public.

These checks establish a working provider/protocol path, literal reference
matching, count/type bounds and source separation. They do not establish that every
explanation, hint or test prediction is correct, that all languages are equally
well supported, or that arbitrary prompt injection is defeated. Listed test ideas
remain **unexecuted**.

## Deterministic checks

| Layer | Meaningful boundaries |
| --- | --- |
| Domain, 6 tests | Inclusive selection; indentation/newlines; declared line spans; context provenance; request/result limits; hint/test/edit bounds; insufficient-context and version rules |
| Data, 10 tests | Standalone mode without an account; fixed OpenAI endpoint/schema/no tools; direct/gateway fixture parity; no implicit owner/profile/key in prompt; invalid line/scalar types; owner removal; refusal/incomplete/quota/redirect failure without retry; verified cell imports; malformed/oversized/unsupported files; cache replacement; owner isolation and catalog limits |
| ViewModel, 12 tests | Explicit send and checked selection only; no university transport; standalone workflow; invalid input/unconfigured connection; local progressive reveal; preview-only edits; cancellation/failure/late replies; metadata changes during loading; assignment-file invalidation; unrelated timestamps; account removal; close and configuration failures |
| Phone UI, 4 tests | Import → preview → analyze → exact code/course evidence → copy proposal → hints → test ideas → edit/leave; compact validation/failure/unknown states; connection/loading/cancellation; Vietnamese at 1.3× font |
| Activity navigation, 1 additional test | Bottom-nav retention; raw editor text absent from saved instance state; new Activity restores an empty editor and Back returns to Courses |
| Optional gateway, 9 new tests | Numbered lines and formatting; missing/invented references; context provenance; hint/test/edit rules; strict schema/provider settings; no execution tools |

The synthetic university adapter throws on every operation except reading the
local snapshot. Account/session/checklist writes are forbidden by test adapters.
No new persistence layer, execution service or dependency installation was added.
The existing native Android Keystore/in-app sign-in tests remain separate from
these host tests.

Reproduce without credentials:

```sh
JAVA_HOME=/tmp/uit-jdk ./gradlew :app:testDebugUnitTest \
  --tests '*CodeTutor*Test' --tests '*WidgetNavigationTest.codeTutor*' -PrecordUi=true
JAVA_HOME=/tmp/uit-jdk ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest check
python3 -m unittest discover -s server
```

## Full phone flow

All captures are actual production Compose screens rendered with Robolectric SDK
35 native graphics and Roborazzi on a rootless Linux host. They are **not** an
emulator, physical-device session or screenshots of the private student's code.
Accounts, course names, selected code, source text and displayed AI outputs in the
21 public captures are authored synthetic fixtures. Source/answer text can remain
English under Vietnamese chrome; real output language is separately selectable.
The shared dark UIT phone shell, five bottom destinations and 8dp/28dp cuts remain.

| Stage | Phone evidence |
| --- | --- |
| Start, import, selected range | [Entry](design/previews/code-tutor-entry-390-phone.png) · [Notebook cell picker](design/previews/code-tutor-import-390-phone.png) · [Editor](design/previews/code-tutor-editor-390-phone.png) · [Selection preview](design/previews/code-tutor-selection-390-phone.png) |
| Analysis and provenance | [Explain](design/previews/code-tutor-analysis-390-phone.png) · [Exact code lines](design/previews/code-tutor-line-evidence-390-phone.png) · [Checked course source](design/previews/code-tutor-course-evidence-390-phone.png) |
| Proposed edit and hints | [Original/suggested preview](design/previews/code-tutor-proposal-390-phone.png) · [First hint, next still hidden](design/previews/code-tutor-hint-one-390-phone.png) · [All hints revealed](design/previews/code-tutor-hints-complete-390-phone.png) |
| Tests and leaving | [Unexecuted test ideas](design/previews/code-tutor-tests-390-phone.png) · [Leave/keep-work confirmation](design/previews/code-tutor-leave-390-phone.png) |
| 360dp recovery | [Invalid range](design/previews/code-tutor-invalid-range-360-phone.png) · [Failed analysis, editor retained](design/previews/code-tutor-failure-360-phone.png) · [Missing context, no generated edit](design/previews/code-tutor-missing-context-360-phone.png) |
| Connection and pending request | [Connection settings](design/previews/code-tutor-settings-390-phone.png) · [Loading/cancel](design/previews/code-tutor-loading-390-phone.png) |
| 412dp Vietnamese, 1.3× text | [Analysis](design/previews/code-tutor-analysis-412-vietnamese-phone.png) · [Line evidence](design/previews/code-tutor-evidence-412-vietnamese-phone.png) · [Scrollable proposed edit](design/previews/code-tutor-proposal-412-vietnamese-phone.png) · [Test ideas](design/previews/code-tutor-tests-412-vietnamese-phone.png) |

Images were inspected for line alignment, readable code, preserved indentation,
cut corners, wrapping labels, reachable Copy/Close actions and bottom navigation.
Short Tutor evidence dialogs wrap their content; long proposals scroll with
footer actions retained. Tests assert that Copy does not change the editor, later
hints remain hidden, revealing sends no request, and invalid input cannot send.
Screenshots and passing tests do not substitute for the owner's visual approval.

## Human quality review gate

Pending reviewer work before marking this feature ready:

- [ ] Review the authored known-correct and buggy examples for factual accuracy;
  do not treat a matching quote as proof of a correct explanation.
- [ ] Check empty/single-item, integer boundary and missing precondition suggestions
  against the declared runtime/input contract, without requiring arbitrary edits.
- [ ] Judge whether the three hints increase specificity without prematurely giving
  away the answer. The client checks distinctness/count, not teaching quality.
- [ ] Review English/Vietnamese explanations and original-language quotations.
- [ ] Review unsupported language/toolchain assumptions and missing dependencies;
  this initial live sample is Python only.
- [ ] Check the misleading-comment case for inappropriate instruction following or
  false execution claims; one example cannot establish broad security resistance.
- [ ] Approve the 360/390/412dp phone UI and try editing, scrolling, selection and
  keyboard behavior on a physical Android device.

No human approval, real-device approval, executed student tests or academic account
mutation is claimed by this evidence.
