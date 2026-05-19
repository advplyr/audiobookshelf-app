# Android TV Basic Support — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the audiobookshelf app launch and be usable on Android TV devices without crashing, and appear in the TV launcher.

**Architecture:** Add Android TV leanback manifest declarations, guard Cast framework initialization against missing GMS, detect TV mode at runtime and expose it to the WebView layer, and provide a TV launcher banner. No UI redesign in this PR — just get the app running.

**Tech Stack:** Kotlin (Android native), Android Manifest XML, Capacitor bridge

**Upstream issue:** advplyr/audiobookshelf-app#606

---

### Task 1: Add TV feature declarations to AndroidManifest.xml

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add feature declarations before `<application>`**

Add these lines after the `<uses-permission>` block (after line 15) and before `<application>` (line 16):

```xml
    <!-- Android TV: declare touchscreen not required, leanback optional -->
    <uses-feature
        android:name="android.hardware.touchscreen"
        android:required="false" />
    <uses-feature
        android:name="android.software.leanback"
        android:required="false" />
```

Both are `required="false"` because this is a mobile-first app that also supports TV — not a TV-only app.

- [ ] **Step 2: Add TV banner to `<application>` tag**

Add `android:banner="@drawable/tv_banner"` to the `<application>` element. The tag should become:

```xml
    <application
        android:allowBackup="true"
        android:banner="@drawable/tv_banner"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="@string/app_name"
        android:networkSecurityConfig="@xml/network_security_config"
        android:requestLegacyExternalStorage="true"
        android:supportsRtl="true"
        android:theme="@style/AppTheme"
        android:usesCleartextTraffic="true"
        android:largeHeap="true" >
```

- [ ] **Step 3: Add LEANBACK_LAUNCHER intent filter to MainActivity**

Add a second category inside the existing MAIN/LAUNCHER intent-filter so TV devices show the app in the launcher:

```xml
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
```

- [ ] **Step 4: Verify manifest is valid XML**

Open the file, read it end to end, confirm no syntax errors were introduced.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/AndroidManifest.xml
git commit -m "feat: add Android TV leanback manifest declarations"
```

---

### Task 2: Create TV banner drawable

**Files:**
- Create: `android/app/src/main/res/drawable-xhdpi/tv_banner.png`

Android TV requires a 320x180px xhdpi banner image for the launcher. This is the icon users see on their TV home screen.

- [ ] **Step 1: Create the banner image**

Generate a 320x180 PNG with the audiobookshelf branding. Use the existing app icon foreground (`android/app/src/main/res/mipmap-xhdpi/ic_launcher_foreground.png`) as reference — it's a white headphones icon. The banner should have:
- Dark background (#232323 — matches the app's theme color)
- The headphones icon centered
- "Audiobookshelf" text to the right of the icon

If image generation tooling is unavailable, create a simple solid-color placeholder banner (320x180, #232323 background with white text "ABS") so the app compiles. The real banner can be polished later.

- [ ] **Step 2: Place the banner file**

Save to `android/app/src/main/res/drawable-xhdpi/tv_banner.png`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/res/drawable-xhdpi/tv_banner.png
git commit -m "feat: add Android TV launcher banner image"
```

---

### Task 3: Guard CastOptionsProvider against missing GMS

**Files:**
- Modify: `android/app/src/main/java/com/audiobookshelf/app/CastOptionsProvider.kt`

The Cast framework auto-loads `CastOptionsProvider` via manifest metadata. On TV devices without GMS Cast support, this crashes the app at startup. The fix is to wrap the initialization in a try-catch.

- [ ] **Step 1: Add try-catch to getCastOptions**

Replace the `getCastOptions` method body:

```kotlin
class CastOptionsProvider : OptionsProvider {
  override fun getCastOptions(context: Context): CastOptions {
    Log.d("CastOptionsProvider", "getCastOptions")
    return try {
      val appId = "FD1F76C5"
      CastOptions.Builder()
        .setReceiverApplicationId(appId).setCastMediaOptions(
          CastMediaOptions.Builder()
            .setMediaSessionEnabled(false)
            .setNotificationOptions(null)
            .build()
        )
        .setStopReceiverApplicationWhenEndingSession(true).build()
    } catch (e: Exception) {
      Log.w("CastOptionsProvider", "Cast initialization failed, using default options: ${e.message}")
      CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .build()
    }
  }

  override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
    return null
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/audiobookshelf/app/CastOptionsProvider.kt
git commit -m "fix: guard CastOptionsProvider against missing GMS on Android TV"
```

