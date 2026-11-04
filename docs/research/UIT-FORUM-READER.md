# Public UIT Forum reader research

This is the initial anonymous-reader investigation. Its exclusions describe that
increment only. The user's full-web-parity requirement and subsequent UIT
capability survey are tracked in [UIT-FORUM-PARITY.md](UIT-FORUM-PARITY.md).

Verified anonymously on 2026-11-03. No university credentials or authenticated
sessions were used. Public GET requests can still affect server access logs and
anonymous view counters; this is not a claim of zero server-side effects.

## Evidence and scope

[UIT Forum](https://forum.uit.edu.vn/) runs Discourse. The official
[UIT rollout notice](https://ctsv.uit.edu.vn/bai-viet/thong-bao-dua-vao-su-dung-dien-dan-uit-moi)
describes university sign-in, but does not establish a supported mobile SSO flow.
The first reader therefore uses a separate anonymous transport. Posting, likes,
notifications, private messages, watching and account bookmarks are excluded.

Public endpoints were checked against actual responses and the upstream
[Discourse API](https://docs.discourse.org/) and
[topic controller](https://github.com/discourse/discourse/blob/main/app/controllers/topics_controller.rb).

| Request | Observed behavior | Reader consequence |
| --- | --- | --- |
| `/latest.json?page=0` | 30 topics, relative HTML `more_topics_url` | Generate the next known JSON route; never follow arbitrary response URLs |
| `/categories.json` | 7 roots | Insufficient for the complete public hierarchy |
| `/site.json` | 38 visible categories with parent IDs, but missing public feed categories 43 and 59 | Preserve hierarchy; exclude restricted categories; metadata is incomplete |
| `/c/43/show.json` | Public metadata, parent 10, unrestricted | Resolve only unknown IDs encountered in feeds, plus their parents; at most 8 metadata requests per load |
| `/c/43.json?page=0` | Public category feed and next cursor | Numeric routes can redirect to canonical slug paths; permit exactly one same-origin JSON rewrite preserving category ID and page |
| `/search.json?q=uit&page=1` | 50 matched posts, topic metadata, more-full-page flag | Preserve exact post targets and explicit paged search |
| `/t/139895.json?track_visit=false` | 20 loaded posts, 7,455 stream IDs | Stream IDs define pagination; post numbers may have gaps |
| `/t/139895/posts.json?post_ids[]=...` | Two requested IDs returned as posts 21 and 22 | Fetch bounded batches, validate ownership and sort by stream order |
| `/t/139895/7000.json?track_visit=false` | 20 posts centered near post 7,000 | Jump without downloading thousands of bodies |

The upstream controller treats a present `track_visit` parameter as truthy; the
string `false` is not a reliable opt-out. Production JSON requests omit that
parameter entirely and have no `current_user`. The research URLs above describe
observed responses, not a tracking guarantee.

Raw live responses remain outside the repository. Counts are observations, not
hardcoded assumptions about future content. No recursive crawling was performed.

## Architecture and limits

`:core:network:forum` owns fixed-origin GET routing, bounded reads, JSON parsing
and HTML-to-native sanitization. `:core:data` owns the independent encrypted
public-content cache and local reading state. `:feature:forum` consumes only the
domain contract. University cookie transports and AI providers are never used.

Automatic redirects are disabled. A category feed may follow one validated canonical
rewrite to the same origin, same numeric category and same page; cross-origin,
HTML, account, repeated and image redirects are rejected.

Responses are limited to 4 MiB, topic streams to 20,000 IDs, fetch batches to 20
IDs and an in-memory reading window to 80 posts. Local saved references are
limited to 100; reading positions to 120. Encrypted response snapshots share a
1,000,000-character fetch-recency budget. Saving a reference does not download a whole topic.
The cache is best-effort: eviction can remove bodies while retaining a saved link.
HTTP 429 exposes a retry delay without automatic retry; 401/403/404/410 are
unavailable, not invitations to forward SSO cookies. Transient failures may use
an explicitly dated cached snapshot. No request is made per keystroke.

HTML is parsed as data. Scripts, forms, frames, CSS and active embeds never run.
Code indentation, quotes, list markers, tables and details survive conversion.
HTTPS links require user action; images are opt-in, fixed-origin uploads with
byte and decode limits. External images use browser handoff. Local saves and
positions never call Discourse bookmark or timing endpoints.

See [phone design](../design/UIT-FORUM.md) for the navigation and reading hierarchy.
