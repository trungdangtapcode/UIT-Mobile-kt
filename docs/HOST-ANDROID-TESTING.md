# Testing Android features without a local Android device

UIT Mobile can run repository/ViewModel tests and render its Android Compose phone
content on a Linux host JVM. This was used over rootless SSH for the authenticated
search and offline-download checks on October 31, 2026. No APK was installed on
that server, and no local Android emulator or ADB device was involved.

The resulting PNGs are executions of the app's Compose components, not HTML
mockups. They capture a configured phone viewport rather than a running phone's
entire Android environment. This workflow does not provide hot reload or a browser
version of the product.

## What runs where

| Layer | Execution environment | What the result establishes |
| --- | --- | --- |
| Pure Kotlin tests | Host JVM, `-Pheadless` | Parser, protocol and domain behavior; Android modules are excluded. |
| Robolectric and Compose tests | Host JVM, Android test runtime and resources | Repository/ViewModel behavior and actual Compose content, including semantic clicks, scrolling and image capture. |
| Authorized live integration | Headless Chromium for SSO, then host JVM for HTTPS and feature logic | Compatibility with the account's current service responses and real downloaded bytes. Selected platform inputs can still be controlled by the harness. |
| `sign-in-smoke` CI job | Android emulator on a GitHub-hosted runner | Native Android WebView/form rendering and the instrumented interactions in `app/src/androidTest`. This emulator runs elsewhere, not on the SSH server. |
| Installed APK | Connected phone or emulator | Device-specific integration, system UI and runtime behavior exercised on that target. |

Local Android tests live under `src/test`; instrumented tests live under
`src/androidTest`. See Android's [local-test guide](https://developer.android.com/training/testing/local-tests).
The CI emulator configuration is in [the Android workflow](../.github/workflows/android.yml).
CI receives no student credentials; its public sign-in smoke test stops at the
SSO password form.

## Why Android code can run on the JVM

