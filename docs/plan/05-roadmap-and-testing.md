# 05. Phased Implementation Roadmap & Testing Strategy

## 1. Project Directory & Module Tree

```text
uit-mobile/
├── build-logic/
│   └── convention/               # Gradle Convention Plugins (AndroidLibrary, Compose, Hilt/Koin, Detekt)
├── gradle/
│   └──libs.versions.toml         # Centralized Version Catalog
├── app/                          # App shell, MainActivity, Navigation Host, WorkManager initializer
├── core/
│   ├── common/                   # AppResult, Coroutine Dispatchers, UitLessonTimeCalculator, UrlSanitizer
│   ├── model/                    # Pure Kotlin Domain Models (Moodle + DAA + Unified entities)
│   ├── auth/                     # SessionVault (Tink/EncryptedDataStore), SessionHealthClassifier, SsoWebView
│   ├── network/
│   │   ├── common/               # Ktor HttpClient factory, OriginGuardPlugin, LoginPageSnifferPlugin
│   │   ├── moodle/               # MoodleAjaxHelper, AjaxMoodleClient, HtmlFallbackMoodleClient, H5pParser
│   │   └── daa/                  # DaaClient, TimetableParser, ExamParser, NotificationParser, GradeParser
│   ├── database/                 # Room Database, Entities, DAOs, TypeConverters
│   ├── data/                     # Offline-First Repositories, SyncManager
│   ├── domain/                   # UseCases (GetUnifiedScheduleUseCase, CrossLinkCoursesUseCase, SubmitAssignmentUseCase)
│   └── designsystem/             # Material 3 UIT Theme, Typography, CourseCard, DeadlineBadge, TimetableGrid
└── feature/
    ├── auth/                     # Login & Account Management UI
    ├── dashboard/                # Today's Overview UI
    ├── schedule/                 # Weekly TKB Grid & Monthly Calendar UI
    ├── courses/                  # Course Tree, Forum Reader, H5P Viewer, Assignment Submission UI
    └── academic/                 # DAA Transcript, GPA Calculator, Exams & Notices UI
```

---

## 2. Technology Stack & Libraries (`libs.versions.toml`)

| Category | Library | Why ("Standing on Giant Shoulders") |
| :--- | :--- | :--- |
| **Language & Async** | Kotlin 2.1+, Coroutines 1.10+, Flow | Structured concurrency, reactive streams, zero callback hell |
| **Serialization** | `kotlinx.serialization-json` | Fast, reflection-free JSON parsing for Moodle AJAX payloads |
| **Date & Time** | `kotlinx-datetime` | Type-safe timezone (`Asia/Ho_Chi_Minh`, UTC+7) calculations for lessons & deadlines |
| **Networking** | Ktor Client (`ktor-client-okhttp`) | Custom streaming plugins for 8 KB login sniffing & manual redirect origin checks |
| **HTML DOM Parsing** | `Ksoup` (`com.fleeksoft.ksoup`) | Pure Kotlin multiplatform Jsoup port — clean CSS selectors instead of brittle regex |
| **Local Database** | Room 2.7+ (SQLite) & DataStore | Reactive `Flow<List<T>>` queries, atomic transactions, FTS search |
| **Security** | Jetpack Security Crypto / Tink | AES-256-GCM encryption at rest for `MoodleSession`, `sesskey`, and `SSESS` cookies |
| **Dependency Injection** | Hilt (or Koin Annotations) | Compile-time verified DI graph and ViewModel scoping |
| **UI Toolkit** | Jetpack Compose (Material 3) | Declarative UI, Adaptive Navigation Suite, smooth animations |
| **Background Sync** | AndroidX `WorkManager` | Guaranteed background execution for deadline & Nghỉ/Bù notifications |
| **Code Quality** | `Detekt` + `Ktlint` | Enforces clean code rules, cyclomatic complexity limits, and layer boundaries |

---

## 3. Phased Execution Plan

### Phase 1: Foundation, Core Models & Security Primitives
- [ ] **Step 1.1**: Initialize Gradle multi-module workspace (`settings.gradle.kts`, `libs.versions.toml`, `build-logic`).
- [ ] **Step 1.2**: Implement `core:common` & `core:model`:
  - `AppResult<D, E>`, `SessionHealthState` (`classifySessionError` ported from `uit-cli/src/session-health.ts`).
  - `MoodleUrlResolver` (`resolveMoodleUrl`, `credentialFreeUrl` ported from `uit-cli/src/api.ts`).
  - `UitLessonTimeCalculator` (maps DAA periods 1–10 and Exam shifts 1–4 to `LocalTime`).
