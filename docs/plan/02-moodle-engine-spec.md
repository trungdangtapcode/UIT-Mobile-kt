# 02. Moodle Engine Specification (Ported from `uit-cli`)

This specification documents the exact mechanics of [`RyanNg1403/uit-cli`](https://github.com/RyanNg1403/uit-cli) that we port to Kotlin (`core:network:moodle` and `core:auth`).

---

## 1. Supported Moodle Portals & URL Resolution

| Portal | Base URL | Auth Mode | Path Prefix |
| :--- | :--- | :--- | :--- |
| **Current UIT Moodle** | `https://courses.uit.edu.vn` | UIT CAS SSO (`sso.uit.edu.vn`) | `/` |
| **Legacy Undergraduate** | `https://coursesold.uit.edu.vn` | Legacy Session Login | `/` |
| **Legacy Graduate (SDH)** | `https://coursesold.uit.edu.vn/sdh` | Legacy Session Login | `/sdh` |

### Prefix-Preserving URL Resolver (`resolveMoodleUrl`)
Ported from `src/api.ts:20-39`:
- When resolving relative or root-relative paths (e.g., `/course/view.php?id=123`) against `https://coursesold.uit.edu.vn/sdh`, standard `URL` resolution drops `/sdh`.
- Our `MoodleUrlResolver.resolve(baseUrl, path)` preserves the `/sdh` installation prefix on all same-origin requests and 3xx redirects.

---

## 2. Ephemeral Browser Authentication (`core:auth`)

Ported from `src/moodle-browser-login.ts`:
1. **Navigation Whitelist**:
   - Only HTTPS requests to `courses.uit.edu.vn`, `sso.uit.edu.vn`, `daa.uit.edu.vn`, and `coursesold.uit.edu.vn` are allowed in the login WebView.
2. **Identity Extraction Script**:
   - On every page load in the authenticated domain, evaluate JS:
     ```javascript
     (() => {
       const cfg = window.M?.cfg || {};
       const loginInfo = document.querySelector(".logininfo");
       return {
         sesskey: String(cfg.sesskey || ""),
         origin: location.origin,
         profileHref: loginInfo?.querySelector('a[href*="/user/profile.php"]')?.href || null
       };
     })()
     ```
   - Parse `userId` from `profileHref` query parameter `?id=<userId>`.
3. **Cookie & Session Capture**:
   - Once `sesskey` (non-empty) and `userId > 0` are verified, extract cookies (`MoodleSession`, etc.) from `CookieManager.getInstance()`, persist them to encrypted storage (`EncryptedDataStore` / Tink), and **immediately destroy the WebView**.

---

## 3. Session AJAX Protocol (`MoodleAjaxHelper`)

Ported from `src/ajax-helpers.ts`:
- **Endpoint**: `POST ${baseUrl}/lib/ajax/service.php?sesskey=${URLEncoder.encode(sesskey)}`
- **Headers**: `Content-Type: application/json`, `Cookie: ${cookieHeader}`
- **Bracket-Key Normalization (`normalizeArgs`)**:
  - Converts flat CLI/Moodle parameter maps like `{"courseids[0]": 19207, "courseids[1]": 19589}` into nested JSON arrays/objects `{"courseids": [19207, 19589]}`.
- **Request Payload**:
  ```json
  [
    {
      "index": 0,
      "methodname": "core_course_get_contents",
      "args": { "courseid": 19207 }
    }
  ]
  ```
- **Response Unwrapping (`unwrapAjaxResponse`)**:
  - Expects a JSON array where element `[0]` has either `{"error": false, "data": ...}` or `{"error": true, "exception": {"message": "...", "errorcode": "..."}}`.

---

## 4. Dual-Engine Method Matrix (AJAX Primary -> HTML Fallback)

Ported from `src/api.ts` and `src/moodle-session-client.ts`:

### A. Course Discovery (`core_enrol_get_users_courses`)
Moodle's primary enrolment endpoint can omit hidden, past, or timeline-bucketed courses, or be disabled entirely.
- **Algorithm** (`api.ts:767-830`):
  1. Concurrently query `core_enrol_get_users_courses` AND `core_course_get_enrolled_courses_by_timeline_classification` for classifications:
     - `allincludinghidden`, `all`, `inprogress`, `past`, `future`, `hidden` (paginating with `limit = 100`, advancing `offset = nextoffset` with non-advancing cursor guards).
  2. Merge courses by `id`: primary enrolment fields take precedence, while sparse duplicates from timeline buckets fill missing fields (`coursecategory` / `categoryname`).

### B. Course Contents (`core_course_get_contents`)
- **AJAX**: `core_course_get_contents(courseid)`
- **HTML Fallback** (`GET /course/view.php?id=${courseId}`):
  1. Verify body/DOM contains `course-${courseId}` or `data-courseid="${courseId}"` and `.course-content` / `[data-region="section"]`.
  2. Parse sections (`[data-region="section"], li.section`) and activities (`li.activity[id^="module-"]`).
  3. Extract `cmid`, `name` (stripping `.accesshide, .sr-only`), `modname` (from `modtype_<type>`), `description` (`.contentwithoutlink, .activity-description`), and inline `pluginfile.php` links.
  4. **Resource Enrichment** (batched 4 at a time): For `resource`, `folder`, and `url` modules, fetch `/mod/${modname}/view.php?id=${cmid}&forceview=1` to discover nested `pluginfile.php`, `<object data>`, and `<iframe src>` files that only appear inside the activity view.

### C. Assignments (`mod_assign_get_assignments` & `mod_assign_get_submission_status`)
- **AJAX**: `mod_assign_get_assignments(courseids)` & `mod_assign_get_submission_status(assignid)`
- **HTML Fallback** (`GET /mod/assign/view.php?id=${cmid}&forceview=1`):
  1. Verify `<body id="page-mod-assign-...">`.
  2. Extract `instance` ID from `data-assignmentid`, hidden inputs `assignid`/`assignmentid`, or grade links `itemmodule=assign&iteminstance=(\d+)`.
  3. Extract `intro`, `introattachments`, `duedate`, `cutoffdate`, `allowsubmissionsfromdate`, or parse `.date-item` labels (`Opened:` / `Due:`).
  4. Parse `.submissionstatustable` for submission status (`submitted` / `Đã nộp để chấm điểm` vs `draft` / `Bản nháp`), `timemodified`, submitted files, and feedback grade.

### D. Assignment Submission (`submitAssignmentFile`)
Ported from `src/assignment-submission.ts` & `src/api.ts:960-1000, 1040-1084`:
1. **Upload Step**:
   - `POST ${baseUrl}/repository/repository_ajax.php?action=upload` (Multipart FormData: `sesskey`, `repo_id` = `"5"` or auto-discovered from `/user/files.php` `"type":"upload"`, `itemid` = random 9-digit draft ID, `repo_upload_file`, `title`).
2. **Save Submission Step**:
   - **AJAX**: `mod_assign_save_submission(assignmentid, "plugindata[files_filemanager]": itemId)`
   - **HTML Fallback**: Fetch `/mod/assign/view.php?id=${cmid}&action=editsubmission`, scrape hidden `_qf__*` form tokens, and `POST /mod/assign/view.php?id=${cmid}` with `action=savesubmission`, `sesskey`, `files_filemanager=${itemId}`, verifying no `.form-error` or `.notifyproblem` appears in the response.

### E. Forums & Announcements (`mod_forum_get_forums_by_courses` & `mod_forum_get_forum_discussions`)
- **Announcement Detection** (`commands.ts:535-553`):
  - Prioritize forum modules whose type is `"news"` or name matches `/thông báo|announcement|tin tức|news/i`.
- **HTML Fallback** (`GET /mod/forum/view.php?id=${cmid}&forceview=1` or `?f=${forumId}`):
  -Supports both `forumid` and `cmid` (critical because student news forums often hide the forum instance ID on SSO portals!).
  - Parses discussion rows (`tr.discussion`, `[data-region="discussion-list-item"]`), then fetches each discussion thread (`/mod/forum/discuss.php?d=${discussionId}`) in batches of 4 to extract the opening post HTML, author, timestamp, inline files, and attachments (excluding `.userpicture` avatars).

### F. Grades (`gradereport_user_get_grade_items`) & Participants (`core_enrol_get_enrolled_users`)
- **Grades HTML Fallback** (`GET /grade/report/user/index.php?id=${courseId}`):
  - Parses `table.user-grade, table.generaltable` rows for `.column-itemname`, `.column-grade`, `.column-range`, `.column-percentage`, `.column-feedback`.
- **Participants HTML Fallback** (`GET /user/index.php?id=${courseId}&perpage=5000`):
  - Parses `table#participants` rows, restricting identity extraction strictly to `/user/view.php?id=` links or `input[id^="user"]` checkboxes (never table header sorting links), plus roles (`.c2`), groups (`.c3`), and last access (`.c4`).

### G. Notifications & Inbox (`src/notifications.ts`)
- **Notifications**: `message_popup_get_unread_popup_notification_count`, `message_popup_get_popup_notifications`, `core_message_mark_notification_read`, `core_message_mark_all_notifications_as_read`.
- **Inbox Conversations**: `core_message_get_unread_conversations_count`, `core_message_get_conversations`, `core_message_get_conversation_messages`, `core_message_send_messages_to_conversation` (max 4,096 UTF-8 bytes, never auto-retried on failure).

---

## 5. File Security, Atomic Downloads & H5P Inspector

Ported from `src/api.ts:144-275` & `src/h5p.ts`:
1. **`credentialFreeUrl(url)`**:
   - Strips username, password, hash, and sensitive query params (`token`, `sesskey`, `password`, `auth`, `secret`, `key`, `signature`, `ticket`, `session`).
   - Rewrites `/tokenpluginfile.php/<token>/<contextId>/...` to `/pluginfile.php/<contextId>/...` so URLs stored in Room DB never leak tokens.
2. **8 KB Login-Page Sniffer (`fetchCourseFile`)**:
   - Reads the first 8,192 bytes of any file download stream. If it matches `<form.*login|name=["']?(?:password|logintoken)`, cancels the stream and throws `SessionExpiredException` instead of saving an HTML login page as a `.pdf`!
3. **Atomic Streaming Download (`writeCourseFile`)**:
   - Streams bytes to `${destPath}.part-${UUID.randomUUID()}` while computing `SHA-256` on the fly, then atomically renames to `destPath`.
4. **H5P Package Parser (`h5p.ts`)**:
   - Calls `mod_h5pactivity_get_h5pactivities_by_courses`, reads `h5p.json` and `content/content.json` directly from the ZIP stream in memory (up to 25 MB), and extracts structured slide/chapter titles, text items, and media references (YouTube, Vimeo, Google Drive, embedded PDFs/videos/audio).
