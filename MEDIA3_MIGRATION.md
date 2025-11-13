# Media3 Migration Summary

This document summarizes our incremental migration from ExoPlayer 2.x to Google's Media3 library and outlines a staged game plan aligned with Patreon’s published best practices.

## Changes Made

### 1. Dependencies Updated (variables.gradle & app/build.gradle)

**Removed:**
- `exoplayer_version = '2.18.7'`
- `com.google.android.exoplayer:exoplayer-core`
- `com.google.android.exoplayer:exoplayer-ui`
- `com.google.android.exoplayer:extension-mediasession`
- `com.google.android.exoplayer:extension-cast`
- `com.google.android.exoplayer:exoplayer-hls`

**Added:**
- `media3_version = '1.5.0'`
- `androidx.media3:media3-exoplayer`
- `androidx.media3:media3-ui`
- `androidx.media3:media3-session`
- `androidx.media3:media3-cast`
- `androidx.media3:media3-exoplayer-hls`

### 2. Package Imports Updated

All imports have been updated from `com.google.android.exoplayer2.*` to `androidx.media3.*`:

**Core packages:**
- `com.google.android.exoplayer2` → `androidx.media3.common`
- `com.google.android.exoplayer2.audio` → `androidx.media3.common` (AudioAttributes)
- `com.google.android.exoplayer2.source` → `androidx.media3.exoplayer.source`
- `com.google.android.exoplayer2.ui` → `androidx.media3.ui`
- `com.google.android.exoplayer2.upstream` → `androidx.media3.exoplayer.upstream`
- `com.google.android.exoplayer2.extractor` → `androidx.media3.extractor`
- `com.google.android.exoplayer2.util` → `androidx.media3.common.util`

**Extensions:**
- `com.google.android.exoplayer2.ext.cast` → `androidx.media3.cast`
- `com.google.android.exoplayer2.ext.mediasession` → `androidx.media3.session`

### 3. Files Modified

The following files have been updated with new imports:

**Player Files:**
- `player/PlayerNotificationService.kt` - Main player service
- `player/CastPlayer.kt` - Cast player implementation
- `player/PlayerListener.kt` - Player event listener
- `player/PlayerNotificationListener.kt` - Notification listener
- `player/MediaSessionPlaybackPreparer.kt` - Media session preparer
- `player/AbMediaDescriptionAdapter.kt` - Media description adapter
- `player/CastTimeline.kt` - Cast timeline implementation
- `player/CastTrackSelection.kt` - Cast track selection
- `player/CastTimelineTracker.kt` - Cast timeline tracker
- `player/CastManager.kt` - Cast manager

**Data Files:**
- `data/PlaybackSession.kt` - Playback session data class

## Important Notes for Testing

### 1. MediaSessionConnector Changes
The project still uses the old `MediaSessionConnector` pattern. Media3 has redesigned the media session integration. The current imports reference `androidx.media3.session.MediaSession` but the code may need additional updates to fully utilize the new Media3 session API.

**Key areas to verify:**
- `PlayerNotificationService.kt` lines with `mediaSessionConnector`
- `MediaSessionPlaybackPreparer.kt` - This may need to be refactored to use Media3's new callback pattern

### 2. API Compatibility
Most ExoPlayer 2.x APIs are compatible with Media3, but some notable changes:
- `TrackSelectionArray` and `TrackGroupArray` are deprecated in favor of `Tracks`
- `PlaybackParameters` usage remains similar
- `AudioAttributes` moved to `androidx.media3.common`

### 3. Build & Runtime Testing Required

After syncing Gradle, test the following:
1. **Basic Playback**: Verify local and streaming audio playback works
2. **Seeking**: Test forward/backward seeking functionality
3. **Sleep Timer**: Verify sleep timer integration
4. **Media Session**: Test media controls (play, pause, skip) from notification and external sources
5. **Cast Integration**: Test Chromecast functionality if used
6. **HLS Streaming**: Verify HLS stream playback
7. **Background Playback**: Ensure playback continues in background
8. **Notification Controls**: Test all notification buttons and actions

### 4. Potential Issues to Watch For

- **MediaSessionConnector deprecation**: The old mediasession extension is deprecated. Consider migrating to the new `MediaSession` API from `media3-session` for full compatibility.
- **Custom actions**: Verify custom playback actions still work correctly
- **Track selection**: If using custom track selection logic, verify it works with the new Tracks API
- **Error handling**: Test error scenarios to ensure PlaybackException handling works correctly

## Gradle Sync & Build

After these changes, you should:
1. Sync Gradle (`./gradlew --refresh-dependencies` or sync in Android Studio)
2. Clean build (`./gradlew clean`)
3. Rebuild the project
4. Run on a test device/emulator

## SDK Requirements

✅ **Minimum SDK**: API 24 (meets Media3 requirement of API 21+)
✅ **Target SDK**: API 35
✅ **Gradle**: Version 8.8.2 (compatible with Media3)

