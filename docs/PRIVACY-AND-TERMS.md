# UIT Mobile Privacy Policy and Terms of Use

Repository draft, version 0.3, November 27, 2026. This draft describes the
project's current data practices and proposed acceptance language. The
acceptance screens below are proposals for a future app release.

## Privacy Policy

### 1. Project and scope

UIT Mobile is an independent, open-source Android utility developed by the
community for students at the University of Information Technology, Vietnam
National University Ho Chi Minh City (UIT). UIT operates its official services
and controls university accounts. The project neither represents UIT nor
provides an official channel for university notices, academic records or
transactions.

The default is client-side operation. Core features run on your device, which
connects directly to the UIT services you link. Core features require no
UIT Mobile-operated backend. UIT servers still authenticate your account and
handle requests for their data. You can use core features without connecting
an external AI provider or gateway.

### 2. Sign-in and account credentials

- For **Courses (Moodle)**, the app opens the UIT login flow in a temporary
  in-app browser. You enter your password on the official UIT sign-in page.
  The app uses the resulting service session and account identifier. It does
  not read or store your password in this flow. If you also connect Portal,
  UIT may use the same sign-in to issue a separate Portal session.
- **Forum** has a separate session. If you use the in-app Forum sign-in form,
  the app handles the username, password and any second-factor code temporarily
  and sends them directly over HTTPS to UIT Forum. The stored session contains
  neither your password nor your second-factor code.
- Builds that include **UMA** use UIT SSO and a separate UMA session. When you
  send a question, the app sends it, limited conversation context and the
  selected source settings directly to UIT's UMA service. UIT may keep a
  record of the request.

University credentials and service sessions do not pass through a backend
operated by the UIT Mobile project. Use UIT's official account recovery
channels to change a password, recover an account or revoke a university
session.

### 3. Data used for app features

- **Academic and course data.** The app may retrieve identifiers, enrolled
  courses, schedules, deadlines, submission status, grades and other academic
  information your account can access. It uses them to display information,
  support offline use and schedule reminders. Enabled features may sync in
  the background subject to Android's scheduling rules.
- **Student profile.** Opening the detailed profile retrieves the selected
  student's record from UIT Portal. It may include sensitive information, such
  as health and insurance details, bank information and scanned documents.
  The profile viewer holds this information in working memory for display
  and does not create an offline profile archive.
- **Local material and exports.** Downloads, drafts, reading positions and
  bookmarks may be kept in app storage. Exports and shares create copies
  at the destination you choose. Notifications and home screen widgets may
  expose course or deadline information according to your Android settings.
- **Actions on UIT services.** When you choose to submit an assignment, post,
  reply or send a message, the app sends the necessary content to the selected
  service using the selected account. Service rules determine visibility,
  retention and the outcome of the action. Services may record connection,
  browsing and synchronization activity.

### 4. Optional AI and external processing

A new installation has no active external AI connection. You may connect
directly to OpenAI, authorize an eligible ChatGPT plan or configure an HTTPS
gateway. When you use an AI feature, its request can include your question,
relevant conversation history and text from the sources chosen for that feature.
If you enable remote semantic indexing, selected document passages may be sent
to the configured embedding service. The app uses credentials for the chosen
AI connection and does not forward UIT passwords, cookies or session tokens.

The receiving provider handles the data under its own terms and account
settings. A separately configured gateway can receive and forward request
content. Processing and retention, including any processing outside Vietnam,
depend on the destination you choose. You can disconnect the AI integration
in settings and use core features separately.

If the project introduces a Codex integration in the future, it will be
optional and off by default. A specific opt-in will describe what the
integration can access, what it sends and who receives it. Accepting these
terms or enabling the existing ChatGPT account connection does not grant
Codex permission to act on a UIT account.

### 5. Security and open-source review

- **In transit.** Android network configuration blocks cleartext HTTP.
  UIT authentication uses HTTPS/TLS with server certificate validation.
  Login WebViews stop on an invalid certificate, restrict navigation to
  approved login hosts, and block mixed content and third-party cookies.
  Selected UIT hosts have a narrowly scoped public root certificate for
  compatibility with older Android devices.
- **On device.** The app uses AES-256-GCM with keys managed by Android
  Keystore for stored service sessions and AI credentials. Course caches,
  files and databases have feature-specific storage and use Android's private
  app storage where applicable. The app disables Android Auto Backup in its
  manifest. Device-to-device transfer behavior may vary by manufacturer.
