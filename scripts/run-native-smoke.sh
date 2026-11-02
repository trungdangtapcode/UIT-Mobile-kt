#!/bin/sh
# The emulator action invokes each script line separately. Keep status and capture
# collection in one shell so failed tests still publish their diagnostic evidence.
set -u
adb wait-for-device
native_status=0
./gradlew :app:connectedDebugAndroidTest --no-daemon \
  -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" \
  -Dkotlin.daemon.jvmargs="-Xmx512m" -Dorg.gradle.workers.max=2 \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || native_status=$?
mkdir -p build/auth-smoke
for capture in moodle-login.png sso-login.png reading-native-page.png reading-native-restored.png reading-native-failure.png reading-export.json; do
  if adb shell run-as vn.edu.uit.mobile test -f "files/auth-smoke/$capture"; then
    adb exec-out run-as vn.edu.uit.mobile cat "files/auth-smoke/$capture" > "build/auth-smoke/$capture" || rm -f "build/auth-smoke/$capture"
  fi
done
exit "$native_status"
