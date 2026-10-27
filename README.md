# UIT Mobile

An Android phone app unifying UIT Moodle and the DAA academic portal. Built from the
[implementation plan](docs/plan/README.md), with protocol behavior ported from
[`uit-cli`](https://github.com/RyanNg1403/uit-cli).

The app has five destinations: Today, Schedule, Courses, Deadlines & Inbox, and
Academic. It supports current UIT SSO and linked undergraduate/graduate legacy
Moodle accounts. Sign-in uses a temporary WebView; passwords stay inside the
portal. Moodle and DAA sessions are encrypted with an Android Keystore AES-256-GCM
key before being stored in DataStore.

## Interface and previews

The Compose interface is designed for portrait Android phones at 360dp, 390dp
and 412dp widths. It uses UIT blue, the transparent lotus/orbit emblem, bundled
IBM Plex fonts and a subtle campus background. Frames keep three small cut corners
and a larger physical bottom-right cut.

Five bottom destinations share a single-column phone layout: daily timetable and
submissions, Day/Week/Month scheduling, course materials, deadlines/messages and
academic records. Conversations open as a separate phone view with Back navigation.

See the [phone preview gallery](docs/design/README.md) and
[mobile scope](docs/design/MOBILE.md). Previews render on a rootless server without
an emulator. Older layout experiments are historical, not current product targets.

## Build and run

Use JDK 21, Android SDK platform 35 and build tools 35.0.0. The checked-in Gradle
wrapper downloads Gradle 8.11.1 and verifies its SHA-256 checksum. Android 8.0
(API 26) or newer is required on the device.

Set `ANDROID_HOME` to your SDK directory, or create an untracked `local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

```sh
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Open **Academic → Accounts & appearance → Sign in with UIT SSO**. The browser
captures Moodle identity, then visits DAA using the same CAS session. Legacy
accounts use separate login sessions. Grant notification permission when offered.
No real credentials, captured cookies, or API tokens are included in this project.

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

For rootless SSH development, ADB tunneling, breakpoint debugging, and the limits
of Compose Live Edit, see [Debugging over SSH](docs/DEBUGGING.md).
The current command-line workflow builds and installs a debug APK; a live-update
development environment has not been configured.

## Verification

```sh
# JVM protocol, security, parser and domain tests, without an Android SDK
./gradlew -Pheadless test check

# Full Android build, Room/MVI/repository tests, Android lint and static checks
./gradlew :app:assembleDebug check

# Apply the Kotlin formatter
./gradlew -Pheadless formatKotlin
```

JUnit 5 covers pure Kotlin modules. Android unit tests use JUnit 4, Robolectric,
coroutines-test and Turbine. Static checks include ktlint, Detekt's coroutine and
potential-bug rules, and a dependency-boundary check. Room exports its versioned
schema under `core/database/schemas/`.

## Behavior

* Screens observe Room snapshots immediately. Refreshes replace data in
  transactions; failed requests retain previously cached portal data.
* Authenticated requests are restricted to HTTPS and their exact portal origin.
  Every redirect is checked before cookies are attached. A 303 after a write is
  followed with GET; writes are never replayed across 307/308 redirects.
* Files are inspected for login HTML before writing, streamed to a temporary file,
  hashed with SHA-256, and renamed atomically. Verified local copies can be opened
  offline through a restricted FileProvider. Account removal deletes its cache.
* The schedule uses `Asia/Ho_Chi_Minh`, UIT's ten lesson periods and four exam
  shifts. Cancellation notices match the exact class, date and overlapping
  periods. Make-up sessions, exams and Moodle deadlines appear together.
* File uploads require a confirmation dialog. The action uploads and saves the
  file; assignments configured with a separate final submission step still need
  that step in Moodle. Private messages are never automatically retried.
* WorkManager refreshes periodically with network and battery constraints.
  Notification delivery is deduplicated in Room. Android controls execution time,
  so 24-hour/1-hour reminders are opportunistic, not exact alarms.

## Validation limits

The implementation is tested against deterministic fixtures and mocked HTTP,
including cases ported from the CLI's tests. DAA fixtures are **synthetic examples
of the planned Drupal markup**, not authenticated production snapshots. Live CAS,
portal-specific selectors, upload permissions and device rendering need validation
with a UIT account and device. No live submissions or messages were sent during
implementation.

Unavailable Moodle student AJAX methods fall back to supported HTML parsers;
unsupported writes and inbox capabilities report a typed error rather than
inventing a successful response. Optional OEP integration and an iOS target are
not enabled. The reusable core is JVM Kotlin; the shipping shell is Android.

See [implementation coverage](docs/IMPLEMENTATION.md) for module responsibilities,
test coverage and remaining live validation.

## Contributing and history

See [CONTRIBUTING.md](CONTRIBUTING.md), the [commit contract](docs/COMMIT-CONTRACT.md)
and the [initial history reconstruction](docs/HISTORY.md).
