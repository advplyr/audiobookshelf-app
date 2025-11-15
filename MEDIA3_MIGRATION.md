# Media3 Migration Summary

This document outlines our incremental, feature-flagged migration from ExoPlayer 2.x to Google's Media3 library, following Patreon's published best practices for low-risk rollout.

## Migration Strategy

**Dual Implementation Approach:**
- Both ExoPlayer v2 and Media3 dependencies coexist in the codebase
- Feature flag (`USE_MEDIA3` in `variables.gradle`) controls which implementation is active at runtime
- Wrapper pattern abstracts the underlying player, allowing seamless switching without UI/service code changes
- Default: `USE_MEDIA3=false` (ExoPlayer v2) until Phase 2 validation completes

## Current State: Phase 2 In Progress 🔄

**Phase 1 Achievements:**
- ✅ PlayerWrapper abstraction with factory pattern
- ✅ Feature flag infrastructure (`BuildConfig.USE_MEDIA3`)
- ✅ Media3 ExoPlayer integration (compiles and runs)
- ✅ Rollout metrics (startup latency, buffer count, error count)
- ✅ Wrapper-managed notification/session attachment
- ✅ Player label telemetry (`media3-exoplayer` vs `exo-player` vs `cast-player`)

**Phase 1.5 Achievements (Minimal Notification Support):**
- ✅ Media3 MediaSession integration in Media3Wrapper
- ✅ Session token exposed for notification provider
- ✅ MediaMetadata added to MediaItems for notification display
- ✅ media3-session dependency added to build.gradle
- ✅ Automatic cleanup on release (session.release())
- ✅ Introduced dual position accessors: thread-safe snapshot (`getCurrentPosition`) & live main-thread (`getCurrentPositionLive`) for precise listener logging

**Phase 2 Progress (Dedicated Media3 Service):**
- ✅ Created `Media3PlaybackService` extending `MediaSessionService`
- ✅ Implemented custom `MediaSession.Callback` with jump commands
- ✅ Added `MediaNotification.Provider` for native notification building
- ✅ Registered service in AndroidManifest with proper intent filters
- ✅ Notification channel creation for Android O+
- 🔄 Wire service startup logic based on USE_MEDIA3 flag
- 🔄 Migrate playback session management from legacy service
- 🔄 Test complete playback lifecycle with new service
- 🔄 Verify all notification actions render correctly

**What Works (flag ON):**
- Basic playback (local files and streaming)
- Seeking, pause/resume, playback speed
- Sleep timer integration
- Cast support via dual-path fallback (switches to v2 notification system when casting)
- Background playback

**Current Issue:**
- Manual notification builder in legacy service shows only play/pause action
- Need to complete migration to dedicated `Media3PlaybackService` for proper MediaSession integration
- Custom jump actions require session command handling (implemented but not wired)

**Known Limitations (Phase 2 In Progress):**
- Android Auto not supported in Media3 path yet
- **Cast uses v2 notification system** (different notification UI when casting vs local playback)
- Full playback session lifecycle not migrated to new service yet

## Dependencies

### Current Dependencies (Both Coexist)

**ExoPlayer v2 (Legacy):**
```gradle
exoplayer_version = '2.18.7'
com.google.android.exoplayer:exoplayer-core
com.google.android.exoplayer:exoplayer-ui
com.google.android.exoplayer:extension-mediasession
com.google.android.exoplayer:extension-cast
com.google.android.exoplayer:exoplayer-hls
```

**Media3 (New):**
```gradle
media3_version = '1.5.0'
androidx.media3:media3-exoplayer
androidx.media3:media3-ui
androidx.media3:media3-session
androidx.media3:media3-cast
androidx.media3:media3-exoplayer-hls
```

**Why Both:** Phase 1 maintains both for safety. ExoPlayer v2 dependencies can be removed after Phase 2-4 complete and Media3 is fully validated.

## SDK Requirements

✅ **Minimum SDK**: API 24 (meets Media3 requirement of API 21+)
✅ **Target SDK**: API 35
✅ **Gradle**: Version 8.8.2 (compatible with Media3)

