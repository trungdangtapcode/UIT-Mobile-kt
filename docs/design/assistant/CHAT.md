# UIT Assistant phone conversation

The student asks short follow-up questions about official notices on a phone.
The conversation is the main reading surface; source inspection supports it.

## Design decisions

- Domain: official notices, public-source scope, scholarship requirements, exact
  deadlines, follow-up questions, retained evidence and personal preparation.
- Color world: UIT emblem blue, navy ink, university document white, muted campus
  gray and amber for uncertain/unverified text. Reuse Material semantic tokens;
  wallpaper-derived themes continue to work.
- Signature: asymmetric 8dp/28dp cut-corner user messages, the authentic transparent
  UIT emblem beside assistant identity, and paragraph-linked notice evidence.
- Hierarchy: right-aligned user message at 88% column width, left-aligned assistant
  prose, compact role/model identity and a fixed bottom composer. Body text uses
  the existing IBM Plex Sans bodyLarge style; secondary metadata uses labelSmall.
- Depth and spacing: the existing low-elevation canvas, no shadows, 4dp spacing
  grid, 16dp phone gutters and user-message padding. The source selector occupies
  one 48dp row; native touch targets remain. Send/Stop uses a 56dp technical shape.
- Source navigation: numbered references in prose map to one collapsed Sources
  control. Expansion groups the notice title/date once, then exposes numbered
  paragraph actions. Copy and reviewed local checklist actions sit alongside.
- Interaction: a newly submitted question becomes the scroll anchor. Individual
  answer paragraphs are lazy items. Streamed tokens do not force-scroll someone
  reading an earlier message; Latest message provides an explicit jump. The user
  can compose the next message during generation, with Stop replacing Send.
- States: empty conversation, disconnected configuration, source loading, writing,
  citation validation, completed answer, stopped/interrupted/failed request and
  storage errors use localized text and native controls. Drafts have no source,
  copy or checklist actions. Specific access/quota errors retain their diagnosis.

## Actual-source gallery

These images use real public UIT text and recorded `gpt-6-luna` responses from the
[SSE evaluation](../../research/evidence/assistant-sse-live.json), replayed through
production persistence and native Compose under Robolectric API 35. They are host
renders, not physical-device captures. The replay excludes credentials, so the
configuration action remains visible while historical answers are readable.
No authored answer is substituted for successful inference.

| State | Capture |
| --- | --- |
| First answer, 360dp | [Open](chat/assistant-chat-answer-360.png) |
| First answer, 390dp dark | [Open](chat/assistant-chat-answer-390.png) |
| First answer, 390dp light | [Open](chat/assistant-chat-answer-390-light.png) |
| First answer, 412dp / 1.3 text scale | [Open](chat/assistant-chat-answer-412-large-text.png) |
| Actual follow-up, 360dp | [Open](chat/assistant-chat-followup-360.png) |
| Actual follow-up, 390dp | [Open](chat/assistant-chat-followup-390.png) |
| Actual follow-up, 390dp light | [Open](chat/assistant-chat-followup-390-light.png) |
| Actual follow-up, 412dp / 1.3 text scale | [Open](chat/assistant-chat-followup-412-large-text.png) |
| Recorded actual streaming frame | [Open](chat/assistant-chat-streaming-390.png) |
| Expanded grouped citations | [Open](chat/assistant-chat-sources-390.png) |
| Exact source paragraph | [Open](chat/assistant-chat-evidence-390.png) |

The streaming image replays the first sampled actual answer prefix at least 200
characters long, with its real question, into the running state. It demonstrates
presentation of recorded streaming content, not a live connection at capture time.
The separate real transport test established incremental callbacks; deterministic
channel tests established callback delivery before completion.

Images were inspected for clipping, wrapping, control reachability, source access
and light/dark contrast. Long messages scroll; the composer stays reachable.
Source matching validates quote integrity, not semantic entailment or personal
eligibility. Independent answer-quality and physical-device acceptance remain open.

## Verification

- 42 focused Kotlin/Compose tests: 13 repository, 7 evidence, 7 protocol, 6 SSE/parser
  and 9 interaction tests. Fixtures are behavior-only and do not publish captures.
- 8 opt-in actual-data render tests cover the workspace/context views and new
  conversation gallery. Renders contain actual source/model captures.
- Separate live production SSE run: 3 completed questions, 9 actual provider calls,
  417 nonempty draft updates, 22,166 input and 4,394 output tokens.
- Full Gradle check, debug APK assembly and instrumentation-test compilation; the
  optional gateway's 37 Python tests also run under check. See the evaluation
  record for the final result and the distinction between host/device verification.

Direct mode supports SSE. The optional Python gateway currently returns a buffered
answer. See [architecture and streaming boundaries](../../UIT-ASSISTANT.md#chat-presentation-and-sse-transport).
