# Plan implementation map

| Plan phase | Implementation |
| --- | --- |
| 1 — Foundation | Version catalog, wrapper, JVM/Android/Compose convention plugins, dependency checks; immutable models, typed errors, URL sanitation, session classification and UIT period/timezone helpers |
| 2 — Moodle | Session AJAX argument normalization and unwrapping; concurrent course discovery across all six timeline buckets; narrow error-classified HTML fallback; sections and four-way resource enrichment; assignments/status/upload/save; forums/replies; grades/participants; monthly calendar; notifications/inbox; H5P activity packages and bounded ZIP inspection; atomic downloads |
| 3 — Academic portals | Current UIT Portal timetable/transcript APIs, server-rendered profile extraction and read-only exam requests; legacy Drupal insert-command extraction; timetable, exam, notice/announcement, transcript and student profile parsers; rowspan handling, Vietnamese header normalization and validated dates/periods |
| 4 — Persistence/auth/domain | Room schema and account-scoped normalized tables plus transactional snapshots; encrypted DataStore session vault; ephemeral unified Keycloak SSO/legacy login; cached repository flows; exact course linking; schedule overlays; deadline reminder and submission-confirmation use cases |
| 5 — Presentation/sync | Material 3 light/dark/dynamic theme; phone bottom navigation; five single-column destinations; MVI ViewModels; date-grouped weekly agenda/monthly calendar; course materials/forums/assignments/grades/participants; file picker and confirmation; native H5P text/media summary; inbox; transcript/GPA projection; constrained WorkManager and persistent alert deduplication |

## Module boundaries

`core:model` has no production library dependencies. `core:domain` depends on
`core:common` (which re-exports the model) and contains contracts and business
rules. Feature modules only depend on domain, common, model or design system.
Networking and HTML parsing are JVM modules and can be tested without an SDK.
Android-specific auth, Room, data and background work are wired with Koin in the
app composition root. The worker shares the same process-scoped graph.

Models use ISO date strings and epoch seconds to keep the model module free of
external dependencies. Date arithmetic is performed with kotlinx-datetime in
common/domain. Persistence uses Room entities and JSON snapshots to publish a
coherent portal state in one transaction. Secrets are stored only in the encrypted
vault; cached links and HTML attributes are sanitized.

## Test coverage

* URL prefix preservation, signed file URL sanitation, session error causes,
  Vietnamese expiry messages, lesson/exam times and invalid compact periods.
* Ktor MockEngine: cross-origin redirects, HTTPS/credential/port checks, login
  responses labelled as PDF, atomic downloads/prefix preservation/checksum,
  same-origin prefix redirects, no write replay, and POST-to-GET confirmation.
* Moodle: nested/sparse bracket arguments and unsafe keys; response validation;
  authentication errors bypassing fallback; concurrent paginated discovery and
  field precedence; verified course context, hidden labels, files, discussion
  IDs/attachments, participant identity, submission errors, bounded H5P ZIPs.
* DAA synthetic fixtures: AJAX timetable, compact period 10, exam shifts/seat
  numbers, cancellations/make-ups, RDFa dates, canonical announcement URLs,
  transcript missing scores and profile fields, expired/unexpected responses.
* Domain: cancellation specificity, make-up injection, exams and deadlines,
  section-safe/ambiguous course linking, reminder windows/deduplication,
  Vietnamese timezone boundaries, weighted GPA and input validation.
* Room/Robolectric/Turbine: cached emission before updates, account isolation,
  transactional normalized-row replacement and deletion.
* Repository/MVI: offline retention and account removal; cached course details
  shown before the network; obsolete loads canceled on course switches;
  unconfirmed submission never reaching the repository.

## Live authentication and remaining validation

Authorized live-browser testing verified Moodle login, reuse of the SSO session
at UIT Portal, and access with each service's own session cookie. Private current
portal captures validated the timetable, transcript and profile production parsers.
See [authentication and Android compatibility](AUTHENTICATION.md) for the current
endpoints and scoped certificate configuration. Credentials and private responses
are not repository fixtures or CI inputs.

The Android instrumented smoke test checks real HTTPS and the public Moodle/SSO
form in API 30 WebView. It does not use student credentials. Remaining checks are:

1. Verify authenticated behavior on individual physical phones and legacy
   undergraduate/graduate Moodle accounts.
2. Validate Moodle write capabilities, upload repository ID, submission forms,
   private messaging permissions and actual H5P packages before using those writes.
3. Exercise document opening, notification permission and background execution on
   physical phones. Host-side Compose previews use synthetic data in the
   [design gallery](design/README.md).

A UI submission saves the draft/file submission and does not silently perform a
separate irreversible final-submit operation. The app explicitly explains this
before confirmation. Calendar actionability and verified submission status remain
separate; absence of an action does not establish that work was submitted.

## Workspace UI verification

The redesigned presentation includes host-side Compose interaction tests and
Robolectric/Roborazzi rendering for phones, larger text and dark mode. See [the design documentation](design/README.md). Inbox ViewModel tests
cover stale conversation responses, preserving drafts after failed sends, and
successful sends completing after the user switches conversations.