## Phase 1 Implementation Details

### Single Player Instance Pattern

**Architecture:**
- `PlayerNotificationService` maintains single `playerWrapper` field abstracting underlying player
- Factory pattern (`PlayerWrapperFactory`) creates appropriate wrapper based on `USE_MEDIA3` flag
- Wrappers handle player-specific configuration (audio attributes, noisy handling, listener management)
- Service uses wrapper interface for all playback operations—no conditional logic scattered throughout
- Position access semantics:
  - `getCurrentPosition()` returns a cached snapshot; safe from any thread (used by background sync, pause callbacks, and now paused-event logging—removed earlier main-thread guard)
  - `getCurrentPositionLive()` queries the underlying player directly; must be on main thread (used in real-time listener event logs for highest fidelity)
  - Guidance: prefer snapshot in non-UI/background code to avoid wrong-thread exceptions; use live only when already on listener/main thread and accuracy matters. Avoid mixing them in the same log line to keep semantics clear.

**Code Structure:**
```kotlin
// PlayerWrapperFactory.kt
object PlayerWrapperFactory {
  fun useMedia3(): Boolean = BuildConfig.USE_MEDIA3
  
  fun wrapExistingPlayer(service: PlayerNotificationService, exoPlayer: ExoPlayer): PlayerWrapper {
    return if (useMedia3()) {
      Media3Wrapper(service.applicationContext, exoPlayer)
    } else {
      ExoPlayerWrapper(exoPlayer)
    }
  }
}
```

### Notification Service Refactoring

**Encapsulation Improvements:**
- Made `mPlayer` and `currentPlayer` fields `private` (only used internally)
- `playerWrapper` remains public (accessed by `SleepTimerManager`, `MediaProgressSyncer`, `MediaSessionCallback`, `PlayerListener`)
- Wrapper owns notification and media session attachment:
  - `attachNotificationManager(playerNotificationManager)`
  - `attachMediaSessionConnector(mediaSessionConnector)`
- Listeners added through wrapper (`playerWrapper.addListener()`) to work with both implementations
- Cast switching preserved: wrapper supports `setActivePlayerForNotification(castPlayer)`

### Metrics Integration

**Lightweight Telemetry:**
- Added helper methods in service:
  - `metricsRecordError()` - Count playback errors
  - `metricsRecordBuffer()` - Count buffer events
  - `metricsRecordFirstReadyIfUnset()` - Measure startup latency
  - `metricsLogSummary()` - Log summary with player label
- `PlayerListener` calls these helpers without accessing private fields
- Metrics include player label for comparing ExoPlayer v2 vs Media3 performance

**Log Output Example:**
```
PlaybackMetrics: startupReadyLatencyMs=234 player=media3-exoplayer item=book-123
PlaybackMetrics: summary player=media3-exoplayer item=book-123 buffers=2 errors=0 startupReadyLatencyMs=234
```

### Files Modified/Created

**New Files:**
- `player/PlayerWrapper.kt` - Interface defining common player operations
- `player/PlayerEvents.kt` - Framework-neutral event interface for decoupled listeners
- `player/PlayerWrapperFactory.kt` - Factory for creating wrapper instances
- `player/ExoPlayerWrapper.kt` - Wrapper for ExoPlayer v2 with event forwarding
- `player/Media3Wrapper.kt` - Wrapper for Media3 ExoPlayer with session + notifications

**Modified Files:**
- `player/PlayerNotificationService.kt` - Refactored to use wrapper pattern, added metrics helpers
- `player/PlayerListener.kt` - Implements PlayerEvents, uses framework constants, and logs using `getCurrentPositionLive()` on main-thread callbacks for precise positions
- `android/variables.gradle` - Added `USE_MEDIA3` flag (default: false)
- `android/app/build.gradle` - Added media3-session dependency

## Migration Roadmap

