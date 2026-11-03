# Forum audit evidence

Baseline: `5c907fdb4a91f421516046d4fc56018c7780a129`. See the
[audit report](../../UIT-FORUM-PARITY-AUDIT-2026-11-03.md).

The `.kt.txt` files are diagnostic counterexamples, not normal regression tests.
Their assertions deliberately confirm the deficient baseline behavior. A pass
means the defect was reproduced; it does not mean the product meets requirements.
They use synthetic fixtures for behavior only, never networking or screenshots.
`diagnostic-results.json` records five successful reproductions in four defect
groups, including source hashes. Do not add these bad-outcome assertions to CI as
acceptance tests; turn them into desired-behavior regressions when fixing the bugs.

To reproduce on the audited commit in an isolated checkout, copy the sources into
the corresponding test modules, then run:

```sh
cp docs/research/evidence/forum-parity-audit-2026-11-03/ForumParityAuditProbeTest.kt.txt \
  app/src/test/kotlin/vn/edu/uit/mobile/ForumParityAuditProbeTest.kt
cp docs/research/evidence/forum-parity-audit-2026-11-03/ForumParityNetworkAuditProbeTest.kt.txt \
  core/network/forum/src/test/kotlin/vn/edu/uit/mobile/forum/network/ForumParityNetworkAuditProbeTest.kt
./gradlew :core:network:forum:test --tests '*ForumParityNetworkAuditProbeTest' \
  :app:testDebugUnitTest --tests '*ForumParityAuditProbeTest'
```

Remove those two copied test files afterward. The application code was not changed
by the audit. A later fix can make a diagnostic fail, which is expected.

The two PNGs are inspected, unedited captures of the same real public UIT
announcement. Native uses production anonymous networking and Compose/Robolectric;
web uses Chromium at a mobile viewport with non-read methods blocked. No university
credentials, private content or fixture screenshots are published. Exact times,
source, runtime, limits and hashes are in `visual-provenance.json`.
