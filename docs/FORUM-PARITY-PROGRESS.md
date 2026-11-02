# Forum parity implementation progress

This is implementation progress against the [46-row matrix](research/UIT-FORUM-PARITY.md),
not a full-parity completion claim. The implementation branch is
`feat/uit-forum-parity`, stacked on `feat/uit-forum-reader` (PR #78).

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
cannot reappear through a late response. No edit/revert operation is implemented.
Metadata-only changes and media diffs still need broader parity work.

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
| F07, F12 | Reply-parent and attributed quote targets; nested quote content | Reply expansion, backlinks and return-to-anchor behavior |
| F10–F11 | Inline spans and nested list/table content | Full whitespace/layout fidelity, complex table headers/spans and broader real examples |
| F13 | Nested details and explicit spoiler concealment | Task controls and wider UIT content coverage |
| F14 | Language labels, source copy, whitespace-preserving code | Syntax highlighting |
| F16–F17 | Real uploaded image, original, fullscreen, zoom/pan and retry | Multi-image gallery, CDN/secure/redirect handling and device lifecycle coverage |
| F22 | Permitted history, before/after diff and revision navigation | Full metadata/media diffs, wider permission/device cases |
| Other rows | Earlier reader behavior or research only | Authentication, account synchronization, participation and enabled extensions remain unfinished |

A dedicated local Discourse environment is being prepared at the observed
upstream revision for subsequent mutation tests. It is not connected to UIT and
must not contain university credentials or private account content. Synthetic
local server data is suitable for contract/action tests, not public Forum
screenshot evidence.