### Phase 1 — Core playback on Media3 (compile-green) ✅
- Keep scope minimal: `media3-exoplayer`, `media3-ui`, `media3-exoplayer-hls` only.
- Introduce PlayerWrapper seam + feature flag.
- Wrapper-managed wiring: wrappers attach notification/session; service avoids conditionals.
- Distinct player label: `media3-exoplayer` vs `exo-player` vs `cast-player` for telemetry.
- Lightweight rollout metrics: startup READY latency, buffer count, error count.
- Resource minimization: when Media3 enabled, avoid configuring audio/noisy on legacy Exo instance.

**Status:** ✅ Complete

### Phase 1.5 — Minimal Media3 notification support ✅
- Add `media3-session` dependency for session-driven notifications.
- Create MediaSession in Media3Wrapper with proper lifecycle management.
- Add MediaMetadata to MediaItems for notification display (placeholder title).
- Introduce PlayerEvents interface to decouple listeners from framework-specific types.
- Update PlayerWrapper to accept PlayerEvents; wrappers forward framework events to neutral interface.
- Basic play/pause notification controls work automatically via MediaSession.

**Status:** ✅ Complete and PR-ready

**Known Limitations:**
- Notification shows placeholder metadata (title from tag; no artist, artwork, or chapter info).
- No custom notification actions (jump forward/backward buttons).
- Android Auto not functional in Media3 path.
- Full session-centric architecture deferred to Phase 2.

### Phase 2 — Full Media3 session-centric architecture (Patreon-aligned)
- Replace legacy `MediaSessionConnector` + `PlayerNotificationManager` with Media3 `MediaSession` and native notifications.
- Implement proper MediaMetadata population with title, artist, artwork from PlaybackSession.
- Eliminate reflection: create Media3-native notification manager; let session drive notifications.
- Maintain single long-lived Media3 ExoPlayer owned by service/session.
- Map commands into `MediaSession.Callback` (play/pause/seek/speed) and route to existing service methods.

**Current State (Phase 1):**
- Using legacy `MediaSessionCompat` + `MediaSessionConnector` (from `androidx.media3.session.legacy`)
- Using `PlayerNotificationManager` (reflection-based)
- `PlayerWrapper` already provides single player instance abstraction ✅

**Phase 2 Goals:**
1. Replace with Media3's native `MediaSession` (from `androidx.media3.session`)
2. Eliminate `PlayerNotificationManager` - MediaSession handles notifications automatically
3. Implement `MediaSession.Callback` for all standard playback commands
4. Maintain backward compatibility through feature flag

**Architecture Changes:**

**1. Media3Wrapper Owns MediaSession:**
```kotlin
// In Media3Wrapper.kt
private var mediaSession: MediaSession? = null

fun initializeMediaSession(service: MediaSessionService, callback: MediaSession.Callback) {
  mediaSession = MediaSession.Builder(service, player)
    .setCallback(callback)
    .build()
  // MediaSession automatically creates and manages notifications
}
```

**3. Wrapper Interface Updates:**
```kotlin
interface PlayerWrapper {
  // Phase 1 methods (keep for backward compatibility):
  fun attachNotificationManager(manager: PlayerNotificationManager)
  fun attachMediaSessionConnector(connector: MediaSessionConnector)
  
  // Phase 2 new methods:
  fun initializeMediaSession(service: MediaSessionService, callback: MediaSession.Callback)
  fun getMediaSession(): MediaSession?
  fun releaseMediaSession()
}
```

**4. Service Initialization (Phase 2 path):**
```kotlin
private fun initializeMPlayer() {
  // ... existing player creation ...
  
  playerWrapper = PlayerWrapperFactory.wrapExistingPlayer(this, mPlayer)
  
  if (PlayerWrapperFactory.useMedia3()) {
    // Media3 playback now handled by Media3PlaybackService.
    // Wrapper remains only for Cast fallback and legacy notification hooks.
  } else {
    // Phase 1/Legacy: Old notification manager
    playerWrapper.attachNotificationManager(playerNotificationManager)
    playerWrapper.attachMediaSessionConnector(mediaSessionConnector)
  }
  
  playerWrapper.addListener(PlayerListener(this))
}
```

