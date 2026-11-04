# Course search: saved-file readiness and reviewed updates

## Product brief

A student has downloaded a lab notebook before class and wants to find an example.
They need to know which files are searchable, whether their saved copies changed,
and whether updating will send text to a paid AI provider. The main task is finding
course evidence; model settings should not dominate that task.

Domain: Moodle sections, lab notebooks, notebook cells, original downloads, source
versions, offline keyword search and selected course scope. Color comes from UIT
blue, navy reading surfaces, white paper, gray metadata and amber attention states.
The signature is a course file row with separate saved-file verification and search
readiness, followed by an explicit transfer receipt. Existing IBM Plex typography,
16dp gutters and asymmetric 8/28dp frames remain in use.

Avoid a statistics dashboard, repeated nested cards and an unexplained “Synced”
badge. Search retains a compact availability panel. Files and updates occupy a
separate phone view, with a fixed action area and a scrollable document list.
Advanced model/chunk parameters are in Search details.

## Primary design research — observed November 5, 2026

- [OneDrive Android: offline files](https://support.microsoft.com/en-us/onedrive/read-files-or-folders-offline-in-onedrive-for-android)
  makes file availability visible at the file itself. Adaptation: each course-file
  row has a textual state, rather than a course-level vector count alone.
- [Obsidian: sync status and activity](https://obsidian.md/help/sync/messages)
  separates a short current status from inspectable activity. Adaptation: show the
  last successful search update and its reuse/request receipt; keep technical
  details secondary. This app does not implement Obsidian cloud synchronization.
- [NotebookLM: automatic Drive source updates](https://workspaceupdates.googleblog.com/2026/05/keep-your-sources-up-to-date-with-automatic-Drive-syncing-in-NotebookLM.html)
  treats changed/deleted sources and lost permissions as a source lifecycle.
  Adaptation: rebind changed source locations, remove deselected sources, and fence
  account removal. NotebookLM's remote Drive auto-sync is not a capability claim
  for this app's downloaded Moodle files.

These are interaction references, not imported templates. No desktop layout or
new graphic asset is needed for this phone workflow.

## Student flow

1. Open Search → Search inside downloaded materials → choose a course.
2. Open Files & updates. Existing scope is selected automatically. Add downloaded
   files, up to eight; undownloaded files explain where to download them.
3. Choose optional Search by meaning. Changing this option or the selection
   invalidates the previous review. Checking never needs an API key.
4. Check saved files. The app reads verified downloaded bytes locally. Each
   selected file becomes Not prepared, Changed, Offline search ready,
   Offline + AI search prepared, or Cannot read this saved file.
5. Review search update. See selected files, searchable passages, reused vectors,
   unique new texts/characters, excluded files and whether OpenAI will be called.
6. Confirm. If no new embeddings are needed, updating works without an AI key.
   Otherwise the action requires a configured Direct connection and states that
   new text leaves the phone. Search queries require their own explicit submission.
7. Turning off meaning search explicitly replaces saved AI search with a keyword-only index; the confirmation warns that enabling it again needs new embeddings.
8. Progress reports newly embedded texts. The previous generation stays active
   until verification and publication finish. The final receipt reports reused
   passage rows, newly embedded passage rows and actual embedding request count.

A timestamp says “Saved files checked”, never “Moodle synced”. The screen explicitly
instructs students to download the latest course file first. The last successful
update timestamp is persistent; the current check is deliberately session-local.

## Failure and accessibility contract

- Missing, unsupported or unreadable selected files block publication. Deselect
  them or redownload them; PDF extraction may still be partial and has no OCR.
- Source/version changes detected by preflight block the update before any embedding call.
  Source changes while a request is running block final publication.
- A failed semantic update preserves an existing smart-search generation. If no
  smart-search generation existed, the local keyword index can still be saved and
  the semantic failure is explicitly shown.
- Stop cancels publication. Already-sent provider requests may be billed. New
  interrupted batches are not persisted for resume; the UI explains this limit.
- Account removal, clear and reset fence late work. Original downloads are retained.
- States are written as text; color is supplementary. Native checkbox/switch/button
  touch targets remain at least 48dp. Long file names wrap. The list scrolls while
  check/review actions remain reachable. Review/settings dialogs retain their
  action footer while their body scrolls at larger font sizes.

## Boundaries

This increment is local, explicit, and single-device. It does not poll Moodle,
watch remote files, schedule background embedding, sync vectors across devices,
resume partial cloud batches, or provide page-aware PDF extraction. These remain
visible limitations, not success states. Issue #52 stays open for its wider gates.

See [algorithm and lifecycle](../research/COURSE-SEARCH-UPDATES.md) and
[phone test guide](../COURSE-SEARCH-PREVIEW.md).

## Actual-material phone review

These native Compose renders use the authorized NT409.R11 notebook capture and
production saved-file check. Source contents were not authored for screenshots.
The reused-vector state has an actual committed 103/0/0 receipt. The new-transfer
state follows a separate production keyword-only preparation of that same source
and an unsent semantic plan. No new university or provider requests were made.
Identity is anonymized. These are host renders; Android window dimming and physical
keyboard/system behavior need the preview APK acceptance test.

| View | 360dp | 390dp | 412dp / 1.3× text | Light |
| --- | --- | --- | --- | --- |
| Files and freshness | [Phone](course-search-updates/updates-files-360.png) | [Phone](course-search-updates/updates-files-390.png) | [Phone](course-search-updates/updates-files-412-large-text.png) | [Phone](course-search-updates/updates-files-390-light.png) |
| No-transfer review | [Phone](course-search-updates/updates-review-360.png) | [Phone](course-search-updates/updates-review-390.png) | [Phone](course-search-updates/updates-review-412-large-text.png) | [Phone](course-search-updates/updates-review-390-light.png) |

- [New text transfer preview](course-search-updates/updates-new-transfer-390.png)
- [Saved update receipt and details](course-search-updates/updates-details-390.png)
- [Capture provenance and image hashes](course-search-updates/provenance.json)
- [Actual-source verification counts](../research/evidence/course-search-updates.json)

Reviewed wrapping, fixed actions, larger text, source identity, readiness wording,
explicit transfer, missing connection, and close/cancel interactions. Failure,
unsupported-file and account-race states use automated fixtures without screenshot
exports. Physical phone acceptance remains open.
