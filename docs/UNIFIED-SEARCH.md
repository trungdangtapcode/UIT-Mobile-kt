# Unified workspace search

The toolbar search action opens **Search workspace** on the phone. It searches
saved course names/codes, module and file titles, assignments and DAA notices.
Results are grouped and filterable, with at most 20 visible hits per group; refine
the query for more matches. Vietnamese accents, case and punctuation in course
codes normalize locally. Queries and the index stay in memory, without a backend,
AI request, full-text document index or search history store.

Only metadata already loaded into the cache is searchable. Load course materials
and assignments to add them to the index. An empty index differs from no matching
results; a global profile refresh timestamp does not establish search coverage.

Each hit opens its exact saved detail. Back preserves the query, filter and list
position. The optional course-workspace action uses normal course loading and Back
returns to search. File opening is explicit and reuses the authenticated verified
download path; merely searching/opening a detail sends no university requests.
File opening requires an external viewer for that format when available.

Moodle results require a currently connected owner and a matching cached course.
DAA notices use provenance attached by the repository to each account's cached
snapshot; merged unowned notices are never reassigned to a remaining account.
Account removal immediately drops hits and closes removed details, including
before the merged cache catches up. Obsolete file-opening effects are rejected.
Profile data, credentials, submission contents, PDF text and scans are not indexed.

Verification covers normalization, account isolation, stale DAA ownership,
metadata-only behavior, explicit network actions, stale downloads and actual
Compose rendering at 360/390/412dp, including larger text and missing-data states.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :core:domain:test \
  :app:testDebugUnitTest --tests '*WorkspaceSearch*' -PrecordUi=true
```

Implements [issue #3](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues/3).