[Robolectric](https://robolectric.org/architecture/) loads instrumented Android
framework implementations into the host JVM. Its shadows replace selected system
and native behavior. That lets tests create an Android test Activity and execute
Compose without booting an Android OS image.

[Roborazzi](https://github.com/takahirom/roborazzi) captures rendered output using
Robolectric's native graphics support. In this repository,
`@GraphicsMode(GraphicsMode.Mode.NATIVE)` enables that rendering path. Native
graphics here describes the host rendering implementation; it does not mean a
physical Android device was used.

The same separation applies to networking: production Kotlin readers and HTTP
transport can make real HTTPS requests from the host. Choosing a JVM test runner
does not require replacing all network responses with fixtures.

## Reproduce the checked-in phone previews

Use the repository's JDK 21 and Android SDK setup described in
[the build instructions](../README.md#build-and-run). These Android-module tests
still need the SDK, resources and downloaded Robolectric runtime dependencies.
They do not need Android Studio, an ADB connection, KVM or root privileges. The
host must have the native libraries required by the rendering dependencies.

From the repository root:

```sh
# Run the existing synthetic Compose interaction tests without writing PNGs.
./gradlew :app:testDebugUnitTest \
  --tests '*WorkspaceSearchDesignTest*' \
  --tests '*OfflineDownloadsDesignTest*'

# Render those same phone scenarios into the tracked preview gallery.
./gradlew :app:testDebugUnitTest \
  --tests '*WorkspaceSearchDesignTest*' \
  --tests '*OfflineDownloadsDesignTest*' -PrecordUi=true
```

The app's Gradle configuration maps `-PrecordUi=true` to
`roborazzi.test.record=true` and sets `uit.preview.dir` to
`docs/design/previews`. Without recording enabled, assertions can pass without
producing new screenshots. Inspect the generated images and their Git diff before
committing them. Test reports are under `app/build/reports/tests/testDebugUnitTest`.

Do not combine these commands with `-Pheadless`: that repository property removes
`:app` and the other Android modules from the build. It is useful for pure JVM
tests, not Compose rendering.

The existing [search design test](../app/src/test/kotlin/vn/edu/uit/mobile/WorkspaceSearchDesignTest.kt)
shows the setup:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    qualifiers = "w390dp-h844dp-mdpi",
    application = Application::class
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneDesignTest {
    @get:Rule val compose = createComposeRule()
    // setContent { ... }, semantic interactions, assertions and capture.
}
```

Phone qualifiers set the viewport and density. Existing scenarios also cover
360dp, 412dp and larger text. They remain Android phone content; running the tests
on a Linux host does not introduce a desktop app target.

Wait for the Compose tree and a drawing frame before capture:

```kotlin
compose.waitForIdle()
compose.mainClock.advanceTimeBy(64)
compose.waitForIdle()
compose.onRoot().captureRoboImage(outputFile.absolutePath)
```

Scroll to the relevant control before capturing a long list. A screenshot with a
queue heading at the bottom of the viewport may hide the file statuses it is
supposed to demonstrate. Image inspection remains necessary even when semantic
assertions pass.

## Add an authorized live account check

The authenticated #3/#6 harness was local-only. A fresh checkout contains the
synthetic tests above, not that harness, its login helper or a reusable student
session. Repeating the live run requires equivalent private setup and fresh
authorization/session material.

1. Open the official Moodle login page in an isolated headless Chromium session,
   follow UIT SSO and authenticate with the authorized account. The browser reads
   the password from its process environment; it is not included in command-line
   arguments, screenshots or test source.
2. After returning to Moodle, obtain its service cookie, sesskey and a validated
   same-origin profile link with a positive user ID. Follow the identity rules in
   [authentication](AUTHENTICATION.md). Do not assume `M.cfg.userid` exists, and do
   not substitute an SSO identity cookie for the Moodle service cookie.
3. Supply a `PortalSession` to a private `SessionStore`. Use production
   `MoodleGateway`, `SafePortalTransport` and `OfflinePortalRepository`, with a
   temporary snapshot store and a dedicated local materials directory.
4. Connect the real repository to `WorkspaceSearchViewModel` and
   `OfflineDownloadsViewModel`. Assert results, file signatures/checksums and
   state transitions. In the recorded run, the journal used an in-memory
   `StudyStorage`; the resource files used production checksum-backed storage.
5. Render `WorkspaceSearchContent` or `OfflineDownloadsContent` inside the usual
   `UitTheme` and `WorkspaceFrame`, using the captured ViewModel state. Keep
   identifiers out of the image. Writing a PNG from this state does not launch
   an external Android document viewer.
6. Review every image before publishing. Keep session JSON, response bodies,
   original documents and unreviewed captures outside the checkout and CI
   artifacts. Delete temporary sessions and file copies after validation.

A real sign-in uses the host browser's TLS stack. It does not validate the app's
Android WebView trust configuration or prove linked UIT Portal sign-in. The
October 31 search/download follow-up authenticated Moodle only.

### Environment variables over SSH

An `export` in another SSH terminal does not update an already-running Codex or
Gradle process. Child processes inherit the environment of the process that
starts them. Non-interactive shells can also skip the interactive portion of
`.bashrc`.

If the password is already exported by the user's `.bashrc`, check availability
without printing its value:

```sh
bash -ic 'test -n "$CPASSWORD"' >/dev/null 2>&1
```

Then start the private login helper from that same shell environment:

```sh
bash -ic 'node /private/path/login.cjs'
```

`/private/path/login.cjs` is a user-supplied helper, not a checked-in script.
Only load shell configuration the user controls. Never use `echo`, shell tracing
or diagnostic environment dumps to inspect credentials.

### Keep a private harness outside the source tree

A local Gradle init script can add an external test source directory without
editing the checked-in build. For example, save this as
`/private/path/live-check.init.gradle`:

```groovy
gradle.projectsEvaluated {
    def app = gradle.rootProject.findProject(':app')
    if (app == null) return
    app.android.sourceSets.test.java.srcDir('/private/path/test-src')
    app.dependencies.add(
        'testImplementation',
        gradle.rootProject.project(':core:network:moodle')
    )
}
```

Use `findProject` and the null guard because an init script also applies to the
included `build-logic` build, which has no `:app` project. Add any other test-only
dependencies needed by the private harness using the repository's versions.

```sh
./gradlew -I /private/path/live-check.init.gradle \
  :app:testDebugUnitTest --tests '*LiveAccountFeatureEvidenceTest*' \
  -PrecordUi=true
```

That class name is an example for a private test the developer must supply.
The private test must explicitly capture to a directory outside the checkout;
`uit.preview.dir` still defaults to the tracked gallery. Externally sourced tests
also produce ordinary Gradle logs/reports under `app/build`, so review those
outputs before uploading artifacts.

### Coordinate virtual time with real network work

Use `StandardTestDispatcher` and `Dispatchers.setMain` to control ViewModel work,
then clear the ViewModels and restore `Dispatchers.Main` during teardown. Advance
the test scheduler when waiting for state changes. An idle Compose tree alone
does not prove a separate ViewModel or network operation has finished.

Real socket I/O uses wall-clock time, while `runTest` can skip delays using
virtual time. A loop that advances virtual delays is not evidence that a real
request had time to finish. The recorded harness used a bounded `runBlocking`
wait, real short delays and explicit `dispatcher.scheduler.runCurrent()` calls;
it kept network/file work on `Dispatchers.IO`. Deterministic fixture tests should
continue using the existing `runTest` patterns.

## Evidence and remaining device checks

The authenticated follow-up loaded 14 Moodle courses and metadata from six. It
matched a real resource in search, emitted a verified local file-open effect,
adopted cached bytes, held missing files behind the Wi-Fi gate, retried downloads,
rejected/repaired local corruption and checked scoped file removal. Published
images and precise results are in the comments on
[PR #59](https://github.com/trungdangtapcode/UIT-Mobile-kt/pull/59#issuecomment-6540135480)
and [PR #60](https://github.com/trungdangtapcode/UIT-Mobile-kt/pull/60#issuecomment-6540135675).

The Wi-Fi input was a controlled `MutableStateFlow<Boolean>`. The run established
queue behavior for that input, not physical network detection. Android Keystore,
native WebView authentication, external viewer launching, real permission/system
dialogs, OS process death, background scheduling and manufacturer-specific
behavior still require the appropriate instrumented or device checks. The
in-memory journal also does not establish encrypted persistence or restart
behavior. Keep these claims separate from the synthetic test coverage and CI
emulator results.

For those checks, use the [ADB/device workflow](DEBUGGING.md) or the CI emulator.
For the queue's actual process-lifetime contract, see
[offline downloads](OFFLINE-DOWNLOADS.md#wi-fi-lifecycle-and-persistence).
