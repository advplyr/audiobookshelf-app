# Audiobookshelf for Wear OS

A standalone watch app (Galaxy Watch 4 and newer, Pixel Watch, any Wear OS 3+ watch) that can
stream or download audiobooks to the watch and listen without the phone.

## How it works

- **Sign in**: the watch asks the phone app for its server and a current access token over a
  Wearable channel (`WatchBridge` in the phone app, `PhoneLink` here), so signing in on the phone
  signs in the watch. The phone's refresh token is never shared, because the server rotates it;
  when the watch's access token expires it asks the phone again and the phone refreshes its own
  session. Signing in with a password on the watch still works for using the watch without a phone.
- **Through the phone**: when the watch can't reach the server directly (for example a server only
  reachable on the home network while the watch is on Bluetooth), every request goes through the
  phone app instead: browsing, covers, streaming and downloads (`ServerRoute` and
  `PhoneRelayInterceptor`). The phone only forwards requests to servers it is signed in to.
  Settings → Connection shows which way the watch is connected.
- **Downloads**: books are saved to app storage (`files/books/<itemId>/`). Downloads run in
  WorkManager and ask Wear OS for Wi-Fi or LTE instead of the slow Bluetooth link through the
  phone. Interrupted downloads resume where they stopped.
- **Progress sync**: listening is reported to the server as local sessions every 15 seconds and on
  pause, the same way the phone app reports downloaded books. Sessions recorded offline are queued
  and sent when the watch is back online. When you press play, the watch resumes from whichever is
  newer: its own position or the server's (which includes listening on the phone).
- **Playback**: Media3 ExoPlayer in a `MediaSessionService`, so the watch's media controls and
  Bluetooth headphone buttons work. Turn the crown or bezel on the player screen to change the volume.

## Build and install

Both apps use the same application id (`com.audiobookshelf.app`, `.debug` for debug builds) and
must be signed with the same key for the Data Layer to connect them.

```sh
cd android
./gradlew :wear:assembleDebug
```

Install on the watch over Wi-Fi debugging (Settings > Developer options > Wireless debugging on the
watch):

```sh
adb pair <watch-ip>:<pairing-port>
adb connect <watch-ip>:<port>
adb -s <watch-ip>:<port> install wear/build/outputs/apk/debug/wear-debug.apk
```