**Files to Modify:**
- `PlayerWrapper.kt` - Add MediaSession methods to interface
- `ExoPlayerWrapper.kt` - Add stub implementations for legacy path
- `Media3Wrapper.kt` - Implement MediaSession creation and management
- `PlayerNotificationService.kt` - Conditional initialization based on flag

**Files to Eventually Remove:**
- `MediaSessionPlaybackPreparer.kt` - Replaced by `MediaSession.Callback`

**Testing Strategy:**
1. Flag OFF: Verify existing behavior unchanged
2. Flag ON: Verify Media3 session path:
   - Notifications with correct controls
   - Play/pause/seek/speed commands work
   - Android Auto functional (may require `MediaLibraryService`)
   - Background playback continues
   - Lock screen controls work
3. Compare metrics for performance regression

**Key Insights from Google's Migration Guide:**
- **Service should extend `MediaLibraryService`** (not `MediaSessionService`) for Android Auto/browsing support
- **`MediaLibraryService` is backwards compatible** with `MediaBrowserServiceCompat` clients
- **Both intent filters required in manifest** for backwards compatibility:
  ```xml
  <intent-filter>
      <action android:name="androidx.media3.session.MediaLibraryService"/>
      <action android:name="android.media.browse.MediaBrowserService" />
  </intent-filter>
  ```
