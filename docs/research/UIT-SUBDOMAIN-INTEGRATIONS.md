# UIT subdomain integration opportunities

Research date: October 29, 2026.

Publication note: these are observations and integration proposals from the
research date. They do not establish current availability or implementation
status; consult the [issue tracker](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues)
before claiming work or extending an existing integration.

This investigation used public pages and a small number of anonymous HTTPS GET
requests. No student credentials, authenticated cookies, submissions, messages,
likes, renewals, registrations, payments or lab instances were used or changed.
It is a targeted integration survey, not an exhaustive subdomain inventory.

## Recommendation

Prioritize a native, read-only **UIT Forum** reader. Anonymous JSON endpoints
already expose useful content, making a first version possible without extending
the current SSO flow. Next investigate **conduct scores**, **room schedules** and
**official announcement aggregation**. Treat library and vLab authentication as
separate investigations.

The app's existing Moodle course discussion support is not an integration with
`forum.uit.edu.vn`; these are different services.

## Candidate services

| Host or page | Observed evidence | Useful Android integration | Authentication status | Priority |
| --- | --- | --- | --- | --- |
| `forum.uit.edu.vn` | Discourse pages, anonymous JSON lists/search/topic responses and RSS were read successfully. | Native topic lists, categories, search, topic reader, local bookmarks and new-reply checks. | Public reading verified. Official account guidance exists; shared browser SSO and authenticated capabilities remain unverified. | First |
| `drl.uit.edu.vn` | Login page explicitly asks for the university authentication account. | Semester conduct scores and criterion breakdowns, followed by optional comparison with personal activity records. | Separate Drupal login form observed; existing OIDC cookie reuse not established. | High, after read-only authentication research |
| `banqlcs.uit.edu.vn` and `portal.uit.edu.vn/lich-phong` | Facilities page links directly to the Portal room schedule. The Portal page exposes rooms, capacities and dated events without login. | Room/day search, reported occupancy and timetable-room cross-links. | Public page verified; normalized data extraction still needs implementation research. | High |
| `ctsv.uit.edu.vn` | Public scholarship, student-service and policy announcements. | Filtered student notices and official instructions linked to academic views. | Public feed does not need an additional student session. | High |
| `oep.uit.edu.vn` | Public exam, make-up, program and scholarship announcements; university-account login form. | Program-specific announcements and schedule notices matched to actual class codes. | Public content verified; authenticated coverage and SSO reuse unverified. | High for relevant programs |
| `tuoitre.uit.edu.vn` | Current youth/student-union notices and research competition announcements. | Activity discovery, event dates and saved official registration links. | Public reading verified. A listed event does not establish conduct-score credit. | Medium |
| `thuvien.uit.edu.vn` | Official resource links, subject collections, OPAC links and library-account instructions. | Course-related catalog search, resources, library guide; later loan due-date reminders. | Library instructions describe a separate identity and link to `opac.vnulib.edu.vn`. UIT OIDC reuse is not established. | Medium |
| `khcn.uit.edu.vn` | Public research calls, research groups, forms and funding notices. | Research opportunity filters, saved calls and deadline reminders. | Public reading verified; application operations unresearched. | Medium |
| Faculty sites: `cnpm.uit.edu.vn`, `httt.uit.edu.vn`, `cntt.uit.edu.vn` / `ise.uit.edu.vn` | Faculty sources expose academic, opportunity, advisor or research content. `cntt.uit.edu.vn` redirected to `ise.uit.edu.vn/en` in this check. | Faculty-specific notices, internship opportunities and advisor/help directories. | Public content; canonical URLs and parsers must be checked per source. | Medium |
| `vlab.uit.edu.vn` | Public landing page describes virtual practice environments and use of UIT credentials. | Initially a verified service link and help; later read-only course/lab status if supported. | UIT credentials documented; SSO protocol, authorized API and account entitlements unverified. | Exploratory |
| `survey.uit.edu.vn` | Public survey index includes course-completion and employer surveys. | Show official survey links and verified deadlines from announcements. | Survey-specific access not tested; no answers were submitted. | Low |
| `alumni.uit.edu.vn` | Public alumni news, a scholarship-fund section and a mentor link to Forum. | Alumni/mentor opportunity links and relevant career events. | Public reading verified; no member-directory API established. | Low |
| `dkhp.uit.edu.vn` | Current client application is reachable; official announcements describe course registration windows. | A local semester-planning companion and official registration links. | No authenticated reads or registration actions tested. | Link/planning only until separately researched |

The faculties are a source family, not a promise that all sites share a platform.
Several direct browser-tool requests timed out, even where indexed official pages
were available. A timeout does not prove that a service is retired.

## Forum: concrete feasibility findings

The root page identifies Discourse, and the public categories page links to
Discourse. The following anonymous responses were checked directly with normal
certificate validation. Counts describe the observed response, not stable limits.

| GET path | Observed status | Finding |
| --- | --- | --- |
| `/latest.json` | 200 | JSON `topic_list`, with 30 topics in the inspected response and fields including category, tags, timestamps and reply counts. |
| `/categories.json` | 200 | JSON `category_list` for public category navigation. |
| `/site.json` | 200 | Site metadata and category records; this is larger than necessary for routine refreshes. |
| `/search.json?q=uit` | 200 | JSON search results, including topics, posts and grouped result metadata. |
| `/t/162923.json` | 200 | Topic metadata and `post_stream`; the inspected response included 20 posts and a longer stream of post IDs. Long-topic loading must be handled explicitly. |
| `/latest.rss` | 200 | Public RSS, useful as a simpler feed option. |
| `/notifications.json` | 403 | Anonymous personal notifications were unavailable. |
| `/session/current.json` | 404 | No authenticated identity was established by this anonymous request. |
| `/session/sso` | 404 | This request did not establish DiscourseConnect availability; it does not prove that all other login mechanisms are absent. |

