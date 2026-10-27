# 03. UIT DAA Portal Specification (`daa.uit.edu.vn`)

The UIT Academic Affairs portal (**DAA** — `https://daa.uit.edu.vn`) is built on **Drupal 7** and uses UIT's centralized CAS SSO (`sso.uit.edu.vn`) or Drupal form login. Because DAA does not expose a public REST API, our `core:network:daa` module uses Ktor + **Ksoup** (Kotlin Jsoup port) to query DAA's Drupal AJAX endpoints and HTML pages, converting them into strongly-typed Kotlin domain models.

---

## 1. Authentication & Session Management

- **Base URL**: `https://daa.uit.edu.vn`
- **SSO Integration**:
  - Navigating to `https://daa.uit.edu.vn/user/login` or CAS login when already signed into `sso.uit.edu.vn` issues a Drupal session cookie (`SSESS<md5_hash>=<session_id>`).
  - Our `UnifiedSsoLoginController` visits both `courses.uit.edu.vn` and `daa.uit.edu.vn` inside the same ephemeral login session so the user signs in **only once**.
- **Session Health Check**:
  - If a response redirects to `/user/login` or contains `<form id="user-login"`, `SessionHealthClassifier` marks the DAA session as `EXPIRED`.

---

## 2. DAA Endpoints & Parser Specifications

### A. Weekly Timetable (Thời khóa biểu)
- **Endpoints**:
  - Drupal AJAX Block: `GET https://daa.uit.edu.vn/ajax-block/tkb/ajax` (returns JSON `[{"command": "insert", "data": "<table...>"}]` or HTML fragment for the active semester).
  - Full Timetable Page (supports querying specific semester/year): `GET https://daa.uit.edu.vn/sinhvien/thoikhoabieu`
- **Extracted Fields (`DaaTimetableSlot`)**:
  - `courseCode`: Base subject code (e.g., `IT004`)
  - `classCode`: Full class section code (e.g., `IT004.O21` or `IT004.O21.1` for lab)
  - `courseName`: Vietnamese/English course name
  - `credits`: Number of credits
  - `dayOfWeek`: Day of week (`Thứ 2` .. `Thứ 7`, `Chủ nhật`)
  - `lessonStart`, `lessonEnd`: Period numbers (`1` to `10`, parsed from condensed string like `"123"` -> `1..3` or `"67890"` -> `6..10` where `0` represents period `10`)
  - `room`: Classroom code (e.g., `B3.14`, `A101`, `P.C201`)
  - `lecturerName`: Lecturer's full name
  - `startDate`, `endDate`: Semester start and end dates (`BĐ: dd/MM/yy`, `KT: dd/MM/yy`)

#### UIT Official Period (Tiết học) to Clock Time Mapping (`UitLessonTimeCalculator`)
| Period (Tiết) | Start Time | End Time | Shift |
| :--- | :--- | :--- | :--- |
| **Tiết 1** | 07:30 | 08:15 | Morning |
| **Tiết 2** | 08:15 | 09:00 | Morning |
| **Tiết 3** | 09:00 | 09:45 | Morning |
| *(Break 15m)* | *09:45* | *10:00* | |
| **Tiết 4** | 10:00 | 10:45 | Morning |
| **Tiết 5** | 10:45 | 11:30 | Morning |
| *(Lunch Break)* | *11:30* | *13:00* | |
| **Tiết 6** | 13:00 | 13:45 | Afternoon |
| **Tiết 7** | 13:45 | 14:30 | Afternoon |
| **Tiết 8** | 14:30 | 15:15 | Afternoon |
| *(Break 15m)* | *15:15* | *15:30* | |
| **Tiết 9** | 15:30 | 16:15 | Afternoon |
| **Tiết 10** | 16:15 | 17:00 | Afternoon |

---

### B. Exam Schedule (Lịch thi)
- **Endpoint**: `GET https://daa.uit.edu.vn/ajax-block/lichthi/ajax` (and `/sinhvien/lichthi`)
- **Extracted Fields (`DaaExamSlot`)**:
  - `classCode`: Course class code (e.g., `IT004.O21`)
  - `courseName`: Course title (matched with enrolled courses)
  - `examShift`: Shift number (`Ca 1`, `Ca 2`, `Ca 3`, `Ca 4`)
    - **Ca 1**: `07:30`
    - **Ca 2**: `09:30`
    - **Ca 3**: `13:30`
    - **Ca 4**: `15:30`
  - `examDate`: `LocalDate` (`dd-MM-yyyy`)
  - `room`: Exam room (e.g., `A204`)
  - `seatNumber`: Student ID / SBD position if listed

---

### C. Make-up & Canceled Class Notices (Thông báo Nghỉ học / Học bù)
- **Endpoints**:
  - DAA: `GET https://daa.uit.edu.vn/thong-bao-nghi-bu?page={0..N}`
  - OEP (Optional Special Programs): `GET https://oep.uit.edu.vn/thong-bao-nghi-hoc-hoc-bu?page={0..N}`
- **DOM Structure**: `<article id="node-{id}" ...>`
- **Extracted Fields (`ClassScheduleChangeNotice`)**:
  - `nodeId`: Drupal node ID (`node/12345`)
  - `type`: `CANCELED` (Thông báo nghỉ học) vs `MAKE_UP` (Thông báo học bù)
  - `classCode`: Extracted from `Lớp : <strong>IT004.O21</strong>`
  - `title`: Extracted from `property="dc:title" content="..."`
  - `room`: Extracted from `Phòng : <strong>...</strong>`
  - `targetDate`: Date of the canceled or make-up session (`dd/MM/yyyy`)
  - `lessonStart`, `lessonEnd`: Starting and ending period numbers
  - `createdAt`: ISO-8601 timestamp from `property="dc:date dc:created" content="..."`

---

### D. General Academic Announcements (Thông báo chung)
- **Endpoint**: `GET https://daa.uit.edu.vn/thong-bao-chung?page={0..N}`
- **Extracted Fields (`DaaAnnouncement`)**:
  - `nodeId`: `id="node-{id}"`
  - `title`: `property="dc:title" content="..."`
  - `url`: `https://daa.uit.edu.vn` + `about="/thongbao/..."`
  - `createdAt`: `property="dc:date dc:created" content="..."`
  - `bodyHtml`: Full article content when viewing `/node/{id}`

---

### E. Academic Grades & GPA (Kết quả học tập & Bảng điều khiển)
- **Endpoints**:
  - Student Profile & Overview: `GET https://daa.uit.edu.vn/sinhvien/bang-dieu-khien` & `/khaibaolylich`
  - Academic Transcript: `GET https://daa.uit.edu.vn/sinhvien/kqht`
- **Extracted Fields**:
  - **`StudentProfile`**: `studentId` (MSSV), `fullName`, `birthDate`, `faculty` (Khoa), `trainingProgram` (Hệ đào tạo: Cử nhân, Kỹ sư, Tiên tiến, CLC...), `avatarUrl` (`#hinhthe img[src]`).
  - **`AcademicTranscript`**:
    - Semester blocks (`SemesterGradeSummary`): `semesterName`, `semesterGpa`, `creditsAttempted`, `creditsEarned`.
    - Individual Course Grades (`CourseGradeRow`): `courseCode`, `courseName`, `credits`, `processScore` (Quá trình), `midtermScore` (Giữa kỳ), `practiceScore` (Thực hành), `finalScore` (Cuối kỳ), `totalScore` (Điểm tổng kết), `status` (Qua môn / Học lại / Miễn).
    - Cumulative summary: `cumulativeGpa`, `totalCreditsEarned`, `graduationProgress`.
