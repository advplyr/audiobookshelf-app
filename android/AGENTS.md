
# Agents Guide (Android)

This file contains Android-specific agent instructions for the audiobookshelf-app project. For general and cross-platform guidance, see `.github/copilot-instructions.md`.


## For AI agents
- Keep edits ASCII unless the file already uses Unicode.
- Avoid destructive git commands; do not revert user changes.
- Prefer `rg` for search; reuse existing tasks/scripts instead of adding duplicates.
- When touching Media3/Android Auto, check `@UnstableApi` opt-ins and cast interop with legacy notification service.
- Reply with concise summaries and file paths; suggest verification steps if edits were made.


## Repo-specific instructions for agents
- Main Activity entry point: `android/app/src/main/java/com/audiobookshelf/app/MainActivity.kt`
- Android Auto / Media3 navigation and browse tree: `android/app/src/main/java/com/audiobookshelf/app/player/Media3BrowseTree*.kt`, `Media3AutoLibraryCoordinator.kt`
- HTTP/server communication: `android/app/src/main/java/com/audiobookshelf/app/server/ApiHandler.kt`
- Business logic: service/controller/manager layers (e.g., `Media3PlaybackService`, `PlaybackController`, `MediaManager`); keep UI concerns out of plugins
- Android native code is Kotlin-first; prefer Kotlin and avoid adding new Java components unless matching existing patterns
- Do not introduce new navigation or UI toolkits on Android (stick with existing Media3 service plus notifications; no new Compose/fragment stacks)
- Code formatting:
  - Respect `.editorconfig` (UTF-8, final newline, trim trailing whitespace, 2-space indents)
  - Kotlin: keep existing style (2-space indents, concise, Kotlin-first), and avoid introducing tabs


## Media3 Feature Branch Notes
- Media3-based playback is experimental and only present in the `feature/Media3-Conversion` branch
- Use `@UnstableApi` opt-ins as needed
- Manual testing: playback, seek, notification actions, Android Auto browse, cast flows

## Build Flavors and Feature Flags

- The Android app uses product flavors (`exov2`, `media3`) to select the player backend at build time.
- The `USE_MEDIA3` flag (in `build.gradle` and `AndroidManifest.xml`) controls which playback implementation is enabled:
  - `exov2` flavor: legacy ExoPlayer2 backend, `USE_MEDIA3=false`
  - `media3` flavor: Media3 backend, `USE_MEDIA3=true`
- Dependencies and service enablement in `build.gradle` and `AndroidManifest.xml` are conditional on the selected flavor/flag.
- Manual and automated testing should cover both flavors to ensure migration safety.
- See `PlayerWrapperFactory` for runtime selection and abstraction between player backends.

## Legacy ExoPlayer2 Implementation (master branch)

The legacy playback system uses ExoPlayer2 for local audio playback and PlayerNotificationService for notifications and session management. Key components:

- **PlayerNotificationService** (`android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationService.kt`):
  - Central service for playback, notifications, and Android Auto integration.
  - Manages an ExoPlayer2 instance (`mPlayer`) for local playback and a CastPlayer for remote playback.
  - Handles switching between ExoPlayer2 and CastPlayer via `switchToPlayer(useCastPlayer: Boolean)`.
  - Uses `PlayerNotificationManager` for notification controls and `MediaSessionCompat` for media session integration.
  - Attaches listeners (`PlayerListener`) to ExoPlayer2 for playback state, errors, and position updates.
  - Prepares playback sessions, manages media sources, and updates widgets and client events.
  - Implements custom actions for Android Auto (jump forward/backward, change speed).

- **ExoPlayer2 setup:**
  - Instantiated with custom buffer durations and seek increments.
  - Audio attributes set for media usage and speech content.
  - Listener attached for playback events and error handling.

- **CastManager/CastPlayer:**
  - Handles Google Cast sessions and switching between local and remote playback.
  - Notifies PlayerNotificationService to switch player and update notifications.

- **PlayerWrapper/ExoPlayerWrapper:**
  - Abstraction layer to allow migration to Media3 or other player implementations.
  - Provides a consistent interface for play, pause, seek, and notification/session attachment.

- **Notification and session management:**
  - Uses `PlayerNotificationManager` for notification controls.
  - Uses `MediaSessionCompat` and `MediaSessionConnector` for media session and Android Auto integration.
  - Notification foreground service is started/stopped based on playback state.

**Key files:**
- `android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationService.kt`
- `android/app/src/main/java/com/audiobookshelf/app/player/ExoPlayerWrapper.kt`
- `android/app/src/main/java/com/audiobookshelf/app/player/PlayerListener.kt`
- `android/app/src/main/java/com/audiobookshelf/app/player/CastManager.kt`
- `android/app/src/main/java/com/audiobookshelf/app/player/CastPlayer.kt`
- `android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationListener.kt`


**Migration notes:**
- When the Media3 feature flag is enabled, both local and cast playback use Media3 APIs and player implementations.
- The legacy ExoPlayer2 path is retained only for builds/flavors where Media3 is disabled.
- The PlayerWrapper abstraction allows toggling between ExoPlayer2 and Media3 implementations via feature flags.

---


## Common dev flows / tips
- For Android Auto desktop HU, use the VS Code port-forward task or run `adb forward tcp:5277 tcp:5277`
- `BuildConfig.DEBUG` gates many logs; avoid noisy logging in release paths
- After web changes, regenerate + sync (`npm run generate && npx cap sync`) before building native


## Useful locations
- Assets/icons: `static/`, `assets/`, Android drawables in `android/app/src/main/res/drawable/`

---
_Last updated: November 24, 2025_
