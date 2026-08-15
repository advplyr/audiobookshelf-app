# Android JVM tests

This project currently runs host-JVM tests only. Device/emulator tests under
`src/androidTest` are deliberately not part of this test foundation.

## Prerequisites

* JDK 21. Capacitor Android is compiled with Java source level 21. Do not run this
  Gradle build with Android Studio's bundled JDK 25; the project's current
  Gradle/Groovy combination does not support it.
* Node.js 20.x and npm (Capacitor's Android Gradle subprojects are provided by
  `node_modules`).
* Android SDK packages installed through **Android Studio → Tools → SDK Manager**:
  * **SDK Platforms**: Android 15.0 / API Level 35 (`Android SDK Platform 35`).
  * **SDK Tools**: `Android SDK Build-Tools 35.0.0`, `Android SDK Platform-Tools`,
    and `Android SDK Command-line Tools (latest)`.
* `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) pointing at the Android SDK.

In SDK Manager, use the **Android SDK Location** shown at the top of the window as
the value for `ANDROID_HOME`. Android Studio itself does not include these SDK
packages until they are installed.

## Commands

From the repository root:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
npm ci
./android/gradlew :app:testDebugUnitTest -p android --no-daemon
./android/gradlew :app:jacocoDebugUnitTestReport -p android --no-daemon
```

Use the actual Android SDK directory selected in Android Studio's SDK Manager if it
differs from `$HOME/Android/Sdk`.

The coverage HTML report is written to
`android/app/build/reports/jacoco/jacocoDebugUnitTestReport/html/index.html`; the
machine-readable XML report is alongside it.

## Test conventions

Place Kotlin host tests under `android/app/src/test/java`, mirroring the production
package. Use JUnit 4, MockK, MockWebServer, and `kotlinx-coroutines-test` as needed.
Keep test inputs deterministic; do not depend on Android device services, sleeps, or
external network access.

Regression specifications for known defects are normal, enabled tests, not `@Ignore`d
ones. The suite is therefore expected to be red while those defects remain in
production. A candidate fix is successful only when its corresponding regression test
changes from failing to passing without weakening the assertion.

## Before adding or changing tests

Read `android/app/src/test/java/com/audiobookshelf/app/TESTING.md`. It documents the
shared `AbsTestEnvironment` harness, which Android APIs are stubbed to null or no-ops
under AGP's mockable `android.jar` (and so cannot be tested here), the current defect
list, and the assertion mistakes that have produced misleading tests in this suite
before.
