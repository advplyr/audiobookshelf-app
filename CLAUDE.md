# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Audiobookshelf mobile app built with **NuxtJS 2** (SSR disabled, static target) and **Capacitor 7**, sharing one JS codebase across Android and iOS. Requires a running Audiobookshelf server to connect to.

## Development Commands

```bash
npm install                  # install dependencies
npm run generate             # build static web app into dist/
npx cap sync                 # copy dist/ into android/ios native shells
npm run sync                 # shortcut: generate + cap sync (use after JS changes)
npm run dev                  # local web dev server at http://0.0.0.0:1337
npx cap open android         # open Android Studio
npx cap open ios             # open Xcode
npm run devlive              # live reload via ionic cap run on connected device
```

There are no automated tests in this project.

## Architecture

### Two-layer structure

**JS Layer (NuxtJS)** → compiled to `dist/` → synced into native shells via Capacitor.

**Native Layer (Android/Kotlin)** in `android/app/src/main/java/com/audiobookshelf/app/`.

### Custom Capacitor Plugins

The bridge between JS and native is five custom Capacitor plugins. Each has a JS counterpart in `plugins/capacitor/` and a Kotlin implementation in `android/.../plugins/`:

| Plugin | Purpose |
|---|---|
| `AbsAudioPlayer` | Audio playback control, cast, sleep timer |
| `AbsDatabase` | Local SQLite DB, device data, download progress |
| `AbsDownloader` | File download management |
| `AbsFileSystem` | File system access, folder scanning |
| `AbsLogger` | Native log capture |

**JS → Native**: Call plugin methods directly, e.g. `AbsAudioPlayer.prepareLibraryItem({...})`.
**Native → JS**: Use Capacitor's `notifyListeners()` on the native side and `addListener()` on the JS side.

### Android Native Key Files

- `MainActivity.kt` — `BridgeActivity` subclass; registers all plugins; binds `PlayerNotificationService` on `onPostCreate` and wires it to `AbsAudioPlayer` via `pluginCallback`.
- `player/PlayerNotificationService.kt` — `MediaBrowserServiceCompat` foreground service; owns ExoPlayer instance; implements `ClientEventEmitter` interface to push events back to `AbsAudioPlayer.kt`.
- `player/CastManager.kt` / `CastPlayer.kt` — Google Cast support.
- `media/MediaManager.kt` — Caches library items, authors, series, collections, and podcast data for Android Auto / media browser.
- `media/MediaProgressSyncer.kt` — Syncs playback progress with the server.
- `managers/DbManager.kt` — SQLite wrapper for local library items and progress.
- `managers/SleepTimerManager.kt` — Sleep timer logic.
- `server/ApiHandler.kt` — All REST API calls to the ABS server.

### JS Layer Key Files

- `plugins/server.js` — `ServerSocket` class (socket.io); injected as `$socket`; handles authentication and real-time events from server.
- `plugins/db.js` — `DbService`; thin wrapper around `AbsDatabase`; injected as `$db`; also listens for `onTokenRefresh` / `onTokenRefreshFailure` events.
- `plugins/localStore.js` — `LocalStorage` class backed by Capacitor `Preferences`; injected as `$localStore`; persists user settings, theme, language, last library.
- `plugins/init.client.js` — Registers global Vue utilities (`$eventBus`, date/time helpers, `$bytesPretty`, `$elapsedPretty`, etc.); handles back-button behavior for Android and iOS swipe navigation.
- `plugins/capacitor/AbsAudioPlayer.js` — Web fallback implementation of `AbsAudioPlayer` using the HTML5 `<audio>` element; used during browser-based development.

### Vuex Store

- `store/index.js` — Global state: network status, current playback session, player playing/fullscreen state, cast availability, playlist queue.
- `store/user.js` — Auth token, server connection config, user object (permissions, media progress, bookmarks), user settings.
- `store/libraries.js` — Library list, current library ID, filter data.
- `store/globals.js` — Modal open state and other UI globals.

### Playlist Queue

Playlist playback uses a two-layer approach so advancement works even when the screen is off or Android's Doze mode throttles the WebView's JS engine:

**Native layer** (`PlayerNotificationService.kt`): Holds `playlistQueue: List<PlaylistQueueItem>` and `playlistQueueIndex`. When ExoPlayer fires `STATE_ENDED`, `PlayerListener` calls `advancePlaylistQueue()` directly on the service. This calls `preparePlayer()` for the next item (local: from DB; server: via `apiHandler.playLibraryItem()`) without touching the JS layer at all.

**JS layer** (`AudioPlayerContainer.vue` + `pages/playlist/_id.vue`): When a playlist starts, `AbsAudioPlayer.setPlaylistQueue(items, currentIndex)` is called to sync the native queue. When `onPlaybackEnded` fires in JS (app in foreground), it only updates the Vuex `currentIndex` for UI purposes — it does NOT trigger playback. `AbsAudioPlayer.clearPlaylistQueue()` is called when a non-playlist item is started.

**`AbsAudioPlayer.kt` background suppression**: `onMetadata` events are suppressed while backgrounded (`isInForeground == false`) except for `ENDED` state. Do not remove this exemption — it allows the JS Vuex state to sync when the app returns to foreground after an episode ended.

### Communication Patterns

- **Server REST API**: `$axios` (configured in `plugins/axios.js`); base URL is the connected server address.
- **Real-time updates**: socket.io via `$socket`; socket must be authenticated with `auth` event after connection.
- **Playback events**: flow from `PlayerNotificationService` → `AbsAudioPlayer.kt` (via `ClientEventEmitter`) → `notifyListeners()` → JS `addListener()` handlers → Vuex mutations.

### i18n

Translation strings live in `strings/<locale>.json`. Applied via `plugins/i18n.js`. Language preference is persisted with `$localStore.setLanguage()`.

### Styling

Tailwind CSS 3 with PostCSS. Global styles in `assets/app.css` and `assets/tailwind.css`. The `tailwind.config.js` controls customization.
