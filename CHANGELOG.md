# Changelog

All notable changes to the Android TV fork of audiobookshelf-app are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to a fork-specific patch/minor versioning convention (`android-tv-vX.Y.Z`) tracked separately from the upstream `package.json` version.

Entries below are quoted from `docs/PR_DESCRIPTION.md` where that file has a per-version section; pre-v1.0.6 versions are summarized from the GitHub release notes and `memory/todo_abs_tv.md` Shipped section.

## [Unreleased]

## [1.0.9] - 2026-05-14

### Changed
- Synced upstream `advplyr/audiobookshelf-app` to `v0.13.0-beta` (40 commits).
- Cover image color sampling now uses CapacitorHttp, fixing the case where the WebView strips the origin header from cover requests.
- Focus-regain progress sync no longer double-fetches when the app returns to the foreground while connected to a server.
- 30+ second socket reconnects now reconcile media-open progress on resume.
- Removed several debug log statements (upstream cleanup).

### Added
- Belarusian and Bulgarian language options (upstream).
- Podcast episode results included in search (upstream).

## [1.0.8] — 2026-04-25 — User-Customizable Focus Ring Color (TV-only)

Adds a TV-only Settings control that lets the user pick the D-pad focus-ring color from a curated set of 7 high-contrast presets, plus the underlying refactor that makes the runtime override possible. Zero effect on phone / tablet / iOS.

### Added

- **`components/ui/TvFocusColorPicker.vue` (new)** — Horizontal swatch row of 7 buttons (ABS Green default · Sky · Amber · Red · Violet · Yellow · White). The currently-in-use swatch carries a `★` glyph (white char with black halo so it reads on every fill including white). D-pad focus draws a black inner edge + outer band in `var(--tv-focus-color)` via box-shadow — the black band keeps the indicator readable when the focused swatch is the same color as the focus ring itself, and box-shadow takes no layout space so swatches don't shift between focused/unfocused states. Component is TV-agnostic; the parent handles `isAndroidTv` gating.
- **`pages/settings.vue`** — new v-if=isAndroidTv "TV Settings" section at the top of the page hosting `<TvFocusColorPicker>`. Adds `isAndroidTv` and `tvFocusColor` computeds plus a `setTvFocusColor` method that dispatches `updateUserSettings`. The `mt-10` spacer on the next section header is gated to TV only so phone layout is byte-identical to before. Section header / row label are hardcoded English on this fork (not routed through `$strings`) to avoid touching `strings/en-us.json`; i18n migration deferred until after upstream PR acceptance.
- **`store/user.js`** — new `tvFocusColor: '#1ad691'` key on the initial `state.settings` object. Rides the existing `updateUserSettings` / `loadUserSettings` / `$localStore.setUserSettings` pipeline alongside `playbackRate`, `collapseSeries`, etc. Persistence model is the same single device-wide `'userSettings'` Capacitor Preferences key every other setting uses — not per-user, matching the existing app contract.
- **`plugins/tv-navigation.js`** — new `VALID_TV_FOCUS_HEXES` allowlist + `applyTvFocusColor(value, store)` helper at module scope. Inside `registerTvListeners`, an initial-apply call handles the case where `loadUserSettings` finishes before TV nav init runs, then a `user-settings` event-bus subscriber writes the chosen hex into `--tv-focus-color` on `<html>` for every later change. Stored values not in the allowlist self-heal back to default by dispatching a corrective `updateUserSettings`.

### Changed

- **`assets/css/tv-focus.css`** — the hardcoded `#1ad691` literal was repeated 8 times across every focus-ring presentation (border overlays, modal left-accents, drawer accents, generic outlines, settings dropdowns, player controls, etc.). Extracted to a single `--tv-focus-color` CSS custom property defined on `:root.android-tv`. All 8 sites now reference `var(--tv-focus-color)` so a single setProperty call retints every surface.

### Verification

- 26-item manual test checklist passed on Google TV Streamer 4K, including a non-default-color regression pass over all 8 I8 focus surfaces and a smoke run of the v1.0.5/v1.0.6 42-item TV checklist with the picker landed.

## [1.0.7] — 2026-04-21 — Post-v1.0.6 Regression Fixes (TV-only)

Three pre-existing regressions surfaced during the v1.0.5+v1.0.6 QA pass — each was present in v1.0.5 and earlier but not caught until a thorough 42-item manual test run. All three fixes are TV-gated and have zero effect on phone/tablet/iOS.

### Fixed

