# UMA: official UIT assistant

UMA is a separate native chat destination inside UIT Assistant. It calls the
university's UMA service directly; it does not use the app's OpenAI connection,
API key, ChatGPT subscription, public-announcement tool loop or local RAG index.
The university determines the model, accessible records and answer quality.

## Authentication research

Observed on 2026-11-12 against the actual UMA student website with the owner's
authorized UIT account:

1. An unauthenticated embedded chat is a sign-in prompt, not an authenticated API
   session. Its login entry redirects through `/oidc/authenticate/` to UIT SSO.
2. The identity provider is `https://sso.uit.edu.vn`. The OIDC client is `ums2`,
   with callback `https://uma.uit.edu.vn/oidc/callback/`. The observed authorization
   uses authorization code and an S256 PKCE challenge. State, nonce, callback codes
   and credentials must never be copied into fixtures or public diagnostics.
3. UMA establishes its own `ums2_sessionid` and `ums2_csrftoken` cookies. A Portal
   `ums_portal_session` or Moodle `MoodleSession` is not a UMA session. Shared SSO
   credentials/identity do not mean the relying parties share application cookies.
4. A request also carries `X-CSRFToken`. The hidden `csrfmiddlewaretoken` is a
   masked token; it can differ from the canonical cookie. Django accepts either
   form. Analytics `_ga` cookies and browser priority headers are unnecessary.
5. The app opens the official OIDC login in a temporary restricted WebView. The
   student enters credentials on UIT SSO. Only the two UMA cookies and CSRF token
   enter app code. A fresh native GET of the embedded chat verifies student mode,
   account identity and available source controls before enabling Send.

The browser boundary accepts HTTPS UMA/SSO origins only, rejects SSL errors and
blocks UMA POSTs during login, logout/account changes and external navigation.
The resulting UMA session is independent of the other app account connections;
its actual identity is shown in the chat. Disconnect removes the local session,
not the university's SSO session or server-side logs.

This describes observed authentication, not a claim that UIT publishes a stable
third-party API. Do not infer the provider/model or its backend credentials from
these routes. Neither a Django session nor a CSRF token is an OpenAI API key.

Primary sources:

