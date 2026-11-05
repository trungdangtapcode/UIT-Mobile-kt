# Incremental course-search updates

## Algorithm

The existing account-owned encrypted index is the reuse source. No global cache or
university credentials are introduced. For the selected course and account:

1. Validate the selected IDs against the currently authorized cached course catalog.
2. Read each original download through the verified-byte loader, computing its
   current SHA-256 and extracting source text with the existing bounded extractor.
3. Chunk the newly extracted text with the requested configuration, reserving
   passage capacity across selected documents. Preserve the new source hash,
   notebook cell, offsets and line positions even when vectors can be reused.
4. If the old embedding model and dimensions exactly match the requested ones,
   construct a reuse map keyed by the existing length-delimited SHA-256 digest of
   `(embedding-text-v1, model, dimensions, exact text)`. Whitespace and Markdown are
   not normalized. The owner/course boundary is enforced before constructing the map.
5. Match each new passage against that map. Deduplicate identical pending texts
   within this update, then compute a review plan without calling any provider.
6. Fingerprint owner, course, previous index revision, configuration, selected IDs,
   states and current source hashes. Recompute before applying the confirmed plan.
   Reject a stale or cross-account review before any embedding request.
7. Batch only new unique texts, at most 16 texts / 64,000 UTF-8 bytes per request.
   Validate provider model, vector count, dimensions, finiteness and normalization.
8. Rebind reused/generated vectors to the new passage rows. Reread source hashes,
   check account/catalog/cancellation generation again, then atomically publish
   the complete index and receipt to encrypted storage.

A raw notebook metadata/output change changes its source identity, while unchanged
source-cell text can reuse vectors. A changed cell transfers only its new chunks;
chunk boundary changes may require more chunks. A model/dimension change invalidates
all vector reuse. Keyword-only preparation never calls the embedding API. Confirming it replaces the current semantic index; the UI warns before removing existing vectors.

## Counts and costs

`reused` counts new passage rows mapped to previously saved vectors. `transfers`
counts unique exact texts missing from the reuse map. Duplicate new text may map to
multiple passage rows after one transfer. The receipt's `embedded` counts those
newly embedded passage rows; `requests` counts actual attempted batch calls.
The UI does not present character counts as token or price estimates. Saved
`embeddingTokens` describes this update's reported indexing usage, not lifetime cost.

Source files are never uploaded as binary files. New extracted text goes directly
to OpenAI only after explicit confirmation. The existing key configuration is used;
embedding model selection remains separate from the chat model. There is no
implicit provider call on typing, selecting a file, checking or opening a screen.

## Persistence, concurrency and migration

Codec version 2 adds an optional update receipt. Version 1 remains readable; old
valid vectors can immediately be reused after restart. The active matrix remains
complete or empty; partially produced new vectors are never published. No durable
partial-batch checkpoint is introduced. A canceled retry can reuse only previously
committed matching vectors, so completed but uncommitted requests can be billed again.

A failed update preserves an existing semantic index. Initial semantic failure may
still publish keyword-only coverage with an explicit error. Subsequent search and
source inspection continue to revalidate original source hashes. A search that
invalidates an old document removes its vectors; they are no longer available for
reuse afterward. This bounds retained content and favors source validity over a
separate long-lived embedding cache.

One active course index is retained per account. Selecting a different course
cannot reuse another course's vectors. Clear/reset/account removal purge the same
index namespace. Epoch and ownership checks prevent late publication. Query vectors
are not persisted. A saved-file check does not attest remote Moodle freshness.

## Verification

Automated cases cover unchanged/restarted reuse, metadata-only source replacement,
one-cell edits, changed embedding dimensions, owner isolation, stale transfer
reviews, unreadable files, keyword-only updates and failed-update preservation.
The opt-in real-material check restores the original NT409 notebook and actual
OpenAI vectors through the production codec/loader/repository. It then verifies
that checking and an unchanged update make zero provider calls. Public evidence
contains counts, hashes and request metadata only, never keys or raw vectors.

Phone renders use the actual captured NT409 source and production update plan.
Host Compose rendering is not physical Android testing. No remote university
request is required for this increment's local verification.

### Recorded checks

- Full repository `check`, `:app:assembleDebug`, and
  `:app:compileDebugAndroidTestKotlin` passed during implementation.
- Final preview build, app lint, Kotlin lint, Detekt and architecture verification
  passed after the final UI/state changes.
- Focused verification: 16 repository tests, 1 actual-material reuse replay,
  7 ViewModel tests, 10 interaction tests and 7 actual-material rendering tests
  passed (41 tests). The real-material renderer exports 10 update-flow images.
- Preview manifest/package and APK v2 signature were checked. The configured local
  API key was checked for absence from the APK entries. No device was attached.

[Sanitized actual-material result](evidence/course-search-updates.json) records
103 reused passage vectors, zero transfers, zero provider calls, zero university
requests and successful v1 → v2 persistence migration. A real provider call is
intentionally forbidden during this unchanged-update test; the saved vectors are
from the earlier actual OpenAI embedding run, not generated fixture vectors.