## References

- [Media3 ExoPlayer Migration Guide](https://developer.android.com/media/media3/exoplayer/migration-guide)
- [Media3 Documentation](https://developer.android.com/media/media3)
- [Media3 Release Notes](https://github.com/androidx/media/releases)

## Next Steps

If you encounter build errors after syncing:
1. Check for any remaining `exoplayer2` package references
2. Verify all dependency versions are compatible
3. Review the specific error messages and consult the migration guide
4. Consider updating MediaSession integration to use the new Media3 API

## Game plan (staged, low-risk)

Phase 0 — Unblock Gradle and dependency graph
- Remove forced `play-services-base` version (done). Let media3-cast resolve compatible Play Services.
- Run Android module Gradle commands from `android/` folder (wrapper lives there).
- Ensure a JDK 17 is available (Gradle requires JAVA_HOME). If using Android Studio, this is automatic.

Phase 1 — Core playback on Media3 (compile-green)
- Keep scope minimal: `media3-exoplayer`, `media3-ui`, `media3-exoplayer-hls` only.
- Temporarily disable `media3-cast` and session if dependency conflicts remain; re-enable later.
- Introduce PlayerWrapper seam + feature flag (done): pick Exo vs Media3 without app/UI churn.
- Wrapper-managed wiring (done): wrappers attach notification/session; service avoids conditionals.
- Distinct player label (done): `media3-exoplayer` vs `exo-player` vs `cast-player` for telemetry.
- Lightweight rollout metrics (done): startup READY latency, buffer count, error count (logged with label).
- Trim/gate logs in release (done).
- Resource minimization (partial): when Media3 is enabled, avoid configuring audio/noisy on legacy Exo instance; wrapper owns active player.

Phase 2 — Media3 session-centric architecture (Patreon-aligned)
- Replace legacy `MediaSessionConnector` + legacy notification manager with Media3 `MediaSession` and Media3 notifications when flag is ON.
- Eliminate reflection: create Media3-native notification manager and let session drive notifications.
- Maintain a single long-lived Media3 ExoPlayer owned by the service/session.
- Map commands into `MediaSession.Callback` (play/pause/seek/speed/bookmarks/sleep/queue) and route to existing service methods.

Phase 3 — Port custom actions
- Recreate custom actions as `SessionCommand`s with stable string IDs.
- Handle them in `onCustomCommand` and wire through to existing service methods.

Phase 4 — Re-enable Chromecast with `media3-cast`
- Update `CastPlayer` and `CastManager` to remove legacy Exo `ext.cast` types; align with `androidx.media3.cast`.
- Ensure Play Services versions are aligned (avoid forcing older versions).
- Validate queue/timeline sync with `CastTimeline` utils.

Phase 5 — Regression, testability, and polish
- Validate: local + streaming, HLS, seek, notifications, background playback, headset/noisy handling.
- Add unit tests around wrapper behavior, session commands, and player events.
- Expand metrics: startup distributions, rebuffer ratio, failure rate by stack if needed.

Acceptance criteria per phase
- Phase 1: App builds; Media3 path plays; wrapper-managed notifications function; metrics emitted; logs quiet in release.
- Phase 2: Media3 session in control (no reflection); single long-lived player; notification behavior matches/exceeds legacy.
- Phase 3: All custom actions available and functional via `SessionCommand`.
- Phase 4: Cast playback works; switching between local/cast preserved.

Suggested PR sequencing
1) Dependencies + Gradle graph cleanup — keep it small, build-only.
2) Core Media3 playback via wrapper + compile-green (no behavior changes, done).
3) Media3 session + notifications (flag ON), remove reflection.
4) Custom actions port (`SessionCommand`).
5) Cast migration (`media3-cast`).

## How to run (local)

Windows PowerShell:

```powershell
# Ensure JAVA_HOME points to JDK 17 (e.g., C:\Program Files\Java\jdk-17)
$env:JAVA_HOME = 'C:\\Program Files\\Java\\jdk-17'
$env:Path = "$env:JAVA_HOME\\bin;$env:Path"

# Run Gradle from the Android module
Set-Location -Path .\android
./gradlew.bat --no-daemon --warning-mode all clean assembleDebug
```

If Gradle still fails on dependency resolution, temporarily comment out `media3-cast` in `android/app/build.gradle` and re-run. Re-enable in Phase 4.

## Rollout & rollback

### Rollout (flag-driven)
- Default: `USE_MEDIA3=false` in `variables.gradle` (current default).
- Enable for internal/beta by flipping the gradle flag or remote flag (when available).
- Compare metrics in logs/dashboards: startup latency, buffer count, error rate labeled by player.

### Rollback Plan

If issues arise, you can revert by:
1. Restoring `exoplayer_version = '2.18.7'` in `variables.gradle`
2. Replacing Media3 dependencies with ExoPlayer 2.x dependencies
3. Reverting all import changes (git checkout the modified files)
