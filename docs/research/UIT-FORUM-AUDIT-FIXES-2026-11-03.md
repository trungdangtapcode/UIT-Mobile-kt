# Forum audit fixes — 2026-11-03

The six defects in the [baseline audit](UIT-FORUM-PARITY-AUDIT-2026-11-03.md)
have implementation fixes and desired-behavior regression coverage on
`feat/uit-forum-parity`, draft PR #79 stacked on #78. This is a self-review,
not independent certification or completion of full Discourse parity.

## Changes and acceptance

| Finding | Corrected behavior | Evidence |
| --- | --- | --- |
| A01: composer loss | Raw/title/category/reason live in ViewModel state and an encrypted account-bound local draft. Keep closes only after storage succeeds; discard requires confirmation. Another topic/edit cannot overwrite an existing draft silently. Original edit text survives for conflict detection. | Compose saved-state restoration and keep/reopen/discard; new ViewModel/repository recovery; different-account isolation; failed storage blocks writes; uncertain submission survives restart and unrelated refresh. |
| A02: truncated history | A 500-message sliding window evicts the opposite end of the requested direction. Older/newer continuation flags are independent. The evicted end remains reachable. | A 600-message test traverses to ID 1 and back to 600 with a bounded window; past/future/around HTTP contract tests; pinned local-server round trips. |
| A03: poll bounds | Multiple polls default maximum to option count, preserve minimum zero and accept numeric/string/null fields using upstream bounds. Removing a vote uses DELETE, never an empty PUT options list. | Parser edge cases plus real production-client vote/removal round trip on isolated Discourse. |
| A04: dropped bookmarks | Topic, channel-message and thread-message targets have distinct types. Chat bookmarks resolve channel permission and load around the requested message. Unknown types stay visible with safe browser fallback. | Mixed-type parser tests, unsafe/external URL cases, native thread/target ViewModel test and local channel/around-message acceptance. |
| A05: poll placement | A semantic poll replaces its cooked placeholder at that exact position, including nested content. Guest rendering is read-only. Cooked content remains readable if metadata is missing. | Exact block/order/options tests, nested hidden content, quoted-poll isolation, cooked-title fallback, metadata-free fallback and local parsed poll acceptance. |
| A06: chat positioning | Opening/refetching a conversation and confirmed send show the latest message; bookmarks open at the requested message. Paging keeps stable message keys and has explicit newer navigation. Refreshing a thread preserves the input subtree and unsent text. | Compose long-batch initial/scroll/send tests and refresh-with-unsent-text test; ViewModel thread/scope checks. |

Tests use synthetic input only for behavioral assertions and isolated server
contracts. They export no Forum screenshot evidence. The existing diagnostic
probes remain archived outside test source sets because they assert the old bugs.

## Draft storage and submission boundary

Production `StudyPrivateStorage` encrypts the draft with Android Keystore AES-GCM,
in the separate Forum vault under the verified account ID. There is one pending
composer slot per account; multiple drafts and server draft synchronization are
still open work. Disconnect clears the visible account and session but retains
the draft for the same verified account. Explicit discard removes that slot.

Each edit schedules local persistence; the editor reports Saving, Saved or an
error. Recovery covers successfully committed saves, not keystrokes lost if the
process is killed before storage completes. A storage failure keeps the editor
open. No draft is automatically submitted to Discourse.

Before a post write, an uncertain marker must be saved. On confirmed success the
slot is removed; on ambiguous failure it remains blocked across restart. A read
of another list cannot silently authorize resubmission. The user must inspect
the outcome and explicitly confirm that the post was not published. If a confirmed
write succeeds but local removal fails, the retained recovery marker stays
uncertain rather than encouraging an automatic duplicate.

The Android instrumentation test `ForumDraftNativeTest` uses the actual production
vault and repository with an isolated mock HTTP server contract. It checks
ciphertext, repository recreation, account isolation, recovery and discard. Host
unit/Compose tests use memory storage. Compilation alone is not Android runtime
acceptance, and repository recreation is not an OS process-kill test.

## Verification and real-data evidence

- Full Gradle `check`, debug APK build and Android instrumentation compilation pass locally. Focused coverage: 43 Forum network tests, 21 Forum ViewModel tests, 6 connected-repository tests and 7 account Compose tests. Counts describe distinct suites, not full workflow coverage.
- The production native client runs against the pinned rootless Discourse lab,
  revision `b81bee142d364526b432788251986871952b5ddb`, including vote removal,
  channel lookup, and past/future/around chat reads. Only generated lab users and
  loopback requests are used. See [aggregate evidence](evidence/uit-forum-native-member-lab.json).
- The authorized live UIT Compose check exercises production login, account feed,
  composer categories, notifications and bookmarks. Its interceptor permits GET
  and necessary login POST only; all other writes are rejected. Necessary login
  may update session metadata. No real-account post/edit/vote/reaction/chat/read
  update was sent.
- The current empty composer capture was inspected at 390 × 844 in Vietnamese:
  cut corners, single column, retained-draft controls and local-save explanation
  fit the phone. It is an actual production-flow capture, not authored content.
  [Capture/provenance](../design/previews/forum-account-provenance.json). Private
  feed/notices/bookmarks remain ignored artifacts. Empty-form screenshots do not
  prove chat/poll visual parity; behavioral tests supply separate evidence.

## Remaining acceptance boundaries

When metadata exists but cooked markup contains no matching placeholder, the poll
is appended after the body; the app does not infer an original position.
Quoted polls remain readable cooked content; voting on them requires opening their
source post. This avoids submitting a quoted choice to the wrong post.

Full parity still needs server drafts/preview/uploads, category/tag requirements,
normal read/tracking synchronization, remote logout, richer account controls,
advanced poll results/types, chat realtime and creation/management, media and
plugin coverage. Larger accessibility text and physical-device lifecycle/TLS
verification remain. The native reader still has the density/navigation concerns
identified in the baseline visual comparison. No percentage or full-parity claim
is made, and PR #79 remains draft.
