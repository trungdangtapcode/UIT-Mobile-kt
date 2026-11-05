# Course passage retrieval: research and decision

Date: 2026-11-05. Issue [#52](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues/52).
The requested outcome is finding inspectable passages in selected course material.
Retrieval is useful without generating an answer; RAG does not require embeddings.
This increment implements retrieval, not a new generative assistant tool. It
extends the [November 2 proposal](SEMANTIC-COURSE-SEARCH.md); its PDF page-aware,
local-model and broader evaluation gates remain open under #52.

## Repository baseline and alternatives

The existing workspace search matches normalized query words against cached titles
and metadata, then sorts by kind/title. It neither reads file bodies nor ranks
passages. Its behavior and routes remain intact. Existing course AI source loaders
cap text at 12,000 characters and lose detailed source positions; they are not a
suitable complete retrieval index.

| Approach | Benefit | Cost or limitation | Decision |
| --- | --- | --- | --- |
| Metadata keyword search | Existing offline navigation; no file processing | Cannot find most body-only information | Preserve as the general search entry |
| All-query-token passage baseline | Simple reproducible comparison | Requires every token; no term rarity, saturation or length model | Benchmark baseline, not a semantic method |
| BM25 | Local, inspectable ranking; useful for terms, identifiers and codes | No inherent cross-language/paraphrase understanding; callers can outrank definitions | Default file-body mode |
| FTS5 BM25 | Mature disk-backed inverted index and tokenizers | Not implied by Android SQLite version; repo Room 2.7.1 predates the reviewed Room3 support; bundled-driver migration adds scope | Do not silently depend on platform FTS5 |
| Dense vectors | Can retrieve conceptual and cross-language similarity | Requires text transfer or a bundled local model; model/dimension/version/usage lifecycle | Optional Direct OpenAI mode |
| Reciprocal rank fusion | Combines rankings without treating BM25 and cosine as comparable score scales | Can also amplify weak lexical matches; not universally superior | Separate optional hybrid mode |
| Approximate nearest neighbors / vector server | May scale to much larger collections | Another index/runtime, memory and operational complexity | Unnecessary for the bounded initial scope |
| Model query rewriting or cross-encoder reranking | Possible relevance improvements | Additional disclosure, inference, latency and evaluation; may alter identifiers | Not part of the default pipeline |

The BEIR paper is evidence that lexical retrieval remains a strong baseline across
its evaluated tasks, not proof of the best method for UIT. The original RRF paper
reports its own experiments; it does not guarantee improvements on this corpus.

## Versioned scope and algorithm

One active course index is kept per Moodle account, with at most eight explicitly
selected verified downloads and 400 passages. The picker explicitly discloses its
200-entry cached-catalog cap. A rebuild replaces that account's
active course index. These are execution budgets, not assumptions about UIT
course sizes. Other accounts, unselected files and the wider university are absent
from corpus statistics and embedding requests.

1. Resolve the course/files against the account-owned cached catalog. Build only
   after a deliberate source selection. No download, refresh, account write or AI
   request occurs because text was typed.
2. Capture at most 8 MiB per verified file and verify the captured bytes again
   against SHA-256. Retain raw-byte versions and extractor identity.
3. Extract notebook-v4 Markdown/code source as separate cells, preserving original
   cell numbers and text. Do not execute cells, import outputs or use notebook
   metadata/attachments. Plain UTF-8/code text preserves lines. HTML is derived
   visible text and explicitly partial. PDF uses the existing bounded best-effort
   text extractor: no OCR, complete font-map support or physical-page locator is
   claimed. Unsupported or unreadable files do not become invented text.
4. Bound each document to 120,000 extracted characters. Split within each source
   part at a nearby whitespace boundary, default 1,400 characters with 180 overlap.
   Do not join notebook cells or split surrogate pairs. Retain exact UTF-16 offsets,
   cell ordinal/kind and one-based line numbers. Divide the remaining 400-passage
   allowance among remaining selected files so a long first file cannot starve
   the selected tail. Report clipping and omissions.
5. Tokenize Unicode terms, fold case/Latin accents consistently and map Vietnamese
   `đ` to `d`. Keep complete code/course identifiers and add camel/snake components;
   retain `C++` and `C#`. Do not invent a hand-maintained course synonym dictionary,
   translate queries, stem Vietnamese with an English stemmer or remove negation.
   This tokenizer is lexical, not Vietnamese word segmentation.
6. Build the BM25 inverted index lazily for the current revision and reuse it.
   Course code and file name appear once in each passage's indexed text. Length
   and document frequency are recomputed only over the current authorized scope.
   Stable passage IDs break score ties. Scores are ranking values, not confidence.
7. If semantic indexing is explicitly enabled, send only selected passage text to
   the fixed embedding endpoint. Batch by both count (16) and UTF-8 bytes (64,000),
   with an 8,000-byte per-input guard. No account/download metadata is added;
   selected document text can itself contain links or personal information.
   Validate all returned indices, model, dimensions, finite values and nonzero norm.
   Normalize vectors; persist all vectors atomically or retain a usable lexical
   index with explicit semantic-failure state. Do not retry paid calls silently.
8. Explicit Search computes BM25 locally. Semantic/hybrid sends the submitted
   query once to obtain a vector; typing makes no provider request. Exhaustive
   cosine ranking is sufficient for this cap (400 × 512 = 204,800 components).
   A 512-dimensional Float32 matrix at the cap uses 819,200 raw bytes, before
   JSON/Base64/encryption overhead; this is a size calculation, not a memory test.
9. Semantic mode keeps cosine ordering. Hybrid fuses the top configured candidates
   with RRF, not a weighted sum of incompatible raw scores. Return at most 20
   inspectable excerpts. A nearest vector is not a verified answer; no generative
   correctness or eligibility claim is made.
10. Revalidate source bytes and ownership before returning a result or inspection,
    including after slow provider calls. Removed/replaced files are pruned from
    the derived index. API failure, missing semantic vectors or unsupported gateway
    falls back explicitly to local BM25. Inspect the exact source part and offset;
    opening a PDF uses the existing native reader without inventing a page number.

### Scoring definitions

For query terms deduplicated after tokenization, corpus size `N`, document
frequency `df(t)`, term frequency `tf(t,d)`, passage token count `|d|` and mean
length `avgdl`, use the positive-IDF variant:

$$
IDF(t)=\ln\left(1+\frac{N-df(t)+0.5}{df(t)+0.5}\right)
$$

$$
BM25(d,q)=\sum_{t\in unique(q)}IDF(t)
\frac{tf(t,d)(k_1+1)}{tf(t,d)+k_1(1-b+b|d|/avgdl)}
$$

Defaults are `k1=1.2`, `b=0.75`; they are configurable choices, not fitted UIT
constants. The exact-positive-IDF score has an independent arithmetic regression
case. Candidate count defaults to 40. For unit vectors `x` and `y`, cosine is
`sum(x[i]*y[i])`. Hybrid uses one-based ranks:

$$
RRF(d)=\sum_{r\in\{BM25,dense\},\ d\in r}\frac{1}{k+rank_r(d)},\quad k=60
$$

The settings dialog validates passage size 400–2400, overlap at most one third,
`k1` 0.1–3, `b` 0–1, candidates 10–100 and fusion `k` 1–200. A rebuild is required.
Embedding model and dimensions are separate from chat configuration; model/dimension
changes cannot silently reuse vectors from a different space.

## Provider and deployment decision

The owner's project denied `text-embedding-3-small` with HTTP 403 / `model_not_found`.
The authenticated model listing exposed `text-embedding-3-large`, which completed
the actual evaluation at 512 dimensions. It is the initial experimental optional model setting;
both third-generation model IDs remain configurable. Chat still uses its separate
configured model, including the owner's `gpt-6-luna` preference.

BM25 requires neither an AI key nor a backend. Direct embeddings use the existing
personal connection, fixed HTTPS endpoint, disabled redirects and connection-change
fences. The optional study gateway currently does not support embedding requests;
its users retain BM25. No key is embedded in the APK. A future on-device embedding
adapter would need a model/tokenizer packaging and multilingual evaluation effort;
API-compatible vector dimensions alone do not make models interchangeable.

The real small diagnostic favors semantic mode for the held-out conceptual queries,
while hybrid improves first-relevant rank but loses some top-five graded relevance.
Do not pick a global hybrid default from that result. BM25 stays the privacy/offline
default; semantic and hybrid remain separate explicit choices.

## Primary sources reviewed

- [Robertson and Zaragoza, BM25 and Beyond (2009), author-hosted paper](https://www.staff.city.ac.uk/~sbrp622/papers/foundations_bm25_review.pdf): probabilistic retrieval, length/frequency modeling and parameter optimization.
- [BEIR (2021)](https://arxiv.org/abs/2104.08663): multi-domain retrieval evaluation; scope differs from this app.
- [Cormack, Clarke and Büttcher, RRF (2009)](https://cormack.uwaterloo.ca/cormacksigir09-rrf.pdf): rank fusion and the original experimental `k` choice.
- [SQLite FTS5 documentation](https://www.sqlite.org/fts5.html): BM25, Unicode61, diacritic handling and the English Porter tokenizer.
- [AndroidX FTS5 implementation change](https://android.googlesource.com/platform/frameworks/support/+/835b2a85be93812f792a3ac2e9860a6f83c7174e): bundled SQLite requirement and Room3 implementation context.
- [Android Room entity documentation](https://developer.android.com/training/data-storage/room/defining-data): driver-dependent FTS support; not proof of support in this repo's pinned Room version.
- [Official OpenAI embedding guide](https://developers.openai.com/api/docs/guides/embeddings) and [create API](https://developers.openai.com/api/reference/resources/embeddings/methods/create): separate embedding models, dimensions, limits and returned usage. Actual model access was verified separately.

See [implementation architecture](../COURSE-RETRIEVAL.md) and the
[recorded evaluation](COURSE-RETRIEVAL-EVALUATION.md) for measured results and limits.