- **History option no longer breaks the fullscreen player** (`components/app/AudioPlayer.vue`) — Selecting History from the fullscreen audio player's ellipsis menu on TV collapsed the player into a stale mini-player state (mini player was retired in an earlier release). Fix: hide History from the fullscreen ellipsis on TV via an `isAndroidTv` guard on the menu-item conditional. History remains accessible from book detail pages, the normal entry point.
- **Library sort modal D-pad wrap-around** (`plugins/tv-navigation.js`) — D-pad Down from the last option in a long sort modal (13-option library sort) wrapped to whatever option happened to be visible in the viewport (typically a middle item) instead of the first option. Root cause: the overlay focusable filter was rejecting scrolled-off-screen items via a viewport check, so the "wrap to index 0" landed on index 0 of the trimmed visible set rather than the full list. Fix: added an `ignoreViewport` option to `isVisible` and `getAllFocusable`; `handleOverlayNavigation` now passes `{ ignoreViewport: true }` so the full logical list is the navigable set regardless of scroll position. `scrollIntoView` (already called after focus) brings the newly-focused item into view. Main-page navigation is unchanged — bookshelf scroll still filters by viewport.
- **Playlist row play-button fingerprint preserved across player close** (`plugins/tv-navigation.js`) — Starting playback from an individual book's play button inside a playlist, then closing the fullscreen player, landed focus on the playlist's primary "Play Playlist" button instead of the row the user started from. Root cause: two interacting problems. (a) No fingerprint was being saved for the pre-playback focus since the route doesn't change when the fullscreen player opens (it's an overlay). (b) Three separate code paths raced to call `focusFirstContentElement()` on close, and Android TV's native focus engine aggressively re-focused a nearby button (the primary Play) the instant the player's focused element unmounted. Fix: save fingerprint synchronously via a dedicated `store.watch` on `playerStartingPlaybackMediaId` (committed inside `playClick` before any async). Added `focusAfterPlayerClose` helper that unconditionally restores the saved fingerprint if one exists (overriding the native engine's recovery guess), falling back only when no fingerprint was saved AND no element is currently focused. Routed all three player-close paths (Back button, MutationObserver, session watch) through the helper so the race no longer matters.

## [1.0.6] — 2026-04-20 — Screensaver Prevention During Playback (TV-only)

Addresses a user-reported issue where the Chromecast with Google TV / Android TV Ambient Mode screensaver engaged during playback and killed audio after ~10 minutes of inactivity. Per Android TV developer guidance, audio should continue through Ambient Mode automatically — but CCwGTV firmware behavior diverges from the docs and stops playback regardless. This fix works around that platform quirk.

### Fixed

- **`components/app/AudioPlayer.vue`** — new `updateKeepAwake(shouldKeepAwake)` helper, TV-gated via the `isAndroidTv` Vuex state. Wired into `onPlayingUpdate` (chokepoint for every play↔pause transition) and `endPlayback` (chokepoint for all session teardown: close, failure, fullscreen-collapse-close, component destroy).
- Uses `@capacitor-community/keep-awake` — already in the dependency tree via the e-reader (`Reader.vue`). No new dependencies.
- Try/catch around plugin calls; plugin errors are logged but never disrupt playback.
- Behavior on phone / tablet / iOS: unchanged — the `isAndroidTv` gate returns early.
- Behavior during paused playback on TV: wake lock released; screensaver engages normally; standard Android TV "Home after ~30 min" behavior applies, matching every other TV media app.

## [1.0.5] — 2026-04-13 — Audit Fix Pack (TV-only)

Full audit fix pack landed: H1, H2+M1, H3, H4, M2, M3+M4, M10+M14. Refocus interval stacking + focusLossTimer cleanup are the headline fixes. See PR #1843 squash structure (`14034b87`) for the consolidated view.

## [1.0.4] — 2026-04-10 — Back Arrow Fingerprint Fix (TV-only)

Back arrow now correctly restores fingerprint focus on Android TV.

## [1.0.3] — 2026-04-10 — Connect Page D-pad Overhaul (TV-only)

Connect page reworked for D-pad navigation.

## [1.0.2] — 2026-04-09 — Server/User Screen, Bug Fixes, Player Changes, Docs (TV-only)

Server / user screen polish, miscellaneous bug fixes, audio player changes, and TV documentation expansions.

## [1.0.0] — 2026-04-07 — Initial Android TV PR Submission

Initial Android TV support submitted to `advplyr/audiobookshelf-app#1843`. Leanback launcher integration, device detection, CSS-class-based TV scoping, spatial D-pad navigation, focus ring styling, focus memory across navigation, overlay focus trapping, fullscreen audio player with full D-pad nav, author detail page, app termination on TV exit, TV user guide, CastOptionsProvider crash guard. See `docs/PR_DESCRIPTION.md` for the full file-by-file breakdown.
