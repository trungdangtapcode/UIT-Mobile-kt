# Student profile

Tap the account icon in the top toolbar to open **Student profile**. It uses the
linked UIT Portal account by default. To choose another account, open the profile's
settings button, then tap that account's name or **View student profile**. Back
returns to the previous destination; the five bottom destinations remain available.
Settings also contains sign-in, account removal and appearance controls.

The native phone screen follows the sections exposed by
[`/sinh-vien/ho-so`](https://portal.uit.edu.vn/sinh-vien/ho-so): Personal, Family,
Academic, Health, Documents, Insurance and Awards. Each section presents a vertical
list of labeled fields inside the existing cut-corner panels. Missing fields say
**Not provided**; zero and false values are retained. Portal-provided names and
values keep their original language. A student portrait is optional.

## Reads and account selection

All requests use the existing origin-scoped UIT Portal session. Opening or refreshing
a profile performs at most these three GET requests:

| Request | Purpose |
| --- | --- |
| `/sinh-vien/ho-so` | Identity and profile sections from the page's embedded Flight data |
| `/api/sinh-vien/bao-hiem` | Current insurance cards, reported insurance details and history, when the section is visible |
| `/api/sinh-vien/anh-the` | Optional JPEG or PNG student portrait |

Selecting a UIT Portal account uses that exact session. A current Moodle account
can use the Portal session linked by the SSO capture identity. Legacy Moodle or
unlinked accounts receive a sign-in explanation; they never fall back to another
student's Portal session. Removing the selected account or its required Portal
session during a request discards the response. Late responses cannot replace a
newer request or another account's screen.

The screen has no profile editing, registration, payment, upload or deletion
actions. Documents show their label, required flag, upload date and on-file status.
Tap **View scan** on an uploaded document to open its image inside the app. Pinch,
double-tap, or use the zoom buttons to enlarge it; drag to move and tap **Fit** to
restore the full image. Android Back or the viewer's Back button returns to the
document list. Documents without an image have a disabled preview action.
Image source URLs and base64 payloads are not retained in document display models.
Current insurance cards are separated from reported profile details; an absent
current card does not imply valid coverage. Insurance failures have a section-level
error instead of an empty history. Portrait failures use an initials fallback.

## Memory and errors

Detailed personal, family, identity, bank, health, insurance, portrait and scan data stay
in the profile ViewModel's memory. This feature does not write them to Room,
DataStore, files or saved instance state. Closing the screen clears the ViewModel
state; reopening fetches again. The existing minimal academic identity snapshot
used elsewhere in the app retains its previous caching behavior.

Opening a scan re-reads the selected account's profile, finds the document by its
Portal type, and decodes only that image. The observed images are embedded JPEG/PNG
data URLs, so they need no separate attachment request. A same-origin HTTPS image
URL can also be read through the authenticated transport; external origins are
rejected. No image is exported, shared, persisted or sent to another app. Closing
the viewer, switching accounts, refreshing or closing the profile clears scan state.
Failed/expired reads and invalid images show retry controls instead of a blank page.

The viewer supports JPEG, PNG and WebP images up to 12 MiB. Decode sampling caps
each bitmap dimension at 4096 pixels; the existing transport limits the entire
profile response to 25 MiB. PDF and other document formats are not rendered.

A failed refresh can keep the same account's already loaded profile visible with
an error and its original loaded time. A fresh offline opening has no detailed
profile to display. This screen does not provide a new offline profile cache.

## Validation and limits

The profile page, insurance endpoint and portrait endpoint were checked in an
authorized authenticated session on October 27, 2026. Profile and insurance private
captures passed the production parsers through the opt-in `LiveProfileResponseTest`.
On October 28, an additional authorized capture verified referenced profile sections
and embedded scan decoding with `UIT_LIVE_SCAN_FIXTURES`. The reader now resolves
Flight data references and byte-length text records before extracting fields; the
previous line-only parser could miss referenced sections. See
[scan protocol research](research/UIT-PROFILE-SCANS.md).
The committed tests and screenshots contain synthetic identities only. Private
captures and temporary authentication scripts are removed after inspection.

The production profile screen is rendered at 360dp, 390dp and 412dp, including
larger text and dark mode. Repository tests exercise exact account selection,
read-only requests, no disk snapshot access, partial failures and account removal.
ViewModel tests cover account switching, stale profile/scan responses, closing,
retry and failed refreshes. Device instrumentation tests cover the real toolbar-to-
profile-to-settings route and native viewer gestures/Android Back with a synthetic
image. They use no student credentials.

The observed Awards collection was empty; populated award field variants remain
unverified. Unknown new fields and nested objects are not rendered automatically.
This is a native read-only profile view with image previews. Portal editing and
document uploads are not implemented. Individual physical-device behavior still
needs ordinary device testing.
