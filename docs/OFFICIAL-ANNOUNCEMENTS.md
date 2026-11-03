# Official university announcements

Issue: [#41](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues/41).
This implementation is stacked on the calendar file sharing branch (#80).

## Student flow

Open **University announcements** from Today or Inbox. The phone keeps its five
bottom destinations. Public announcements work without signing in. Portal UIT is
enabled initially; other verified sources are optional in Sources and alerts.

Students can search downloaded titles/excerpts, filter unread/saved items or suggested
scholarship, tuition and course-registration topics, read the original article in
native Compose, save it, follow original links/attachments, and set a local reminder.
My reminders and Saved include retained items from disabled sources. Back returns
from article to list, then to the entry screen. Filters survive screen recreation.

Images load only on an explicit tap, through a separate anonymous client, with
size/pixel limits and a fullscreen zoom viewer. Supported HTTPS UIT upload paths and the verified Portal
`/media/ir.attachment/{numeric-id}/file` route load natively; other media has an explicit original-link fallback. Article text,
formatting, tables and links are cached after opening. Images and attachments are
not downloaded for offline use. Embedded unsupported content links back to the
original instead of being represented as a completed native interaction.

## Verified source inventory

Observed on 2026-11-03 with public anonymous GETs. No university credentials were
used. See [production-client evidence](research/evidence/uit-announcements-public.json).

| Adapter | Entry route | Extraction and scope |
| --- | --- | --- |
| Portal UIT | `/bai-viet?category=thong-bao` | Streamed server HTML; article cards can be outside the initially empty main element. Pages are one-based. This is the primary general-announcement feed. |
| CTSV | `/` | Drupal main article teasers; subsequent pages use `/node?page=N`. |
| OEP | `/vi/thong-bao-chung` | Drupal main view rows, zero-based page parameter. Special-program notices are not assumed to apply to every student. |
| Youth | `/` | Main teasers and dated news rows. This homepage is a bounded collection, not a complete historical archive. |
| Research office | `/thong-bao` | Drupal main taxonomy list. Pinned historical items can precede recent announcements. |
| Information Systems faculty | `/category/thong-bao-hoc-vu/` | WordPress category article cards, excluding sidebar stories. `/page/N/` pagination. Only this faculty is covered initially. |

The route is **`thong-bao`**, not the guessed `thong-bao-chung` Portal slug. A real
scholarship application notice is tagged only as a general announcement. Therefore,
fetching the scholarship category alone is insufficient. The school migration
notice also makes the old DAA feed unsuitable as the only general-news source.

Sources:

- [Portal general announcements](https://portal.uit.edu.vn/bai-viet?category=thong-bao)
- [Portal scholarship application notice](https://portal.uit.edu.vn/bai-viet/thong-bao-dang-ky-xet-cap-cac-loai-hoc-bong-hk-1-nh-2026-2027)
- [DAA migration notice](https://daa.uit.edu.vn/thong-bao-ve-viec-chuyen-doi-cong-thong-tin-sinh-vien-portaluiteduvn)
- [CTSV](https://ctsv.uit.edu.vn/), [OEP](https://oep.uit.edu.vn/vi/thong-bao-chung), [Youth](https://tuoitre.uit.edu.vn/), [Research](https://khcn.uit.edu.vn/thong-bao), [Information Systems](https://httt.uit.edu.vn/category/thong-bao-hoc-vu/)

## Shared record contract

`OfficialAnnouncement` and `AnnouncementRepository` live in `core:domain` for reuse
by scholarship, internship, event and tuition consumers. Records preserve the
source enum, HTTPS article permalink, source title, publication date (ISO local
calendar date or explicitly absent), source excerpt/labels, sanitized article HTML
and its retrieval time. Read revision, saved state and reminder state are local.
No numeric student identifier, SSO cookie or academic snapshot is part of this store.

The canonical identity is the observed source permalink. Cross-source records are
never destructively merged. Display groups use equal publication dates and equal
normalized titles (lowercase, Vietnamese diacritic folding, punctuation/whitespace
normalization). Every variant remains accessible, with a warning that content may
differ. Similar but unequal titles remain separate to avoid dropping corrections.

Topic labels are deterministic suggestions based on normalized source titles and
labels: scholarship, tuition, course registration, or other. They neither establish
eligibility nor suppress the default All view. The implementation does not infer
student cohort eligibility. Exact source deadline/event statements remain in the
article; no regex-extracted date is presented as a verified deadline. A future date
extraction consumer must retain the supporting statement and its applicability.

## Synchronization and correction handling

1. Initialize encrypted device-local storage. No account login is required.
2. For each enabled source, read the first page and one overlap page if advertised.
   Source templates and content types must match. Empty/unrecognized HTML is an
   error, not permission to clear good cached data.
3. Merge by permalink. Preserve saved state, read revision and reminders. Failed
   sources retain their last successful data and show their own error/freshness.
   If only the overlap page fails, retain the successfully fetched latest page and
   show the source as partially failed; an archive timeout must not hide fresh news.
4. Revalidate up to eight previously opened bodies per source, choosing the oldest
   retrieval times first. This rotates through saved/opened articles, including
   articles no longer in the first pages. It can detect body-only deadline changes.
   Unopened article bodies are loaded on demand; their body-only edits are not
   claimed to be monitored in the background.
5. Compare SHA-256 of title, publication date, excerpt and sanitized body with the
   last read revision. Changed content becomes unread. Downloading a list never
   marks its articles read. Successfully opening the native article does.
6. Establish each source's first successful sync as a silent baseline. Re-enabling
   a source establishes another silent baseline. When alerts are enabled, later
   discoveries qualify only if their publication date is at least the previous
   successful sync date minus one Vietnam-calendar day. This prevents archive and
   pinned backfill from producing new-news alerts. An existing record correction
   qualifies regardless of its original publication date.
7. Suppress new cross-post alerts for an already known title/date group. Pending
   deliveries are persisted and grouped. Permission/channel denial leaves pending
   delivery intact. Successful delivery acknowledges it without marking the article
   read. A crash between Android notification delivery and durable acknowledgement
   can repeat the same stable notification ID; exactly-once delivery is not claimed.

Refresh has a bounded overlap, not an unlimited crawl. **Older articles** paginates
explicitly per source and does not create new-news alerts. A long offline interval
or a source publishing more than the overlap window can leave a gap; this is not a
complete school-wide historical archive. The interface exposes the checked time,
source errors and access to the original. No announcement changes an official
personal schedule, registers a course, applies for a scholarship or pays tuition.

## Reminders and Android scheduling

New-article alerts are opt-in. Android notification permission and channel settings
also apply. WorkManager schedules connected source checks at a 30-minute interval.
A separate unconstrained 15-minute pass handles saved local reminders offline.
These are inexact background jobs; Doze, force-stop, permissions and manufacturer
battery settings can delay delivery. This is polling with local notifications, not
server push or an exact alarm.

Reminders require an explicit Vietnam-time `YYYY-MM-DD HH:mm` entry, in the future
and within 366 days. They are the student's chosen times, not university-verified
deadlines. Disable a source to stop its news polling; an explicitly saved reminder
continues until removed. Reminder delivery is recorded separately from article read
state. Opening a notification routes into the announcement reader.

## Storage, failures and security

- Dedicated public namespace using existing encrypted private storage. Local read,
  saved and reminder preferences are device-wide across SSO sign-ins; UI discloses
  this and offers confirmed deletion/reset. They never transfer to UIT.
- Up to 800 retained metadata records, prioritizing saved and undelivered-reminder items.
  Up to 100 saved items and 100 undelivered reminders. Article bodies are retained for the most recently opened
  40 plus saved items, with a 128,000-character source-body cap. The semantic reader
  exposes an omission notice beyond its smaller rendering budget.
- Source lists and article responses are capped at 2 MiB; supported image responses
  at 3 MiB, decoded dimensions at 4096 per axis and 8 million pixels.
- HTTPS only, source-origin checks, bounded same-origin redirects, blocked account
  routes, no cookie/auth plugins, no HTML execution or form submission. Image reads
  are restricted to verified source upload paths and the public Portal attachment route. External links open only after
  an explicit user action. TLS verification remains enabled.
- A generation fence discards network results after source reset/configuration.
  Persistence failures are visible and do not claim that an action was saved.
- Public article dates and labels are preserved, not fabricated. Missing dates stay
  absent. No AI API or backend is required.

## Validation and review evidence

Parser tests cover real public DOM fragments for all six adapters, streamed Portal
HTML, pagination, native formatting/table/image/attachment extraction, unknown
layouts, unsafe URLs, anonymous transport and response limits. Repository tests
cover silent baselines, source failures, cross-posts/backfill, persistence, offline
reads, revisions and resets during requests. Compose tests exercise filters,
restoration, controls and invalid reminder input. Notification tests exercise the
Android permission/delivery boundary.

Run from the repository with JDK 21 and an Android SDK configured:

```sh
./gradlew :core:network:daa:test :core:data:testDebugUnitTest --tests '*Announcement*'
./gradlew :app:testDebugUnitTest --tests '*AnnouncementsDesignTest' --tests '*AnnouncementNotificationTest'
./gradlew :core:network:daa:researchAnnouncements
./gradlew :app:testDebugUnitTest --tests '*AnnouncementsLiveDesignTest' -PannouncementsLive=true
```

Live captures use actual public UIT responses through production code, at 360/390/
412dp including 1.3 font scale. They are **host-rendered native Compose under
Robolectric**, not proof of physical-phone execution, Android background timing or
OEM notification delivery. Runtime/source provenance accompanies the gallery.
Physical-device notification/Doze acceptance remains a follow-up check.

Reviewed phone captures and validation counts: [real-data gallery](design/ANNOUNCEMENTS.md).

During final live verification, Portal temporarily timed out while streaming its
public list. A later production-client retry succeeded. This was not replaced with
synthetic success data; it motivated the overlap-page partial-failure regression
case and is distinct from the repeatable offline suite.
