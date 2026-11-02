# UIT Forum reader

This document describes the current initial reader, not the completed product
target. The required target is full student-facing UIT web parity. Track missing
capabilities, research and acceptance gates in the
[Forum parity matrix](research/UIT-FORUM-PARITY.md), with current implementation
changes in [parity progress](FORUM-PARITY-PROGRESS.md).

Open **Inbox → UIT Forum**. University sign-in is not required. Forum is a
dedicated native phone workspace inside the existing five-destination shell;
Moodle course discussions remain separate.

Latest and category feeds support explicit pagination. Search is submitted by
button, accepts Discourse search syntax, and opens the matched post. Topic
navigation supports numbered jumps, a local resume anchor and previous/next
reply windows. **Saved** stores references on this device. It does not change
Discourse bookmarks, notifications, likes or subscriptions.

## Reading algorithm

A topic response supplies an ordered stream of post IDs, `S`, plus a partial set
of bodies. Stream positions and post numbers are different: deleted or hidden
posts can leave gaps in post numbering. Every returned body must belong to the
requested topic and requested ID set.

For a current stream interval `[a, b)`, forward loading requests
`S[b : min(b + 20, |S|)]`; backward loading requests
`S[max(0, a - 20) : a]`. The reader deduplicates by post ID, orders by `S`, then
keeps at most 80 bodies, evicting from the opposite end. Missing bodies still
advance the requested interval and are reported as unavailable entries. They
are not retried indefinitely or renumbered into fake consecutive replies.

A numbered jump uses Discourse's bounded topic response around that post number.
It does not fetch all intervening bodies. A local reading anchor stores topic ID,
post ID, actual post number and a bounded density-independent scroll offset.
Writes are debounced by 500 ms and flushed when leaving the reader. The position
is best-effort during abrupt process termination. Search and topic requests use
operation generations so canceled or late responses cannot replace a newer view. A completed jump is remembered for the current reader session so Activity
state restoration preserves the visible reply instead of repeating the initial
jump. A fresh ViewModel session still applies a new requested jump.

```mermaid
flowchart TD
    Inbox[Inbox: public Forum entry] --> Hub[Latest / Categories / Search / Saved]
    Hub --> Reader[Native topic reader]
    Reader --> Window[Ordered ID stream + bounded body window]
    Window --> Transport[Anonymous fixed-origin GET transport]
    Transport --> Discourse[UIT Discourse JSON endpoints]
    Transport --> Parse[JSON validation and HTML sanitization]
    Parse --> Cache[Independent encrypted guest cache]
    Reader --> Local[Local saves and reading positions]
    Reader --> Browser[Explicit browser handoff]
```

## Local data and failure behavior

The Forum guest namespace uses Android Keystore AES-GCM independently of UIT
sessions and AI configuration. Response snapshots use a shared fetch-recency budget of
1,000,000 serialized JSON characters, at most 32 snapshots. Saved references have
a 100-topic limit and reading anchors have a 120-topic limit. Repeated saves are
idempotent. Failed writes are surfaced rather than shown as successfully saved.

Saving does not download a whole topic. Offline availability depends on retained
snapshots. Offline resume can reassemble retained bodies across topic and batch
responses, even when no exact numbered-jump response was previously cached. It
uses an actual cached stream and reports nearby content when the target body is
missing. Cached views expose retrieval time and incomplete window coverage.
Transient network failures may use cache; explicit access-denied or removed
responses invalidate related topic snapshots. Rate limits show a delay and do
not trigger automatic retries. Clear **Saved → Clear Forum data** to remove local
references, anchors and response snapshots. Clearing also invalidates in-flight
cache writes; it does not contact UIT.

## Content handling

HTML becomes native text, headings, quotes, code, lists, tables, collapsible
details and links. The app never executes forum JavaScript, CSS or forms. Code
keeps whitespace and scrolls horizontally; tables scroll horizontally. Unsupported
interactive content has an explicit source action. Native images require a user
tap, a same-origin upload URL, an allowed image content type, at most 3 MiB, at
most 4096 pixels on either axis and at most 8 million decoded pixels. Large images
are sampled before rendering. External images and other HTTPS links require a
browser handoff. Avatars and remote trackers are not loaded automatically.