---

### Task 4: Add TV device detection to DeviceManager

**Files:**
- Modify: `android/app/src/main/java/com/audiobookshelf/app/device/DeviceManager.kt`

Add a utility method to detect if the app is running on an Android TV device. This will be used by the Capacitor bridge and eventually by the web UI to adapt the layout.

- [ ] **Step 1: Add isAndroidTV method**

Add this method to the `DeviceManager` object:

```kotlin
  /**
   * Checks if the current device is an Android TV.
   * @param context The context to check.
   * @return True if running on an Android TV device.
   */
  fun isAndroidTV(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
    return uiModeManager.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
  }
```

Add the import at the top of the file (it uses `Context` which is already imported).

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/audiobookshelf/app/device/DeviceManager.kt
git commit -m "feat: add Android TV device detection to DeviceManager"
```

---

### Task 5: Expose TV detection to WebView via AbsDatabase plugin

**Files:**
- Modify: `android/app/src/main/java/com/audiobookshelf/app/plugins/AbsDatabase.kt`
- Modify: `plugins/capacitor/AbsDatabase.js` (web fallback)

The web layer needs to know if it's on a TV so it can adapt the UI later. AbsDatabase already serves device data to the WebView — add `isAndroidTV` to its device info response.

- [ ] **Step 1: Find the getDeviceData method in AbsDatabase.kt and add isTV flag**

Read `AbsDatabase.kt` to find where device data is returned to the WebView. Add `isAndroidTV` to that response.

The exact code depends on how `getDeviceData` is structured — read the file first, then add a `"isAndroidTv"` key to the JSObject returned.

- [ ] **Step 2: Add web fallback in AbsDatabase.js**

In the web implementation, `isAndroidTv` should always return `false`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/audiobookshelf/app/plugins/AbsDatabase.kt
git add plugins/capacitor/AbsDatabase.js
git commit -m "feat: expose Android TV detection flag to WebView"
```

---

### Task 6: Inject TV mode CSS class into WebView on startup

**Files:**
- Modify: `android/app/src/main/java/com/audiobookshelf/app/MainActivity.kt`

On TV devices, inject a CSS class `android-tv` on the `<html>` element at WebView startup. This gives CSS/Tailwind a hook for TV-specific styling in future PRs without any JavaScript needed.

- [ ] **Step 1: Add TV class injection after the safe-area CSS variable injection**

In `MainActivity.onCreate()`, after the existing `webView.setOnApplyWindowInsetsListener` block, add:

```kotlin
    // If running on Android TV, inject a CSS class for TV-specific styling
    if (DeviceManager.isAndroidTV(this)) {
      Log.d(tag, "Android TV detected, injecting tv mode class")
      webView.post {
        webView.evaluateJavascript(
          "document.documentElement.classList.add('android-tv');",
          null
        )
      }
    }
```

Add the import for DeviceManager at the top:
```kotlin
import com.audiobookshelf.app.device.DeviceManager
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/audiobookshelf/app/MainActivity.kt
git commit -m "feat: inject android-tv CSS class on TV devices"
```

---

### Task 7: Build and verify

- [ ] **Step 1: Generate the web assets**

```bash
cd C:/Users/jscha/source/repos/abs-app
npm install
npm run generate
npx cap sync android
```

- [ ] **Step 2: Build debug APK**

```bash
cd android
./gradlew assembleDebug
```

The APK will be at `android/app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Verify APK builds without errors**

If build fails, diagnose and fix. Common issues:
- Missing tv_banner drawable: ensure the file exists at the correct path
- Manifest merge conflicts: check for duplicate feature declarations
- Import errors in Kotlin: ensure DeviceManager import is correct

- [ ] **Step 4: Commit any build fixes**

Only if changes were needed to fix build issues.

---

## Summary of Changes

| File | Change |
|------|--------|
| `AndroidManifest.xml` | Leanback feature, touchscreen optional, TV banner, LEANBACK_LAUNCHER |
| `res/drawable-xhdpi/tv_banner.png` | New 320x180 TV launcher banner |
| `CastOptionsProvider.kt` | Try-catch around Cast init to prevent crash |
| `DeviceManager.kt` | `isAndroidTV()` detection method |
| `AbsDatabase.kt` + `.js` | Expose `isAndroidTv` flag to web layer |
| `MainActivity.kt` | Inject `android-tv` CSS class on TV devices |
