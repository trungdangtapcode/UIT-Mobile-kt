# UIT Assistant verification record

Date: 2026-11-04. See [architecture and delivered scope](../UIT-ASSISTANT.md).
The initial public-announcement phase is implemented. A successful end-to-end model
run and semantic answer-quality review remain open release gates.

## Evidence matrix

| Check | Result | What it establishes |
| --- | --- | --- |
| Isolated Koog 1.3.0 Android probe | Failed with Kotlin 2.3 metadata vs repo compiler 2.1 | Existing toolchain cannot consume the resolved artifacts; bounded native runtime selected |
| Assistant repository tests | 12 passed | Source/tool loop persistence, cancellation, deletion fences, interrupted recovery, read-only scope, local saves and invalid-output repair |
| Evidence adapter tests | 6 passed | Pinned scope, disabled source rejection, Vietnamese normalization, coverage, unprocessed media/truncation, versioning and storage failure |
| Protocol/transport tests | 6 passed | Strict schemas, direct/gateway routing, no credential forwarding after connection change, quote integrity and refusal handling |
| Compose interaction tests | 7 passed | Explicit create/send/save/delete, evidence navigation, connection requirements and stop controls |
| Python assistant gateway tests | 5 passed | Fixed tools/instructions, bounded input, rejected roles/file inputs, continuation and no server-side UIT tool execution |
| Full Python gateway suite | 37 passed | Existing gateway behavior plus assistant contract regressions |
| Public UIT capture | Passed | Anonymous production Portal reads and workspace persistence, without model credentials |
| Actual model call, `gpt-6-luna` | Provider rejected credential/project access | No completed live model answer or quality claim is available |
| Native phone context rendering | Three width tests passed; model-answer capture skipped | Actual downloaded UIT titles and source context at 360/390/412dp, including 1.3 font scale |
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
No mock or authored model answer is included in the published gallery.

[Source capture record](../design/assistant/provenance.txt) ·
[Koog compiler observations](evidence/assistant-runtime-spike.json) ·
[Live model status](evidence/assistant-live-model-status.json).

## Phone gallery

| State | Capture |
| --- | --- |
| Workspace list, compact 360dp | [Open](../design/assistant/assistant-workspaces-360.png) |
| Workspace list, 390dp | [Open](../design/assistant/assistant-workspaces-390.png) |
| Workspace list, 412dp / 1.3 text scale | [Open](../design/assistant/assistant-workspaces-412-large-text.png) |
| Empty chat and explicit connection requirement | [Open](../design/assistant/assistant-chat-empty-390.png) |
| Exact public-source scope, 390dp | [Open](../design/assistant/assistant-context-390.png) |
| Source scope with larger text, 412dp | [Open](../design/assistant/assistant-context-412.png) |

The images were visually inspected. Source titles wrap in the single column;
context is scrollable, the composer stays below the chat and native bottom
navigation is retained. Dialog controls below the fold remain reachable by scroll.
These are host captures, not emulator/device screenshots.

## Model evaluation prepared, not claimed complete

`AssistantLiveTest` is prepared to run three questions over a real historical
Portal scholarship policy using the owner's selected `gpt-6-luna` model:

1. Extract the stated audience, requirements and historical dates.
2. Ask a follow-up that distinguishes official requirements from preparation advice.
3. Ask for a future registration time that the selected notice does not establish.

It requires completed turns, tool activity, resolvable quotes in the first two
answers, an UNKNOWN in the third and no source mark-read change. The request uses
a process environment key, not a command-line argument or committed credential.
The current `.bashrc` key was read locally for the authorized test; the provider
rejected access. No alternate endpoint or model was substituted.

After access is restored, run the opt-in model test, inspect the answers manually
for meaning/audience/date correctness, then capture the actual successful answer
and source sheet. Until then, no latency, token-cost, retrieval recall, factual
accuracy or semantic-support score is asserted. Passing mock protocol tests is
not a substitute for these measurements.

Keep the PR draft while this live-model gate remains unresolved. Do not close the
broader #46/#47/#55 work or merge merely because the deterministic checks pass.
