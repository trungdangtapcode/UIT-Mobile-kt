# UIT Forum web parity: research and implementation contract

**Audit follow-up (2026-11-03):** [The baseline audit](UIT-FORUM-PARITY-AUDIT-2026-11-03.md)
found six concrete defects. The [fix and regression report](UIT-FORUM-AUDIT-FIXES-2026-11-03.md)
records encrypted local drafts, bidirectional chat paging, poll bounds/source
position, typed bookmarks and chat scroll fixes. Full web parity remains incomplete;
the baseline counterexamples and broader workflow gaps remain documented.

Research date: 2026-11-03, Asia/Tokyo. App baseline: `1417d5c` on
`feat/uit-forum-reader`, PR #78. This document changes the target from an initial
public reader to the full student-facing UIT Forum experience. It does not claim
that the missing functionality has been implemented.

Current status includes the partial implementation on draft PR #79, stacked on
#78. [Forum parity progress](../FORUM-PARITY-PROGRESS.md) records changed behavior,
real-data evidence and remaining acceptance gaps. Unchanged rows retain their
explicit research-baseline status; no row is certified as full parity.

## What full parity means

A student should be able to complete the workflows available to that same account
on UIT's mobile website, with equivalent content, permissions and resulting state.
The layout remains native and phone-first; parity is not a desktop layout clone.
Account- or category-dependent features must follow the actual server decision.
Staff/admin tools are not presumed available to a student, and upstream features
disabled at UIT are recorded separately rather than silently added or forgotten.

The original #39 describes a smaller public-reader milestone. Its exclusions are
historical staging decisions, not the user's final product scope. PR #78 is an
initial increment. Completing or merging it cannot establish full web parity.
Do not quote a completion percentage without a complete, reviewed denominator.
Unknown workflows and temporary browser handoffs remain open gaps.

## Direct UIT observations

The [aggregate survey](evidence/uit-forum-parity-survey.json) records route status,
field names, counts and selected public settings, without raw post/account bodies.
Public JSON and an actual 390 × 844 mobile browser page were inspected. This pass
used no credentials; all non-read browser requests were blocked. Earlier private
website login verification is documented in [FORUM.md](../FORUM.md), and did not establish authenticated app support at that baseline. The subsequent
[native account verification](../FORUM-ACCOUNTS.md) now verifies Kotlin login and
read-only account screens separately. Public reads can still affect server logs
and view counters. There was no recursive crawl or mutation testing.

| Observed source | Finding | Consequence |
| --- | --- | --- |
| Mobile website generator | Discourse `2026.10.0-latest`, revision `b81bee142d364526b432788251986871952b5ddb` | Compare relevant upstream code at this revision; current upstream main can differ |
| `/site.json` | 38 categories; filters advertise latest, unread, new, unseen, top, read, posted, bookmarks, hot and votes | The app's four tabs are not equivalent coverage; advertised filters still require permission/behavior checks |
| `/tags.json` | HTTP 200, 3,386 tags | Tag discovery/filtering must scale and be server-driven |
| `/top.json?period=weekly` | HTTP 200, 23 topics in this response | Ranking and time-range flows exist beyond Latest; counts are observations only |
| `/t/164929.json` | `cooked` HTML, no `raw` in the first returned post; image/lightbox markup and revision metadata | Preserve rendered structure; do not assume a plain Markdown body or flatten everything into strings |
| Official announcement image | HTTP 200, JPEG, 54,191 bytes; mobile web exposes a lightbox | Fetching a thumbnail is only one part of image parity |
| `/posts/453770/revisions/2.json` | HTTP 200 anonymously; inline, side-by-side HTML and side-by-side Markdown diffs | Revision reading is feasible for this permitted post; hidden revisions and other posts require separate authorization checks |
| `/t/164929.md` | 301 to canonical same-origin topic slug; canonical URL returns HTTP 200 `text/markdown` | Markdown export exists here; this does not prove that a Markdown-only renderer reproduces the website |
| Public client settings | Chat, polls, reactions, solved, tagging, topic voting and Markdown endpoints enabled | These are concrete discovery tracks, not hypothetical upstream extras |
| Public client settings | Math disabled; Mermaid not verified | Do not claim either works at UIT; recheck when settings/content change |
| Public client settings | `enable_discourse_connect=false`, `enable_local_logins=true`; zero entries in `auth_providers` | Do not infer a standard DiscourseConnect/OAuth flow or infer that university login is unavailable |

