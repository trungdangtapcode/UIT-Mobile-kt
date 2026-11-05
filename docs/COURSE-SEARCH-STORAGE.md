# Course-search storage, file access and cleanup

This describes the implementation in PR #85, checked on November 5, 2026. It covers
course-search data and the original materials it reads. It does not describe every
other feature's database or imply that local checks synchronize the Moodle server.

## What is stored

| Data | Contents | Lifetime |
| --- | --- | --- |
| Original downloads | The selected course files and their SHA-256 sidecars | Until removed through the download flow, app storage is cleared, or the app is uninstalled |
| Search passages | Extracted source text, document version, cell/line locations and offsets | Until replaced, invalidated or the local index is deleted |
| Optional embeddings | Normalized Float32 vectors for prepared passages, plus model and dimensions | Same account-owned index lifetime as the passages |
| Search metadata | Selected source identity, chunk configuration, successful update time, and reuse/request receipt | Saved with the index |
| AI connection | The configured endpoint/mode/model and personal credentials | Separate encrypted connection storage; deleting the search index retains it |
| Current interaction | Query, result list, transfer plan and current progress | ViewModel memory; not persisted by the passage index codec |

Notebook extraction uses Markdown/code source cells, excluding outputs, execution
state and attachments. Original notebook downloads can still contain those fields:
extraction does not rewrite the original file.

The app stores one active course-search index per Moodle account. Keyword search
needs no vectors or provider connection. Optional semantic preparation stores its
vectors locally; there is no separate vector database server or cross-device
index synchronization in this implementation. New texts are sent directly to
OpenAI only after the user confirms the transfer. Query embeddings are not saved.

## Actual Android locations

Both variants use Android internal, app-private storage:

| Installation | Application ID | Typical primary-user data directory |
| --- | --- | --- |
| Normal app | `vn.edu.uit.mobile` | `/data/user/0/vn.edu.uit.mobile/` |
| Side-by-side preview | `vn.edu.uit.mobile.preview` | `/data/user/0/vn.edu.uit.mobile.preview/` |

Paths are illustrative. Android users/work profiles can have different user IDs;
use the app's runtime directory or `run-as ... pwd` instead of assuming user `0`.
Some tools show an equivalent `/data/data/<application-id>/` path.

Relative to that private application directory, the current implementation uses:

```text
files/
  materials/
    <account-folder>/
      <course-id>/
        <url-hash-prefix>-<sanitized-original-filename>
        <url-hash-prefix>-<sanitized-original-filename>.sha256
shared_prefs/
  course_passages.xml       # Encrypted owner registry and account-owned indexes
  offline_downloads.xml     # Encrypted download journal
  study_ai.xml             # Separate encrypted AI connection/settings
```

`StudyPrivateStorage` encrypts each preference value with AES-256-GCM and keeps the
key in Android Keystore. The file suffix `.xml` does not mean that passage text or
vectors are readable XML. Its values contain Base64 ciphertext; Base64 decoding
alone does not decrypt them. Copying this XML to another installation is not an
implemented index migration mechanism.

The original files in `files/materials/` are not additionally encrypted by that
wrapper. They rely on Android's private application storage and device protection.
The `.sha256` file verifies bytes; it is not encryption. The normal and preview
packages have separate app storage and do not automatically share credentials,
original downloads or vectors.

Implementation references:
[download paths and verification](../core/data/src/main/kotlin/vn/edu/uit/mobile/data/OfflineDownloadStorage.kt),
[encrypted storage wrapper](../core/data/src/main/kotlin/vn/edu/uit/mobile/data/StudyAiRepository.kt),
[index codec](../core/data/src/main/kotlin/vn/edu/uit/mobile/data/PassageIndexCodec.kt),
[preview package](../app/build.gradle.kts).

## Can a phone file explorer see these files?

