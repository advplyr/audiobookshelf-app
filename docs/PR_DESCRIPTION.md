# PR: Add Android TV support with D-pad navigation

**Closes #606**

## Summary

Adds full Android TV support to the audiobookshelf app, including leanback launcher integration, spatial D-pad navigation, focus ring styling, and a TV user guide. All TV behavior is gated behind device detection — mobile/tablet behavior is unchanged.

This PR combines what was originally scoped as two separate PRs (basic TV scaffolding + D-pad navigation) into a single submission since the scaffolding is a prerequisite with no standalone value.

## What's included

- **Leanback launcher integration** — TV banner, manifest declarations, app appears in Android TV launcher
- **Device detection** — `DeviceManager.isAndroidTV()` exposes an `isAndroidTv` flag to the WebView via the AbsDatabase plugin and Vuex store
- **CSS class injection** — `android-tv` class added to `<html>` on TV devices, used to scope all TV-specific styles
- **Spatial D-pad navigation** — full arrow-key navigation using a beam model (horizontal stays in row, vertical finds nearest row)
- **Focus ring styling** — green (#1ad691) focus indicators scoped to `.android-tv`, with different treatments for cards, player controls, modals, and general elements
- **Focus memory** — saves and restores the focused element across page navigation using a fingerprint system (ID, structural path, position fallback)
- **Overlay handling** — focus trapping inside modals and side drawer, with focus restore on close
- **Audio player** — auto-expands to fullscreen on playback start, full D-pad navigation across three control rows (top/main/utility)
- **Author detail page** — new page (`pages/author/_id.vue`) with bio, image, and book grid, accessible from AuthorCard on TV
- **App termination** — on Android TV, the app fully terminates on exit (`finishAndRemoveTask` + `killProcess` in `onStop`) to prevent stale WebView state on resume
- **TV user guide** — `docs/TV_USER_GUIDE.md` with inline screenshots covering all navigation features
- **CastOptionsProvider guard** — prevents crash on devices without Google Play Services (e.g., some TV devices)

## Shared code changes

These changes add `tabindex` and `keydown.enter` attributes to existing components. On mobile/touch devices, these attributes are inert — they don't affect tap behavior or layout. They exist so that TV remotes (which fire keyboard events) can focus and activate these elements.

| File | Change |
|------|--------|
| `AudioPlayer.vue` | Added `.prevent` to all `keydown.enter` handlers (fixes double-fire on Android TV). Added `tabindex` + `keydown.enter.prevent` on more_vert, bookmark, playback speed, sleep timer, chapters button, and collapse button. |
| `ChaptersTable.vue` | Wrapped timestamp text in `<span tabindex="0">` inside `<td>` for focus targeting. Added `tabindex` + `keydown.enter.prevent` on expand bar. |
| `TracksTable.vue` | Added `tabindex` + `keydown.enter.prevent` on expand bar div. |
| `EpisodeRow.vue` | Added `tabindex` + `keydown.enter.prevent` on row, play button, playlist button, download icon. |
| `LatestEpisodeRow.vue` | Same as EpisodeRow. |
| `EpisodesTable.vue` | Added `tabindex` + `keydown.enter.prevent` on sort control. |
| `ToggleSwitch.vue` | Added `tabindex` + `keydown.enter.prevent` on toggle div. This affects all toggles app-wide but has no effect on touch interaction. |
| `TextInput.vue` | Readonly inputs get `tabindex="-1"` to prevent focus stealing from their wrapper. |
| `SideDrawer.vue` | Disconnect button: added `tabindex` + `keydown.enter.prevent`. Removed conflicting local keydown handlers. |
| `ServerConnectForm.vue` | Server config rows, edit/delete icons, back arrows: added `tabindex` + `keydown.enter.prevent`. |
| `Appbar.vue` | `tabindex="-1"` on logo (prevents accidental focus). `keydown.enter` on back button. |
| `BookshelfToolbar.vue` | Added `tabindex` + `keydown.enter` on filter/sort/view icons. |
| `LibrariesModal.vue` | Added `tabindex` + `keydown.enter` on list items. Auto-focus first item on open. |
| `LazyBookCard.vue` | Added `@keydown.enter="clickCard"`. |
| `LazySeriesCard.vue` | Added `tabindex` + `keydown.enter`. |
| `LazyCollectionCard.vue` | Added `tabindex` + `keydown.enter`. |
| `LazyPlaylistCard.vue` | Added `tabindex` + `keydown.enter`. |
| `AuthorCard.vue` | Added `tabindex`, click/enter handler. On TV, navigates to `/author/{id}` detail page. |
| `AuthorImage.vue` | `tabindex="-1"` to prevent double-focus with parent AuthorCard. |
| `pages/item/_id/index.vue` | Cover art: added `id`, `tabindex`, `keydown.enter` for focusable/clickable. Read More toggle: added `tabindex` + `keydown.enter.prevent`. |
| `pages/item/_id/_episode/index.vue` | Added `id="episode-page"` for scroll container recognition. |
| `pages/settings.vue` | All 11 dropdown wrappers: added `tabindex`, `keydown.enter.prevent`, `settings-dropdown` class. 8 info icons: added `tabindex` + `keydown.enter.prevent`. Added `id="settings-page"`. |
| `pages/account.vue` | Added `id="account-page"` + `overflow-y-auto`. |
| `pages/stats.vue` | Added `id="stats-page"`. |
| `pages/logs.vue` | Added `id="logs-container"` on log scroll container. |
| `pages/localMedia/item/_id.vue` | Added `id="manage-files-page"`. Header ellipsis, episode/track ellipsis: added `tabindex` + `keydown.enter.prevent`. Play button: added `keydown.enter.prevent`. |

## TV-only changes

These files are either new or contain changes gated behind the `android-tv` CSS class or `isAndroidTv` Vuex state:

| File | Description |
|------|-------------|
| `AndroidManifest.xml` | Leanback launcher intent filter, `uses-feature` declarations (touchscreen + leanback both `required="false"`), banner attribute |
| `MainActivity.kt` | Injects `android-tv` CSS class on `<html>`. On TV: `finishAndRemoveTask` + `killProcess` in `onStop` to fully terminate app on exit. |
| `DeviceManager.kt` | `isAndroidTV()` detection method |
| `AbsDatabase.kt` | Exposes `isAndroidTv` flag to WebView |
| `CastOptionsProvider.kt` | Guard against missing Google Play Services |
| `tv_banner.png` | 320x180 leanback launcher banner |
| `tv-focus.css` | All focus ring styles scoped to `.android-tv` |
| `tv-navigation.js` | Spatial navigation plugin (~1500 lines): D-pad handling, focus memory/fingerprinting, overlay detection, scroll coordination, author page scroll handler, player navigation |
| `pages/author/_id.vue` | Author detail page (TV navigation target from AuthorCard) |
| `layouts/default.vue` | Sets `isAndroidTv` from device data on mount |
| `store/index.js` | `isAndroidTv` state + mutation |
| `nuxt.config.js` | Registers `tv-focus.css` and `tv-navigation.js` plugin |
| `plugins/init.client.js` | Side drawer check added to existing back button handler |
| `plugins/capacitor/AbsDatabase.js` | Reads `isAndroidTv` from device info |
| `pages/bookshelf/authors.vue` | Responsive column count for TV screen widths |
| `docs/TV_USER_GUIDE.md` | User guide with inline screenshots |
| `docs/images/*` | 9 TV screenshots |

## Robustness & Audit Hardening

After the initial submission, a full code audit of the TV-related changes surfaced 35 findings (5 HIGH, 15 MEDIUM, 15 LOW; 0 CRITICAL). All HIGH and actionable MEDIUM items are fixed in this PR:

**Safety guards**
- `try/finally` around all navigation `setTimeout` callbacks — if an exception is thrown mid-restore, the nav guard is always cleared instead of permanently blocking focus recovery.
- `CSS.escape()` polyfill for older Android TV WebViews (Chrome < 46, still present on some ATV devices) to prevent selector-construction errors when card IDs contain special characters.

**Double-fire prevention**
- `.prevent` modifier on 11 `@keydown.enter` handlers across 8 components (cards, toolbar, modal, drawer). On Android TV, D-pad Enter fires both a `keydown` and a synthesized `click`; without `.prevent`, navigation fired twice.

**Connect-page TV detection**
- `connect.vue` now sets `isAndroidTv` in the Vuex store on mount. The connect page bypasses `layouts/default.vue` (where this was previously set), so the first launch with no saved server was rendering a phone layout on TV.

**Vuex reactivity**
- Replaced a direct `store.state.lastBookshelfScrollData = {}` mutation with a proper Vuex `resetLastBookshelfScrollData` mutation so scroll-restore invalidation is observable and reactive.

**Phone/tablet safety**
- All TV-only listeners (`focusout`, store watchers, router hooks, eventBus subscriptions) are gated behind an `android-tv` class check. Previously a subset could fire on phones if the class was set briefly during init.

**Focus interval bookkeeping**
- `refocusAfterContentChange` tracks and clears its prior interval before creating a new one — prevents two polling loops from fighting for focus during rapid navigation.
- `focusLossTimer` is now cleared on `router.beforeEach` so a stale `focusout` recovery timer from the previous page can't fire during a transition.

**Player controls + accessibility**
- Hidden fullscreen player controls (`v-show`) use a dynamic `tabindex` so the invisible jump-chapter buttons don't receive D-pad focus when the player is collapsed.
- Removed the blanket `:not(.android-tv) *:focus { outline: none }` rule that was suppressing focus indicators for phone/tablet keyboard and accessibility users. Focus suppression is now scoped to only what TV needs.

A 33-item manual test plan covering each fix plus regression scenarios was executed on a Chromecast with Google TV before release.

## v1.0.6 — Screensaver Prevention During Playback (TV-only)

Addresses a user-reported issue where the Chromecast with Google TV / Android TV Ambient Mode screensaver engaged during playback and killed audio after ~10 minutes of inactivity. Per Android TV developer guidance, audio should continue through Ambient Mode automatically — but CCwGTV firmware behavior diverges from the docs and stops playback regardless. This fix works around that platform quirk.

- `components/app/AudioPlayer.vue` — new `updateKeepAwake(shouldKeepAwake)` helper, TV-gated via the `isAndroidTv` Vuex state. Wired into `onPlayingUpdate` (chokepoint for every play↔pause transition) and `endPlayback` (chokepoint for all session teardown: close, failure, fullscreen-collapse-close, component destroy).
- Uses `@capacitor-community/keep-awake` — already in the dependency tree via the e-reader (`Reader.vue`). No new dependencies.
- Try/catch around plugin calls; plugin errors are logged but never disrupt playback.
- Behavior on phone / tablet / iOS: unchanged — the `isAndroidTv` gate returns early.
- Behavior during paused playback on TV: wake lock released; screensaver engages normally; standard Android TV "Home after ~30 min" behavior applies, matching every other TV media app.

## v1.0.7 — Post-v1.0.6 Regression Fixes (TV-only)

Three pre-existing regressions surfaced during the v1.0.5+v1.0.6 QA pass — each was present in v1.0.5 and earlier but not caught until a thorough 42-item manual test run. All three fixes are TV-gated and have zero effect on phone/tablet/iOS.

**History option no longer breaks the fullscreen player** (`components/app/AudioPlayer.vue`)
- Selecting History from the fullscreen audio player's ellipsis menu on TV collapsed the player into a stale mini-player state (mini player was retired in an earlier release).
- Fix: hide History from the fullscreen ellipsis on TV via an `isAndroidTv` guard on the menu-item conditional. History remains accessible from book detail pages, the normal entry point.

**Library sort modal D-pad wrap-around** (`plugins/tv-navigation.js`)
- D-pad Down from the last option in a long sort modal (13-option library sort) wrapped to whatever option happened to be visible in the viewport (typically a middle item) instead of the first option.
- Root cause: the overlay focusable filter was rejecting scrolled-off-screen items via a viewport check, so the "wrap to index 0" landed on index 0 of the trimmed visible set rather than the full list.
- Fix: added an `ignoreViewport` option to `isVisible` and `getAllFocusable`; `handleOverlayNavigation` now passes `{ ignoreViewport: true }` so the full logical list is the navigable set regardless of scroll position. `scrollIntoView` (already called after focus) brings the newly-focused item into view. Main-page navigation is unchanged — bookshelf scroll still filters by viewport.

**Playlist row play-button fingerprint preserved across player close** (`plugins/tv-navigation.js`)
- Starting playback from an individual book's play button inside a playlist, then closing the fullscreen player, landed focus on the playlist's primary "Play Playlist" button instead of the row the user started from.
- Root cause: two interacting problems. (a) No fingerprint was being saved for the pre-playback focus since the route doesn't change when the fullscreen player opens (it's an overlay). (b) Three separate code paths raced to call `focusFirstContentElement()` on close, and Android TV's native focus engine aggressively re-focused a nearby button (the primary Play) the instant the player's focused element unmounted.
- Fix: save fingerprint synchronously via a dedicated `store.watch` on `playerStartingPlaybackMediaId` (committed inside `playClick` before any async). Added `focusAfterPlayerClose` helper that unconditionally restores the saved fingerprint if one exists (overriding the native engine's recovery guess), falling back only when no fingerprint was saved AND no element is currently focused. Routed all three player-close paths (Back button, MutationObserver, session watch) through the helper so the race no longer matters.

## v1.0.8 — User-Customizable Focus Ring Color (TV-only)

Adds a TV-only Settings control that lets the user pick the D-pad focus-ring color from a curated set of 7 high-contrast presets, plus the underlying refactor that makes the runtime override possible. Zero effect on phone / tablet / iOS.

**Variable extraction** (`assets/css/tv-focus.css`)
- The hardcoded `#1ad691` literal was repeated 8 times across every focus-ring presentation (border overlays, modal left-accents, drawer accents, generic outlines, settings dropdowns, player controls, etc.). Extracted to a single `--tv-focus-color` CSS custom property defined on `:root.android-tv`. All 8 sites now reference `var(--tv-focus-color)` so a single setProperty call retints every surface.

**Settings storage** (`store/user.js`)
- New `tvFocusColor: '#1ad691'` key on the initial `state.settings` object. Rides the existing `updateUserSettings` / `loadUserSettings` / `$localStore.setUserSettings` pipeline alongside `playbackRate`, `collapseSeries`, etc. Persistence model is the same single device-wide `'userSettings'` Capacitor Preferences key every other setting uses — not per-user, matching the existing app contract.

**Runtime apply** (`plugins/tv-navigation.js`)
- New `VALID_TV_FOCUS_HEXES` allowlist + `applyTvFocusColor(value, store)` helper at module scope. Inside `registerTvListeners`, an initial-apply call handles the case where `loadUserSettings` finishes before TV nav init runs, then a `user-settings` event-bus subscriber writes the chosen hex into `--tv-focus-color` on `<html>` for every later change. Stored values not in the allowlist self-heal back to default by dispatching a corrective `updateUserSettings`.

**Picker component** (`components/ui/TvFocusColorPicker.vue` — new)
- Horizontal swatch row of 7 buttons (ABS Green default · Sky · Amber · Red · Violet · Yellow · White). The currently-in-use swatch carries a `★` glyph (white char with black halo so it reads on every fill including white). D-pad focus draws a black inner edge + outer band in `var(--tv-focus-color)` via box-shadow — the black band keeps the indicator readable when the focused swatch is the same color as the focus ring itself, and box-shadow takes no layout space so swatches don't shift between focused/unfocused states. Component is TV-agnostic; the parent handles `isAndroidTv` gating.

**Settings page wiring** (`pages/settings.vue`)
- New v-if=isAndroidTv "TV Settings" section at the top of the page hosting `<TvFocusColorPicker>`. Adds `isAndroidTv` and `tvFocusColor` computeds plus a `setTvFocusColor` method that dispatches `updateUserSettings`. The `mt-10` spacer on the next section header is gated to TV only so phone layout is byte-identical to before. Section header / row label are hardcoded English on this fork (not routed through `$strings`) to avoid touching `strings/en-us.json`; i18n migration deferred until after upstream PR acceptance.

**Verification**
- 26-item manual test checklist passed on Google TV Streamer 4K, including a non-default-color regression pass over all 8 I8 focus surfaces and a smoke run of the v1.0.5/v1.0.6 42-item TV checklist with the picker landed.

## Notes

- **`android.view.View` import** — During development we explored using Android's native `View.setFocusHighlightEnabled(false)` to suppress a green focus box that appeared on the author bio page. This turned out to be our own CSS (a missing `position: relative` on a card container), not a native Android highlight. The import was reverted.

- **App termination on TV** — On Android TV, the app calls `finishAndRemoveTask()` and `Process.killProcess()` in `onStop`. This is necessary because the WebView and JavaScript state go stale when the app is backgrounded on TV (there's no swipe-to-close gesture). Without this, resuming the app shows stale UI. This only runs on TV devices.

- **Podcast episode download** — On TV with D-pad navigation, it's easy to accidentally press Enter on a podcast episode's download button. Books have a confirmation dialog before downloading, but podcast episodes do not. Would a confirmation dialog for podcast episode downloads be a welcome addition?

## Test plan

- [ ] Install on Android TV device and verify app appears in leanback launcher
- [ ] Connect to server, navigate all main pages (Home, Library, Series, Collections, Playlists, Authors)
- [ ] Verify D-pad navigation moves focus with green ring visible on all focusable elements
- [ ] Open a book detail page, verify chapters/tracks/cover art are navigable
- [ ] Start playback, verify player opens fullscreen with all controls navigable
- [ ] Open author card, verify author detail page loads with bio and books
- [ ] Test Back button restores focus to previous position
- [ ] Test modals (libraries, chapters list) trap focus and restore on close
- [ ] Test side drawer opens/closes with proper focus management
- [ ] Verify all settings toggles, dropdowns, and info icons are navigable
- [ ] Install on phone/tablet and verify no behavior changes (no focus rings, no keyboard artifacts)
