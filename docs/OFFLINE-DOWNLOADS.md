# Offline download manager

Open **Courses → Offline downloads**, choose a course and an actual saved Moodle
section/week, or all saved sections. Load file metadata explicitly when needed;
this reads course sections and assignment attachments without downloading files.
Review/select individual resources and queue up to 100 files at a time. The list
holds at most 500 resource identities. Shared resources retain every section
membership but transfer only once per selected pack.

The queue runs one file at a time. Progress counts files after checksum verification,
with an indeterminate indicator for the active transfer; it does not invent byte
percentages. Each item exposes its own Cancel, Retry, Open saved file and Remove
controls. Partial failures retain successful files and can be retried individually.
Removing one resource deletes its own bytes/checksum, not another course/account.

## Wi-Fi, lifecycle and persistence

Wi-Fi only is enabled by default for queued packs. Cached resources can be verified
and adopted without Wi-Fi. A missing resource waits for Wi-Fi, and losing Wi-Fi
cancels the active request before resuming the queue when Wi-Fi returns. A VPN
without Wi-Fi transport evidence is treated conservatively as not Wi-Fi. Turning
the preference off permits other available connections.

This setting governs queued packs. Opening files elsewhere uses the existing
individual-file workflow and can use mobile data. Queued work runs while the app
process remains alive; no foreground-service or OS background-completion guarantee
is provided. Keep UIT Mobile running while downloading. After process death, pending
work becomes interrupted and requires explicit Retry. Configuration changes retain
the activity-owned queue. Closing the manager screen does not cancel the queue.

The queue and Wi-Fi preference use a separate Android Keystore AES-GCM journal.
It stores resource metadata/URLs and status, not passwords, cookies or new copies
of university records. It has its own encryption alias/preferences; existing study
AI settings retain their original defaults. Malformed metadata is rejected and
existing resource files are retained if journal loading fails.

## Verified files and account isolation

Authenticated transfers reuse the existing origin/TLS guards, login-HTML rejection,
streaming temporary files and atomic replacement. A checksum is written atomically.
A saved status requires checksum verification; opening rechecks the copy. A missing
or corrupted resource becomes Needs retry. Existing verified copies are reused
without a network transfer. Checksums establish local integrity, not whether the
remote content at a reused URL has changed.

Queue identity is account + course + URL. Re-queuing renamed metadata preserves
the original saved filename/locator until removal, avoiding duplicate orphaned
copies. Resource names are UTF-8 bounded and scoped under the private account/course
folder. Storage totals include individual downloads, checksum metadata and transient
temporary files in connected accounts' resource folders; the queue lists resources
that were queued through this manager.

Removing an account clears its queue records and cancels active work. The existing
account removal deletes its resource folder; a transfer completing after removal
is checked against the vault and cleans late bytes instead of saving a new copy.
Obsolete open-file effects cannot open another account's files.

## Validation

Synthetic tests cover pack deduplication, shared section membership, same-URL
renaming, individual retry/removal, Wi-Fi gating and loss, cancellation, restart,
cache corruption, account removal, late-transfer cleanup, journal round trips and
Unicode paths. Compose tests render 360/390/412dp phones, including larger text,
section selection, explicit queueing and separate Open/Retry/Remove controls.
No private account credentials are required by these fixtures.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :core:data:testDebugUnitTest \
  :app:testDebugUnitTest --tests '*OfflineDownloads*' -PrecordUi=true
JAVA_HOME=/path/to/jdk21 ./gradlew :app:assembleDebug \
  :app:assembleDebugAndroidTest check :app:testDebugUnitTest
```

Implements [issue #6](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues/6).