- [Actual embedded UMA](https://uma.uit.edu.vn/uma/nhung/?site=portal&trang=%2Fsinh-vien%2Ftkb)
- [UMA's current frontend contract](https://uma.uit.edu.vn/static/uma/thu.js)
- [Django AJAX CSRF and masked tokens](https://docs.djangoproject.com/en/5.2/howto/csrf/)
- [Django Origin/Referer validation](https://docs.djangoproject.com/en/5.2/ref/csrf/)

## Native request contract

The network client has exactly two authenticated destinations:

| Operation | Method and route | Purpose |
| --- | --- | --- |
| Bootstrap | GET `/uma/nhung/?site=portal&trang=%2Fsinh-vien%2Ftkb` | Verify session, CSRF, student identity and supported sources |
| Ask | POST `/uma/api/hoi/` | Explicit student question and selected conversational context |

Requests use HTTPS, host-only session cookies, no redirect following and no
application-level automatic retries. Asking may create a UMA server-side question
log (`log_id` in the observed response); this is normal chat behavior, not an
academic record change. There are no feedback, uploads, submission, payment,
registration, message or university-account mutation operations in this client.

The JSON body follows the observed web contract:

- `q`: nonempty question, at most 1,000 characters, matching the web field.
- `goi_y_id`: an explicitly chosen server suggestion ID or null.
- `truoc`: previous successful interpreted question (`cau_hieu`).
- `lich_su`: a **JSON-encoded string**, containing at most three successful prior
  `{q, tl}` pairs. Whitespace in each prior answer is normalized and the answer
  is shortened to 200 characters, matching the official frontend.
- `ho_so=toi`, `cho_duyet=false`, empty `nam`, `domain`, `hoi_nhu` and `tu_khoa_vb`.
  There is no impersonation or administrative profile selector.
- `site=portal`, `trang=/sinh-vien/tkb`: the observed portal embed context, not a
  claim that the native app is currently on a web timetable page.
- `nguon`: supported selected sources discovered from the signed-in student page.
  The native choices are `chung`, `van_ban`, `ca_nhan` and `thong_bao`. Unsupported
  administrative, plagiarism and document-register modes are not requested.

The initial source is General search, as on the website. The app does not
preselect My academic information; the student can explicitly include that source.
UMA ultimately enforces permissions and interprets source selections on its server. Changing the source starts a new in-memory conversation
so earlier personal text is not carried into a narrower scope. New conversations
and disconnect also invalidate in-flight results; a late response cannot repopulate
another conversation. Stop cancels the local HTTP operation and preserves the
question for explicit reuse. It cannot promise to cancel UMA's server computation.

## Answers and phone UX

Intent: a student asks a school-specific question while using the native phone app.
The question/answer is the focal content. Account and source scope remain visible
above it; the composer stays below the scrolling conversation. Use existing Plex
fonts, UIT colors, 16dp gutters, 4dp spacing and asymmetric TechnicalShapes frames.
User messages occupy at most 88% width. UMA answers stay on the reading canvas.

The protocol returns a complete JSON object, not an SSE stream. The UI shows an
honest waiting state, then the actual answer; it never simulates token streaming.
`ket_qua=no_source` is an answer with missing evidence, not a network failure.
The app retains the server's text, interpreted follow-up question, numbered
citations, scope notes, scanned/low-confidence flags, warnings about older
regulations, document/list links and approved/pending follow-up suggestions.
Choosing a suggestion fills the composer; Send remains explicit.

Markdown uses CommonMark with GFM tables, then the existing bounded semantic native
article renderer. Raw HTML is escaped and no JavaScript or remote image is run.
Sources expand below the answer; source/browser links never receive the app's
UMA cookies. A browser may request its own UIT sign-in. See
[CommonMark Java](https://github.com/commonmark/commonmark-java) for its parser and
HTML escaping contract. Native rendering does not constitute independent factual
validation of UMA's citations.

Structured `hien_thi` supports metric cards, label/value rows, horizontally
scrollable tables, nested expandable groups and notes. `lich_tuan` becomes a
phone-friendly date-grouped class list, retaining course, period/time, room,
lecturer and notes. Week navigation fills a server-provided follow-up question.
The original text version remains available. Unknown structured kinds expose a
website fallback; it is not a claim of full UMA web parity. Upload/OCR, QR images,
plagiarism checking and feedback controls remain web features.

## Storage, failures and privacy

The session uses Android Keystore AES-GCM through a separate private `uma`
SharedPreferences file and `uit.uma.v1` key alias. App backup is disabled. It is
outside public external storage and is removed by local disconnect or uninstall.
The password is never stored. Conversations, personal records, drafts and parsed
responses remain in process memory; they are not written to Room, saved-instance
state, an export or provider context. Process termination clears chat history. The on-screen conversation retains the
latest 24 turns to bound memory use; only three completed turns enter a request.

Every response body has a 1 MiB transport limit; text, arrays and display nesting
also have limits. Redirect/login HTML, 401, CSRF/403, rate limits, server failures,
malformed replies and local-storage failures have distinct UI outcomes. Raw
response bodies, account identifiers and credentials do not enter diagnostics.
Question requests are not silently resent after timeout, reconnect or rotation.
Users explicitly decide whether to reuse a failed question.

The UMA service may retain its own question logs. Clearing this app's conversation
or uninstalling cannot delete the university's logs. No retention policy or
underlying model identity is inferred from the response example.

## Verification

Implementation validation and actual-source evidence are recorded separately.
Deterministic fixtures cover protocol/error boundaries; real owner-authorized
checks exercise the production Kotlin network path. Phone screenshots use actual
UMA reply captures, with account identity hidden and provenance stated. Host
Compose/Robolectric is not evidence of physical Android WebView/OEM behavior.

### Observed result

The production Kotlin client completed one bootstrap and two explicit questions
against the owner's real UMA session: all three returned HTTP 200. The regulations
question returned an actual answer with five source citations; the personal GPA
question returned academic records and structured metric/table/group blocks.
Neither OpenAI credentials nor fixture university responses were used. The exact
native OIDC entry also reached the authenticated embedded chat in Chromium; this
is browser-flow evidence, not physical Android WebView acceptance.

See [network evidence](research/evidence/uma-native-live.json), the
[public-source capture](research/evidence/uma-public-reply.json), and
[reviewed phone renders](design/UMA.md). The owner's academic values and transcript
capture remain private. Routine tests skip the explicitly enabled owner probe.

### Local validation

The full local `check`, preview APK assembly and Android instrumentation-test
compilation passed. There are 23 new deterministic checks (14 protocol/repository,
4 restricted-login and 5 Compose interaction tests), plus an explicitly enabled
owner live test and four actual-response rendering methods. The latter cover
360dp, 390dp, 412dp at 1.3× text, and a private personal-record display.

The initial full suite hit the existing localhost-only
`ChatGptWireTest.repeatedBodyFailurePreservesHttpMetadataInsteadOfReportingStatusZero`
timing-sensitive diagnostic assertion. It passed in isolation and on the subsequent
full run, without changing the ChatGPT implementation or weakening the test. A
pre-existing hardcoded-text checker finding in `ForumScreen.kt` is unchanged;
architecture and staged repository-hygiene checks are separate from that finding.
No GitHub Actions run was requested.