### Useful public sections

Public pages expose dedicated sections for student affairs, IT support, jobs and
internships, scholarships and technical events. Read-only JSON access was checked
separately from those public category pages; exact category coverage and permissions
must be validated when implementing the reader.

- [Student affairs](https://forum.uit.edu.vn/c/cong-dong-uit/phong-cong-tac-sinh-vien/43)
- [IT support](https://forum.uit.edu.vn/c/cong-dong-uit/phong-du-lieu-va-cong-nghe-thong-tin/59)
- [Jobs and internships](https://forum.uit.edu.vn/c/ho-tro-doi-song/viec-lam-thuc-tap/32)
- [Scholarships and support](https://forum.uit.edu.vn/c/ho-tro-doi-song/hoc-bong-giai-thuong-giao-luu/31)

A university notice dated July 6, 2025 says students and staff can sign in using
their university authentication account, while external users can register. This
establishes account guidance, not compatibility with the app's existing Keycloak
session. A shared username/password is not evidence of shared browser SSO.

### Proposed first release

1. Native phone lists for recent topics and selected public categories, with
   pagination, pinned/closed states and a visible saved-data timestamp.
2. Search with direct topic navigation and explicit loading/empty/error states.
3. A sanitized topic reader supporting quotes, images, links, attachments and long
   reply streams. Preserve attribution and the canonical Forum link.
4. Local bookmarks, selected categories and an explicitly local reading position.
   Do not claim they synchronize with the Forum account.
5. Optional checks for new public replies or topics in locally followed content,
   with best-effort Android notification timing. These are not server account
   notifications or remote watch settings.
6. An official-browser action for login and operations not supported by the reader.

Avoid another bottom destination just for this source. A clearly labeled Forum
entry can open a dedicated full-screen phone flow; it should remain distinct from
Moodle course forums. Final navigation needs a phone UI review.

Authenticated notifications, remote bookmarks, watch settings, private messages,
posting, replies, likes and read tracking are a later scope. They need validation
against the actual account protocol and permissions. The app must never contain
a site administrator API key. The [Discourse API documentation](https://docs.discourse.org/)
is a protocol reference; it does not grant credentials or guarantee enabled features
on UIT's instance.

## Room schedules: a useful discovery

The facilities homepage contains a dated daily room-use table and links to
`https://portal.uit.edu.vn/lich-phong` for the weekly view. The public Portal page
provides building/type filters, room capacities and dated schedule records.

Direct HTML inspection contained the week of October 26 to November 1, 2026.
An indexed browser representation showed an earlier week. Treat the selected week
and response freshness as explicit data, rather than trusting a page title such as
"today". No standalone JSON endpoint was established in this pass.

A first feature can answer which recorded events use a room on a selected date.
An unoccupied interval is not proof that students may enter or reserve the room.
Booking rules and submission permissions are a separate question. This service
also exposes more period rows than the app's existing ten-period schedule model;
mapping needs verification rather than truncation.

## Remaining leads and boundaries

- Public IT support discussions mention WeCode and Slurm. A direct browser-tool
  request to `wecode.uit.edu.vn` was unsuccessful; no viable integration contract
  was established. Neither host should be presented as an available native feature.
- Slurm is a compute-service lead, not an ordinary public student-content feed.
  This investigation did not connect to it, submit jobs or provision resources.
- A direct request to `doantn.uit.edu.vn` returned general UIT site content rather
  than a validated youth-service source. Use the verified `tuoitre.uit.edu.vn`
  source instead of treating the alias as another integration.
- Existing Portal tuition, procedure and graduation capabilities are already
  implemented and are not newly discovered independent services.
- Authentication hosts and short-link hosts are infrastructure, not additional
  student features. Follow redirected public links cautiously and keep credentials
  scoped to their validated service origins.

## Sources

- [Forum homepage](https://forum.uit.edu.vn/) and [public categories](https://forum.uit.edu.vn/categories)
- [UIT notice introducing the upgraded Forum](https://ctsv.uit.edu.vn/bai-viet/thong-bao-dua-vao-su-dung-dien-dan-uit-moi)
- [Discourse API documentation](https://docs.discourse.org/)
- [DRL login page](https://drl.uit.edu.vn/)
- [Facilities homepage](https://banqlcs.uit.edu.vn/) and [Portal room schedule](https://portal.uit.edu.vn/lich-phong)
- [Student affairs](https://ctsv.uit.edu.vn/) and [special programs](https://oep.uit.edu.vn/)
- [Youth/student-union site](https://tuoitre.uit.edu.vn/)
- [Library account instructions](https://thuvien.uit.edu.vn/page/tai-khoan-thu-vien)
- [Research office](https://khcn.uit.edu.vn/)
- [Software Engineering opportunities](https://cnpm.uit.edu.vn/vi/tin-t%E1%BB%A9c/h%E1%BB%8Dc-b%E1%BB%95ng-tuy%E1%BB%83n-d%E1%BB%A5ng.html),
  [Information Systems faculty](https://httt.uit.edu.vn/) and [Information Science and Engineering](https://ise.uit.edu.vn/en)
- [vLab](https://vlab.uit.edu.vn/en/) and [UIT's vLab introduction](https://en.uit.edu.vn/vietnam-international-innovation-expo-2023-viie-2023-showcases-uits-virtual-lab-technology)
- [Survey index](https://survey.uit.edu.vn/) and [alumni site](https://alumni.uit.edu.vn/)
- [Course registration client](https://dkhp.uit.edu.vn/)

All findings are observations from the research date, not stable API contracts.
No raw student posts, profile records, cookies or credentials are included here.
