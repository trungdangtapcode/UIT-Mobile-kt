# Local bookmarks

Open **Courses → Saved items**, or **Search → Saved items**, to see the local
collection. Pin/unpin courses from the directory, and course/material/file,
assignment and attributable DAA notice results from workspace search. Moodle
discussion cards expose a bookmark toggle. Use **Add UIT link** to save an official
HTTPS page, including a public UIT Forum topic, under a connected account.

These are personal bookmarks on this phone. They do not create remote Moodle or
UIT Forum bookmarks, change watch/read settings, submit work or send messages.
The native UIT Forum reader remains separate work in issue #39. Timetable/exam
search rows are not bookmark targets in this release.

## Opening and availability

The list supports text/category filtering and preserves each item's account label.
Supported source items reopen their exact current saved metadata using the shared
search detail and file-opening workflow. Back returns to Saved items; opening a
course retains the course → detail → saved list return path.

Bookmarks save references, not document contents or downloaded file copies.
Pinning does not fetch a file. The existing explicit file action downloads or
reuses its verified private copy. Load the course in Courses to populate its file
metadata; a bookmark does not initiate background service refreshes.

A file reference uses account + course + URL hash, so a renamed file or the same
URL moved to another module resolves against current metadata. A different URL is
a different resource. Other source references use stable course/module/assignment
or attributable notice keys. Removed or unloaded content remains listed with its
saved title, an unavailable explanation and a working unpin action. Its Open action
stays disabled until the source is present again; no unrelated item is substituted.

Discussion bookmarks and manually added UIT links open their canonical original
URL in a browser. The browser may need its own university login or report that the
page is unavailable; remote availability is not inferred from local metadata.
No app cookies are forwarded to another application. Manual links require HTTPS
on `uit.edu.vn` or its subdomains and reject credential-bearing URLs, sign-in
routes, session/token query parameters and token fragments. Ordinary query strings
and escaped path segments are preserved.

## Storage and account isolation

The collection holds up to 200 distinct items per account. Re-saving the same
identity updates its metadata and retains the original saved time. Persistence
uses a separate `bookmarks` preference namespace and Android Keystore AES-GCM
alias `uit.bookmarks.v1`, reusing the existing private-storage implementation.
Each account has its own hashed bucket; records retain bounded titles, context,
references and original URLs for external links, not source bodies or session data.

Only connected accounts contribute items or resolved sources to the screen.
Removing an account clears its bookmark bucket along with existing account data.
Writes recheck ownership and clean late data even if account removal or
cancellation overlaps a write. A malformed/unreadable bucket produces an explicit
load error and is retained; another account's valid bucket remains available.
Reload retries local storage only. A failed link save keeps the editor draft open.
The URL draft is not written to Activity saved state.

Moodle forum reads belong to the selected-course operation: changing courses
or removing the selected course cancels the request, and a late response cannot
populate another course's discussion list or bookmark action.

## Validation and phone previews

Synthetic tests cover stable identities, URL rules, persisted restoration,
deduplication, account separation, late-write cleanup, malformed buckets, capacity,
source disappearance/return, dialog failure/draft preservation and stale forum
responses. Compose tests exercise independent pin/open actions and render actual
360/390/412dp phone content, including Vietnamese at larger text size. Activity
navigation tests check the saved item → detail → course → back path and existing
widget routes.

Recorded bookmark previews use full-window Roborazzi capture and the host hardware
PixelCopy mode to include retained drawing layers. This remains host JVM rendering,
with the same device-validation limits described in the host testing guide.

`BookmarksPersistenceTest` is an instrumented Android check using synthetic
accounts and a temporary Keystore alias. It verifies encrypted restoration and
account-scoped removal without making university requests; it requires a device
or emulator, including the existing CI emulator job.

```sh
./gradlew :core:domain:test :core:data:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests '*Bookmark*' -PrecordUi=true
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest check
```

See the [phone gallery](design/README.md) and
[host testing boundaries](HOST-ANDROID-TESTING.md). Implements
[issue #9](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues/9).
