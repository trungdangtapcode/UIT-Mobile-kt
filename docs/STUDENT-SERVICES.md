# Student services in UIT Mobile

Open **Academic → Tuition / Procedures / Graduation**. The existing Records view
keeps the transcript, GPA planner, exam schedule and account settings. The five
bottom destinations remain unchanged on portrait phones.

These views require a linked UIT Portal account from the existing SSO flow. The
first student-service tab opened during an Academic ViewModel's lifetime loads
the services. Switching tabs shares the result rather than issuing duplicate
requests. The refresh action retries the service reads independently of Moodle's
full academic/content sync. Services are refreshed on opening and manually;
the background academic sync does not currently refresh these additional records.

## What is available

- **Tuition:** select a reported term, see its payable/paid/remaining amounts and
  deadlines, expand subject charges and existing payment records, and read tuition
  extension history. Monetary values retain their decimal precision; an unavailable
  amount is an em dash. Historical balances are not summed into a new claimed debt.
- **Procedures:** grouped histories for tuition extensions, student certificates,
  official transcript requests, appeals, exam deferral/re-examination, certificate
  verification and leave/withdrawal. Available document types and their reported
  fees can be expanded for official transcript requests.
- **Graduation:** university-provided assessment period, registration state,
  eligibility gates, conditions and outstanding requirement explanations. The app
  does not replace the university's eligibility decision with a local GPA formula.

All three are readers. No new controls submit applications, create drafts, cancel
requests, upload documents or initiate payments. The network client exposes only
the nine researched read operations. Tuition's POST is a field-projection query;
the remaining eight reads use GET. No action or payment payload is sent.

## Offline data and failure handling

Normalized service models are stored in the existing account-scoped Room snapshot.
Each section retains its own successful fetch time. A failed section keeps its
previous data and records a typed issue while other sections can update. An
expired session stops the remaining reads and directs the student to sign in.
Errors, never-loaded states and valid empty responses have different UI states.

The snapshot extension is nullable, so older caches without these records remain
readable without a Room table migration. Service data disappears from the combined
view when the Portal account is removed; the existing account removal deletes its
stored snapshot. The reader excludes raw identity fields, document/image URLs,
transaction identifiers and invoice codes from the new persisted models.

## Verification and known limits

```sh
./gradlew :core:network:daa:test :core:data:testDebugUnitTest \
  :core:database:testDebugUnitTest :feature:academic:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests '*WorkspaceDesignTest*' -PrecordUi=true
./gradlew :app:assembleDebug check
```

Tests cover the permitted request methods, missing schemas and amounts, precise
monetary parsing, partial failures/offline retention, expired sessions, account
removal, older snapshots, duplicate-request prevention, term selection and phone
navigation. The [gallery](design/README.md) uses synthetic student records and
actual Compose rendering at phone widths, including dark mode and larger text.

Production parsers were additionally checked locally against all nine responses
read using an authorized UIT account. `LiveStudentServicesResponseTest` accepts a
private `UIT_LIVE_SERVICE_FIXTURES` directory and is skipped by default. Student
credentials and private captures are not supplied to CI or included in previews.

The Portal endpoints are observed private interfaces, not a published stable API.
Some procedure histories were empty in the research account, so non-empty shapes
for every procedure type remain unverified. Unknown/missing status fields display
as unavailable; malformed identities or required collections fail the section
without deleting its cached data. Individual physical-phone behavior still needs
device confirmation. The research evidence is in
[UIT-PORTAL-SERVICES.md](research/UIT-PORTAL-SERVICES.md).
