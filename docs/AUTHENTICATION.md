# UIT authentication and Android compatibility

The current sign-in flow uses UIT's Keycloak/OpenID Connect service. Moodle and
the new student portal share the SSO browser session, but each service issues its
own session cookie. The app retains only those service cookies in its encrypted
vault; it never reads the password field or persists Keycloak identity cookies.

1. Open `https://courses.uit.edu.vn/login/index.php` in the temporary WebView.
2. Follow the portal's UIT SSO link and sign in at `sso.uit.edu.vn`.
3. Read Moodle's sesskey and a same-origin profile link with a positive user ID.
   Current Moodle exposes more than one profile link; the first link can omit its
   ID. Inspect all eligible profile links rather than stopping at the first one.
4. Visit `https://portal.uit.edu.vn/api/auth/login` in the same WebView. An active
   SSO session returns to the authenticated student portal without another password.
5. Capture `MoodleSession` for Moodle and `ums_portal_session` for UIT Portal,
   store the two accounts, and destroy the temporary browser session.

If the second service cannot be reached, an explicit **Continue with Moodle only**
action can save the Moodle session. A successful sign-in connects both services.
Loading, timeout, HTTP, TLS and renderer failures have native feedback and a Close
control. Retry starts at a safe login entry rather than replaying a submitted form.

UIT announced migration of the old student services to
[`portal.uit.edu.vn` in July 2026](https://ctsv.uit.edu.vn/bai-viet/thong-bao-ve-viec-chuyen-doi-cong-thong-tin-sinh-vien-portaluiteduvn).
The old Drupal `/user/login` page is not the current portal's SSO entry. Historical
planning assumptions about a silent CAS visit to that page must not drive the
current flow. Legacy DAA snapshots and parsers remain compatible with saved accounts.

## TLS compatibility

During investigation on October 27, 2026, UIT's Moodle, SSO and DAA servers sent
their wildcard leaf certificate and Sectigo DV E36 intermediate, chaining to the
new Sectigo Public Server Authentication Root E46. They did not send its legacy
cross-sign. Some Android system trust stores do not include the new root, leaving
WebView unable to load the page even when a recent desktop browser can.

The application uses Android's declarative
[Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)
to supplement system trust with that public root on exactly five hosts:
`courses.uit.edu.vn`, `coursesold.uit.edu.vn`, `sso.uit.edu.vn`,
`portal.uit.edu.vn` and `daa.uit.edu.vn`. Subdomains and unrelated origins do not
inherit this addition. Cleartext, user-installed CAs and invalid TLS certificates
remain disallowed. The SSL error handler always cancels invalid connections.

The bundled public certificate comes from
[Sectigo's published E46 root](https://crt.sh/?d=4256644603), linked by its
[official certificate directory](https://www.sectigo.com/knowledge-base/detail/Sectigo-Public-Intermediates-and-Roots),
and was matched byte-for-byte against an independently installed Mozilla CA bundle.
Its SHA-256 fingerprint is:

```text
C90F26F0FB1B4018B22227519B5CA2B53E2CA5B3BE5CF18EFE1BEF47380C5383
```

This is a public CA certificate, not a private key or a replacement for hostname,
chain or validity checks. Keep its resource, scoped configuration and fingerprint
regression test together when updating trust material.

## Current academic reads

The academic adapter reads the authenticated student portal endpoints observed
during live testing, retaining the legacy domain models for compatibility:

| Data | Source |
| --- | --- |
| Registered timetable | `GET /api/sinh-vien/tkb/dang-ky` |
| Transcript | `GET /api/sinh-vien/bang-diem` |
| Profile | Server-rendered records in `GET /sinh-vien/ho-so` |
| Exams | Read-only `POST /api/sv/exam-schedule` for midterm/final lists |

Fortnightly timetable rows retain their recurrence interval. Exam clock times take
precedence over numbered shifts. Missing or changed response schemas produce a
typed error and preserve the existing offline snapshot. The profile reader decodes
server-rendered records without executing scripts and keeps only the profile fields
needed by the app. It does not store the full personal-information form.

## Verification

```sh
./gradlew :core:auth:testDebugUnitTest :core:network:daa:test :core:domain:test
./gradlew :app:assembleDebug check
./gradlew :app:connectedDebugAndroidTest
```

`LiveSignInSmokeTest` checks real UIT HTTPS connections and opens the Moodle and
SSO pages in Android WebView on API 30. It verifies that the password form renders;
CI receives no student credentials and does not claim to test an authenticated
student session. Its screenshots contain only the public login pages.

Full authenticated Moodle-to-Portal login was separately tested interactively with
an authorized UIT account. Timetable, transcript and profile responses were used
for local parser validation. Private responses, credentials and session values
are excluded from source, fixtures, screenshots and CI artifacts.

An optional local `LivePortalResponseTest` accepts a private capture directory via
`UIT_LIVE_FIXTURES`. It is skipped by default; deterministic fixtures use synthetic
student data. Never place private captures inside the repository.
