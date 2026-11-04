# University announcements: real UIT phone review

Issue #41. These captures use public UIT responses retrieved by
`PublicAnnouncementClient`, merged by `LocalAnnouncementRepository`, parsed into
native semantic blocks and displayed by the production Compose components.
No university login, credentials or account mutation was used.

Runtime: Robolectric API 35 native graphics on a Linux host. These are not physical
phone/emulator screenshots. They demonstrate native phone layouts and real source
content, not OEM background-notification timing. Widths are 360dp, 390dp and 412dp;
the 412dp capture uses a 1.3 font scale. Dark UIT colors and the existing asymmetric
cut-corner frames are retained.

| State | Capture |
| --- | --- |
| Compact list, 360dp | [Open](announcements/announcements-360.png) |
| List, 390dp | [Open](announcements/announcements-390.png) |
| Larger text, 412dp | [Open](announcements/announcements-412-large-text.png) |
| Actual scholarship notice | [Open](announcements/announcement-scholarship-detail-390.png) |
| Original body and list formatting | [Open](announcements/announcement-scholarship-body-390.png) |
| Source settings and notification opt-in | [Open](announcements/announcement-sources-390.png) |
| Explicit local reminder form | [Open](announcements/announcement-reminder-390.png) |
| Native image viewer | [Open](announcements/announcement-public-image-390.png) |

The image viewer contains **UIT's own instructional screenshot embedded in its
public scholarship notice**. Any sample account state in that image belongs to the
published illustration; it is not a logged-in view of the repository owner's
account and was not fabricated for this review.

Source and capture-time records: [360dp](announcements/announcements-360-provenance.txt),
[390dp](announcements/announcements-390-provenance.txt),
[412dp](announcements/announcements-412-provenance.txt),
[scholarship article and image](announcements/scholarship-provenance.txt).
The [six-adapter research report](../research/evidence/uit-announcements-public.json)
records production fetch results and article body hashes.

## Reviewed behavior

- Titles wrap within one phone column; no desktop navigation or secondary pane.
- Source/date, checked time, unread state and opt-in action stay explicit.
- Article opening starts at its header; Back restores the list position.
- Native text preserves emphasis, lists, links and bounded horizontal tables.
- Images load on request; the original, fit and zoom controls remain available.
- The reminder form states GMT+07, local ownership and possible Android delay.
- Source failure retains cached records; a failed save does not claim success.

25 focused deterministic tests cover parsers, transport, repository lifecycle,
Compose behavior and Android notification delivery/permission semantics. Four
opt-in live tests produce the captures above. Twelve existing Forum design tests
also pass after the shared renderer extraction. Full Gradle `check`, debug APK
assembly and Android instrumentation-test compilation pass. Physical-device
Doze/notification acceptance remains unverified.
