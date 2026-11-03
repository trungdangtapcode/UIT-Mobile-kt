# Forum parity implementation progress

**Audit update (2026-11-03):** [The behavioral and mobile-web audit](research/UIT-FORUM-PARITY-AUDIT-2026-11-03.md)
finds the current implementation is not web-equivalent. Five diagnostic probes
reproduce draft loss, truncated chat history, incorrect optional poll bounds and
dropped chat bookmarks. These are open defects in existing flows, in addition
to the missing capabilities below; passing baseline CI does not resolve them.

This is implementation progress against the [46-row matrix](research/UIT-FORUM-PARITY.md),
not a full-parity completion claim. The implementation branch is
`feat/uit-forum-parity` ([draft PR #79](https://github.com/trungdangtapcode/UIT-Mobile-kt/pull/79)), stacked on `feat/uit-forum-reader` (PR #78).

## Content, media and history increment

The production renderer now consumes a bounded semantic content tree rather than
only flat strings. Inline bold, italic, code, strike, underline and links retain
their ranges. Quotes, details, lists and table cells retain nested content and
images. Quote attribution and explicit quoted-post targets are preserved when
supplied. Spoilers stay absent from accessibility semantics until revealed.
Code blocks expose language labels and source-copy actions.

Uploaded images can open in a fullscreen phone viewer, load the original
lightbox URL, zoom/pan and reset. Native buttons provide a gesture alternative.
Image failures have retry controls. The existing origin, byte and decode limits
remain; unsupported external/original resources still need browser handoff.
There is no claim of animated-image, audio/video or complete gallery support.

Posts retain edit version, update time, history permission and reply target.
Permitted posts expose native edit history with revision navigation, author/time,
reason, title/body diffs and Changes/Before/After views. Request revision IDs are
kept separate from server display-version numbers. Hidden history is rejected;
history is neither persisted nor replayed offline. Cancelled/dismissed history
cannot reappear through a late response. The account increment below adds conflict-aware raw editing; revision reverts are not implemented.
Metadata-only changes and media diffs still need broader parity work.

## Public discovery increment

The Topics tab supports Latest, Top, Hot and Votes only when advertised in
`anonymous_list_filters` from UIT `/site.json`. Authenticated-only list filters
are not inferred from the broader `filters` array. Top supports day/week/month/
quarter/year/all-time periods; labels describe elapsed periods rather than calendar
weeks/months. Observed UIT monthly web dates span the preceding month.

A Tags tab fetches `/tags.json`, keeps server IDs/counts/descriptions, excludes
PM-only entries and searches the loaded directory locally. The directory is bounded
to 10,000 entries and reports truncation. Selection opens a native topic list through
`/tag/{id}/l/{filter}.json`. Pagination retains tag, sort and period. Switching a
category clears the unrelated tag/sort; Back from a topic retains its feed selection.
Denied/rate-limited feeds remain visible errors, not substituted empty success.

Live UIT checks exercised weekly and monthly Top, Hot, Votes, tag search and the
selected tag feed. A separate anonymous Chromium viewport at 390×844 opened
`/top?period=monthly` with mutation methods blocked. Its first four titles matched
the production native monthly list in order. The web labels replies; the app
explicitly labels total posts (which includes the first post). No pixel-identical
layout claim is intended.

Observed guest `/tags/list.json?filter=uit&offset=0` returned 403, so the directory
does not depend on or attempt to bypass that authenticated pagination route.
Category/filter combinations, multiple-tag intersections, aliases, exact topic
metadata and account-only lists remain open. The local tag filter only searches
currently loaded public names; it is not a substitute for full server search.

## Evidence and limits

Manual live verification uses the existing opt-in command:

```sh
./gradlew :app:testDebugUnitTest --tests '*ForumLiveDesignTest' \
  -PforumLive=true -PrecordUi=true
```

It loads real UIT responses through the production client, parser, repository,
ViewModel and Compose renderer. It now additionally loads an actual announcement
image, opens/zooms the viewer and reads the first permitted edited announcement
found in a bounded scan of at most four announcement topics from the actual feed.
No authored response substitutes for live content.

At the current observation, the image is from topic 164929 and the official
announcement edit is from topic 164719 (post 452924, revision 2). These are
observations, not production constants. Captures and provenance remain under
`app/build/outputs/forum-live/`; inspect them before publishing any selected
public announcement images. Runtime is Robolectric with in-memory test storage,
not an installed device or authenticated app test.

Automated contract tests cover nested/hidden/malicious content, original image
URLs, revision ID/display-version differences, denied history and history cache
isolation. UI behavior tests use fixtures without exporting screenshots. The
real university account is not used for mutation testing.

## Matrix status

| Rows | Progress | Still open |
| --- | --- | --- |
| F02, F04 | Advertised public sorts, Top periods and searchable server tag directory; real UIT and mobile-web comparison | Category/filter combinations, multiple tags, aliases and wider period/permission coverage |
| F07, F12 | Reply-parent and attributed quote targets; nested quote content | Reply expansion, backlinks and return-to-anchor behavior |
| F10–F11 | Inline spans and nested list/table content | Full whitespace/layout fidelity, complex table headers/spans and broader real examples |
| F13 | Nested details and explicit spoiler concealment | Task controls and wider UIT content coverage |
| F14 | Language labels, source copy, whitespace-preserving code | Syntax highlighting |
| F16–F17 | Real uploaded image, original, fullscreen, zoom/pan and retry | Multi-image gallery, CDN/secure/redirect handling and device lifecycle coverage |
| F22 | Permitted history, before/after diff and revision navigation | Full metadata/media diffs, wider permission/device cases |
| F03, F24, F26–F30 | Native separate login/restore, account isolation, personal filters, server resume/bookmarks/notices | Installed Android, remote session revocation, tracking/preferences and broader account cases |
| F23, F33–F35, F38, F41 | New/reply/edit with stale-source conflict, explicit bookmark/like/reaction/poll actions, existing channel/thread chat | Uploads/drafts/preview, advanced poll types, chat creation/management/realtime, production write acceptance |
| Other rows | Earlier reader behavior or research only | See the matrix; no full-parity certification |

A dedicated local Discourse environment runs at the observed upstream revision
for subsequent mutation tests. See [the isolated lab guide](FORUM-LAB.md). It is not connected to UIT and
must not contain university credentials or private account content. Synthetic
local server data is suitable for contract/action tests, not public Forum
screenshot evidence.

The isolated harness passed login/create/edit/reply/bookmark contract groups and
sampled authenticated XHR read-state checks. [Aggregate evidence](research/evidence/uit-forum-local-contract.json)
records zero production UIT requests. The subsequent native-client suite now
exercises the app's actual session/API classes; see [account behavior](FORUM-ACCOUNTS.md)
and [native contract evidence](research/evidence/uit-forum-native-member-lab.json).
The task-specific lab container was stopped after verification; its isolated data
remains available for subsequent contract work.

At the earlier discovery baseline, the full Gradle `check` and Android debug/instrumentation compilation
passed after discovery implementation. After final localized period/tab wording,
all 13 focused Forum UI/live tests passed and the debug APK was rebuilt. At that
baseline, Forum network tests totaled 25 and ViewModel tests totaled 9. Host captures and local server
experiments do not replace installed-device or authenticated app acceptance.

## Native account increment

The app now signs in natively with the same university credentials, while keeping
Forum cookies separate from Portal/Moodle. UIT's actual login returns 204; current
identity verification succeeds afterward. Cookies are encrypted with Android
Keystore in production, and member bodies never enter guest storage.

Personal filters, notifications (including real UIT badge notices), server
bookmarks and server last-read resume are implemented. Explicit new topic/reply,
raw edit with conflict detection, bookmark/like/reaction, regular/multiple poll
voting and channel/thread chat use typed native requests. No action is automatically
replayed after an ambiguous result. Authenticated content is memory-only.

See [Forum accounts](FORUM-ACCOUNTS.md) for the exact contracts, reproduction,
privacy boundaries and remaining work. The production client passed the local
mutation suite. Live UIT login and read-only Compose screens also passed with all
non-login writes blocked. Final validation for the native account increment: full Gradle `check`,
`:app:assembleDebug` and `:app:compileDebugAndroidTestKotlin` passed. Forum network
contracts: 37 passing tests; Forum ViewModel: 15; connected-repository isolation:
4; account Compose interactions: 4. The separate opt-in real UIT account test and
local native-client mutation suite passed. These counts describe their respective
suites, not installed-device coverage. PR #79 stays draft: full web parity and
installed-device acceptance remain open.
