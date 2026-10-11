# Audiobookshelf for Wear OS

A standalone watch app (Galaxy Watch 4 and newer, Pixel Watch, any Wear OS 3+ watch, minSdk 30)
that can stream or download audiobooks and podcast episodes to the watch and listen without the
phone. Built with Jetpack Compose for Wear OS, Media3 and Tiles.

## How it works

- **Sign in**: the watch asks the phone app for its server and a current access token over a
  Wearable channel (`WatchBridge` in the phone app, `PhoneLink` here), so signing in on the phone
  signs in the watch. The phone's refresh token is never shared, because the server rotates it;
  when the watch's access token expires it asks the phone again and the phone refreshes its own
  session. Signing out on the phone (or removing the server the phone is connected to) signs out a
  watch that signed in this way (`WatchSync` → `PhoneConfig`). Signing in with a password on the
  watch still works for using the watch without a phone, and isn't affected by the phone signing out.
- **Through the phone**: when the watch can't reach the server directly (for example a server only
  reachable on the home network while the watch is on Bluetooth), every request goes through the
  phone app instead: browsing, covers, streaming and downloads (`ServerRoute` and
  `PhoneRelayInterceptor`). The phone only forwards requests to servers it is signed in to (same
  scheme, host and port). Requests that could be applied twice (POST, PATCH) are only resent on the
  other route if they never reached the server. Settings → Connection shows which way the watch is
  connected; tap it to check again.
- **Browse**: the home screen is a card hub with Continue listening, Library and Series; turn on
  Settings → Scrollable homepage for a list instead. Libraries, series, search, book and episode
  pages, and the books downloaded to the watch (On this watch).
- **Downloads**: books are saved to app storage (`files/books/<itemId>/`) and run in WorkManager.
  They ask Wear OS to bring up Wi-Fi or LTE, which is much faster than the Bluetooth link to the
  phone (`FastNetwork`). If neither comes up within 15 seconds they download over the normal
  connection instead, often Bluetooth through the phone (and through the phone app when the server
  can't be reached directly). Interrupted downloads resume where they stopped. Ids and file names
  from the server are cleaned (`safeFileName`) so a download can't be written outside its folder.
- **Progress sync**: listening is reported to the server as local sessions every 15 seconds and on
  pause, the same way the phone app reports downloaded books (`ListeningTracker`, `SessionSync`).
  Sessions recorded offline are queued and sent when the watch is back online (Settings → Sync
  progress sends them now). When you press play, the watch resumes from whichever is newer: its own
  position or the server's (which includes listening on the phone). Resuming after a pause of more
  than 5 seconds checks the server again, in case you listened elsewhere in between.
- **Progress on the phone**: so the phone doesn't overwrite what you listened to on the watch, the
  phone app uses the server's progress when it's newer: when resuming after a pause of more than 5
  seconds (`checkServerProgressOnResume`; it recognises another device's progress by position,
  since phone and server clocks can't be compared), when starting a downloaded book, and it ignores
  progress pushed from the server that's older than its own.
- **Playback**: Media3 ExoPlayer in a `MediaSessionService`, so the watch's media controls and
  Bluetooth headphone buttons work. The player has back 10 s / forward 30 s (long press for the
  previous / next chapter), chapters, playback speed (kept across books) and a sleep timer (minutes
  or end of chapter). The outer ring shows progress through the book, the ring around the play
  button progress through the chapter. Turn the crown or bezel on the player screen to change the
  volume. The media notification is an Ongoing Activity, so a "now playing" icon on the watch face
  opens the player.
- **Audio output**: with no headphones connected, pressing play asks where to play (`HeadphoneGuard`):
  connected headphones, the watch speaker, or Connect device (the Bluetooth picker). The speaker
  choice is kept until headphones connect, and playback starts by itself when headphones connect
  within 5 minutes of pressing play. Player options → Audio output switches output at any time.
- **Podcasts**: podcast libraries list their podcasts; each episode plays, downloads, syncs progress
  and can be marked finished like a book. Episodes are keyed as `itemId/episodeId` (`MediaKey`) and
  downloaded to `files/books/<itemId>__<episodeId>/`.
- **Finished**: an item that plays to the end, or is marked finished on its page, is marked finished
  on the server. With Settings → Delete finished downloads on (off by default), its download is
  removed, also for items finished on the phone (checked when the app starts).
- **Tiles**:
  - **Continue listening**: covers of the books and episodes in progress (what's playing first); tap
    one to play it.
  - **On this watch**: downloads, storage used and free, and running downloads. Works offline.
  - **Listening stats**: time today, a 7-day chart, the week total and the streak, from
    `/api/me/listening-stats`. The only place stats are shown on the watch; tap to refresh.

  Server data is cached (`TileCache`) so tiles show instantly and work offline. Add one for
  debugging with `adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es
  operation add-tile --ecn component com.audiobookshelf.app.debug/com.audiobookshelf.wear.tile.<Tile>`
  (`ContinueListeningTile`, `DownloadsTile`, `StatsTile`).
- **Security**: tokens are encrypted with a key in the Android Keystore (`AuthStore`). Credentials
  and custom headers are only sent to the signed in server, matched by scheme, host and port
  (`AbsApi.isForServer`).

## Phone app side

The phone app (`android/app`) has a small bridge for the watch, in `com.audiobookshelf.app.device`:

- `WatchBridge` answers sign in (`/abs/auth`) and relay (`/abs/relay`) requests over Wearable
  channels; `WatchListenerService` receives them even while the phone app is closed.
- `WatchSync` shares the phone's current server with the watch over the Data Layer (`/abs/server`),
  or tells it the phone signed out.
- `res/values/wear.xml` declares the `abs_phone_app` capability the watch looks for (the watch
  declares `abs_wear_app`).

Paths, keys and framing on both sides must match.

## Tests

```sh
cd android
./gradlew :wear:testDebugUnitTest
```

JVM tests with Robolectric and MockWebServer, using saved server responses in
`src/test/resources/fixtures`: API parsing and auth, models and download file names, listening
stats, saved progress, auto-delete, the headphone check, the session sync queue and the tile data.

## Build and install

Both apps use the same application id (`com.audiobookshelf.app`, `.debug` for debug builds) and
must be signed with the same key for the Data Layer to connect them. Install the phone app from the
same build as the watch app; an older phone app without the bridge can't sign in the watch.

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