Settings were read from the live browser's existing site-settings service;
this inspection is a research technique, not a proposed stable app API. The
reaction setting currently advertises six choices. Do not embed that list in the
app: availability and permitted actions remain dynamic. A setting enabled at the
site level does not prove access to any particular chat, poll or category.

## Source and evidence vocabulary

- **UIT observed:** a specific real route, setting or mobile UI behavior was read.
- **Upstream documented:** supported by official Discourse documentation/code;
  UIT availability or account behavior still needs verification.
- **Unverified:** no sufficient real example or permitted test yet.
- **Partial:** some app behavior exists, but the full acceptance target does not.
- **Missing:** no corresponding app flow/contract exists at the baseline.

No row below is certified as full parity. A server setting is weaker evidence than
a tested end-to-end account flow. The initial survey is deliberately bounded;
it is not a complete inventory of every UIT plugin or account role.

## Parity matrix

### Discovery and topic navigation

| ID | Web capability and evidence | Current app | Required acceptance evidence |
| --- | --- | --- | --- |
| F01 | Latest/category feeds; UIT observed | Partial: explicit pagination, discovered parent categories | Same topic order and metadata; pinned/closed/archived/unlisted states, duplicate handling and end-of-list behavior |
| F02 | Top/time period, hot and votes filters; UIT advertised, weekly Top observed | Partial: advertised guest Top/periods, Hot and Votes; live UIT and monthly mobile-web comparison | Verify supported periods/order and category combinations against actual responses |
| F03 | New/unread/unseen/read/posted; UIT advertised, authenticated behavior unverified | Partial: server-advertised authenticated lists; guest filtering enforced; live account feed verified | Same account sees consistent lists/counts; research does not reset or mark them read |
| F04 | Tags and tag combinations; UIT tags observed | Partial: searchable server tag directory and single-tag feeds; multi-tag/category combinations remain | Search a large tag catalog, apply permitted combinations, handle no matches and navigation restoration |
| F05 | Full search and exact matched replies; UIT observed | Partial: submitted query and post jump | Search scopes/filters/order, pagination, excerpts, Unicode and restricted-result handling match web |
| F06 | Long topics and timeline; real 7,455-ID stream verified earlier | Partial: numbered jump and bounded windows | Timeline/date navigation, gaps, edits/deletions during loading and stable scroll anchors across refresh |
| F07 | Reply context/backlinks; fields exposed by UIT and upstream serializer | Partial: reply-parent and attributed quote targets; no inline reply expansion or return anchor | Open parent/child replies, return to the same position and preserve attribution |
| F08 | Permalinks/share/navigation; mobile web observed | Partial: some topic links route internally | Exact post/slug/fragment targets, Android links/back stack, copy/share, inaccessible and legacy redirects |
| F09 | Topic summary, participants, statistics and suggested topics; upstream documented, UIT coverage incomplete | Partial: title/category/post count | Inventory actual UIT controls; preserve meaningful metadata, suggestions and return navigation |

### Content and media fidelity

