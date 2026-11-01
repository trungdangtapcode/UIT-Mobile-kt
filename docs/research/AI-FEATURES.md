# AI feature implementation research

Research date: October 29, 2026.

Publication note: this preserves the research and proposed architecture from that
date. Assignment analysis and selected-text Q&A have since shipped with direct
OpenAI access and an optional gateway; see [Study AI](../STUDY-AI.md) for current
behavior. The backend recommendations below are historical proposals, not a
requirement to replace the implemented personal-key client mode.

Scope: eight proposed student-facing capabilities and three shared engineering
work items. This research used public documentation and repository inspection.
No provider account was configured, no paid API was called, and no student content,
credentials or university requests were sent to a model. Provider/model selection
and quality measurements remain implementation work.

## Repository fit

`Assignment` already carries an introduction, due/cutoff/open times and files.
Course modules carry descriptions and resources; downloaded files have verified
checksums. These are useful inputs for assignment analysis and source versioning.
The domain interfaces expose saved academic/content data and verified downloads,
but do not currently expose an AI service, PDF text extraction or vector retrieval.

Existing roadmap features remain independent: personal assignment subtasks (#7),
keyword search (#3), revision planning (#12), the Forum reader (#39), announcement
aggregation (#41) and PDF reading (#32). AI extends these workflows instead of
replacing their deterministic behavior or requiring AI for ordinary app use.

## Evidence and implications

| Finding from primary sources | Implementation implication |
| --- | --- |
| Gemini documents PDF vision processing and extraction; non-PDF document handling differs [S1]. | Test text PDFs, scanned pages, tables, diagrams and code separately. Keep unsupported formats explicit. No blanket claim of accurate OCR. |
| Structured generation can follow a JSON Schema [S2]. | Use a versioned output contract, then validate facts, source references and dates separately. Valid JSON is not evidence of correctness. |
| Embeddings and managed file search support retrieval workflows [S3, S4]. | Benchmark retrieval with bilingual course material and retain authoritative ownership filters and source locators. A managed store does not remove lifecycle/access-control work. |
| Gemini key guidance recommends keeping production keys off mobile clients and using a backend [S5]. | Use an authenticated app backend/provider adapter; never ship a shared provider key in the APK. |
| ML Kit GenAI support depends on AICore and supported devices [S6]. | Do not make Gemini Nano a requirement for this Android app. On-device capability can be an optional later adapter. |
| ML Kit lists Vietnamese among supported Latin-script OCR languages [S7]. | OCR is a candidate fallback; evaluate accents, mathematical notation and code on representative pages before claiming support. |
| Provider service terms distinguish data handling by service configuration [S8]. | Select and document the actual provider, account tier, retention and deletion behavior. Do not assume a free endpoint is suitable for private course content or claim zero retention without evidence. |
| OWASP describes indirect prompt injection through external content [S9]. | Treat PDFs and Forum posts as untrusted data. Models do not receive university credentials or access university write tools. |
| Google's study-tool examples include source-linked explanation and generated practice material [S10]. | Useful product references for question answering and quizzes; not proof of effectiveness or accuracy for UIT material. |

## Product backlog

| Roadmap ID | Capability | First useful delivery | Dependencies |
| --- | --- | --- | --- |
| 43 | Assignment Breakdown | Extract deliverables, stated constraints and rubric items with evidence; present editable suggested subtasks before saving locally. | Gateway 51, sources 52, evaluation 53; coordinate personal tasks #7. |
| 44 | Course Q&A | Answer within selected course sources, show clickable evidence and acknowledge unsupported questions. | 51–53; reuse document navigation #32 where available. |
| 45 | Quiz and Flashcards | Generate an editable source-linked practice set; explain answers and save the approved set offline. | 51–53; coordinate Course Q&A evidence. |
| 46 | Forum Digest | Summarize an explicit topic/post range with attribution, chronology and links to evidence. | 51–53 and Forum reader #39. |
| 47 | Semantic Course Search | Retrieve relevant passages from an authorized source set and open their exact location. | Sources 52, evaluation 53, keyword search #3; gateway 51 if remote embeddings are used. |
| 48 | Revision Coach | Draft a topic/session plan, then validate time slots with deterministic scheduling and save only after edits/confirmation. | 51–53 and revision planning #12/free-time rules #11. |
| 49 | Code Tutor | Explain a student-selected snippet with incremental hints and suggested test cases, preserving code and line references. | 51, 53; sources 52 for optional course evidence. |
| 50 | Announcement Assistant | Extract stated audience, dates, documents and next steps from selected official notices, with source/version evidence. | 51–53 and announcements #41. |

## Shared work that should have one owner each

### 51 — AI gateway, provider configuration and data controls

Define a provider-neutral request/result contract and an authenticated backend
adapter. Backend ownership checks must not trust client-supplied account IDs.
University passwords, cookies, sesskeys and tokens stay in the existing university
transport/vault. Provider credentials stay server-side. The backend must not fetch
arbitrary supplied URLs with university credentials.

Let students select the actual text/files sent for a task and see the destination
and relevant retention behavior. Scope permission to the selected sources; never
silently send a complete snapshot, private messages or profile scans. Persist only
necessary results under the correct account, with deletion behavior for server and
provider copies. Log operational metadata without raw educational/private content.

Define input/output limits, timeouts, cancellation, request deduplication, rate and
spend controls and a feature kill switch. Report actual usage/latency during the
pilot. No provider, model identifier, pricing promise or production deployment is
selected by this research.

### 52 — Selected-source ingestion, retrieval and citations

Start with assignment HTML/text and selected verified PDF downloads. Preserve
document versions, page locators or HTML paragraph anchors and source identity.
An example citation shape is `sourceId`, `version`, `locator`, `quote` and optional
`page` or `postId`; these are proposed fields, not an implemented schema.

Apply server-side ownership/course constraints before retrieval. Version the
extractor and embedding/index configuration; invalidate old embeddings and derived
answers when a source changes or is deleted. Keep English/Vietnamese text, code,
tables and extraction failures distinguishable. Raw authenticated download URLs
must not become model-visible credentials or provider fetch instructions.

Resolve generated references against supplied source records, not arbitrary model
URLs. Validate that an excerpt exists at the cited location and let users inspect
it. Citation existence and whether it supports the claim need separate evaluation.
Treat text embedded in documents/posts as data, never as tool authorization.

### 53 — Evaluation fixtures and release gates

Use synthetic or appropriately licensed public fixtures, not captured private
student records. Include Vietnamese/English text, code and diagram-heavy slides,
ambiguous dates, absent facts, conflicting document versions, scanned pages,
injected instructions and separate-account corpora.

For extraction, measure field correctness, omitted facts and unsupported additions.
For retrieval, measure relevance/recall on labeled questions. For generated answers,
check evidence support, citation resolution and willingness to report missing
evidence. For quizzes, review answer correctness and ambiguity. For revision plans,
validate constraints in ordinary code. For all features, exercise source deletion,
cross-account attempts, cancellation, provider failure and request/cost limits.

Record model, prompt, extractor and index versions plus latency/usage. Set explicit
acceptance thresholds on a reviewed benchmark before release; no measured accuracy
or cost is claimed here. Keep normal CI deterministic; paid/live model evaluations
must be opt-in and must not receive university credentials.

## Delivery recommendation

1. Agree on gateway and source-reference contracts (51–52), and establish the
   synthetic benchmark/evaluation path (53).
2. Ship Assignment Breakdown (43): start with existing assignment text, preserve
   official LMS dates, and keep generated work as an editable local draft.
3. Add selected-source Course Q&A (44) and Semantic Search (47), then practice
   generation (45), reusing the same evidence and deletion infrastructure.
4. Add Forum Digest (46) after the reader handles long topic streams, and
   Announcement Assistant (50) after canonical source/version ingestion exists.
5. Add Revision Coach (48) and Code Tutor (49) as separately evaluated workflows.

Schema correctness, fluent language and citations are separate from factual
correctness. Model suggestions never establish official submission, grade,
scholarship eligibility or university policy decisions. None of these tasks require
automatic portal writes, message sending or code execution.

All implementation work follows feature branches and PRs. Main requires the two
existing CI checks; human approval is optional under the user's current settings.
Native UI stays phone-focused and follows `AGENTS.md`. Repository artifacts and
issues stay English; localized UI may use Vietnamese.

## Primary sources

- **S1:** [Gemini document understanding](https://ai.google.dev/gemini-api/docs/document-processing)
- **S2:** [Gemini structured outputs](https://ai.google.dev/gemini-api/docs/structured-output)
- **S3:** [Gemini embeddings](https://ai.google.dev/gemini-api/docs/embeddings)
- **S4:** [Gemini file search](https://ai.google.dev/gemini-api/docs/file-search)
- **S5:** [Gemini API key security](https://ai.google.dev/gemini-api/docs/api-key)
- **S6:** [ML Kit GenAI device support](https://developers.google.com/ml-kit/genai)
- **S7:** [ML Kit OCR language support](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages)
- **S8:** [Gemini service data-handling terms](https://ai.google.dev/gemini-api/terms)
- **S9:** [OWASP: prompt injection](https://genai.owasp.org/llmrisk/llm01-prompt-injection/)
- **S10:** [Google study-tool examples](https://blog.google/innovation-and-ai/models-and-research/google-labs/notebooklm-student-features/)

These references establish capabilities and design constraints, not a provider
endorsement, an SDK version pin or a benchmark of this app. Recheck current service
behavior when implementing an adapter.
