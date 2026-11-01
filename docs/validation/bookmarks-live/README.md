# Read-only bookmark recheck

The current captures were produced by a fresh owner-authorized authenticated host
check of the merged bookmark feature on November 1, 2026. They show public course
metadata under a private account label, with student identifiers and credentials
excluded. Optional PNG text/EXIF metadata was removed without changing pixels.

## Scope and results

- Read the real Moodle catalogue (14 courses) and student-guide course metadata.
- Pin a real material/module reference and course; recreate the production local
  bookmark repository from private host storage and verify restored identities.
- Open the exact locally indexed source through the production search ViewModel
  and Compose controls. This does not open a remote module or discussion.
- Add a credential-free public UIT link, remove only the material/module bookmark,
  and verify the other two references remain.
- Verify no file downloads and clear only the isolated local account mirror.

The captures use host Compose rendering and a private file-backed storage adapter,
not an installed APK. External browser launching and Android Keystore are outside
this authenticated check. PR #66 separately passed all four synthetic Android 11
emulator tests, including real Keystore restoration and account-scoped removal.

## Network restrictions

The private harness permitted only explicit read RPCs and catalogue/course-page
GET requests. The ordinary metadata loader encountered the stricter restriction;
the final check used the production course parser without following activity
links. It did not weaken the guard to allow resource views.

The successful metadata-only run recorded these requests after authentication:

| Allowed operation | Count |
| --- | ---: |
| `core_enrol_get_users_courses` | 1 |
| `core_course_get_enrolled_courses_by_timeline_classification` | 10 |
| `core_course_get_contents` | 1 |
| `GET /course/view.php` | 1 |

Submission, messaging, profile/account edits, completion actions, remote bookmark
changes, activity views, discussion threads and downloads were blocked or never
requested. Normal login and access logs can still be recorded by UIT. No account
session, password, raw response or private test harness is committed here.

## Independent visual review

A separate subagent inspected all four checked-in collection/search previews at
360/390/412dp and the sanitized authenticated collection captures. It also rendered
the add-link dialog with synthetic accounts at 360dp, Vietnamese, 1.3x font size,
and an additional constrained 500dp viewport. The dialog fields scroll and the
Save/Cancel controls remain visible. The short viewport is host coverage, not an
actual Android keyboard/device test.

The review reproduced a medium-priority navigation issue: Saved items loses its
query/category state after opening an item and returning. The local filter state
is scoped to `BookmarksContent`, which is removed from composition during the
detail route. Bookmark records are preserved. This review records the finding;
it does not change the production implementation.
