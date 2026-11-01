# UIT Mobile feature opportunities

Research date: October 29, 2026.

Publication note: this preserves the original research backlog. Some proposals
have since shipped; consult the [issue tracker](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues)
and feature documentation for current delivery status. The table IDs below are
local roadmap item numbers, not GitHub issue numbers; use the
[tracking roadmap](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues/2)
to find the corresponding issues.

This is a product research backlog, not an implementation commitment. Research
used public sources and the current repository; no authenticated student requests,
submissions, payments or account changes were made for this investigation.

## Starting point

The app already has schedules, exams, Moodle materials and assignments, basic
deadline and grade alerts, a GPA projection, student service readers, profile scan
viewing and two home screen widgets. Suggestions below extend these capabilities
or add something distinct. They do not count the existing readers as new features.

Effort labels are relative engineering estimates, not calendar commitments:

- **Small:** a bounded addition using existing data and established UI patterns.
- **Medium:** new local state, indexing, routing or substantial presentation work.
- **Large:** a new service integration, complex lifecycle or significant data work.

Data labels distinguish **Existing** snapshots, **Local** student-entered data,
**Public** official sources and **Investigate** integrations not established by this
research. Public documentation confirms a service exists; it does not establish
API access, SSO compatibility or permission to perform a write.

## Daily study workflow

| ID | Feature | Concrete behavior | Data | Effort |
| --- | --- | --- | --- | --- |
| 01 | Unified search | Search course codes, material titles, assignments and notices together; open the exact result. Start with metadata, then consider document contents separately. | Existing | Medium |
| 02 | Academic change history | Show verified before/after changes to rooms, times, deadlines and grades, with a timestamp and direct navigation. Partial refresh failures must not become removal alerts. | Existing; new local history | Medium |
| 03 | Lesson preparation pack | Open the current or next lesson to its room, relevant course materials, pinned links and upcoming assignments in one phone view. | Existing + Local | Medium |
| 04 | Offline download manager | Choose course/week packs; show download progress, verified availability, storage size and removal controls. Add Wi-Fi-only preferences. Existing individual downloads remain the foundation. | Existing + Local | Medium |
| 05 | Assignment subtasks | Break an LMS assignment into personal milestones. Personal completion never changes or implies the official submission status. | Existing + Local | Medium |
| 06 | Notes attached to lessons | Keep text and optional photos against a specific course/date, with local deletion and export controls. | Local | Medium |
| 07 | Bookmarks across content | Pin important files, forum discussions, notices and official links into a small personal collection. | Existing + Local | Small |
| 08 | Workload preview | Show clustered deadlines and exams across the coming week. Estimate hours only when the student supplies an estimate. | Existing + Local | Medium |

## Scheduling and exam preparation

| ID | Feature | Concrete behavior | Data | Effort |
| --- | --- | --- | --- | --- |
| 09 | Free-time finder | Find gaps between the student's own classes, exams and personal events, with configurable travel/break buffers. | Existing + Local | Medium |
| 10 | Exam revision planner | Split preparation into editable topic sessions and place them into available time; support explicit rescheduling after a missed session. | Existing + Local | Medium |
| 11 | Required final-score calculator | Extend the GPA tools with per-course target calculations using known grading weights. Ask for missing weights instead of inventing them. | Existing + Local; weight availability varies | Medium |
| 12 | Calendar file export | Export selected classes, exams and deadlines as an `.ics` file. Define recurrence exceptions and stable event identifiers; file export is distinct from a live subscription. | Existing | Medium |
| 13 | Shareable timetable image | Produce a readable phone timetable with explicit choices about course names, rooms and personal details before sharing. | Existing | Small |
| 14 | Semester planning sandbox | Compare candidate classes, timetable clashes and credit totals locally. Show prerequisite claims only from verified program/cohort rules; offer an official registration link. | Local; Public/Investigate for catalog and rules | Large |

MyStudyLife provides a useful reference for task planning, rotating schedules,
revision and focus workflows [S1]. These are workflow references, not evidence
that any specific feature will improve UIT students' results.

## UIT academic services