Discourse supports Markdown plus selected HTML/BBCode constructs, rather than a
single plain Markdown dialect. Its [`raw` and `cooked` distinction](https://meta.discourse.org/t/is-there-a-way-to-convert-cooked-content-back-to-markdown/322279)
and [formatting guide](https://meta.discourse.org/t/formatting-posts-using-markdown-bbcode-and-html/239348)
explain why a generic Markdown converter is not sufficient evidence of fidelity.

| ID | Web capability and evidence | Current app | Required acceptance evidence |
| --- | --- | --- | --- |
| F10 | Inline emphasis/code, paragraph breaks and links; upstream documented | Partial: semantic inline spans and link ranges; broader fidelity/selection cases remain | Preserve ordering and nested spans, link ranges, Unicode, line breaks and selection/copy |
| F11 | Headings, ordered/nested lists and tables; upstream documented | Partial: nested list and table-cell blocks; complex headers/spans remain | Mixed cell/list content, link/image children, headers, numbering and horizontal scrolling |
| F12 | Attributed/nested quotes and reply references; upstream documented | Partial: nested quote content, author/target attribution; context navigation remains | Preserve author, target post and nested content; expand context without losing position |
| F13 | Details, spoilers and task-like controls; upstream documented, UIT examples still needed | Partial: nested details and concealed spoilers; task controls and broader live examples remain | Keep summaries and nested media; concealed content must remain concealed until requested |
| F14 | Code language, syntax and copy; upstream documented | Partial: preserved code, language label and copy; no syntax highlighting | Preserve exact whitespace/source, language metadata, long lines and copy behavior |
| F15 | Emoji, mentions, user cards and flair; UIT fields/upstream documented | Partial: emoji alt text and author string | Custom emoji and mention targets, accessible labels, account-visible profile fields and identity attribution |
| F16 | Image thumbnails/originals/lightbox; UIT observed | Partial: actual original URL, fullscreen, zoom/pan and accessible controls; gallery/orientation coverage remains | Actual original vs thumbnail mapping, fullscreen, zoom/pan, multiple images, captions, orientation and accessible controls |
| F17 | Image delivery and lifecycle | Partial: explicit retry and bounded decode; broader delivery/lifecycle/account cases remain | Inspect CDN/short URLs/secure uploads; bounded decode, retry/cancel, memory eviction and account isolation; no arbitrary credential forwarding |
| F18 | Animated images/video/audio; upstream documented, UIT examples unverified | Missing animation/playback; browser fallback | Same content with playback controls, lifecycle, captions where available and explicit unavailable states |
| F19 | Attachments and onebox previews; upstream documented | Partial: extracted links | Names, type/size, authorized download/open, expired access, cancellation and safe rich preview semantics |
| F20 | Legacy migrated content; BBCode visible in real support posts | Partial: literal tags can remain | Compare the same post on web; preserve content and distinguish upstream migration defects from app defects |
| F21 | Math/diagrams/plugin-generated blocks | Math disabled in observed UIT settings; Mermaid unverified; no app renderer | Recheck capabilities; preserve unsupported content with a visible gap, never silently remove meaningful SVG/canvas content |

### Revisions, permissions and sessions

The [post serializer at UIT's advertised upstream revision](https://github.com/discourse/discourse/blob/b81bee142d364526b432788251986871952b5ddb/app/serializers/post_serializer.rb)
exposes edit/history/reply/action metadata. The app retains permissions, history and participation metadata in `ForumPost`;
this is still only part of the full web contract. The corresponding [post controller](https://github.com/discourse/discourse/blob/b81bee142d364526b432788251986871952b5ddb/app/controllers/posts_controller.rb)
applies visibility checks to revision access. Do not infer permission from a
version number or from another post's successful response.

| ID | Web capability and evidence | Current app | Required acceptance evidence |
| --- | --- | --- | --- |
| F22 | Edit indicator/history; real UIT revision observed | Partial: uncached permission-aware history, revision navigation and Changes/Before/After; metadata/media diffs remain | Version, time, reason, previous/next navigation, readable diff, hidden history and permission changes |
| F23 | Edit/delete/restore/wiki and moderation notices; upstream documented | Partial: permitted raw edit and original-text conflict; delete/restore/wiki remain | Server-authorized state, conflicts and tombstones; test writes only in an authorized test environment |
| F24 | UIT login/session; prior private website login verified | Partial: native CSRF/session login (UIT 204), encrypted cookie restore and live read-only Compose acceptance; installed Android remains | Research actual UIT redirects/CSRF/session contract, expiry, cancellation, logout and re-login on Android |
| F25 | Per-user app credentials; official User API key mechanism documented, UIT eligibility unverified | Missing | Determine whether UIT allows applicable scopes, groups and callback; no key grant during read-only research |
| F26 | Account/role/category restrictions; permissions exposed by server | Partial: separate member repository, permissions and expiry handling; private content excluded from guest cache | Distinguish guest vs expired vs forbidden; private content never enters guest cache or another account's UI |
| F27 | Revoked access, logout and account switching | Partial: local disconnect, generations and private UI purge; remote session revocation/device lifecycle remain | Cancel in-flight requests and purge/partition private text, media, revisions and pending operations; no stale-content flash |

The [User API key specification](https://meta.discourse.org/t/user-api-keys-specification/48536)
describes a user-consented, scoped application credential flow. It is a candidate
to investigate, not proof of UIT support and not authorization to create a key.
Never ship an administrator API key or substitute Portal cookies for a verified
Forum session contract.

### Personal state and participation

The [official user guide](https://meta.discourse.org/t/understanding-discourse-for-new-users/96331)
documents discovery, replies, actions and notification settings. These workflows
are part of the target when available to the UIT student; the current guest cache
does not replace server account state.

| ID | Web capability and evidence | Current app | Required acceptance evidence |
| --- | --- | --- | --- |
| F28 | Account bookmarks/reminders; upstream documented | Partial: server bookmark list/add/remove and displayed reminder time; reminder editing remains | Post/topic targets, reminder metadata, synchronization and explicit distinction from device-only saves |
| F29 | Read position and cross-device unread state; upstream documented | Partial: local/session anchors and server last-read resume; no timing/read writes or cross-device write sync | Research timings/read side effects; reconcile server progress without marking content read during research |
| F30 | Notifications and account menu; upstream documented, prior authenticated header observed | Partial: native paged notices, real badge titles, read state and topic links; broader type targets/read actions remain | Read-only list discovery first; type-specific targets, paging, read/unread semantics, logout isolation and deep links |
| F31 | Watching/tracking/muting by topic/category/tag; upstream documented | Missing | Server options and effective state, permission errors and separately authorized state-change tests |
| F32 | User profiles/activity, preferences, groups/badges; upstream documented | Author text only | Inventory permitted fields and tabs; privacy, navigation and separately authorized preference changes |
| F33 | Composer, new topic/reply/quote/mention; reply control observed on UIT | Partial: native raw composer, permitted category selection, new/reply/edit; preview/uploads/quote/mention helpers remain | Native phone editing, category rules, preview, keyboard/IME, length limits and server validation |
| F34 | Drafts, uploads and interrupted submission; upstream documented | Partial: ambiguous writes are not replayed; text retained on rejection/conflict; one encrypted account-bound local draft with recovery and confirmed discard; server drafts and uploads remain | Local vs server drafts, attachment progress, conflict recovery and ambiguous timeout handling without duplicate posts |
| F35 | Likes and reactions; UIT setting/action metadata observed | Partial: native server choices/counts/permissions and local Kotlin like/reaction round trips; real UIT writes intentionally untested | Server-provided choices/counts/permissions; no reactions on the real university account during tests |
| F36 | Flags/reporting and own-post actions; UIT action types advertised | Missing | Available reasons, eligibility, result/error handling; no live reports or deletion as test cleanup |
| F37 | Personal messages/invitations; upstream documented, UIT account eligibility unverified | Missing | Verify permitted workflows without reading unrelated private conversations; test delivery only in a dedicated environment |

### Enabled extensions and operational behavior

| ID | Web capability and evidence | Current app | Required acceptance evidence |
| --- | --- | --- | --- |
| F38 | Polls: UIT setting enabled; [official poll behavior](https://meta.discourse.org/t/creating-and-managing-polls/77548) | Partial: native choices, own votes, hidden-result handling and regular/multiple voting; advanced types/results/fidelity remain | Find permitted real examples; preserve choices, visibility/closed state and result rules; no research votes |
| F39 | Solved: UIT setting enabled; [official Solved behavior](https://meta.discourse.org/t/discourse-solved/30155) | Missing | Category eligibility, accepted-answer attribution/link and authorized accept/unaccept flow |
| F40 | Topic voting: UIT setting/filter advertised | Missing | Distinguish topic votes from post reactions/poll votes; inspect category eligibility, limits and existing vote state |
| F41 | Chat: UIT setting enabled; [official chat behavior](https://meta.discourse.org/t/exploring-features-in-discourse-chat/239695) | Partial: existing channels/DMs and threads, latest/older history and explicit send; no new conversation/thread, message management or realtime yet | Separate channel/thread/DM model, permissions, history and reconnect; typing/read/send events are not safe research writes |
| F42 | Live updates and notifications | Missing | Investigate UIT message-bus/event contracts, deduplication, edits/deletions, app backgrounding and battery; never equate blocked polling with verified real-time support |
| F43 | Offline, stale data and recovery | Partial: bounded guest snapshots | Show age/coverage; distinguish stale content from current permission; no unauthorized replay of queued writes |
| F44 | Accessibility and phone behavior | Partial: limited phone tests | TalkBack semantics, 360/390/412dp, larger text, contrast, image descriptions, IME, rotation/process death and scroll restoration |
| F45 | Rate limits, payload limits and server changes | Partial: size bounds and retry states | Slow/unreliable networks, 429/5xx/expired sessions, schema drift and visible truncation; no silent fake success |
| F46 | Broader UIT themes/plugins/custom navigation | Unverified beyond bounded inspection | Inventory authenticated mobile menus and actual content examples before declaring discovery complete |

Reactions are [configurable in Discourse](https://meta.discourse.org/t/discourse-reactions/183261),
so the observed UIT list is evidence, not a constant. [Math](https://meta.discourse.org/t/discourse-math/65770)
and [Mermaid](https://meta.discourse.org/t/discourse-mermaid/218242) have their own
rendering behavior and configuration. Their upstream availability is not a
substitute for checking UIT. Chat, poll, reaction and solved settings being enabled
still leave their category/account-specific workflows unverified.

## Architecture changes required before claiming fidelity

1. **Keep structure through the parser.** Replace the flat text-centric block
   contract incrementally with a typed content tree and inline spans. Preserve
   order, nested quotes/details/lists/tables, link targets and concealed content.
   Sanitize attributes and URLs without destroying meaning. Use `cooked` as the
   reading source and `raw` where verified for editing; do not blindly round-trip
   cooked HTML through generic Markdown.
2. **Separate post identity, capability and revision state.** Retain version,
   edit metadata, reply references and server permissions. Sanitize revision diff
   HTML as untrusted input; never execute it. Cache by origin/account/post/version
   and invalidate on visibility or session changes.
3. **Model media explicitly.** Store original/preview URLs, dimensions, captions,
   authorization scope and delivery type. Decode safely without mistaking a small
   thumbnail for the original. Research real UIT redirects/CDN hosts before
   extending the allowlist. Keep credential handling origin-bound.
4. **Add authenticated contracts without contaminating the guest reader.** Own
   session lifecycle and account namespaces explicitly. Separate pure reads from
   actions, including seemingly passive read receipts and draft autosave. Retry
   reads deliberately; never replay uncertain writes unless the verified server
   contract provides deduplication or the user resolves the outcome.
5. **Discover extensions.** Use capabilities plus a verified resource contract,
   not just a switch that shows an enabled button. Preserve an unsupported block
   and its source while the native implementation remains open in the matrix.
6. **Track equivalence at the interaction level.** A screenshot cannot prove
   fullscreen zoom, revision paging, session recovery or cross-device state. Pair
   real-content comparison with interaction and failure evidence.

Concrete code-audit findings at the baseline: `ForumParser` flattens inline
styles, quotes, details and table cells; media nested in some containers can be
lost. Spoiler concealment is not modeled. `ForumImage` uses a static bitmap and
does not reset its requested state after failure, leaving no in-place retry.
`ForumPost` lacks revision/permission/reply metadata. These are known gaps from
code inspection, not newly executed runtime reproductions. They must be tested
with relevant real content when fixed.

## Delivery order and acceptance gates

| Increment | Matrix rows | Concrete review gate |
| --- | --- | --- |
| Content foundation | F10–F15, F20–F21 | Same real UIT post on mobile web and app; nested structure, concealment and unsupported content compared; no synthetic review screenshots |
| Media reader | F16–F19 | Real UIT media loaded in app, original/fullscreen/zoom/retry behavior recorded, malicious/oversized cases exercised separately |
| Revisions and context | F07–F08, F22 | Actual permitted UIT edit history and reply target; denied/hidden revision cases in controlled tests |
| Complete discovery | F01–F06, F09 | Real tags/filters/search and long-topic behavior, with account-only filters clearly gated |
| Authentication and isolation | F24–F27 | Proven UIT login/callback/session contract on Android, expiry/logout and account boundary tests; no admin keys |
| Account state | F28–F32, F42–F43 | Permission-aware reads first; synchronization/read receipt writes verified only in the authorized test environment |
| Participation | F23, F33–F37 | Controlled create/edit/upload/error/duplicate tests; no university-account mutations for evidence |
| Extensions and full audit | F38–F41, F44–F46 | Actual UIT eligibility mapped; controlled interactive coverage, accessibility and device checks; no unresolved matrix rows hidden as complete |

This order is an engineering proposal, not permission to remove later increments
from the target. Each PR must report the rows it changes and the rows it leaves
open. Browser fallbacks count as disclosed temporary gaps, not completed parity.

For every row being completed, record: source URL/post/version and capture time;
account mode and permission; expected mobile-web behavior; actual app behavior;
tested viewport/runtime; a real-data capture or interaction trace; failure cases;
and remaining differences. Public screenshots must be inspected for personal data.
If a meaningful state has no safe real example yet, retain the gap and use fixtures
only for automated contract tests, never fabricate screenshot evidence.

Research on the owner's university account remains read-only, apart from already
authorized necessary login. Do not open controls that automatically mark messages
read, grant application credentials or autosave drafts without understanding the
side effect. No posting, reacting, voting, flags, settings changes or test cleanup
deletions on that account. Dedicated staging/test accounts are required for those
acceptance cases unless the user separately authorizes a concrete test action.

## Remaining research before full implementation

- Inventory authenticated mobile navigation and per-category restrictions using
  permitted, narrowly scoped observations; this pass did not do that full audit.
- Verify the exact Forum login integration and whether scoped user API keys are
  available. The observed public setting excludes assuming DiscourseConnect.
- Find permitted real examples of polls, solved answers, voting categories, rich
  nested content, videos and attachments. Enabled settings alone are insufficient.
- Inspect notification/chat synchronization and implicit tracking without sending
  read receipts, typing events, messages or changing subscriptions.
- Establish a controlled mutation test environment. Until then, posting and
  account-state write parity cannot be certified on the owner's real account.
- Revisit the bounded stream/cache/media limits with measured phone behavior.
  A protective limit must produce a truthful state, never silently truncate into
  an apparently complete topic.

## Native member contract update (2026-11-03)

The [native account increment](../FORUM-ACCOUNTS.md) implements the typed session,
personal lists, basic mutations and channel/thread chat. UIT login returned 204
and issued Secure host-scoped Forum cookies; the production Kotlin client then
verified the authorized identity. Same university credentials are supported;
Portal/Moodle session sharing is not. Public settings still do not establish a
standard DiscourseConnect flow.

Pinned upstream sources consulted for the implementation include
[`SessionsController`](https://github.com/discourse/discourse/blob/b81bee142d364526b432788251986871952b5ddb/app/controllers/session_controller.rb),
[`PostsController`](https://github.com/discourse/discourse/blob/b81bee142d364526b432788251986871952b5ddb/app/controllers/posts_controller.rb),
[`NotificationSerializer`](https://github.com/discourse/discourse/blob/b81bee142d364526b432788251986871952b5ddb/app/serializers/notification_serializer.rb),
[`CreateMessage`](https://github.com/discourse/discourse/blob/b81bee142d364526b432788251986871952b5ddb/plugins/chat/app/services/chat/create_message.rb)
and [`ListChannelThreadMessages`](https://github.com/discourse/discourse/blob/b81bee142d364526b432788251986871952b5ddb/plugins/chat/app/services/chat/list_channel_thread_messages.rb).
The local Kotlin acceptance suite also exercises enabled polls/reactions at that
revision. Actual UIT badge-notification field shapes were inspected read-only to
correct the topic-title-only assumption. No production content/action writes were
used to prove the mutation contracts.

These are partial rows. Host-rendered login/account reads and local-server writes
are different acceptance evidence; neither proves full web parity or installed
Android behavior. Full remaining gaps stay in the matrix and draft PR #79.

## Public AI context increment (#51)

The public topic-window connector and source-backed digest are documented in
[Forum AI](../FORUM-AI.md). They reuse the general assistant and preserve selected
post identity, authorship, coverage and retained versions. This is an AI reading
capability, not completion of any missing account, mutation, synchronization or
full-web-parity row. Original-post navigation uses explicit anonymous citation
mode; assistant reads have no tracking or account-write capability.
