# AI provider evidence audit

Audit date: 2026-11-04. This audit answers whether a failing current key means the
older AI features were never tested with a real model. It separates transport,
source provenance, UI fixtures and answer-quality acceptance.

## Earlier `.bashrc` credential: exact observed failure

The key assignment exists at line 151 of the owner's `.bashrc`. The API process
initially did not inherit the environment variable; the live test launcher read
the literal export locally and supplied it to the test process. That earlier
failure is therefore not explained by an absent key.

A separate minimal request was sent to `https://api.openai.com/v1/responses` with
`model=gpt-6-luna`, `store=false` and no university data. The response was:

```text
HTTP 401
error.code: expired_secret_key
error.message: Your API key has expired. Create a new API key to continue.
request ID: req_131ecbd106dd44d5b7f6de4f29d549b3
```

[Sanitized diagnostic](evidence/assistant-auth-diagnostic.json). This request ID
belongs to the new minimal diagnostic, not the earlier integration-test request,
whose raw HTTP status/request ID had not been retained. No credential is included.
The owner subsequently supplied a new ignored `.env` credential. That retest
completed three real Luna turns with nine final-round requests; see the
[actual live evidence](evidence/assistant-luna-live.json). PR #82 remains draft for
semantic review, rather than a provider-access failure.

## Historical feature evidence

| Feature | Provider evidence found | Source input | Screenshot provenance / remaining limit |
| --- | --- | --- | --- |
| Quiz and flashcards (#50) | Three recorded live `gpt-6-luna` smoke checks, plus nine recorded real-course diagnostic/fix requests; live harness uses OkHttp and production repository, not MockEngine | Authored teaching examples and selected real Moodle notebook excerpts | Original UI gallery uses synthetic accounts and model-output fixtures; a separate follow-up records real-source replay. Semantic quality review remains pending. |
| Code Tutor (#54) | Three archived completed raw provider responses with response IDs, token usage and `model=gpt-6-luna`, dated 2026-11-02; aggregate evidence separately records three final production calls after the capture round | Two authored code examples and one selected real notebook cell | Published UI gallery uses authored fixtures. Offline replay of captured responses uses MockEngine and is not a new provider call. |
| Knowledge Maps (#76) | Public evaluation files record initial and corrected live `gpt-6-luna` rounds; no original HTTP request logs were located in this audit | Authored graph-traversal text | UI gallery uses deterministic fixtures. Real API usage here does not establish real UIT-course coverage or semantic accuracy. |
| Initial Assignment Breakdown / Course Q&A (#48/#49) | No successful live-provider artifact located for that initial delivery | Deterministic source fixtures in the documented checks | Existing documentation says provider access/quality needs a separate live run; do not report those checks as successful real inference. |
| UIT Assistant (#82) | Earlier `.bashrc` key expired; new `.env` key completed 9 final-round Luna calls / 3 turns | Actual public UIT scholarship notice, including 51 text paragraphs | Gallery now includes recorded real model answers and exact source inspection; semantic caveats remain documented. |

Supporting records:

- [Practice provider and real-course evaluation](../AI-PRACTICE-EVALUATION.md)
- [Recorded practice smoke checks](../evaluations/ai-practice/gpt-6-luna-synthetic.json)
- [Real-course practice summary](../evaluations/ai-practice/gpt-6-luna-real-summary.json)
- [Code Tutor evaluation](../CODE-TUTOR-EVALUATION.md)
- [Code Tutor aggregate](../evaluations/code-tutor/gpt-6-luna-summary.json)
- [Knowledge Maps live evidence](../KNOWLEDGE-MAPS.md#verification-and-live-model-evidence)
- [Sanitized provider metadata and harness audit](evidence/ai-provider-audit.json)

The retained Code Tutor response bodies remain outside Git because one contains
real course material. The public audit extracts only response metadata. The live
harnesses were inspected for their OkHttp transport, production repository and
HTTPS endpoint guard. A separate replay harness deliberately uses MockEngine.

## What can and cannot be concluded

A synthetic source can be sent to a real model. That proves a live provider path,
not real-course ingestion. A synthetic model response in a deterministic UI test
proves UI behavior, not provider inference. Reading real UIT data proves source
access, not a successful AI call. Exact quote validation proves a reference exists,
not that an explanation is correct.

The blanket claim that every earlier AI feature used only fake provider data is
not supported by the retained evidence. The blanket claim that all AI features
have passed real end-to-end model and semantic-quality evaluation is also not
supported. Reports must state these dimensions independently.