| ID | Feature | Concrete behavior | Data | Effort |
| --- | --- | --- | --- | --- |
| 15 | Conduct-score dashboard | Read semester scores, criterion breakdowns and official provisional/final states where exposed. Keep self-estimates clearly separate. | Investigate DRL authentication and response shapes | Large |
| 16 | Activity evidence organizer | Keep participation records, dates and personal proof files for later checking against conduct-score records. No automatic evidence submission. | Local; optional future DRL comparison | Medium |
| 17 | Graduation action checklist | Extend the current graduation reader with actionable explanations, official source links and student reminders for outstanding requirements. Preserve the university's eligibility decision. | Existing + Local | Medium |
| 18 | Tuition due-date tracking | Extend tuition reading with reminders for reported installment deadlines and balance/status changes. Service refresh must be added deliberately; it is not part of current background academic sync. | Existing service models; refresh extension | Medium |
| 19 | Procedure progress tracking | Notify when an existing certificate, transcript or appeal request changes state; show its timeline and next official instruction. No request submission is included. | Existing service models; refresh extension | Medium |
| 20 | Scholarship discovery | Collect official opportunities, deadlines and document checklists. Match explicit filters; attach the applicable cohort/program and rule version instead of declaring a guaranteed award. | Public + Local | Large |

UIT identifies DRL as the place to check conduct scores [S3]. The CTSV scholarship
page lists different rules and replacement periods [S4], making rule versioning a
real product requirement. The existing app's tuition, procedure and graduation
coverage and refresh limits are documented in [student services](../STUDENT-SERVICES.md).

## Campus and library

| ID | Feature | Concrete behavior | Data | Effort |
| --- | --- | --- | --- | --- |
| 21 | Room and building directory | Search room codes, buildings, service desks and verified access information in an offline directory. A reliable UIT room dataset still needs to be assembled. | Public/Investigate + curated data | Medium |
| 22 | Directions from the next class | Connect a timetable room to a building location and walking guidance. Indoor turn-by-turn routing needs verified floor plans and is a separate project. | Existing + verified map data | Large |
| 23 | Course-linked library search | Open catalog searches from a course or syllabus bibliography; prefer exact titles, authors and identifiers over claiming an unverified book is required. | Public; investigate catalog integration | Medium |
| 24 | Library loans and return reminders | Show active loans and due dates after a separately supported library login. Start read-only and link to the official renewal flow. | Investigate OPAC authentication and loan reads | Large |
| 25 | Study-space directory | Show verified locations, opening information and official group-room booking links. Do not infer live room availability from an empty personal timetable. | Public/Investigate | Medium |
| 26 | Student help directory | Find the right office, room, contact and official instructions for academic, financial, library and IT questions, including the UIT Wi-Fi guide. | Public; maintained directory | Small |

UWE's official app combines library search, campus maps, staff contacts and student
services [S2]. UIT's library site links course resources, group-room registration
and OPAC. Its account instructions describe a separate library identity and point
to VNUHCM Libraries [S5]. This research does not establish library UIT SSO support.

## Opportunities and course resources

| ID | Feature | Concrete behavior | Data | Effort |
| --- | --- | --- | --- | --- |
| 27 | Internship opportunity board | Collect official faculty opportunities, with technology, location, schedule and deadline filters plus personal application tracking. Applications open the official destination. | Public + Local | Medium |
| 28 | Technical event calendar | Follow official workshops, hackathons, research calls and competitions by interest; save selected events to the personal schedule. Participation is not automatically treated as conduct-score credit. | Public + Local | Medium |
| 29 | Thesis and internship milestones | Track proposal, supervisor meetings, deliverables, reports and defense preparation against verified faculty announcements and personal tasks. | Public + Local | Medium |
| 30 | Reading and annotation workspace | Read downloaded PDFs in the app with remembered position, bookmarks and local notes. Preserve original files and allow annotation export. | Existing downloads + Local | Large |

The Software Engineering faculty publishes scholarship/recruitment opportunities
[S7], and the Information Science and Engineering faculty publishes research and
thesis/internship notices [S8]. These are candidate feeds; dates and categories need
validation during ingestion rather than assumptions based on an index page.

