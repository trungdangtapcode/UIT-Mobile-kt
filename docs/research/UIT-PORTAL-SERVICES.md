# UIT Portal: tuition, student procedures and graduation

Research date: October 27, 2026. This is an investigation report, not an application
implementation or a published API contract.

Implementation follow-up: the researched reads are now available in the Android
app. See [student services](../STUDENT-SERVICES.md). The investigation and its
no-mutation scope remain separate from this later implementation.

## Findings

All nine investigated student pages are available under `portal.uit.edu.vn` after
the existing UIT SSO sign-in. Their associated data requests succeeded in an
authorized authenticated session. No additional service login was required.
These are capabilities of the already linked UIT Portal service, rather than
additional independent SSO accounts.

Most features expose a JSON read endpoint. Tuition uses a read-only POST with a
field projection, while procedure and graduation reads use GET. A native reader
can use the existing origin-scoped Portal session without scraping rendered tables.
None of these features has been added to the Android app by this research.

## Scope and handling

- Signed in with the account authorized for this investigation, taking the password
  from the existing shell environment without printing or persisting it.
- Opened the nine pages below, inspected their ordinary data requests, and read
  public JavaScript to distinguish reads from mutations.
- Blocked non-read browser requests by default. The only allowed POST operations
  were the authentication form and the verified tuition projection request.
- Did not click submit, register, cancel, delete, pay, export or upload controls.
  Did not generate draft applications, change contact information, upload files,
  access linked personal attachments, or send messages.
- Used temporary private storage for inspection. This report and its companion
  schema file contain no account identifiers, amounts, grades, personal statuses,
  attachment URLs, session values, private response bodies or collection sizes.
- Authentication creates a normal login session; this investigation did not submit
  academic, financial or administrative changes.

## Observed reads

Paths are relative to `https://portal.uit.edu.vn`. Every data request in this table
returned HTTP 200 during this investigation. A successful HTTP status alone does
not establish that a user is eligible for an operation; inspect response errors and
server-provided conditions separately.

| Capability | Page | Data request |
| --- | --- | --- |
| Tuition | `/sinh-vien/hoc-phi` | `POST /api/sv/tuition` |
| Tuition extension | `/sinh-vien/gia-han-hoc-phi` | `GET /api/sinh-vien/gia-han-hoc-phi` |
| Student certificates | `/sinh-vien/giay-xac-nhan` | `GET /api/sinh-vien/giay-xac-nhan` |
| Official transcript requests | `/sinh-vien/xin-bang-diem` | `GET /api/sinh-vien/xin-bang-diem` |
| Grade appeals | `/sinh-vien/phuc-khao` | `GET /api/sinh-vien/phuc-khao` |
| Exam deferral and re-examination | `/sinh-vien/hoan-thi` | `GET /api/sinh-vien/hoan-thi` |
| Certificate verification | `/sinh-vien/xac-nhan-chung-chi` | `GET /api/sinh-vien/xac-nhan-chung-chi` |
| Withdrawal and leave of absence | `/sinh-vien/thoi-hoc-bao-luu` | `GET /api/sinh-vien/thoi-hoc-bao-luu` |
| Graduation assessment | `/sinh-vien/tot-nghiep` | `GET /api/sinh-vien/tot-nghiep` |

Field names and observed value types are recorded in
[uit-portal-response-shapes.json](uit-portal-response-shapes.json). That file is a
sanitized structural observation, not a complete schema: empty histories reveal
no item fields, and a limited set of records cannot establish every possible type.

## Tuition

The page calls `/api/sv/tuition` automatically on mount, using the following JSON
projection. It contains field names, with no payment instruction or mutation action.
The exact body was inspected in the public tuition page bundle before allowing it.