- **References.** [FIPS 197][aes] specifies AES and [NIST SP 800-38D][gcm]
  specifies GCM. The project uses [OWASP MASVS][masvs] as a reference when
  describing mobile security controls. These statements describe specific
  controls and do not represent an independent certification or security audit.

The project makes its [MIT-licensed source code](../LICENSE) available for
inspection and independent builds. The safety of a particular APK also depends
on its dependencies and how it was built and distributed. The project makes no
guarantee that every APK copy is free of malware or vulnerabilities. Third-party
materials are recorded in [NOTICE](../NOTICE).

### 6. Retention, deletion and privacy requests

The app keeps saved sessions for subsequent connections and manages local
caches by feature. Disconnecting a service removes its saved local session.
Some local drafts and exports can have their own retention or deletion controls.
Clearing app storage in Android or uninstalling removes private app storage on
that device. Copies previously exported to shared storage and records held
by UIT, an AI provider or a gateway are managed at their respective destinations.
Local deletion does not revoke a session on a remote service.

Subject to applicable law, you may request access, correction, deletion,
restriction of processing or withdrawal of consent. Direct requests about
official records or data retained by another service to that provider. For
questions about app behavior, contact the project maintainers through the
[repository's issue tracker][issues]. Use it for nonsensitive descriptions.
Keep student records, passwords, verification codes and session tokens private.
A private contact route for sensitive requests remains to be confirmed for
the published in-app policy.

## Terms of Use

### 7. Accounts, content and acceptable use

Use only accounts and information you are authorized to access. Protect your
device, credentials and verification codes. You choose which account is linked,
which actions to request and what content to send or share. UIT determines
the permissions and final result for actions on its services.

Follow the applicable rules of UIT and any external services you connect.
Unauthorized access, impersonation, circumvention of access controls,
interference with services and unauthorized disclosure of another person's
information are prohibited. You are responsible for the content you choose
to upload, post or share. The source-code license does not grant rights in
UIT records, names, logos or other third-party materials.

### 8. Availability and responsibility

Use UIT's official channels to verify grades, exam schedules, tuition,
submission status and consequential deadlines. Cached records can be stale,
Android can delay reminders, and AI answers can omit information or be wrong.
After submitting content, check its status on the destination service.
Optional AI usage may count against your plan, API or gateway quota or charges.

To the extent permitted by law, the project is not responsible for disclosure
or account takeover caused entirely by circumstances outside its control where
UIT Mobile did not cause or contribute to the incident. Examples include
providing credentials to an unrelated phishing site and a security incident
at an external service that is independent of the app. Applicable user rights
and liability for the project's own defects or data handling still apply.

### 9. Changes and ending use

You may stop using the app, disconnect a service or uninstall at any time.
The project will date published revisions and give notice of material changes.
Where a new purpose or expanded scope relies on consent, it requires a new
affirmative choice before processing.

## Proposed acceptance text

Before the first account connection, show links to this policy and the terms
and leave the following acknowledgment unchecked by default.

> I have read the Privacy Policy and agree to the UIT Mobile Terms of Use.

Offer **Agree and continue** and **Decline**. Before connecting a UIT service,
explain what the app will access, why it needs the data and where it stores the
session. For optional processing based on consent, show the specific purpose,
data and destination, with a separate affirmative choice and a way to skip.
For example, before sending selected material to an AI provider for a new purpose:

> Send your question and the selected source text to [provider] for [purpose]?

Offer **View data**, **Allow and send** and **Skip**. Keep the policy available
for later review and provide the corresponding settings to withdraw consent or
disconnect an optional integration. Record the policy version and applicable
consent choices where the feature requires them. See Vietnam's
[Law on Personal Data Protection][pdp] and its [implementing decree][decree].

[aes]: https://csrc.nist.gov/pubs/fips/197/final
[gcm]: https://csrc.nist.gov/pubs/sp/800/38/d/final
[masvs]: https://mas.owasp.org/MASVS/03-Using_the_MASVS/
[pdp]: https://vanban.chinhphu.vn/?docid=214590&pageid=27160
[decree]: https://vanban.chinhphu.vn/?docid=216387&pageid=27160
[issues]: https://github.com/trungdangtapcode/UIT-Mobile-kt/issues