## Android usability

| ID | Feature | Concrete behavior | Data | Effort |
| --- | --- | --- | --- | --- |
| 31 | Android share capture | Receive a shared file, link or text snippet, then let the student attach it to a course, note or personal task. Show the destination before saving. | Local | Medium |
| 32 | Configurable widgets | Let each widget select its content, such as next exam or deadlines, and its privacy level and account where account separation is supported. | Existing + Local | Medium |
| 33 | Notification preferences and destinations | Extend current basic alerts with category controls, quiet hours, grouping and exact in-app destinations. Background freshness remains visible. | Existing + Local | Medium |
| 34 | Sync and session health | Show last successful refresh per source, stale sections, expired sessions and a focused retry/sign-in action. Diagnostic exports must exclude student records and credentials. | Existing status; finer tracking needed | Medium |
| 35 | Accessibility pass | Verify TalkBack reading order, focus after navigation, large text, contrast and non-color-only status cues across real phone screens and widgets. | Existing UI | Medium |
| 36 | Complete Vietnamese localization | Move interface strings into resources, add Vietnamese translations, and preserve consistent course/portal terminology and date/number formatting. | Existing UI | Medium |

UIT's public document catalog can also support the help directory and link to
official forms and procedures [S6].

## Suggested delivery order

1. **Unified search (01):** high daily utility across data already collected.
2. **Change history and notification destinations (02 + part of 33):** make changes
   understandable and actionable; define successful-fetch baselines first.
3. **Lesson preparation pack and bookmarks (03 + 07):** connect timetable access
   to the actual materials needed before class.
4. **Offline download manager (04):** make existing download support manageable.
5. **DRL investigation (15):** assess authentication, authorized read endpoints and
   criterion/status semantics before committing to a native implementation.

For a stronger UIT-specific differentiator, prioritize DRL after that investigation.
For the shortest path to everyday usefulness, prioritize search and preparation.
These rankings are product judgments based on the current code and source review;
they have not been validated by a student survey.

Registration planning should stay a local planning tool. UIT's published
2026–2027 registration announcement explicitly rejects automatic registration
tools [S9]. This backlog does not propose automated registration or seat holding.

## Public sources

- **S1:** [MyStudyLife](https://mystudylife.com/) — planner, rotating schedules,
  reminders and revision/focus workflow reference.
- **S2:** [UWE Bristol app](https://www.uwe.ac.uk/study/uwe-bristol-app) — university
  app feature reference for library, maps, contacts, news and events.
- **S3:** [UIT CTSV: conduct scores](https://ctsv.uit.edu.vn/bai-viet/diem-ren-luyen-sinh-vien-0)
  — identifies the DRL service; does not document an API.
- **S4:** [UIT CTSV: scholarship regulations](https://ctsv.uit.edu.vn/bai-viet/quy-dinh-lien-quan-den-hoc-bong-sinh-vien)
  — applicable rules, replacement periods and program distinctions.
- **S5:** [UIT library account instructions](https://thuvien.uit.edu.vn/page/tai-khoan-thu-vien)
  — separate account guidance, OPAC, loans and library application links.
- **S6:** [UIT Portal: documents and forms](https://portal.uit.edu.vn/van-ban-bieu-mau)
  — official procedures, forms and Wi-Fi instructions.
- **S7:** [Software Engineering: scholarships and recruitment](https://cnpm.uit.edu.vn/vi/tin-t%E1%BB%A9c/h%E1%BB%8Dc-b%E1%BB%95ng-tuy%E1%BB%83n-d%E1%BB%A5ng.html)
  — candidate internship and opportunity source.
- **S8:** [Information Science and Engineering faculty](https://cntt.uit.edu.vn/index.php/)
  — candidate research, thesis and internship notice source.
- **S9:** [UIT announcements](https://www.uit.edu.vn/thong-tin-thong-bao)
  — published 2026–2027 course registration announcement and its automation restriction.

Sources were inspected on the research date. Some individual guidance pages are
older than the surrounding site; verify current rules again before implementing
eligibility logic, deadlines or office instructions.
