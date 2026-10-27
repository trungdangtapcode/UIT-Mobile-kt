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
Private scan URLs are neither retained in the display model nor downloaded/opened.
Current insurance cards are separated from reported profile details; an absent
current card does not imply valid coverage. Insurance failures have a section-level
error instead of an empty history. Portrait failures use an initials fallback.

## Memory and errors

Detailed personal, family, identity, bank, health, insurance and portrait data stay
in the profile ViewModel's memory. This feature does not write them to Room,
DataStore, files or saved instance state. Closing the screen clears the ViewModel
state; reopening fetches again. The existing minimal academic identity snapshot
used elsewhere in the app retains its previous caching behavior.

A failed refresh can keep the same account's already loaded profile visible with
an error and its original loaded time. A fresh offline opening has no detailed
profile to display. This screen does not provide a new offline profile cache.

## Validation and limits

The profile page, insurance endpoint and portrait endpoint were checked in an
authorized authenticated session on October 27, 2026. Profile and insurance private
captures passed the production parsers through the opt-in `LiveProfileResponseTest`.
The committed tests and screenshots contain synthetic identities only. Private
captures and temporary authentication scripts are removed after inspection.

The production profile screen is rendered at 360dp, 390dp and 412dp, including
larger text and dark mode. Repository tests exercise exact account selection,
read-only requests, no disk snapshot access, partial failures and account removal.
ViewModel tests cover account switching, stale responses, closing and failed
refreshes. A device instrumentation test covers the real toolbar-to-profile-to-
settings route without student credentials.

The observed Awards collection was empty; populated award field variants remain
unverified. Unknown new fields and nested objects are not rendered automatically.
This is a native read-only profile view, with document metadata rather than the
Portal's editable forms and private scans. Device-specific rendering and portrait
decoding still need ordinary physical-device testing.
