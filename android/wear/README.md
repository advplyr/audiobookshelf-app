# Audiobookshelf for Wear OS

A standalone watch app (Galaxy Watch 4 and newer, Pixel Watch, any Wear OS 3+ watch) that can
stream or download audiobooks to the watch and listen without the phone.

## How it works

- **Sign in**: the phone app shares the server address and username with the watch over the
  Wearable Data Layer (`WatchSync` in the phone app, `PhoneConfig` here), so only the password has to
  be typed on the watch. The watch keeps its own server session because the server rotates refresh
  tokens, so sharing the phone's tokens would sign one of the devices out.
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
