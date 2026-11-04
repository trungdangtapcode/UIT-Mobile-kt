# Source-linked quizzes and flashcards

Issue [#50](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues/50) adds
personal practice inside Courses. It uses the existing Study AI connection:
direct OpenAI on the phone, or the optional HTTPS gateway. Direct mode needs no
hosted backend. Generation is online; saved questions, source copies and recorded
answers work offline.

## Phone workflow

1. Open a course and choose **AI quiz & flashcards**. For existing material,
   choose **Courses → Saved practice sets**.
2. Inspect and explicitly check saved activity descriptions, verified downloaded
   text documents, Jupyter notebooks, or your own pasted excerpts. This screen does not download or
   refresh university material. The existing offline source loader supplies text
   formats, including basic PDF text extraction. Scanned PDFs still need external
   text/OCR; this workflow does not execute notebook cells.
3. Choose Multiple choice, Flashcards or Mixed, a count from 1–12, English or
   Vietnamese, and an optional focus topic. Mixed needs at least two questions.
4. Configure the shared AI connection if needed. In direct mode, enter your own
   OpenAI API key and supported model on the phone. Then explicitly choose
   **Send selection and generate**. Configuration, selection and local practice
   do not generate requests.
5. Review the draft's answers, explanations and source quotations. Edit questions,
   choices, correct answers, explanations or citations; reorder or remove questions.
   Save only after reviewing. An unsupported quotation or inconsistent answer
   prevents saving. Discarding requires confirmation.
6. Practice one question at a time. Multiple choice records an attempt only when
   **Check answer** is pressed. A flashcard reveals its answer without recording
   an attempt; **I remembered** or **Review again** explicitly records self-report.
7. The results separate multiple-choice correctness from flashcard self-report.
   Review missed questions, practice the whole set again, or return to the library.
   **Delete set** requires confirmation and removes only local questions, evidence
   copies and progress.

Preview text is not an official assessment, predicted examination, university
mark or measurement of mastery. Generated language follows the chosen output
language independently of the app's interface language.

## Data and account boundaries

`PracticeViewModel` only observes `PortalRepository.snapshot`, the saved local
snapshot. It never calls login, refresh, enrollment, submission, profile update,
payment or other university transport operations. Opening the feature does not
contact UIT. Local source reads use the existing verified offline file loader.

The AI request includes only checked source IDs/titles/text, count, question kind,
language, topic and fixed instructions. Account identifiers, course identifiers,
profile fields, university credentials/cookies and original file bytes are not
implicitly included. A student's deliberately selected text can itself contain
personal information; inspect it before sending it to the configured processor.
Authentication travels in the provider Authorization header, outside the prompt.

Direct mode posts to the existing fixed `https://api.openai.com/v1/responses`
endpoint with `store: false`, strict JSON schema and no tools. Optional gateway
mode uses the same versioned practice contract and the existing HTTPS endpoint,
authentication, request limits and redirect restrictions. Neither path retries
automatically or falls back to another model. Cancellation stops accepting late
results; provider processing or billing may already have happened.

The initial test suite and gallery used synthetic data. A later owner-authorized
integration check read actual course pages and downloaded teaching notebooks,
then sent selected educational excerpts to the configured OpenAI model. It did
not invoke submission, grading, profile/account update or activity-completion
endpoints. Authentication and normal access logs can still change server session
and access records; “read-only” is not a promise of zero server-side writes.
No university password or cookie was sent to AI or committed. Only aggregate
real-data results are public. See [evaluation evidence](AI-PRACTICE-EVALUATION.md).

## Jupyter notebook sources

Verified `.ipynb` files use a notebook v4 source reader rather than sending their
raw JSON. It accepts the [official string or string-array cell source forms](https://nbformat.readthedocs.io/en/latest/format_description.html),
keeps Markdown and code in cell order, and preserves code indentation, newlines,
Unicode and LaTeX. It reads no execution outputs, authors/kernel metadata, image
attachments or raw cells and never starts a kernel, installs packages or executes
code. Invalid UTF-8, malformed/unsupported notebooks and empty usable content
produce no source instead of falling back to raw JSON.

The existing 8 MiB file and 12,000-character source limits remain; notebook input
also has a 10,000-cell ceiling. The file picker supplies a bounded **prefix**, not
an automatic full-notebook index or a cell-range picker. Inspect that text before
sending. To study later cells, explicitly paste the desired excerpt. A cap can
cut a cell midway, so a source preview is necessary for meaningful context.

Quotation validation stays literal after whitespace normalization. Both direct
and gateway instructions require copied Markdown emphasis, backticks, LaTeX and
punctuation, in the source's language even when the answer uses Vietnamese.
Formatting-stripped or translated quotes are rejected rather than treated as
verified evidence. Model output remains structured JSON; Markdown may appear
inside its text fields. The current phone UI displays literal source/answer text,
not rendered Markdown, syntax-highlighted code or typeset LaTeX. Preserving source
markup for evidence is separate from adding a rich-text renderer.

## Contract and mechanical validation

The direct provider and gateway share these logical fields:

```json
{
  "version": 1,
  "mode": "practice",
  "question": "",
  "language": "English",
  "practice": { "kind": "mixed", "count": 2, "topic": "Transactions" },
  "sources": [{ "id": "excerpt:example", "title": "Lesson", "text": "Selected text..." }]
}
```

Results have `status`, `message` and `questions`. Ready results must match the
requested count and kind; Mixed must include both kinds. An explicit
`insufficient_evidence` result has an explanation and no questions. Each question
includes a prompt, choices, correctIndex, answer, explanation and 1–3 citations.
Local question IDs are assigned by the client rather than accepted from the model.

Validation enforces:

- 1–5 sources, at most 12,000 characters per source and 36,000 in total; topic
  length at most 200. Bounds are checked before network transport.
- Exactly four nonblank, distinct choices for multiple choice, index 0–3 and an
  answer equal to the indexed choice after whitespace normalization. Flashcards
  use an empty choice list and index −1.
- Unique question prompts after case/whitespace normalization, bounded fields,
  known source IDs, and a 6–500-character supporting quote contained in selected
  text after whitespace normalization.
- Completed provider output, the expected schema and valid metadata. Refusals,
  incomplete responses, invalid evidence and malformed results produce no draft.

These checks cannot establish factual truth, semantic uniqueness, plausible
distractors or whether a quote actually supports the answer. Instructions ask
the model for those properties; students must inspect the preview. Human quality
evaluation remains a separate gate, not a consequence of schema validation.

Implementation: `Practice.kt`, `PracticeProtocol.kt`, `PracticeJson.kt`,
`ConnectedStudyAiRepository`, and `server/practice_contract.py`. A shared synthetic
wire fixture and schema parity test protect Android/gateway compatibility.
The Responses format follows the official
[structured output guide](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses).

## Source versions and local progress

Each saved set owns an immutable copy of its selected source text. SHA-256
fingerprints use UTF-8 length-prefixed fields. Source version includes sorted
source ID, title and text. Question version includes its content and citations;
set version includes owner/course, title, language, source version and ordered
question versions. IDs and generation timestamps are not used for content
deduplication. There is no claim that a hash proves semantic correctness.

Source loading rechecks the cached metadata after asynchronous reads. If it changed
mid-load, the selection is rejected and must be explicitly reloaded. Reloading a
course-scoped saved library refreshes local records without opening generation.

While a course is open, loaded cache changes invalidate an unsaved draft and
require source reload before generation. Saved sets retain their historical
evidence and progress. The library distinguishes matching, changed, missing and
unchecked loaded text. A match is **not a live university freshness check**.
Pasted excerpts may be unavailable in the current catalog after reopening; their
saved evidence copy remains usable.

Saved sets cannot be silently overwritten by regeneration. Identical approved
content is deduplicated. Each explicit answer has an attempt ID, question version,
attempt/success counters, timestamp, last outcome and last multiple-choice index.
Retries reuse the same ID and do not double-count a committed attempt. Concurrent
changes use expected-record/progress checks; stale operations fail instead of
overwriting newer data. Question correctness is deterministic against the saved
answer, while flashcard success is explicitly self-reported.

`LocalPracticeRepository` uses separate `StudyPrivateStorage` preferences named
`practice` and Android Keystore alias `uit.practice.v1`. Data is partitioned by
owner with a maximum of 20 sets per account and 2,000,000 serialized characters.
Corrupt data is retained and reported per account rather than replaced with empty
data. Account removal clears that owner's practice bucket and in-memory drafts;
other owners remain. Late writes recheck ownership and remove their bucket if
the owner disappeared.

Saved sets and committed answers survive process recreation. Unsaved generation,
edits, source selection and the current session cursor are in memory; they are
not restored after process death. Failed saves retain the draft or revealed
answer while the screen remains open. Explicit retry is available; Next stays
disabled until that answer is recorded.

## Verification and remaining limits

See [the evaluation record](AI-PRACTICE-EVALUATION.md) for reproducible commands,
full phone-flow screenshots, synthetic live results and the human review gate.
This implementation adds no background generation, spaced repetition, official
assessment submission, automatic grading service or university account mutation.
