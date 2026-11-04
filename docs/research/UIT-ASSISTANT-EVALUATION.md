# UIT Assistant verification record

Date: 2026-11-04. See [architecture and delivered scope](../UIT-ASSISTANT.md).
The initial public-announcement phase is implemented. A successful end-to-end model
run is now recorded using the new ignored `.env` credential. Semantic audience/
eligibility wording remains an open review gate.

## Evidence matrix

| Check | Result | What it establishes |
| --- | --- | --- |
| Isolated Koog 1.3.0 Android probe | Failed with Kotlin 2.3 metadata vs repo compiler 2.1 | Existing toolchain cannot consume the resolved artifacts; bounded native runtime selected |
| Assistant repository tests | 13 passed | Source/tool loop persistence, cancellation, deletion fences, interrupted recovery, read-only scope, local saves and invalid-output repair |
| Evidence adapter tests | 7 passed | Pinned scope, disabled source rejection, Vietnamese normalization, coverage, unprocessed media/truncation, versioning and storage failure |
| Responses SSE/parser tests | 6 passed | Incremental delivery before completion, UTF-8/CRLF/multi-line events, failure/truncation/refusal, cancellation, size limits and escaped partial JSON |
| Protocol/transport tests | 7 passed | Strict schemas, direct/gateway routing, no credential forwarding after connection change, quote integrity and refusal handling |
| Compose interaction tests | 9 passed | Explicit create/send/save/delete, evidence navigation, connection requirements and stop controls |
| Python assistant gateway tests | 5 passed | Fixed tools/instructions, bounded input, rejected roles/file inputs, continuation and no server-side UIT tool execution |
| Full Python gateway suite | 37 passed | Existing gateway behavior plus assistant contract regressions |
| Public UIT capture | Passed | Anonymous production Portal reads and workspace persistence, without model credentials |
| Actual model calls, `gpt-6-luna` | 9 final-round calls and 3 completed turns passed | Actual Responses tool loop, current-request citations and UNKNOWN behavior; semantic caveats remain |
| Native phone rendering | Three context width tests and one real-answer/evidence capture passed | Actual UIT context and recorded real model answers through production persistence and Compose |
| Android device/Doze execution | Not performed | Host renders do not establish physical-device acceptance |

The initial whole-repository `check`, debug APK assembly and instrumentation-test
compilation passed. The final PR records the last validation after all edits.
Deterministic tests do not require an API key, university credentials or a live UIT
server. The opt-in model test remains visible and skipped by default in CI.

## Real source provenance

The public capture used the production `PublicAnnouncementClient` and
`LocalAnnouncementRepository` against:

- https://portal.uit.edu.vn/bai-viet?category=thong-bao
- https://portal.uit.edu.vn/bai-viet?category=thong-bao&page=2

The saved metadata was replayed through the production workspace codec/repository
for Compose rendering under Robolectric API 35 native graphics. No student account
was logged in. Workspace names are local labels, not claims about university data.
The new answer/evidence captures replay the successful real provider run. No mock
answer is presented as a live result.

[Source capture record](../design/assistant/provenance.txt) ·
[Koog compiler observations](evidence/assistant-runtime-spike.json) ·
[Live model status](evidence/assistant-live-model-status.json).

## Current chatbot gallery

The conversation redesign and Direct-mode SSE run are recorded in the
[chat design and verification record](../design/assistant/CHAT.md). It includes
actual Luna answers at 360/390/412dp, larger text, light mode, a replayed real
streaming frame and grouped citations. The earlier captures below show the prior
layout and remain historical evidence.

## Earlier phone gallery

| State | Capture |
| --- | --- |
| Workspace list, compact 360dp | [Open](../design/assistant/assistant-workspaces-360.png) |
| Workspace list, 390dp | [Open](../design/assistant/assistant-workspaces-390.png) |
| Workspace list, 412dp / 1.3 text scale | [Open](../design/assistant/assistant-workspaces-412-large-text.png) |
| Empty chat and explicit connection requirement | [Open](../design/assistant/assistant-chat-empty-390.png) |
| Recorded real Luna answer | [Open](../design/assistant/assistant-real-model-answer-390.png) |
| Exact paragraph from real Luna citation | [Open](../design/assistant/assistant-real-model-evidence-390.png) |
| Exact public-source scope, 390dp | [Open](../design/assistant/assistant-context-390.png) |
| Source scope with larger text, 412dp | [Open](../design/assistant/assistant-context-412.png) |

