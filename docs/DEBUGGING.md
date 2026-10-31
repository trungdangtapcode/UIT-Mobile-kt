# Debugging UIT Mobile over SSH on a rootless server

The [remote development research](REMOTE-DEV-RESEARCH.md) compares browser-accessible
Android Studio, ADB/WebUSB, device streaming, official Android CLI and experimental
CLI hot reload for the Windows + SSH setup. The commands here describe the verified
build/deployment tools, not a configured live-update service.

## Current capabilities

The project supports **building a debug APK, installing the app, reading logs,
and attaching a debugger**. A workflow that automatically updates the running UI
when source code changes has **not been configured for an SSH-only environment**.
The build and ADB commands below do not enable hot reload.

| Workflow | Behavior |
| --- | --- |
| `assembleDebug` → `adb install` → launch | Builds a debug APK and installs it on a device |
| Attach a debugger to the debug app | Supports breakpoints, stepping, and variable inspection |
| Compose Live Edit in Android Studio | Applies supported UI changes while the app is running |
| SSH tunnel for ADB | Lets the server control a device on your local machine; does not enable Live Edit |

## Rootless server requirements

Using the JDK, Android SDK, Gradle wrapper, or an SSH tunnel from user-owned
directories does not require `sudo`. During project setup, the tools were placed
at these locations:

```text
JDK:         /tmp/uit-jdk
Android SDK: /tmp/uit-android-sdk
Project:     /home/nguyentnt/PDWAIA/kl/uit-mobile
```

Directories under `/tmp` may be cleaned up. For a persistent setup, place the
JDK and SDK in your home directory and update `JAVA_HOME`, `ANDROID_HOME`, and
`local.properties` accordingly.

During the environment check, `/dev/kvm` existed but the current account did not
have read/write access to it. Android Emulator was also not installed, so an
accelerated emulator was not available on the server. Rootless access does not
inherently prevent emulation; an administrator can grant KVM access to enable
hardware acceleration.

Check the current permissions with:

```bash
id
ls -l /dev/kvm
test -r /dev/kvm && test -w /dev/kvm
```

## Build on the server and install locally

On the server, over SSH:

```bash
cd /home/nguyentnt/PDWAIA/kl/uit-mobile
JAVA_HOME=/tmp/uit-jdk ./gradlew :app:assembleDebug
```

On your local machine, replace `USER@SERVER` with your SSH account and host:

```bash
scp USER@SERVER:/home/nguyentnt/PDWAIA/kl/uit-mobile/app/build/outputs/apk/debug/app-debug.apk .
```

Connect a phone with **USB debugging** enabled and accept its ADB authorization
prompt, or start an Android Emulator locally. Your local machine needs Android
SDK Platform-Tools to run `adb`:

```bash
adb devices
adb install -r app-debug.apk
adb shell am start -n vn.edu.uit.mobile/.MainActivity
```

Read crash logs with:

```bash
adb logcat -s AndroidRuntime
```

This workflow builds and installs an APK. To update the code using this workflow,
build and install a new debug APK.

## Control a local device from the server

To install the app and read logs from the server terminal, forward the ADB
connection through SSH. The app still runs and displays its UI on your local
device or emulator.

On your local machine, after connecting a phone or starting an emulator:

```bash
adb start-server
adb devices
ssh -NT -o ExitOnForwardFailure=yes -R 127.0.0.1:15037:127.0.0.1:5037 USER@SERVER
```

Keep that terminal open. In another SSH terminal on the server:

```bash
ADB=/tmp/uit-android-sdk/platform-tools/adb

$ADB -H 127.0.0.1 -P 15037 devices
$ADB -H 127.0.0.1 -P 15037 install -r \
  /home/nguyentnt/PDWAIA/kl/uit-mobile/app/build/outputs/apk/debug/app-debug.apk
$ADB -H 127.0.0.1 -P 15037 shell am start \
  -n vn.edu.uit.mobile/.MainActivity
$ADB -H 127.0.0.1 -P 15037 logcat -s AndroidRuntime
```

Port `15037` on the server forwards to the ADB server on port `5037` on your local
machine. The SSH server must allow remote forwarding. See the
[ADB documentation](https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/user/adb.1.md)
for ADB host and port options.

The tunnel provides ADB connectivity. It does not synchronize source files,
watch code changes, rebuild the app, or apply Compose updates automatically.

## Debug with breakpoints

Open the matching version of the source code in Android Studio on your local
machine and install the debug APK on your device. Select **Run → Attach debugger
to Android process**, then select `vn.edu.uit.mobile`. Once attached, the debugger
supports breakpoints, variable inspection, and stepping. See the
[Android debugging guide](https://developer.android.com/studio/debug).

Installing and launching a debug APK does not automatically attach a debugger.

## Update Compose UI with Live Edit

Android Studio provides **Compose Live Edit**. According to the
[Live Edit documentation](https://developer.android.com/develop/ui/compose/tooling/iterative-development):

- Android Studio and a device or emulator running Android 11, API 30 or newer,
  are required. The app itself supports API 26 or newer; API 30 is a separate
  requirement for Live Edit.
- The app must be built and launched initially.
- Supported changes inside Compose function bodies can be applied to the running app.
- Renaming functions, changing signatures, adding or removing functions, and
  changing class structure require rebuilding and running the app again.

Configure **Settings → Editor → Live Edit** in Android Studio. The IDE must manage
the corresponding project and connect to the device. It may run on the Linux
server and be accessed remotely; it does not have to run on the Windows laptop. An SSH terminal and an ADB
tunnel alone do not provide a Live Edit environment.

## Static UI previews without an emulator

The repository can render Compose screenshots on the host JVM using Robolectric
and Roborazzi. This works without KVM, root access, or a connected Android device:

```bash
cd /home/nguyentnt/PDWAIA/kl/uit-mobile
JAVA_HOME=/tmp/uit-jdk ./gradlew :app:testDebugUnitTest \
  --tests '*WorkspaceDesignTest*' -PrecordUi=true
```

See the [design gallery](design/README.md) for the images and interaction coverage.
The checked-in preview tests use synthetic data. An authorized private harness can
also exercise production readers over real HTTPS and render authenticated state.
See [testing without a local Android device](HOST-ANDROID-TESTING.md) for the
architecture, commands, environment-variable tips and limits of that workflow.
Both produce static images rather than a live-updating app session.

## Browser-based preview

The project does not currently include a development server or web preview that
updates the UI in a browser when source files change on the server. Such a
workflow requires additional implementation, such as a suitable web preview
target or a remotely accessible Android Studio and device/emulator environment.
Neither has been configured in this repository. A browser-based device viewer
would stream the native Android app; it would not turn the app into a web product.
See the [research comparison](REMOTE-DEV-RESEARCH.md) for the practical options.
