# PR 01 — Foundation + TV detection

Part of a 10-PR series adding Android TV support to audiobookshelf-app,
replacing the original PR #1843 (closed in favor of this series to address the
review-volume feedback).

## Full series plan

See **[bilbospocketses/abs-app — PR_DECOMPOSITION_PLAN.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/PR_DECOMPOSITION_PLAN.md)**
for the complete 10-PR breakdown, dependency graph, and rationale.

## This PR's scope

Foundational Android TV support — device detection and the launcher entry, with
**no navigation behavior yet**. After this merges the app appears in the Android
TV launcher and knows when it is running on a TV; phone/tablet behavior is
byte-identical to before.

- **`AndroidManifest.xml`** — declare `touchscreen` not required and `leanback`
  optional (both `required="false"`, so phones/tablets are unaffected), add the
  `LEANBACK_LAUNCHER` intent category, and set the TV banner.
- **`DeviceManager.isAndroidTV(context)`** — `UiModeManager`-based TV detection.
- **`AbsDatabase.kt` / `plugins/capacitor/AbsDatabase.js`** — expose `isAndroidTv`
  on device data to the WebView (native plugin + web fallback).
- **`MainActivity.kt`** — on TV, inject an `android-tv` class onto `<html>` via a
  `WebViewListener.onPageStarted` hook (so the class is present before page
  scripts run) with an idempotent `webView.post` backup; and fully terminate the
  app in `onStop` (TV WebView/JS state goes stale when resumed from memory).
- **`CastOptionsProvider.kt`** — guard cast initialization in try/catch and fall
  back to the default receiver, so devices without full Google Play Services
  (common on TV hardware) don't crash.
- **`store/index.js` / `layouts/default.vue` / `pages/connect.vue`** — an
  `isAndroidTv` Vuex flag set from device data on mount; `connect.vue` also adopts
  a scrollable, top-aligned layout on TV.
- **`tv_banner.png`** — the leanback launcher banner.

## Architecture context (fork-hosted)

- [TV_FOCUS_SYSTEM.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/TV_FOCUS_SYSTEM.md) — overall TV focus/navigation architecture (relevant once the engine PRs land).

## Testing

Sideloaded on a Google TV Streamer 4K and an Android phone: verified the TV
launcher entry and banner, the `isAndroidTv` flag value on each device, and that
phone behavior is unchanged.

## Relationship to the series

- **Depends on:** nothing
- **Blocks:** PR3 (side-drawer gate — inert until `isAndroidTv` exists), PR5
  (engine kit), PR7 (audio-player TV behavior); foundational for the remaining PRs.
- **Wave:** 1 of 3