- **MediaSession automatically handles notifications** - no separate `PlayerNotificationManager` needed
- **Implement `onAddMediaItems()`** - replaces `PlaybackPreparer`, handles both Media3 and legacy API calls
- **Reference implementation**: [AndroidX Media3 session demo app](https://github.com/androidx/media/tree/release/demos/session)

**Risks & Mitigation:**
- **Service lifecycle**: Moving from `MediaBrowserServiceCompat` to `MediaLibraryService` requires thorough testing
- **Custom notification actions**: Standard actions work automatically; custom actions (bookmarks, sleep timer) need Phase 3
- **Android Auto compatibility**: `MediaLibraryService` handles this with proper intent filters

**Acceptance Criteria:**
- ✅ Media3 session created and owns player lifecycle when flag ON
- ✅ Notifications display correctly via MediaSession (no separate manager)
- ✅ Standard playback commands work (play, pause, seek, speed)
- ✅ No reflection warnings in logs
- ✅ Existing functionality preserved when flag OFF
- ✅ Android Auto integration functional
- ✅ Performance metrics show no regression

### Phase 3 — Port custom actions
- Recreate custom actions as `SessionCommand`s with stable string IDs
- Handle in `onCustomCommand` and wire to existing service methods
- Custom actions: bookmarks, sleep timer, queue management

### Phase 4 — Re-enable Chromecast with `media3-cast`
**Current Status: Cast works via dual-path fallback in Phase 1.5**

- Cast functionality preserved using dual-path approach:
  - When casting: switches to ExoPlayer v2 notification system
  - When local: uses Media3 MediaSession notifications
  - User sees different notification UI depending on cast state
- ✅ Existing `CastPlayer` (ExoPlayer v2 based) works without changes
- ✅ Cast switching functional with Media3 enabled

**Phase 4 Goals (Full Media3 Cast Migration):**
- Migrate to Media3's `androidx.media3.cast.CastPlayer`
- Replace custom `CastPlayer` implementation (~1000 lines)
- Update `CastManager`, `CastTimeline`, `CastTimelineTracker` to use Media3 APIs
- Consistent Media3 MediaSession notifications for both local and cast
- Align with `androidx.media3.cast` API patterns
- Ensure Play Services versions aligned
- Validate queue/timeline sync with Media3 `CastTimeline` utilities

**Migration Strategy:**
- Current dual-path approach allows Cast to work immediately while planning full migration
- Full migration can be done incrementally in a later phase
- No user-facing functionality lost during transition

### Phase 5 — Regression, testability, and polish
- Validate: local + streaming, HLS, seek, notifications, background playback, headset/noisy handling
- Add unit tests around wrapper behavior, session commands, player events
- Expand metrics: startup distributions, rebuffer ratio, failure rate by stack

## Acceptance Criteria (Per Phase)

- **Phase 1**: App builds; Media3 path plays; wrapper-managed notifications function; metrics emitted; logs quiet in release ✅
- **Phase 2**: Media3 session in control (no reflection); single long-lived player; notification behavior matches/exceeds legacy
- **Phase 3**: All custom actions available and functional via `SessionCommand`
- **Phase 4**: Cast playback works; switching between local/cast preserved
- **Phase 5**: Full test coverage; production-ready

## Suggested PR Sequencing

1. ✅ **Phase 1** - Core Media3 playback via wrapper (compile-green, no behavior changes) - **CURRENT PR**
2. Phase 2 - Media3 session + notifications (flag ON), remove reflection
3. Phase 3 - Custom actions port (`SessionCommand`)
4. Phase 4 - Cast migration (`media3-cast`)
5. Phase 5 - Remove ExoPlayer v2 dependencies, final polish

## Rollout & Rollback Strategy

### Rollout (Flag-Driven)
- Default: `USE_MEDIA3=false` in `variables.gradle` (current default)
- Enable for internal/beta by flipping gradle flag or remote flag (when available)
- Compare metrics in logs/dashboards: startup latency, buffer count, error rate labeled by player

### Rollback Plan
If issues arise, you can revert by:
1. Set `USE_MEDIA3=false` in `variables.gradle` (immediate rollback)
2. Rebuild and redeploy
3. If needed, git revert the PR to remove Media3 code entirely

**Note:** Phase 1's dual implementation allows instant rollback via feature flag without code changes.

## How to Build (Local)

### Enable Media3 (Testing):

```gradle
// In android/variables.gradle
ext {
  USE_MEDIA3 = true  // Change to true to test Media3 path
}
```

## Testing Phase 1

### Manual Testing Checklist:
- [ ] Basic playback starts (local file)
- [ ] Streaming playback works (HLS)
- [ ] Pause/resume functions correctly
- [ ] Seeking forward/backward works
- [ ] Playback speed change works (0.5x, 1.0x, 1.5x, 2.0x)
- [ ] Sleep timer integration functions
- [ ] Notification controls work (play, pause, skip)
- [ ] Background playback continues
- [ ] Lock screen controls work
- [ ] Cast switching works (if cast device available)
- [ ] Check logs for metrics output (startup latency, buffer count)
- [ ] No crashes or ANRs during 30min continuous playback

### Compare Flag ON vs OFF:
- Startup latency should be comparable (±50ms acceptable)
- Buffer behavior should match
- No new errors in logs
- Notification behavior identical

## References

- [Media3 ExoPlayer Migration Guide](https://developer.android.com/media/media3/exoplayer/migration-guide)
- [Media3 Documentation](https://developer.android.com/media/media3)
- [Media3 Session Documentation](https://developer.android.com/media/media3/session)
- [Media3 Release Notes](https://github.com/androidx/media/releases)
- [Patreon's Media3 Migration Blog](https://blog.patreon.com) (referenced approach)

## FAQ

**Q: Why keep both ExoPlayer v2 and Media3 dependencies?**
A: Phase 1's dual implementation allows instant rollback via feature flag. We'll remove ExoPlayer v2 after Phase 5 validation.

**Q: What happens if I enable `USE_MEDIA3=true` now?**
A: Phase 1 is complete and functional. Media3 playback works but still uses legacy MediaSessionConnector (Phase 2 will modernize this).

**Q: Can I use this in production?**
A: Not yet. Keep `USE_MEDIA3=false` until Phase 2-3 complete and metrics prove stability.

**Q: Why not use Media3's new session API in Phase 1?**
A: Incremental approach. Phase 1 proves Media3 playback works. Phase 2 adds session modernization. Reduces risk per PR.

**Q: What about Android Auto?**
A: Currently works with legacy `MediaBrowserServiceCompat`. Phase 2 will verify Media3 `MediaSession` / `MediaLibraryService` compatibility.