**An ordinary file explorer cannot browse these private directories on a non-rooted
phone.** Android isolates internal app storage from other apps. Granting access to
shared files does not publish this app's private directory. See
[Android internal app storage](https://developer.android.com/training/data-storage/app-specific).

These paths are different from the visible Downloads folder and from
`/storage/emulated/0/Android/data/`. Looking for the package in the latter will not
reveal this implementation's materials or search preferences.

| Access method | Current behavior |
| --- | --- |
| Ordinary phone file explorer / USB file transfer | Cannot browse the private index or original-download directory |
| File explorer with all-files access | Does not gain access to another app's internal sandbox |
| Open a material from UIT Mobile | The app can grant an external viewer temporary read access to that one material through `FileProvider` |
| Save a separate copy through a viewer, if that viewer supports it | The chosen shared copy can be browsed independently; it is not the internal search index |
| Authorized ADB with a debuggable build | `run-as` can inspect that app's directory without rooting the phone, subject to device support |
| Root-capable tooling on a rooted phone | May inspect private files; the index values remain encrypted at the application layer |

The app currently has no decrypted passage-index export, no dedicated original-file
“Save to Downloads” action in this search view, and no public folder containing all
embeddings. Its `FileProvider` exposes selected materials with a read grant, not
`course_passages.xml` or a generally browsable document tree. Inspect
[the provider paths](../app/src/main/res/xml/file_paths.xml) and
[material opener](../feature/courses/src/main/kotlin/vn/edu/uit/mobile/courses/SavedFileOpener.kt).

## Inspect the debug preview from Windows without Android Studio

Use the official [SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools)
on the laptop connected to the phone. Android Studio and root access are not
required for this debug workflow. Install the preview APK, enable USB debugging,
connect the phone and authorize the laptop on the phone. In PowerShell, from the
extracted `platform-tools` directory:

```powershell
.\adb.exe devices -l
.\adb.exe shell run-as vn.edu.uit.mobile.preview pwd
.\adb.exe shell run-as vn.edu.uit.mobile.preview ls -l files/materials
.\adb.exe shell run-as vn.edu.uit.mobile.preview ls -l shared_prefs/course_passages.xml
.\adb.exe shell run-as vn.edu.uit.mobile.preview du -k files/materials shared_prefs/course_passages.xml
```

These commands list paths and sizes; they do not edit source files or print keys
or notebook contents. `du -k` reports filesystem usage in KiB, which can differ
from serialized payload bytes. Paths can be absent until a file has been downloaded
and an index has been saved. Substitute `vn.edu.uit.mobile` when inspecting a
normal-package **debug** installation; that substitution does not make a production
release debuggable.

The published `v0.1.7-search-preview.1` APK was checked with `aapt dump badging` and
reports `application-debuggable`. `run-as` requires an installed debuggable package
and an authorized shell; device policy can disable it. A non-debuggable production
APK normally rejects this access. See the
[AOSP run-as implementation](https://android.googlesource.com/platform/system/core/+/master/run-as/run-as.cpp).

Optional Android Studio users can use
[Device Explorer](https://developer.android.com/studio/debug/device-file-explorer).
Without a working ADB connection to the phone, SSH access to the Linux build server
alone cannot inspect the phone's filesystem. The existing
[ADB-over-SSH guide](DEBUGGING.md) describes the remote connection separately.

These inspection commands have not been executed against the owner's phone:
no device is attached to the server. The APK debug flag and storage paths were
verified from the artifact and production code.

## Disk usage: measurements and limits

For the actual authorized NT409.R11 `Lab01-student.ipynb` capture, the unchanged
update reused 103 vectors at 512 dimensions. Host artifact sizes were:

| Component | Bytes | Meaning |
| --- | ---: | --- |
| Original notebook | 86,545 | The original file, counted separately from the derived index |
| Raw Float32 vector matrix | 210,944 | `103 × 512 × 4`; already included in the serialized index below |
| Complete serialized index, UTF-8 | 378,163 | Text, vector encodings, source identity, configuration and update receipt |
| Estimated encrypted/Base64 preference value | 504,256 | Complete index after the storage wrapper, excluding XML/filesystem overhead |
| Original plus estimated stored index | 590,801 | About 0.59 MB decimal, or 577 KiB; not the entire app's storage usage |

The raw vector row is explanatory and must not be added to the complete index a
second time. This measurement is one notebook, not an average or a maximum. It is
not a physical-phone disk measurement, heap measurement or provider token count.
The receipt/reuse verification is recorded in
[the sanitized evidence](research/evidence/course-search-updates.json).

For `N` passages and `D` dimensions, the raw vector payload is `4 × N × D` bytes.
At the current 400-passage limit, default 512-dimensional vectors occupy 819,200
raw bytes; 1024-dimensional vectors occupy 1,638,400 raw bytes. Text and storage
encoding are additional.

The codec Base64-encodes vector bytes inside JSON. The encrypted preference wrapper
then Base64-encodes its 12-byte IV and ciphertext with a 16-byte GCM tag. For a
serialized UTF-8 index of `P` bytes, its stored value has this estimated length:

```text
4 × ceil((P + 12 + 16) / 3)
```

SharedPreferences XML markup, the owner registry, allocation units, temporary
backup files and unrelated app data are outside that estimate. The codec's
`5 × 1024 × 1024` limit checks the serialized string's length, not final on-disk
bytes; it must not be advertised as a 5 MiB disk quota.

Current indexing limits are eight files, 400 passages, 120,000 extracted characters
per file and 8 MiB verified source bytes per file. The source-size restriction is
an extraction limit, not a total limit on all course downloads or all app storage.
Each additional Moodle account can have its own active index. The UI exposes the
update receipt but does not yet provide an exact passage-index storage meter.
Android Settings → Apps → UIT Mobile / UIT Mobile Preview → Storage shows the
installation's aggregate usage; labels vary by device.

## What deletion actually removes

| Action | Local search index | Original private downloads | Other app/account data |
| --- | --- | --- | --- |
| Delete local index in Search details | Deleted for that Moodle account | Retained | AI connection and unrelated data retained |
| Reset local search indexes | All indexes in the passage-search namespace deleted | Retained | Other namespaces retained |
| Replace the selected course scope | Old active index for that account replaced | Retained | Other accounts and features retained |
| Remove a download through the app | Stale evidence is invalidated on validation; removing the file alone is not an immediate index wipe | That selected material/checksum is removed | Other data retained |
| Android Clear cache | Persistent search index retained | Retained | Removes eligible cache data, not all app storage |
| Android Clear storage / Clear data | Removed with the app's private data | Removed | Local accounts/settings/connection data also reset |
| Normal uninstall without a retain-data option | Removed with the app's private data | Removed | That installation's private app data removed |
| Install an APK update over the same package | Retained unless explicitly migrated/cleared | Retained | Updating is not a data wipe |

Account removal also fences passage operations and prunes that account's derived
index, including on repository initialization after a restart. Removing the preview
installation does not clear the separate normal installation.

Android documents removal of app-specific files on uninstall in its
[storage guide](https://developer.android.com/training/data-storage/app-specific).
Copies explicitly saved to shared storage, Downloads, another app or Drive have a
separate lifetime. GCM encryption and normal deletion are not a claim of physical
secure erasure of flash storage or destruction of copies already made elsewhere.

Uninstall does not delete the university account, original Moodle documents,
provider-side requests already submitted, or independent development/test artifacts
on the Linux server. The app has no backend endpoint that receives an uninstall
request to erase those external copies. Reinstalling and preparing an AI index
again can require new embedding requests because its local vectors were removed.

## Backup and migration limitation

The current manifest sets `android:allowBackup="false"`. It does not configure
`android:dataExtractionRules` or separate device-transfer exclusions. Android notes
that some Android 12+ manufacturers can still permit device-to-device transfer
with this flag. See
[Android backup and transfer rules](https://developer.android.com/identity/data/autobackup).

Consequently, this implementation does not promise uniform exclusion from every
OEM migration flow, and uninstalling one installation does not revoke independent
backups or exports. Explicit transfer-exclusion rules and device-specific acceptance
remain future hardening work; this documentation update does not change that policy.

## Verification boundary

Storage layout, encryption, deletion boundaries and code limits were inspected in
the production implementation. The NT409 sizes were recomputed from the private
host capture/index, publishing counts only. The preview APK's package/debug flag
was inspected. No university mutation, new provider call, root operation or
phone-data deletion was performed. ADB inspection on the owner's physical phone,
OEM transfer behavior and exact filesystem usage remain unverified on that device.
