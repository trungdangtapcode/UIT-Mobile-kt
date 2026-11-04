# Course PDF reading and local annotations

Issue [#32](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues/32) adds native
course-PDF reading with remembered position, page bookmarks and page-linked text
notes. It builds on PDF support from [#68](https://github.com/trungdangtapcode/UIT-Mobile-kt/pull/68),
with a shared Android `PdfRenderer` wrapper. The separate student-profile viewer
continues to keep scan state temporary; course reading adds its own local store.

## Open and read

Open **Courses → Reading workspace → Read in app** for a verified saved PDF.
The library scans saved snapshot/journal metadata and checks local downloads; it
does not refresh the university, download files or send content to AI. If no PDF
is available, download one through the existing Courses/Offline downloads flow.

PDF opening from course materials, Offline downloads and workspace search (including
Saved items file routes) opens the same native reader. Other file formats keep the
existing external-app handler. Download effects retain their exact course/file
metadata and an obsolete course download cannot open under a newer selection.

- Use Previous/Next or the page-number action to jump to a specific page.
- Pinch, double-tap, or use zoom buttons. Drag to pan an enlarged page. At fit-page
  scale, a vertical swipe can scroll the surrounding phone reading controls.
- **Fit width** derives zoom from the measured page/viewport and aligns its top;
  **Fit page** restores the overview. Zoom stays within 1–4× with bounded pan.
- The page remains its original rectangular white document, including in dark
  appearance. Chrome uses the existing UIT theme and cut-corner controls.

Page number, zoom and normalized horizontal/vertical translation are saved after
500 ms of inactivity. A page change, explicit save retry, export or ordinary Back /
bottom-navigation action flushes the pending position first. A failed save keeps
the workspace open with Retry rather than silently dropping the change. Sudden
process termination or an external widget route can lose the short pending
position update; previously committed notes/bookmarks remain saved. Opening a
file again restores its committed position. Window recreation keeps the Activity's
ViewModel; a fresh process starts from the normal app navigation.

## Bookmarks and notes

The bookmark icon toggles the current page. The Bookmarks tab lists page numbers
and navigates to their pages. These are document-page bookmarks, separate from
Saved items, which pins a course/file reference.

Tap **Add note**, then tap the page to place a numbered pin. Enter a text note and
explicitly save. The Notes tab lists page-linked notes, opens their pages and
provides Edit/Delete. Pins open the corresponding note editor. A note stores its
page and normalized coordinates, so zooming or changing phone size does not attach
it to a different source location. Notes are local text annotations; this does not
add text-selection highlights, freehand drawing, OCR or embedded PDF annotations.

An unsaved note draft lives in the ViewModel, including across ordinary window
recreation, and is excluded from saved-instance-state bundles. Failed saves retain
the draft. An uncertain committed write retries the exact pending change, with
optimistic record revision checks, avoiding duplicate notes. If another writer
changed the record, **Reload saved data** explicitly discards pending local state
and the draft after confirmation; it does not overwrite the newer saved record. An explicit Return to library without retrying action also closes a failed workspace after confirmation; a previous uncertain write may already have completed.

## Document identity and account lifecycle

A reading identity contains the owning account, the existing course/file download
identity and SHA-256 of the captured PDF bytes. Records are stored separately for
each account in the existing Android Keystore AES-GCM storage with an independent
`reading` preference namespace / `uit.reading.v1` key alias.

The loader reads only a verified owner/course cache entry, bounds captured bytes
and hashes them again against the checksum sidecar. This rejects replacement
between the initial cache check and reading. The renderer consumes an independent
snapshot through a temporary file in the app's private cache, unlinked before the
native renderer opens pages. Handles close on leaving, replacement, account removal
and ViewModel destruction. The original course PDF is never edited by reading,
bookmarking, notes, export or reading-record deletion.

A changed PDF starts a separate reading record; old notes are not relocated onto
new pages. The reader shows when other versions are saved. **Saved reading records**
retains old notes and bookmarks even when the original downloaded file is absent.
Review/export/delete those records from the library. Archived review does not open
page images or pretend it has the unavailable original version. Current PDFs are
opened separately from the verified-file section.

Removing an account clears its active reading/draft/export state and its encrypted
reading bucket using the existing account-removal hook. Other accounts are kept. Once the local session is removed, cleanup runs despite cancellation, and a reading-store cleanup error does not skip the snapshot/material purge.
Late loads/writes cannot restore a removed owner's visible workspace; late writes
also clean their bucket. An unreadable owner's bucket is preserved with an error,
while another owner's records remain available. Reading does not edit university
accounts, submit work, send messages, execute PDF links/scripts or call a model.

## Export and deletion

**Export reading notes** first flushes the position, checks the saved revision and
shows a preview. **Choose export location** uses Android's `ACTION_CREATE_DOCUMENT`
with `application/json`. The app writes only after the system picker returns the
student's chosen URI and the selected record/account still passes validation.
Canceling the picker does not export. A pending export stays in Activity-owned
memory across window recreation; owner removal or a record change invalidates it.
A writer failure reports an export error and preserves the reading record.

The version-1 JSON sidecar includes:

- `format: "uit-course-reading"`, `version: 1` and one-based page numbering;
- document title, content-version SHA-256 and page count;
- reading position, sorted page bookmarks, and text notes with normalized pin
  coordinates plus creation/update epoch seconds.

It omits PDF bytes, account IDs, course IDs, authenticated URLs and credentials.
Note text and document names are exported verbatim and may contain whatever the
student chose to write. An export is a separate file outside app storage; later
account removal does not revoke it. The app does not import sidecars or write
annotations back into PDF bytes. If the process dies during the system picker,
retry the export; private payloads are not saved into Android state bundles.

Delete requires confirmation, for an individual note or the entire current
version's reading record (position, bookmarks and notes). The original PDF and
other versions remain. A failed deletion preserves the displayed record for retry.

## Bounds and explicit limits

| Boundary | Limit |
| --- | --- |
| Captured PDF | 25 MiB; verified `%PDF-` header |
| Document | 1–5,000 pages |
| Rendered page | 4,096 px maximum dimension, at most 8,388,608 pixels; page aspect ratio 1:32–32:1 |
| Render scale | Course pages up to 4× PDF points, profile scans up to 2×; pixel bounds still apply |
| Page bookmarks | 200 per document version |
| Text annotations | 100 per document version, 4,000 UTF-16 code units each |
| Owner collection | 50 document versions; 500,000 UTF-16 note-text code units total; serialized bucket at most 2,000,000 code units |

Corrupt, password-protected, inaccessible and unsupported PDFs have a recoverable
error; no password prompt or claim of universal PDF support is provided. Page
rendering failures retain the previous page and controls. Unreadable local reading
state disables note editing while allowing the verified page to be read. At the saved-version limit, a new PDF can still be read without creating a record; return to the library to remove older records before saving new reading data.

Navigation, bookmark/note actions and zoom controls have native accessibility
semantics and support larger text. Page images have page-number/gesture descriptions,
but the rendered PDF itself does not provide selectable or TalkBack-readable body
text. Physical-device keyboard, gesture and document compatibility testing remains
useful beyond the host/emulator coverage. See the [evidence](COURSE-READING-EVIDENCE.md)
and [phone design brief](design/COURSE-READING.md).