The images were visually inspected. Source titles wrap in the single column;
context is scrollable, the composer stays below the chat and native bottom
navigation is retained. Dialog controls below the fold remain reachable by scroll.
These are host captures, not emulator/device screenshots.

## Live model retest with the new `.env` credential

`AssistantLiveTest` is prepared to run three questions over a real historical
Portal scholarship policy using the owner's selected `gpt-6-luna` model:

1. Extract the stated audience, requirements and historical dates.
2. Ask a follow-up that distinguishes official requirements from preparation advice.
3. Ask for a future registration time that the selected notice does not establish.

It requires completed turns, tool activity, resolvable quotes in the first two
answers, an UNKNOWN in the third and no source mark-read change. The request uses
a process environment key, not a command-line argument or committed credential.
The current `.bashrc` key was read locally for the authorized test; the provider
rejected access. A subsequent minimal diagnostic confirmed HTTP 401 with
`expired_secret_key`; see the [sanitized HTTP evidence](evidence/assistant-auth-diagnostic.json). No alternate endpoint or model was substituted.

The owner then provided a replacement key in the ignored local `.env`. That key
succeeded against the fixed Responses endpoint with `gpt-6-luna`. The final round
completed all three questions using nine actual provider requests (search, read,
answer per question). The provider reported 22,455 input tokens and 5,094 output
tokens across that round, with 46,844 ms total provider-call time. These are API
usage counters, not a billing estimate or a general latency benchmark.

[Actual answers, citations, evidence paragraphs, response IDs, timings and usage](evidence/assistant-luna-live.json).
The raw provider JSON and original live log remain in ignored build output and
`/tmp/assistant-dotenv-live.log`; no key or Authorization header is recorded.

Two earlier explicit diagnostic rounds used 17 additional requests: the first
completed three turns but exposed a 16-paragraph prefix that omitted later deadline
text; the second read more text but was rejected because the provider schema did
not enforce the client's citation-count limit. The extraction now keeps up to 64
paragraphs within the same 32,000-character budget, reports clipping explicitly,
and versions this behavior as `assistant-text-v2`. The final schema now enforces
at most twelve sections and four references per section. Regression tests cover
late deadline paragraphs and schema/validator cardinality parity. There were
26 actual provider requests across all three explicit rounds; no model substitution.

The final evidence includes all 51 extracted text paragraphs of the selected
notice, including the registration deadline after the old prefix. The first two
answers have verifiable source citations; the third explicitly returns UNKNOWN
for a 2030 registration time absent from the selected notice. No university
account was accessed, and reading through assistant tools did not mark the notice
read. Any unprocessed image content is still flagged.

Source comparison by Codex found the historical deadline was no longer described
as an upcoming deadline and preparation advice was separately labelled. However,
the first summary groups the BCU separate-program/non-required-registration clause
with non-registration cases; that wording needs closer audience/eligibility review.
Some sections also summarize multiple requirements under limited quotations.
Reference integrity is not proof of entailment, and this is not independent human
acceptance. Keep the PR draft for this semantic review rather than claiming broad
factual accuracy or personal eligibility advice.

The [AI provider evidence audit](AI-PROVIDER-AUDIT.md) preserves the earlier
expired `.bashrc` result and distinguishes it from this successful `.env` retest.

## Direct SSE verification after the chat redesign

A separate authorized live run completed three questions over the same actual
public notice through the new production SSE transport: **9 provider calls**, all
`gpt-6-luna` and completed, with **417 nonempty decoded draft updates** delivered to
repository progress. The provider reported **22,166 input tokens and 4,394 output
tokens** for this run. These nine calls are additional to the 26 earlier calls,
not a relabeling of buffered inference. [SSE evidence](evidence/assistant-sse-live.json)
includes response IDs, per-call usage, preview counts, first structured-text
callback times, sampled actual previews and retained public workspace data.

Draft samples are real captured structured-output prefixes; host previews replay
those samples rather than generating a typewriter effect or fabricating a model
response. Deterministic tests separately verify callback delivery before a
completion frame and exclusion of drafts from persisted answers. The new BCU
summary distinguishes non-required registration explicitly; broader semantic
accuracy remains an open review gate. Physical-device execution is still unverified.
The Python gateway remains buffered; these streaming results apply to Direct mode.

## Selected public Forum increment

The subsequent #51 connector has a separate [evaluation record](FORUM-AI-EVALUATION.md)
covering real public UIT text, actual `gpt-6-luna` SSE, hostile synthetic sources,
phone renders and the limits of exact-quote validation. This does not retroactively
change the initial announcement-only scope or evidence above.