Responses are limited to 4 MiB, streams to 20,000 IDs, cooked HTML to 64,000
characters per post and rendered blocks to 200. These are resource limits, not
assumptions about the size of a university topic. Public category metadata can be
incomplete; only IDs found in public content and their parents are resolved,
with at most eight extra metadata requests per load.

## Verification

[Research and endpoint observations](research/UIT-FORUM-READER.md) explain the
upstream contract, category metadata gaps and tracking caveat.
[Aggregate live evidence](research/evidence/uit-forum-public.json) contains counts
and route identifiers only. Raw live bodies and student identities are excluded.
[Design notes](design/UIT-FORUM.md) and the [phone gallery](design/README.md) document
the actual Compose UI with real UIT Forum responses. Fixture-based behavior
tests do not produce screenshots.

```sh
./gradlew :core:network:forum:test :feature:forum:testDebugUnitTest \
  :core:data:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests '*ForumDesignTest'
# Explicit manual network verification; not run by normal check/CI:
./gradlew :core:network:forum:researchPublicForum
```

The Android instrumentation test exercises the production Compose reader,
repository, parser and real Keystore with an authored transport fixture. It
checks local save, restore, offline reading and clear without constructing any
university account transport. It does not export a screenshot. The gallery uses
the separate live-data Compose scenario below.

### Live UI verification (manual, local only)

Fixture-based behavior tests do **not** prove current server compatibility or
Forum sign-in. To exercise the
actual production client, parser, repository, ViewModel and Compose reader
together against the public server:

```sh
./gradlew :app:testDebugUnitTest --tests '*ForumLiveDesignTest' \
  -PforumLive=true -PrecordUi=true
```

This opt-in test makes real anonymous HTTPS GET requests. It captures latest,
categories, submitted search, topic reading and a numbered jump. It also checks
forward reply loading and captures an announcement when present in the current
feed. The main Robolectric looper is explicitly drained while asynchronous
network work completes. Normal tests skip this network scenario.

Images and `provenance.txt` stay under ignored `app/build/outputs/forum-live/`.
They can contain student names or addresses from public posts; inspect locally
and do not automatically publish them as repository or PR assets. The gallery
contains only manually inspected public feed/category/official-announcement
captures with provenance; support replies and authenticated images stay local.
No fixtures
replace responses in this scenario. Persistence uses in-memory `StudyStorage`;
the runtime is Robolectric, not an installed APK or Android emulator. This test
does not verify Android Keystore, device TLS behavior or Forum authentication.

On 2026-11-03 (Asia/Tokyo), the real reader loaded the Microsoft 365 topic with
7,455 stream IDs, its initial 20 bodies, a further reply batch and the window
around post #3727. Some migrated posts still contain literal legacy BBCode in
their upstream cooked HTML; the reader currently displays those tags as text.

A separate, private browser check authenticated the owner's authorized account:
the read-only `/session/current.json` response returned HTTP 200 and its username
matched the supplied account. Only verified same-origin login POST routes were
allowed; other POSTs, including message-bus polling and view telemetry, were
blocked. Credentials and cookies were not persisted, and the screenshot remains
local. Login can still update server-side session/last-login metadata. This
confirms website authentication only: **the app's Forum transport is anonymous
and does not yet implement Forum SSO**.

## Discovery and isolated mutation research

[The parity increment](FORUM-PARITY-PROGRESS.md) adds native public filters and a
searchable tag directory as well as rich content, fullscreen images and revisions.
The current app remains an anonymous reader. Account-based functionality is not
implied by the availability of server capabilities or local upstream experiments.

Use [the isolated Discourse lab](FORUM-LAB.md) for write-contract research. Never
point the mutation harness at UIT or supply university credentials to it.
