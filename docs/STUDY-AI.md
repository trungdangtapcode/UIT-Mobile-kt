# Study AI: assignment analysis and selected-text Q&A

This branch adds two native Android workflows inside **Courses**:

- **Assignments → Analyze assignment & checklist:** inspect the saved brief, send
  selected text to the configured gateway, review stated requirements separately
  from suggestions, and explicitly add/edit/reorder/save a personal checklist.
- **Ask course text:** select saved assignment/activity descriptions or add a pasted
  excerpt, ask a question, and inspect the original text behind each citation.

The first release is text-only. It does not parse downloaded PDF files, OCR profile
scans or retrieve material from a shared vector index. An empty description is not
treated as an empty document. Long source text stays visible for inspection but
must be shortened into an explicit excerpt before sending. Select at most five
sources, 12,000 characters each and 36,000 characters in total.

## Configure the development gateway

The included Python 3 standard-library gateway uses OpenAI's Responses API with a
strict JSON schema. Provider calls use the fixed official HTTPS origin and do not
follow redirects. No provider key is compiled into Android.

Run from a terminal where the key is already exported. `export` affects that shell
and its child processes; exporting in another terminal does not change a running
gateway or Codex process.

```sh
# Export OPENAI_API_KEY in this terminal without putting it in Git or shell history.
read -rsp 'OpenAI API key: ' OPENAI_API_KEY
export OPENAI_API_KEY

# Choose a model available to this API project that supports Responses and
# strict structured outputs. There is deliberately no hardcoded model default.
export OPENAI_MODEL='<available-model-id>'

# This separate token authenticates one owner's app to this gateway.
export UIT_AI_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
export UIT_AI_PORT=8765
export UIT_AI_HOURLY_LIMIT=30
python3 server/ai_gateway.py
```

The gateway binds **127.0.0.1**. Put a TLS reverse proxy with a certificate trusted
by Android in front of it and forward `/v1/study` to this loopback port. The phone
must be able to reach the HTTPS origin. This rootless development service does not
configure DNS, certificates or a public listener automatically. Plain HTTP and TLS
verification bypasses are not enabled in the app.

Inside either study screen, open **Study gateway settings** and enter that HTTPS
origin and **UIT_AI_TOKEN**, not OPENAI_API_KEY. The connection and saved checklists
are encrypted with Android Keystore AES-GCM, and app backups remain disabled.
**Clear connection** removes the gateway configuration without deleting personal
checklists. Removing a course account deletes that account's stored checklists.

This is a **single-owner development gateway**, not a multi-user university backend.
Give each owner an isolated instance/token; do not share an owner's token as team
authentication. A shared production service still needs its own user identity,
authorization, deployment controls and durable usage accounting. No university
credential is forwarded to the gateway or used as its caller identity.

## Data and result handling

Only checked source titles/text, the question, action and requested language are
sent after **Send selected text**. There is no background AI processing. The app
does not send snapshots, profile scans, submission files, university cookies,
passwords, sesskeys or provider keys. Pasted excerpts stay in screen memory until
sent; the student controls their contents.

The gateway holds source text for the request and has no content store or raw
request logging. It requests `store: false` from OpenAI; this is not a claim of zero
provider retention. OpenAI processing follows the API account's applicable data
controls. Source text and generated answers are not saved by this implementation.
Opening another course, closing the study flow or removing its account clears the
in-memory study state. App process death clears unsaved excerpts and answers.

Every stated requirement or answer must cite a supplied source and an exact short
quote. Both gateway and app reject unknown source IDs and quotes absent from the
selected text, allowing whitespace normalization. Suggestions and unknown facts
are separately labeled. These checks establish reference validity, not whether a
claim is logically supported; the source viewer lets students inspect that gap.

Official Moodle due/cutoff values and submission state are never changed by AI.
Personal tasks are editable drafts until explicitly saved locally. Saving checked
tasks does not submit work. Regeneration preserves the checklist and adding the
same text again does not duplicate it. Leaving dirty edits asks whether to keep
editing or discard them. Canceled/obsolete generation cannot populate another
course/account's screen. Once a request reaches the provider, canceling the phone
request cannot guarantee that provider processing or billing stops.

## Gateway contract and limits

`POST /v1/study`, JSON body, `Authorization: Bearer <UIT_AI_TOKEN>`:

```json
{
  "version": 1,
  "mode": "ask",
  "question": "What does a transaction preserve?",
  "language": "English",
  "sources": [
    {"id": "module:1", "title": "Synthetic lesson", "text": "A transaction preserves consistency."}
  ]
}
```

`analyze` and `ask` share `items` with a kind, text and source/quote citations.
The response also identifies the configured model and generation time. A model
refusal, incomplete output or invalid evidence becomes an explicit error rather
than a fabricated successful result. No model tools, arbitrary URL fetching, code
execution or university writes are provided.

The service caps body size, selected text, output size, two simultaneous provider
calls and an in-memory hourly call count. A restart resets that count; it is not a
durable billing limit. Set project spend controls separately. Configure reverse
proxy connection/request limits for any reachable deployment. There are no retries
that silently repeat a potentially billable provider call.

## OpenAI authentication

The included adapter reads **OPENAI_API_KEY** from its server process. Existing
Codex credentials are not read or copied. OpenAI documents an alternative
[Sign in with ChatGPT flow for open-source clients](https://developers.openai.com/siwc/quickstart),
which requires the application's own registration, scopes and user authorization.
That OAuth integration is a separate follow-up and is not implemented here.

## Verification and limits

```sh
python3 -m unittest discover -s server -v
JAVA_HOME=/path/to/jdk21 ./gradlew :app:assembleDebug check :app:testDebugUnitTest
JAVA_HOME=/path/to/jdk21 ./gradlew :app:testDebugUnitTest \
  --tests '*StudyDesignTest*' -PrecordUi=true
```

Tests use synthetic content and provider fixtures. They check the real loopback
HTTP contract, OpenAI request shape/refusals, source and response limits, quote
validation, ownership/lifecycle behavior, explicit sends/saves and phone UI.
Actual OpenAI answer quality, account/model access, latency/billing and phone-to-
gateway HTTPS deployment require a separately configured live run. No private
student content or API key is put in test fixtures or CI.

This text-only implementation addresses the first milestones of [Assignment
Breakdown #48](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues/48) and
[Course Q&A #49](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues/49).
PDF ingestion/indexing, multi-user gateway deployment and a representative live
quality benchmark remain in the broader foundation issues; they are not marked
complete by this branch.

References: [Responses API](https://developers.openai.com/api/docs/guides/migrate-to-responses),
[structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs),
[API data controls](https://developers.openai.com/api/docs/guides/your-data).