- [ ] **Step 1.3**: Implement `core:network:common`:
  - Ktor client with `OriginGuardPlugin` (blocks cross-origin credential leaks) and `LoginPageSnifferPlugin` (8 KB prefix inspection).

### Phase 2: Porting the `uit-cli` Moodle Engine (`core:network:moodle`)
- [ ] **Step 2.1**: Implement `MoodleAjaxHelper` (`normalizeArgs`, `buildAjaxInfo`, `unwrapAjaxResponse`) with 100% parity with `uit-cli/src/ajax-helpers.ts`.
- [ ] **Step 2.2**: Implement `AjaxMoodleApiClient` and `HtmlScrapingMoodleApiClient` (using `Ksoup`), wrapped in `ResilientMoodleApiClient`:
  - Multi-classification course discovery (`core_enrol_get_users_courses` + 6 timeline classifications).
  - Course contents & batched activity resource enrichment.
  - Assignments, submission status, draft repository file upload, and HTML/AJAX submission saving.
  - Forums, announcement auto-discovery, discussion posts, and replies.
  - Grades, participants, calendar monthly view (`calendar.ts`), notifications, and inbox (`notifications.ts`).
- [ ] **Step 2.3**: Implement `H5pPackageReader` (`h5p.ts` port) and atomic SHA-256 file downloader.

### Phase 3: UIT DAA Portal Engine (`core:network:daa`)
- [ ] **Step 3.1**: Implement `DaaTimetableParser` (`/ajax-block/tkb/ajax` & `/sinhvien/thoikhoabieu`).
- [ ] **Step 3.2**: Implement `DaaExamParser` (`/ajax-block/lichthi/ajax`).
- [ ] **Step 3.3**: Implement `DaaNoticeParser` (`/thong-bao-nghi-bu` & `/thong-bao-chung`).
- [ ] **Step 3.4**: Implement `DaaAcademicParser` (`/sinhvien/kqht` & `/sinhvien/bang-dieu-khien`).

### Phase 4: Persistence, Auth & Cross-Portal Domain UseCases
- [ ] **Step 4.1**: Implement `core:auth` (`EncryptedSessionVault` + `UnifiedSsoWebViewClient` for 1-tap Moodle + DAA login).
- [ ] **Step 4.2**: Implement `core:database` (Room schema for courses, modules, assignments, timetable slots, make-up notices, exams, grades) & `core:data` offline-first repositories.
- [ ] **Step 4.3**: Implement `core:domain` UseCases:
  - `GetUnifiedScheduleUseCase` (merges DAA TKB + Nghỉ/Bù overrides + Exams + Moodle Deadlines).
  - `CrossLinkCourseUseCase` (links DAA class codes to Moodle courses).
  - `CalculateDeadlineRemindersUseCase` (ported from `calendarReminders` in `calendar.ts`).

### Phase 5: Compose UI, Widgets & Background Sync
- [ ] **Step 5.1**: Build `core:designsystem` (UIT Material 3 theme, reusable components).
- [ ] **Step 5.2**: Build `feature:auth`, `feature:dashboard`, `feature:schedule`, `feature:courses`, and `feature:academic`.
- [ ] **Step 5.3**: Implement `UitSyncWorker` (`WorkManager`) for background 24h/1h deadline notifications and DAA Nghỉ/Bù alerts.

---

## 4. Rigorous Testing Strategy (Porting `uit-cli/test` Fixtures)

We inherit the test-driven discipline of `uit-cli` (`uit-cli/test/*.test.ts`):
1. **Protocol & Parser Unit Tests (JUnit 5 + Ktor `MockEngine`)**:
   - Port test cases from `moodle-session-client.test.ts`, `course-resources.test.ts`, `assignment-submission.test.ts`, `calendar.test.ts`, and `session-health.test.ts`.
   - Test every HTML fallback parser against real Moodle & DAA HTML snapshots (including edge cases like expired sessions returning HTTP 200 login forms, missing forum instance IDs on student news forums, and `/sdh` prefix redirects).
2. **Domain & Repository Tests (Turbine + Room In-Memory DB)**:
   - Verify `GetUnifiedScheduleUseCase` accurately marks classes as `CANCELED` when a matching DAA Nghỉ học notice exists on that date, and inserts `MAKE_UP` slots.
   - Verify offline-first emission order (cached DB state emitted first, followed by network-synced state).
3. **UI & MVI State Tests**:
   - Test ViewModel `StateFlow` transitions for every `UiIntent` using `kotlinx-coroutines-test`.
