# UIT Forum mobile reading audit — November 5, 2026

## Evidence and limits

The reported topic is [ICPC 2026, topic 163707](https://forum.uit.edu.vn/t/163707).
Public GETs currently return HTTP 200. The production Kotlin anonymous transport
and parser successfully load both its first-post route and `/t/163707/20.json`.
The live stream contains five posts numbered 1, 16, 17, 19 and 20. Gaps in post
numbers are valid; they are not proof of a network failure or private content.
This does not reproduce or explain the owner's physical-phone failure by itself.

The supplied phone screenshot shows a NETWORK banner, an empty topic title, stale
window counters, an enabled AI action and zero loaded posts. Code inspection
confirms that selecting a new topic clears bodies/stream but retains the previous
window endpoints and retrieval timestamp. This is an independently reproducible
state-presentation defect even when the underlying request failure is transient.
The reported build discards the underlying network exception category.

A fresh anonymous 390×844 Chromium comparison used real topic 164929. It verified
that images load inline without a Load image action, tapping opens a lightbox,
and a guest still sees reaction/login and copy-link controls. The server advertises
Discourse `2026.10.0-latest`, revision `b81bee142d364526b432788251986871952b5ddb`.
All browser non-read methods, service workers and WebSockets were blocked; attempts
to POST message-bus polling and `/srv/pv` were blocked. No university account was
used. A GET can still affect server logs/counters; no zero-side-effect claim is made.

Upstream behavior was read at that exact revision:
[like button](https://github.com/discourse/discourse/blob/b81bee142d364526b432788251986871952b5ddb/frontend/discourse/app/components/post/menu/buttons/like.gjs),
[reply button](https://github.com/discourse/discourse/blob/b81bee142d364526b432788251986871952b5ddb/frontend/discourse/app/components/post/menu/buttons/reply.gjs),
[lightbox](https://github.com/discourse/discourse/blob/b81bee142d364526b432788251986871952b5ddb/frontend/discourse/app/lib/lightbox.js).
UIT-observed behavior and upstream capability are recorded separately from physical
phone acceptance. The full [Forum parity scope](UIT-FORUM-PARITY.md) remains open.

## Product and design brief

The student came to read an announcement or reply in a discussion, not inspect a
retrieval pipeline. Domain: topic titles, named authors, post numbers, reply parents,
inline announcement images, reactions, source links and reading position. Colors
remain UIT blue, navy ink/surfaces, white paper, gray timestamps and amber attention.
The identifying structure is a continuous phone discussion with a quiet post toolbar
and a compact position control. Keep the transparent emblem and asymmetric frames.

The title and post body lead. Secondary actions belong in overflow menus; jump
input should open on demand. Use Plex Sans weight/color for hierarchy, native 48dp
hit targets, 16dp phone gutters and a 4dp spacing grid. Keep flat post separators,
quiet surfaces and no desktop panels. Reuse native controls and TechnicalShapes.
Reject manual image gates, a permanently visible debug-like jump form, and blank
failure screens that still advertise reading/AI actions.

## Findings to verify in the implementation

| ID | Baseline finding | Required behavior |
| --- | --- | --- |
| UX01 | Images require a manual click before any pixels load | Safe same-origin previews load when visible; pending/error/external media remain explicit; tapping the image opens its original viewer |
| UX02 | Guest post actions disappear entirely | Source copy remains available; explicit interaction prompts Forum login; signed-in actions honor live permissions |
| UX03 | Primary actions are scattered text rows and hidden horizontal controls | A readable post toolbar groups interaction, reply and overflow without hiding essential actions offscreen |
| UX04 | Jump input, cache timestamp and AI entry displace the discussion | Compact position navigation; secondary topic actions on demand; content leads |
| UX05 | A failed new topic inherits the old title/window/timestamp context | Preserve known selected topic identity, clear stale scope fields, and show retry/source access without phantom counts or AI actions |
| UX06 | NETWORK collapses TLS, DNS, timeout and server errors into the same instruction | Preserve a safe typed connection category and offer explicit diagnostics without credentials or raw response bodies |
| UX07 | Automatic images would refetch on scroll and risk account scope leakage | Bounded in-memory reuse, explicit session/public-source scope, and late-result fencing |

No posting, editing, voting, reactions, bookmarks or read-tracking writes may be
performed on the owner's university account for this acceptance work. Mutation
behavior is verified with isolated fixtures; actual university content is used for
reading and published rendering evidence. Full gallery, unsupported plugin flows,
realtime chat and other remaining parity items must not be called complete here.

## Implemented increment and verification

UX01–UX07 are implemented in this preview. The image byte cache is bounded to 8 MiB
and 12 entries in memory, fenced by session/public-source generation. Diagnostics
retain only a typed category; copying requires an explicit action and excludes
credentials, raw exception messages and response content. See the
[preview guide](../FORUM-MOBILE-PREVIEW.md) for behavior and reproduction commands.

Actual owner-account reads verified the ICPC identity, five source post numbers,
reply permission and five like permissions. Two separately guarded probes each
performed one necessary login POST: Chromium and the production Kotlin host path.
Neither issued other writes. No posting, editing, voting, bookmarks, reactions,
read-timing or account-setting operation was used. Login may update session metadata.
Account screenshots remain private. Published captures are anonymous public reads.

`./gradlew check :app:compileDebugAndroidTestKotlin` passed. The Forum network suite
ran 47 tests and Forum ViewModel suites ran 26 tests; app debug ran 354 tests with
31 opt-in tests skipped, and app release ran 126 tests. No failures/errors occurred.
The app includes 11 focused reading UX behavior tests, without exported fixture
screenshots. Separate opt-in runs passed five actual-public-source render tests
and the real-account host read. Those live runs are not implied by ordinary CI.

The preview APK and Android test APK built successfully. The new installed live
test compiled, but was not executed: an isolated API 30 software emulator without
KVM reported boot completion and then failed installation because its package
service was unavailable. No account credentials entered that emulator. There is
no physical Itel runtime acceptance yet, and no claim that its earlier NETWORK
failure has been reproduced or resolved.

[Sanitized aggregate evidence](evidence/forum-reading-ux-2026-11-05.json) records
runtime distinctions and write counts. The [reviewed phone gallery](../design/FORUM-READING-UX.md)
includes 360/390/412dp, larger text, light appearance, inline media and the original
viewer, with source routes, UTC capture times and hashes.
