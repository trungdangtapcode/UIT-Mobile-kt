# Forum mobile reading: actual UIT data

The topic body leads. Safe upload images appear as the reader reaches them;
post actions stay below the post. A compact position button replaces the permanent
jump form. Topic overflow contains source, copy-link, resume and AI actions.
The existing UIT palette, Plex type, asymmetric corners and five tabs remain.

These are actual production Compose components with live anonymous UIT responses,
rendered on Robolectric API 35 on November 5, 2026. They are **host renders**, not
screenshots from an installed phone. No Forum posts were authored for this gallery.
Private authenticated captures and later student support bodies are excluded.

## ICPC announcement

Source: [topic 163707](https://forum.uit.edu.vn/t/163707), first post. Source post
numbers are sparse; the reader retains their identity rather than renumbering them.

| State | 360dp | 390dp | 412dp / 130% text |
| --- | --- | --- | --- |
| Title and original body | [Compact](forum-reading-ux/reading-icpc-360.png) | [Default](forum-reading-ux/reading-icpc-390.png) | [Larger text](forum-reading-ux/reading-icpc-412-large-text.png) |
| Guest post controls | [Compact](forum-reading-ux/reading-icpc-actions-360.png) | [Default](forum-reading-ux/reading-icpc-actions-390.png) | [Larger text](forum-reading-ux/reading-icpc-actions-412-large-text.png) |
| Light appearance | — | [Body](forum-reading-ux/reading-icpc-390-light.png) · [Controls](forum-reading-ux/reading-icpc-actions-390-light.png) | — |

![ICPC announcement through the native production reader](forum-reading-ux/reading-icpc-390.png)

Guest controls provide source copying and explicit login for account interactions.
Successful login returns to the selected post; it never automatically replays a
like, bookmark or submission. Member controls honor live permissions.

## Inline image and original viewer

Source: [official gift announcement, topic 164929](https://forum.uit.edu.vn/t/164929).
The same public university poster is visible in the app and mobile website.

| Native reader | Actual mobile website |
| --- | --- |
| [Announcement](forum-reading-ux/reading-announcement-390.png) · [Automatic inline preview](forum-reading-ux/reading-inline-image-390.png) · [Tap-to-open viewer](forum-reading-ux/reading-image-viewer-390.png) | [Same announcement and poster](forum-reading-ux/web-image-inline-390.png) · [Guest controls, element-only crop](forum-reading-ux/web-post-controls-only.png) |

![Automatic preview of the actual UIT announcement poster](forum-reading-ux/reading-inline-image-390.png)

The viewport placeholder reserves bounded space using source dimensions. Concealed
images wait for expansion. External delivery and failures retain explicit fallback
and retry controls. Gallery swiping, secure uploads/CDNs, animated media and full
web interaction parity remain open; this increment does not close issue #39.

## Review and evidence limits

All 11 native captures and both web comparisons were inspected before publication.
The title wraps, the five destinations remain reachable, and the post toolbar fits
at 360dp and with larger text. Long URLs wrap; source code/tables retain horizontal
scrolling. This review does not establish TalkBack, rotation or OEM acceptance.

The owner's older installed build failed to open ICPC while Chrome could open it.
Public and authenticated Kotlin reads succeeded on the host. The physical-phone
failure's cause remains unknown. The new build preserves typed error details and
clears stale reading counters after a failed topic switch.

An isolated API 30 software emulator reported boot completion but lacked a working
package service during installation. It supplied no installed-app acceptance or
native screenshots. The opt-in installed test compiled only.

- [Capture provenance, GET routes, UTC times and image hashes](forum-reading-ux/provenance.json)
- [Research, server revision and verification](../research/UIT-FORUM-MOBILE-UX-2026-11-05.md)
- [Installable preview and device checks](../FORUM-MOBILE-PREVIEW.md)
- [Remaining parity scope](../research/UIT-FORUM-PARITY.md)
