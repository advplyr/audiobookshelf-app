# Agents Guide

Use this as the single source of instructions for AI/code agents to orient and respond quickly.

## For AI agents
- Keep edits ASCII unless the file already uses Unicode.
- Avoid destructive git commands; do not revert user changes.
- Prefer `rg` for search; reuse existing tasks/scripts instead of adding duplicates.
- When touching Media3/Android Auto, check `@UnstableApi` opt-ins and cast interop with legacy notification service.
- Reply with concise summaries and file paths; suggest verification steps if edits were made.

## Repo-specific instructions for agents
- Main Activity entry point `android/app/src/main/java/com/audiobookshelf/app/MainActivity.kt`.
- Android Auto / Media3 navigation and browse tree `android/app/src/main/java/com/audiobookshelf/app/player/Media3BrowseTree*.kt` and `Media3AutoLibraryCoordinator.kt`.
- HTTP/server communication lives in `android/app/src/main/java/com/audiobookshelf/app/server/ApiHandler.kt`; web API use is centralized there.
- Business logic on Android should stay in service/controller/manager layers (e.g., `Media3PlaybackService`, `PlaybackController`, `MediaManager`); keep UI concerns out of plugins.
- Android native code is Kotlin-first; prefer Kotlin and avoid adding new Java components unless matching existing patterns.
- Frontend follows Nuxt 2 patterns: put routing pages in `pages/`, shared UI in `components/`, and keep business logic in `store/` or dedicated plugins/utils (avoid stuffing it into components).
- Do not introduce new navigation or UI toolkits on Android (stick with existing Media3 service plus notifications; no new Compose/fragment stacks).
- Code formatting:
  - Respect `.editorconfig` (UTF-8, final newline, trim trailing whitespace, 2-space indents).
  - JavaScript/Vue/JSON: follow `.prettierrc` (no semicolons, single quotes, printWidth 400, no trailing commas; HTML override uses double quotes).
  - Kotlin: keep existing style (2-space indents, concise, Kotlin-first), and avoid introducing tabs.

## What this repo is
- Nuxt 2 web app packaged with Capacitor 7 to target Android (and iOS).
- Native Android layer under `android/app/src/main/java/com/audiobookshelf/app`.
- Media3-based playback (new) with some legacy ExoPlayer/cast glue.

## Key run/build commands
- `npm install` -> install web dependencies.
- `npm run generate` -> build static web bundle.
- `npx cap sync` -> copy web bundle into native projects.
- `npx cap open android` -> open Android Studio project.
- VS Code task `ABS: Start Android Auto port forward` in `.vscode/tasks.json` (forwards `tcp:5277` to AA Desktop Head Unit).

## Frontend (Nuxt)
- Routes in `pages/`, layouts in `layouts/`, shared UI in `components/`.
- Vuex store modules in `store/` for user/session/media state.
- Styling via Tailwind (`tailwind.config.js`) plus custom CSS in `assets/`.
- API/socket helpers in `plugins/`, `middleware/`, `mixins/`.

## Native Android highlights
- **Media3PlaybackService** (`android/app/src/main/java/com/audiobookshelf/app/player/Media3PlaybackService.kt`):
  - MediaLibraryService-based playback, notification buttons (seek back/forward, speed), player switching (local vs cast).
  - Android Auto browse via `Media3BrowseTree` and `Media3AutoLibraryCoordinator`.
  - Uses `ApiHandler`, `MediaManager`, `DeviceManager`, `SleepTimerManager`, etc.
- **AbsAudioPlayer Capacitor plugin** (`android/app/src/main/java/com/audiobookshelf/app/plugins/AbsAudioPlayer.kt`):
  - Bridge exposing playback control/events to the JS layer.
  - Manages `PlaybackController`, `PlayerNotificationService`, cast availability, progress sync (`MediaProgressSyncer`), and emits events to the web app.
- Other helpers: legacy `PlayerNotificationService`, `CastManager`, sleep timer support, DB via `DbManager`, telemetry via `PlaybackTelemetryHost`.

## Data models and managers
- Data classes: `android/app/src/main/java/com/audiobookshelf/app/data/` (playback session, items, progress, device info, settings).
- Managers: `android/app/src/main/java/com/audiobookshelf/app/managers/` (sleep timer, DB).
- Media handling: `android/app/src/main/java/com/audiobookshelf/app/media/` (MediaManager, browse tree, progress sync).
- Server/API: `android/app/src/main/java/com/audiobookshelf/app/server/ApiHandler`.

## Common dev flows / tips
- For Android Auto desktop HU, use the VS Code port-forward task or run `adb forward tcp:5277 tcp:5277`.
- `BuildConfig.DEBUG` gates many logs; avoid noisy logging in release paths.
- After web changes, regenerate + sync (`npm run generate && npx cap sync`) before building native.
- Media3 code uses `@UnstableApi`; keep opt-in annotations on touchpoints.

## Testing / verification ideas
- Native: manual playback/seek, notification actions, Android Auto browse roots; cast flows still rely on legacy `PlayerNotificationService`.
- Frontend: manual smoke via `npm run dev`; no automated tests present.

## Useful locations
- Assets/icons: `static/`, `assets/`, Android drawables in `android/app/src/main/res/drawable/`.