```json
{
  "tuition_field_list": [
    "id", "semester", "year_id", "tuition_amount", "tuition_semester",
    "tuition_renewal", "reduction_amount", "tuition_credit_number",
    "must_be_paid", "paid", "remaining", "debt_in_advance", "payment_status",
    "late_payment_date", "period_1_due_date", "period_2_due_date",
    "renewal_date", "tuition_status", "paid_time", "note"
  ],
  "detail_field_list": [
    "id", "subject_id", "subject_code", "subject_name", "tuition_credit_number",
    "unit_price", "additional_tuition", "amount", "repayment", "improve", "note"
  ]
}
```

The response exposes `records`, including term/year labels, amounts payable and
paid, remaining balance, deadlines, status labels, `detail_ids` for subject-level
charges and `payment_ids` for existing payment records. Existing payment history
is returned by the read; this does not initiate a payment.

Several monetary fields are JSON strings, while payment amounts are numeric.
Relation fields such as `year_id` and `subject_id` are arrays rather than scalar
IDs. Do not assume that their database IDs are calendar years or course codes.
Use explicit decimal parsing, preserve missing values, and keep raw/display dates
separate. Transaction identifiers and invoice codes are personal financial data;
they must not appear in diagnostics or test fixtures.

The first pass intentionally blocked this POST. The resulting page displayed an
empty state. Once the verified read was allowed, data appeared. An empty/error UI
must therefore not be interpreted as evidence of no fees or no debt.

The public client currently handles the Excel button with an informational
placeholder. No working server export or payment flow was verified.

## Student procedures

### Tuition extension

The read exposes `periodStatus`, `history`, `canMutate` and `presentStatusName`.
`periodStatus` includes the opening/closing dates, academic term, `canRegister` and
`registerBlockReason`. This supports displaying an application window and tracking
existing requests without submitting one.

### Student certificates

The response groups `history` by certificate category. Observed keys are `xnsv`,
`vvnh`, `ggt`, `xntt`, `drl`, `udgd` and `gkhk`. The public UI includes student
status certificates, bank-loan certificates, introduction letters, achievement
certificates, conduct-score certificates and educational-benefit certificates.
An additional history key does not establish that every account can create that
document category.

Observed history fields include ticket identifiers, document category, stage
codes/names, registration time, language, reason, notes, rejection reasons and
attachment names. Eligibility/options and history should be modeled separately.
Do not infer a history item's layout from other empty category arrays.

### Official transcript requests

This is distinct from the existing academic transcript reader. The endpoint
returns document parameters, display names, costs, academic years/semesters,
request history and a fee-payment location description. It is suitable for a
read-only request tracker and document catalog. No paid document was ordered.

### Appeals, deferrals and re-examination

The appeal response separates `eligible` exam/course rows from `history`. Rows
include the current score, exam type, deadline and `can_appeal`. Do not derive
appeal eligibility solely from the existence of a score.

The deferral response separates `eligible`, `history`, `reexamEligible` and
`reexamHistory`; the active window is represented by `eligible.is_open` and
`title_register`. Empty arrays were observed for some branches, so their item
schemas remain unverified. No appeals, deferrals or re-examination registrations
were submitted.

### Certificate verification and leave requests

Certificate verification exposes `certs`, `certTypes`, status, score/level, exam
and registration dates, and the output-standard flag. Image fields may identify
private attachments; the investigation did not follow them.

Withdrawal/leave exposes request history and account capability information.
No draft was generated. The public client uses a POST `create` action even before
the final signed-file submission, so a future reader must not invoke that action
merely to preview the workflow.

## Graduation

The response provides a structured `data` object rather than requiring a local
reconstruction of all university eligibility rules:

| Group | Observed fields |
| --- | --- |
| Assessment period | `config.periodLabel`, `startDate`, `endDate`, `graduationYear` |
| Conditions | `conditions[]` with `id`, `name`, `status`, `note` |
| Academic summary | `studentInfo.gpa`, `passedCredits`, `totalProgramCredits`, `trainingScore` |
| Registration gates | `registerGates.docs`, `fee`, `credit`, `phone`; `canRegister` |
| Registration state | `alreadyRegistered`, `registrationStatus`, `registeredInfo`, `ketQuaCuoi` |
| Period and exceptions | `inRegistrationPeriod`, `registrationPeriodStatus`, `isGradException`, `summerRegistrationBlock`, `isAdminOverride`, `isVb2` |
| Supporting requirements | `defenseEducation`, `foreignLanguage`, `debts[]`, `requiresTrainingScore` |
| Other supplied information | `surplusSubjects`, `contactPhone`, `feeGuide`, `contactGuide`, `nhanBang` |

