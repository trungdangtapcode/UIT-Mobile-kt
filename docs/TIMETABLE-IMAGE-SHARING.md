# Shareable timetable images

Open **Schedule → Share timetable image**. Choose one to seven dates in Vietnam
time, inspect the preview, then use **Share PNG** to open Android's share chooser.
The image contains saved classes, canceled classes and make-up lessons only;
exams, deadlines and personal planning blocks are excluded. Empty dates explicitly
say there are no classes in saved data. Without a saved workspace snapshot, sharing
is disabled.

## Image contents and privacy

- Course names and class codes are enabled initially and can be hidden together.
- Rooms are hidden initially and can be included independently.
- Student name and ID are hidden initially. The option is disabled if neither
  field is available; no placeholder identity is invented.
- Lecturer details, profile portraits, other profile fields, portal URLs and
  credentials are never passed to the renderer. Make-up notice text can contain
  private reasons or locations, so its title is replaced with a known course name
  when available rather than copied into the image.

The domain projection removes unselected fields before drawing. It does not cover
them with a visual mask or attach the raw snapshot as image metadata. **Read image
contents** provides a native text version of the same redacted preview. Source
titles are bounded to 300 characters with a visible ellipsis, class codes to 80,
rooms to 120, and optional identity fields to 120/64 characters.

Preview rendering stays in memory. Only the explicit Share action creates a file.
The exact preview bitmap is encoded as PNG; changing visible source data, options,
locale or font scale prepares a new preview. The current plan and local account
session are checked before and after encoding, before the chooser opens. An
account-session change resets the form and cancels an unfinished share.
Opening a schedule widget closes the image screen and focuses the requested date.

The chooser sends only an `image/png` stream via the existing `FileProvider`, with
a temporary read grant and matching ClipData. It does not send timetable text in
Intent extras, grant write access or expose other cache directories. Recipients
can save or forward the confirmed image; the app cannot retract those copies.
Opening a chooser does not establish that another app received or delivered it.

## Layout and limits

The exported image is a single phone reading column, 1080 pixels wide and at most
7800 pixels tall. It uses the existing transparent UIT emblem, bundled IBM Plex
fonts, UIT blue and dark palette, and asymmetric 8/28dp cut-corner day-entry frames.
Dates and teaching times use GMT+07. Canceled and make-up entries retain explicit
labels from the shared schedule rules. The saved snapshot time and a reminder to
check for updates remain in the image.

Text scales with the device setting from 1× to 2×. The planner rejects more than
60 entries; the renderer measures wrapped text before allocating a bitmap and
rejects an image beyond its height limit. Choose fewer dates when the content or
larger text cannot fit. No entries are silently dropped to make a file smaller.

## Temporary copies

Confirmed shares use random filenames in private `cache/timetable-shares`, a
separate narrow FileProvider path. They are intentional unencrypted PNG copies,
not bookmark or profile storage. Incomplete files are written with a `.part`
suffix and atomically renamed after encoding. A failed or canceled in-app share
deletes its copy; leaving the screen cancels a pending write.

After the Android chooser opens, a copy stays available for the receiving app.
Cleanup runs at app startup, entering the feature and preparing another share:
it removes matching copies/partial files at least an hour old and keeps at most
seven previous matching files before creating the next one. At most eight copies
are retained after a successful write. Cleanup is opportunistic, not an exact
one-hour background alarm; force-stopping the app can retain files until its next
startup. Unrelated cache files and downloaded course materials are not deleted.

No university refresh, submission, account edit, upload or message is performed by
this feature. It uses the currently saved schedule and opens the selected Android
sharing app only after confirmation.

## Validation

Domain tests cover recurrence, canceled/make-up entries, date/entry limits, empty
days, bounded text and redaction. Host Android tests verify that changing hidden
fields cannot alter image pixels, PNG decode matches the preview, FileProvider
grants are read-only, temporary cleanup is scoped, and oversized content fails
before creating a cache file. Compose checks cover explicit confirmation, option
changes, source changes, invalid ranges, empty dates, navigation, and actual
360/390/412dp renders including Vietnamese at larger text size.

The synthetic instrumented `TimetableImageSharingTest` exercises native Canvas,
PNG and FileProvider on an Android device/emulator without university requests.
Host previews do not prove physical-device keyboard, TalkBack, or delivery to a
third-party sharing app. See the [host testing guide](HOST-ANDROID-TESTING.md).

```sh
./gradlew :core:domain:test
./gradlew :app:testDebugUnitTest --tests '*TimetableImage*' -PrecordUi=true
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest check
```

Implements [issue #15](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues/15).
