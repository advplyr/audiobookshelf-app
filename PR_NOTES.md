> **NOTE: DELETE THIS FILE BEFORE SUBMITTING THE PR.**
> This file is working notes for the PR description — copy the content below into the GitHub PR body, then delete this file and amend the commit.

---

## Fix: foreground service not preserved on Bluetooth audio output switch

### What the user experiences

When listening on Bluetooth (headphones or car) and the audio output changes — headphones powered off, car stereo disconnects on parking, switching between two BT devices — the ABS notification disappears from the notification shade. When the user reopens the app they land on the library screen instead of the player, and must navigate back to the book and press play manually. Playback position is correct (server sync works fine); this is purely a local Android service lifecycle failure.

### Root cause

Two missing code paths in `PlayerNotificationListener.kt`.

When Bluetooth disconnects, ExoPlayer fires `ACTION_AUDIO_BECOMING_NOISY` and pauses. This causes `PlayerNotificationManager` to post a non-ongoing notification (`onGoing=false`). The existing `onNotificationPosted` handler only covered two cases:

```kotlin
if (onGoing && !isForegroundService) {
    // start foreground — handled ✓
} else {
    // do nothing — logs only
}
```

The missing case is `!onGoing && isForegroundService` (player just paused, service is currently in foreground). Without handling it, the service stays registered as a foreground service but its notification becomes non-ongoing. On Android 12+, the system then removes the notification from the shade and eventually kills the service.

Similarly, `onNotificationCancelled` with `dismissedByUser=false` never called `stopForeground()`, leaving the service in the same broken state.

### How to reproduce

1. Open ABS Android, start playing any audiobook via Bluetooth headphones or car stereo
2. Power off the Bluetooth device (or let the car park and disconnect)
3. Wait 1–5 minutes (the bug is time-dependent — short gaps often look fine)
4. Check the notification shade: ABS notification is gone
5. Open ABS: lands on library screen, not the player

Logcat signature of the bug (original code, `onGoing=false | isForegroundService=true` hitting the else branch — no action taken):
```
PlayerNoti...onListener  com.audiobookshelf.app  D  Notification posted 10, not starting foreground - onGoing=false | isForegroundService=true
```

### The fix

Add the missing `else if (!onGoing && isForegroundService)` branch to `onNotificationPosted` that:
- Calls `stopForeground(STOP_FOREGROUND_DETACH)` (API 24+) or `stopForeground(false)` (pre-N) — demotes from foreground but keeps service alive
- Re-posts the notification via `NotificationManagerCompat.notify()` so it stays visible in the shade as a regular (non-foreground) notification
- Sets `isForegroundService = false`

Add a guard in `onNotificationCancelled` when `dismissedByUser=false` that calls `stopForeground(STOP_FOREGROUND_REMOVE)` if still in foreground mode, then exits — but does NOT call `stopSelf()`, so the service survives as a background service.

### Changes

Only one file changed: `android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationListener.kt`

### Testing

Tested on Pixel 10 (Android 16), ABS server 2.35.1.

Logcat with fix applied, player pauses on BT disconnect:
```
PlayerNoti...onListener  com.audiobookshelf.app.debug  D  Notification Posted 10 - Stopping foreground (player paused), keeping service alive
```

User then resumes from the notification without opening the app:
```
MediaSessionCallback  com.audiobookshelf.app.debug  D  ON PLAY MEDIA SESSION COMPAT
PlayerNoti...onListener  com.audiobookshelf.app.debug  D  Notification Posted 10 - Start Foreground
```

Cycled through multiple Bluetooth device disconnects and reconnects — notification persisted in shade throughout, playback resumable from notification each time without opening the app.