Document and image references also appear in `studentInfo`. They were not opened
or downloaded. Some registration-result fields were null; their populated shapes
have not been established. An observed `canRegister` field is a server response,
not an instruction to register or permission for automated submission.

A useful first integration would show the server's condition list, requirement
statuses, assessment window and existing registration status. It should preserve
the university's explanations rather than inventing eligibility from credit/GPA
totals or treating missing values as passed conditions.

## Mutation boundary found in public client code

The following actions were identified by reading public JavaScript only. None
was called during this investigation. These are observations, not tested contracts.

| Feature | Actions or endpoint to exclude from a reader |
| --- | --- |
| Tuition extension | `submit`, `delete` |
| Student certificates | `submit-verification`, `submit-bank-loan`, `submit-ggt`, `submit-xntt`, `submit-drl`, `submit-udgd`, `delete` |
| Transcript requests | `submit`, `delete` |
| Grade appeals | `submit` |
| Exam requests | `submit-deferral`, `submit-reexam` |
| Certificate verification | `submit`, `delete` |
| Withdrawal/leave | `create`, `submit-file` |
| Graduation | `register`; document uploads through `/api/sinh-vien/ho-so-image` |

Several GET reads share their URL with POST mutations. An integration must
distinguish the method and body, rather than approving a URL without its operation.
Tuition's projection POST is the explicit read exception observed here.

## Suggested integration sequence

1. Tuition summary and existing payment/subject details, with separate error,
   empty and cached states. No payment initiation.
2. A unified procedure history showing request category, stage, relevant date and
   existing rejection/explanation fields. Empty branches need synthetic fixtures
   and later validation before defining a complete item model.
3. Graduation conditions and existing registration tracking using server-provided
   eligibility gates. Keep uploads and registration outside the reader.

Keep these features inside the current UIT Portal integration and encrypted
session store. A new SSO account per feature is unnecessary. The current adapter
in `core:network:daa` is named for its original source; a later implementation can
introduce clearer Portal-specific clients while preserving legacy DAA support.

Before implementing parsers, use synthetic fixtures for monetary strings,
relation arrays, null result fields, different history stages, expired sessions,
closed windows and schema changes. No production account responses should be
committed as fixtures. This research made no changes to application behavior.

## Evidence and limits

- Authenticated observation: the nine page routes and nine data endpoints above.
- Public client inspection: the scripts served by those pages, including tuition's
  automatic projection read and separately triggered mutation handlers.
- Existing authentication behavior: [AUTHENTICATION.md](../AUTHENTICATION.md).
- Official context: [Portal SSO login](https://portal.uit.edu.vn/login),
  [student-service migration notice](https://ctsv.uit.edu.vn/bai-viet/thong-bao-ve-viec-chuyen-doi-cong-thong-tin-sinh-vien-portaluiteduvn),
  [tuition-extension announcement](https://portal.uit.edu.vn/bai-viet/thong-bao-dang-ky-gia-han-hoc-phi-hk1-nam-hoc-2026-2027-dot-2)
  and [graduation assessment notice](https://portal.uit.edu.vn/bai-viet/ke-hoach-xet-tot-nghiep-dot-04-nam-2026).

These endpoints are reverse-engineered observations, with no documented stability
guarantee. Only one authorized student account was used; other cohorts, programs,
periods and roles may expose different data. No new eligibility, application,
payment or document-upload behavior was exercised. Separate-host services such
as Forum, legacy DAA/DRL and the library were outside this investigation.
