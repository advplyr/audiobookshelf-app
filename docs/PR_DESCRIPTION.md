# Android TV Support — Upstream PR Descriptions (9-PR series)

**Supersedes the single ~7,000-LOC PR #1843.** This document holds the ready-to-use
description for each PR in the 9-PR series that replaces #1843. It is the detailed
companion to [`PR_DECOMPOSITION_PLAN.md`](PR_DECOMPOSITION_PLAN.md) — the plan is the
high-level map (titles, waves, dependencies, LOC budget); this file is the per-PR copy.

- **Release history** for the fork (v1.0.0 → v1.0.11) lives in [`CHANGELOG.md`](../CHANGELOG.md), not here.
- **Module-by-module line ranges** for the engine are in the design spec:
  [`docs/superpowers/specs/2026-05-18-pr-decomposition-and-fork-modularization-design.md`](superpowers/specs/2026-05-18-pr-decomposition-and-fork-modularization-design.md).
- **LOC counts** quoted in `PR_DECOMPOSITION_PLAN.md` predate the v1.0.11 bundle. The
  v1.0.11 work (I2 init hardening, I4 spatial-nav perf, I5 stable selectors, loading-dot
  color) is folded into PRs 3 / 4 / 5 / 7 below, so those PRs run slightly larger than the
  plan's table until it is re-validated post-v1.0.11 (fork TODO item 15).

All TV behavior is gated behind device detection (`android-tv` CSS class on `<html>`, or the
`isAndroidTv` Vuex state). Phone / tablet / iOS behavior is unchanged — the additive
`tabindex` / `keydown.enter` attributes are inert on touch devices.

---

# Wave 1 — Foundation (parallel, no inter-dependencies)

PRs 1, 2, and 3 are independent and submit simultaneously off `upstream/master`. After
Wave 1, phone/tablet behavior is byte-identical to upstream — the foundation *enables* TV
but activates nothing visible until the Wave 2 engine lands.

## PR 1 — Foundation + TV detection

**Scope:** Leanback launcher integration, device detection, and the `android-tv` class
injection that scopes every later TV change. No navigation behavior yet.

| File | Change |
|------|--------|
| `android/.../AndroidManifest.xml` | Leanback launcher intent filter; `uses-feature` declarations (`touchscreen` + `leanback` both `required="false"`); banner attribute |
| `android/.../MainActivity.kt` | Injects the `android-tv` class on `<html>` at `WebViewClient.onPageStarted` (via a Capacitor `WebViewListener` registered on the bridge) so it lands before page-script execution, with a `webView.post {}` injection retained as an idempotent backup. On TV: `finishAndRemoveTask()` + `Process.killProcess()` in `onStop` to fully terminate on exit (no swipe-to-close on TV; prevents stale WebView state on resume). |
| `android/.../DeviceManager.kt` | `isAndroidTV()` detection |
| `android/.../AbsDatabase.kt` | Exposes the `isAndroidTv` flag to the WebView |
| `android/.../CastOptionsProvider.kt` | Guard against missing Google Play Services (some TV devices) |
| `android/.../tv_banner.png` | 320×180 leanback launcher banner |
| `store/index.js` | `isAndroidTv` state + mutation (also `resetLastBookshelfScrollData` mutation used by the engine) |
| `layouts/default.vue` | Sets `isAndroidTv` from device data on mount |
| `plugins/capacitor/AbsDatabase.js` | Reads `isAndroidTv` from device info |
| `pages/connect.vue` | Sets `isAndroidTv` on mount — the connect page bypasses `layouts/default.vue`, so first launch with no saved server was rendering a phone layout on TV |

**Folded-in fork work:** the `onPageStarted` injection point is the **Kotlin half of the I2
CSS-injection-race fix (v1.0.11)** — replacing the original `webView.post {}` that raced
Nuxt plugin execution on slower devices. The JS-side poll half lives in PR 5 (`index.js`).

**Testing:** install on Android TV, confirm the app appears in the leanback launcher and the
`android-tv` class is present on `<html>` at first paint; confirm clean exit (no stale UI on
relaunch); confirm phone/tablet layout unchanged.

## PR 2 — Keyboard hygiene (tabindex + `keydown.enter.prevent`)

**Scope:** Additive focus/activation attributes on existing shared components so TV remotes
(which fire keyboard events) can focus and activate them. Inert on touch devices.

| File | Change |
|------|--------|
| `AudioPlayer.vue` | `.prevent` on all `keydown.enter` handlers (fixes D-pad Enter double-fire); `tabindex` + `keydown.enter.prevent` on more_vert, bookmark, playback speed, sleep timer, chapters, collapse |
| `ChaptersTable.vue` | Timestamp text wrapped in `<span tabindex="0">`; `tabindex` + `keydown.enter.prevent` on expand bar |
| `TracksTable.vue` | `tabindex` + `keydown.enter.prevent` on expand bar |
| `EpisodeRow.vue` / `LatestEpisodeRow.vue` | `tabindex` + `keydown.enter.prevent` on row, play, playlist, download |
| `EpisodesTable.vue` | `tabindex` + `keydown.enter.prevent` on sort control |
| `ToggleSwitch.vue` | `tabindex` + `keydown.enter.prevent` on toggle div (app-wide; no touch effect) |
| `TextInput.vue` | Readonly inputs get `tabindex="-1"` to prevent focus stealing |
| `SideDrawer.vue` | Disconnect button `tabindex` + `keydown.enter.prevent`; removed conflicting local keydown handlers |
| `Appbar.vue` | `tabindex="-1"` on logo; `keydown.enter` on back button |
| `BookshelfToolbar.vue` | `tabindex` + `keydown.enter` on filter/sort/view icons |
| `LibrariesModal.vue` | `tabindex` + `keydown.enter` on list items; auto-focus first item on open |
| `LazyBookCard.vue` / `LazySeriesCard.vue` / `LazyCollectionCard.vue` / `LazyPlaylistCard.vue` | `tabindex` + `keydown.enter` / `clickCard` |
| `pages/item/_id/index.vue` | Cover art `id` + `tabindex` + `keydown.enter`; Read More toggle `tabindex` + `keydown.enter.prevent` |
| `pages/item/_id/_episode/index.vue` | `id="episode-page"` for scroll-container recognition |
| `pages/settings.vue` | 11 dropdown wrappers `tabindex` + `keydown.enter.prevent` + `settings-dropdown`; 8 info icons; `id="settings-page"` |
| `pages/account.vue` / `stats.vue` / `logs.vue` | Scroll-container `id`s (`account-page`, `stats-page`, `logs-container`) + `overflow-y-auto` where needed |
| `pages/localMedia/item/_id.vue` | `id="manage-files-page"`; ellipsis/play `tabindex` + `keydown.enter.prevent` |

**Hardening:** the `.prevent` modifier across 11 `@keydown.enter` handlers prevents the
Android-TV double-fire where D-pad Enter emits both `keydown` and a synthesized `click`.

**Folded-in fork work (I5, v1.0.11):** `SideDrawer.vue` gains `data-tv-overlay="side-drawer"`
on its panel (bound to the open state) and the item/episode/playlist/collection detail pages
gain `data-tv-target="play-button"` on the primary Play button. These stable hooks are *read*
by `selectors.js` (PR 4) — see the I5 note there. localMedia "Save Order" is deliberately
**not** tagged (removes a latent mis-focus).

**Testing:** on touch devices confirm no layout/tap changes; on TV confirm every listed
element can receive D-pad focus and activates once (no double-fire).

## PR 3 — CSS foundation (`tv-focus.css` + `--tv-focus-color`)

**Scope:** All focus-ring presentation, scoped to `.android-tv`, driven by a single CSS
custom property.

| File | Change |
|------|--------|
| `assets/css/tv-focus.css` | All focus-ring styles scoped to `.android-tv`. The focus color is a single `--tv-focus-color` custom property on `:root.android-tv` (default `#1ad691`); all eight focus-ring surfaces (card borders, modal left-accents, drawer accents, generic outlines, settings dropdowns, player controls, etc.) reference `var(--tv-focus-color)`, so one property write retints everything. Removed the blanket `:not(.android-tv) *:focus { outline: none }` rule so phone/tablet keyboard + a11y users keep focus indicators. |
| `components/ui/LoadingIndicator.vue` | A `tv-focus-dots` hook class on the loading-dots wrapper |
| `nuxt.config.js` | Registers `tv-focus.css` (the engine plugin registration is PR 5) |

**Folded-in fork work (loading dots, v1.0.11):** a single TV-gated rule —
`.android-tv .tv-focus-dots > div { background-color: var(--tv-focus-color) !important; }` —
makes the content-loading overlay dots a ninth `--tv-focus-color` surface, so they follow the
user's chosen focus color live (zero JS). Phone/tablet keep green. The circular `la-ball`
spinner stays white for contrast (deliberate).

**Testing:** on TV confirm the green focus ring renders on cards/controls/modals and that
changing `--tv-focus-color` retints every surface including the loading dots; on phone confirm
focus indicators are present for keyboard/a11y and the dots stay green.

---

# Wave 2 — Engine (sequential)

## PR 4 — Engine kit (utility modules + page handlers)

**Scope:** The TV focus engine as a library — modules that export functions but attach to
nothing at runtime until PR 5 wires them. All under `plugins/tv/`. Stacked dependency: opens
after PR 1 merges. Full architecture overview: [`TV_FOCUS_SYSTEM.md`](TV_FOCUS_SYSTEM.md).

| Module | Responsibility |
|--------|----------------|
| `plugins/tv/context.js` | The `tvContext` singleton (shared mutable state: `pageFocusMemory`, `focusHistory`, `lastFocusRect`, `fingerprintRestoreActive`, `verticalNavInProgress`, the `cssEscape` polyfill, etc.) |
| `plugins/tv/visibility.js` | `isVisible` (display/visibility/opacity + non-zero rect + viewport bounds), `getAllFocusable(root, { ignoreViewport })`, `centerOf`, `isSameRow` |
| `plugins/tv/scrollHelpers.js` | `findPageScrollContainer`, `scrollParentToReveal` |
| `plugins/tv/focusColor.js` | `VALID_TV_FOCUS_HEXES` (7 presets) + `DEFAULT_TV_FOCUS_HEX` + `applyTvFocusColor(value, store)` — writes `--tv-focus-color`; an out-of-allowlist value self-heals to the default via a corrective `user/updateUserSettings` |
| `plugins/tv/focusMemory.js` | `getElementFingerprint` + `restoreFromFingerprint` — 5-tier lookup (unique ID → author index → non-unique-ID position → structural path → position fallback), 12-attempt retry (250 ms fast-poll → 500 ms) with scroll restored synchronously before each attempt |
| `plugins/tv/spatialNav.js` | `findVerticalTarget` / `findHorizontalTarget` — beam model (horizontal stays in row, vertical finds nearest row); `lastFocusRect` holds the column when the virtualizer detaches the focused card |
| `plugins/tv/overlayFocus.js` | `saveFocusBeforeOverlay`, `restoreFocusAfterOverlay`, `getActiveOverlay`, `handleOverlayNavigation` — traps D-pad inside modals/drawers; uses `{ ignoreViewport: true }` so long scrolled lists (e.g. the 13-option sort modal) wrap across the full logical list |
| `plugins/tv/focusEntry.js` | `focusFirstContentElement`, `focusAfterPlayerClose`, `refocusAfterContentChange`; logged-out recovery (clear stale state → toast → `router.replace('/')`) |
| `plugins/tv/selectors.js` | `findPlayButton` / `findVisibleSideDrawer` — the single read-point for the `data-*` hook contract |
| `plugins/tv/pageHandlers/*.js` (8) | Per-context key handling: `playerNav`, `episodeRow`, `logsContainer`, `navBarEscape`, `statsPage`, `itemPage`, `authorPage`, `gridNav` |

**Folded-in fork work:**

- **I4 spatial-nav perf (v1.0.11):** `findVerticalTarget` / `findHorizontalTarget` snapshot every
  candidate's `getBoundingClientRect()` once per keypress into an ephemeral `Map`, eliminating the
  repeated forced reflows previously incurred inside the filter + `sort` comparators (O(n log n)
  layout flushes per D-pad press). `restoreFromFingerprint` hoists the invariant scroll-container
  rect out of its non-unique-ID position-match loop.
- **I5 stable selectors (v1.0.11):** `selectors.js` is the new single source of truth for overlay /
  target detection, replacing fragile Tailwind utility-class selectors. It resolves
  `data-tv-overlay="side-drawer"` and `data-tv-target="play-button"`; the attributes themselves are
  added to the owning components in PR 2. Contract documented in `TV_FOCUS_SYSTEM.md`.

**Hardening:** `CSS.escape()` polyfill (via `tvContext.cssEscape`) for older ATV WebViews
(Chrome < 46); `try/finally` around navigation `setTimeout` callbacks so a mid-restore
exception always clears the nav guard; all engine work is `android-tv`-gated.

**Testing:** ships dormant (no runtime attachment); verify ESLint clean and `npm run generate`
succeeds. Behavior is exercised once PR 5 wires it.

## PR 5 — Engine integration (listeners + dispatcher)

**Scope:** Activates the engine — registers the global keydown dispatcher and wires
router / store / eventBus hooks. Stacked on PR 4.

| File | Change |
|------|--------|
| `plugins/tv/index.js` | Nuxt client plugin entry. `checkAndInit` gates init on the `android-tv` class, with a ~5 s JS-side poll fallback (the **JS half of the I2 race fix**) before giving up. Slim `handleKeyDown` dispatcher routes each press to the right page handler, the spatial finders, or overlay navigation. |
| `plugins/tv/listeners.js` | `registerAllTvListeners(store)` orchestrates five sub-registrations: `registerRouterHooks` (beforeEach fingerprint save / afterEach restore + main→main scroll-to-top), `registerPlayerWatchers` (auto-fullscreen on playback start; `focusAfterPlayerClose`; synchronous fingerprint save on `playerStartingPlaybackMediaId`), `registerFocusOutHandler` (DOM-removal focus recovery; stats-page Next-button retry), `registerOverlayWatchers` (side-drawer + debounced modal open/close), `registerEventBusSubscribers` (`library-changed`, `bookshelf-total-entities`, and the `user-settings` → `applyTvFocusColor` focus-color subscriber). |
| `nuxt.config.js` | Registers `@/plugins/tv/index.js` (client mode) — updated from the pre-v1.0.10 `@/plugins/tv-navigation.js` |

**Folded-in fork work:** the **I2 JS-side poll** (`index.js`) pairs with PR 1's Kotlin
`onPageStarted` injection — together they make the CSS-injection race structurally impossible on
the happy path and self-healing on edge cases (e.g. mid-session reload).

**Hardening:** `focusLossTimer` cleared on `router.beforeEach` so a stale `focusout` recovery
timer can't fire during a transition; `refocusAfterContentChange` clears its prior interval
before starting a new one (no duelling focus pollers); the `resetLastBookshelfScrollData`
mutation keeps scroll-restore invalidation reactive.

**Testing:** full D-pad navigation across Home/Library/Series/Collections/Playlists/Authors;
Back restores focus; modals trap + restore; side drawer focus management; the focus-color
picker live-updates every surface.

---

# Wave 3 — Features (parallel, on top of the live engine)

## PR 6 — Audio player TV behavior

**Scope:** `components/app/AudioPlayer.vue` — TV-gated player behavior. Depends on PR 1 + PR 5.

- **Auto-fullscreen** on playback start (full D-pad nav across the three control rows).
- **Close-vs-minimize:** on TV the player closes entirely on any fullscreen exit (no mini-player on TV).
- **Screensaver prevention (v1.0.6):** `updateKeepAwake(shouldKeepAwake)` via
  `@capacitor-community/keep-awake` (already in the tree via the e-reader — no new dependency),
  wired into `onPlayingUpdate` (every play↔pause) and `endPlayback` (all teardown). Works around
  CCwGTV/ATV Ambient-Mode firmware killing audio after ~10 min. Try/catch so plugin errors never
  disrupt playback; wake lock released on pause.
- **History gate (v1.0.7):** History is hidden from the fullscreen ellipsis on TV (it collapsed
  the player into a retired mini-player state); still reachable from book detail pages.

**Hardening:** hidden (`v-show`) fullscreen controls use a dynamic `tabindex` so collapsed
jump-chapter buttons don't take D-pad focus.

**Testing:** start/pause/close playback on TV; confirm audio survives idle (screensaver) while
playing and the screensaver engages normally while paused; phone/tablet unchanged.

## PR 7 — Settings + focus-color picker

**Scope:** The TV-only user control for the focus-ring color (v1.0.8). Depends on PR 3 (the
`--tv-focus-color` variable) + PR 5 (the `user-settings` subscriber).

| File | Change |
|------|--------|
| `components/ui/TvFocusColorPicker.vue` (new) | Horizontal swatch row of 7 presets (ABS Green default · Sky · Amber · Red · Violet · Yellow · White). The in-use swatch carries a `★` glyph (white with a black halo so it reads on every fill); D-pad focus draws a black inner edge + outer band in `var(--tv-focus-color)` via box-shadow (readable even when the focused swatch matches the ring color; no layout shift). TV-agnostic — the parent gates on `isAndroidTv`. |
| `pages/settings.vue` | A `v-if="isAndroidTv"` "TV Settings" section at the top hosts the picker; adds `isAndroidTv` + `tvFocusColor` computeds and a `setTvFocusColor` method dispatching `updateUserSettings`. The `mt-10` spacer is TV-gated so phone layout is byte-identical. Labels are hardcoded English on the fork (not routed through `$strings`) — i18n deferred until after upstream acceptance. |
| `store/user.js` | `tvFocusColor: '#1ad691'` on the initial `state.settings`; rides the existing `updateUserSettings` / `loadUserSettings` / `$localStore.setUserSettings` pipeline. Device-wide (single `userSettings` Capacitor Preferences key), matching every other UI preference. |

The allowlist + apply logic live in `plugins/tv/focusColor.js` (PR 4); the `user-settings`
subscriber that re-applies on change lives in `plugins/tv/listeners.js` (PR 5).

**Testing:** pick each preset on TV and confirm every focus surface (including loading dots)
retints live and persists across restart; confirm phone/tablet show no TV Settings section.

## PR 8 — Server connect form D-pad navigation

**Scope:** `components/connection/ServerConnectForm.vue` (+ `pages/connect.vue` TV layout) —
D-pad navigation for the server list / add-server flow (v1.0.3 overhaul). Depends on PR 2 + PR 5.

- Component-local TV focus helpers: `dpadFocus(el)` (defers `.focus({ preventScroll: true })`
  via `setTimeout(0)` to run after the native engine; saves/restores scroll to prevent
  oscillation), `handleIconFocus` (redirects cross-row icon focus to the entry row),
  `onEntryRowFocus` (deferred scroll-to-top so the back arrow stays visible), `lockScroll`
  (prevents oscillation on dead-end presses), plus `@keydown.*.prevent.stop` on icons to stop
  bubbling.
- TV layout: column-flow server list, in-flow logo, `overflow-y-auto`, focus breathing room,
  username shown above URL in the focusable row.

**Testing:** add/edit/delete/select servers via D-pad; confirm focus lands correctly on each
transition and the list scrolls without oscillation; phone/tablet unchanged.

## PR 9 — Author detail page

**Scope:** `pages/author/_id.vue` (new) + related Vue files. Depends on PR 2.

- New author detail page (bio, image, book grid) — TV navigation target from `AuthorCard`.
- `AuthorCard.vue`: `tabindex` + click/enter; on TV navigates to `/author/{id}`.
- `AuthorImage.vue`: `tabindex="-1"` to avoid double-focus with the parent card.
- `pages/bookshelf/authors.vue`: responsive column count for TV screen widths.

**Maintainer note:** the page itself is arguably **not TV-specific** — mobile users could
benefit too. Flagged for the maintainer's discretion (keep as TV-target-only, or generalize).

**Testing:** open an author from a card; confirm bio scroll → book-grid focus transfer, Up
reaches the nav bar at the top, and Enter opens a book.

---

# Notes

- **App termination on TV (PR 1):** `finishAndRemoveTask()` + `Process.killProcess()` in `onStop`
  is necessary because the WebView/JS state goes stale when backgrounded on TV (no swipe-to-close).
  Runs only on TV.
- **Podcast episode download (PR 2 / PR 9):** on TV it's easy to D-pad-Enter a podcast episode's
  download button. Books have a download confirmation dialog; podcast episodes do not — a
  confirmation dialog would be a welcome addition (open question for the maintainer).
- **`android.view.View` import:** an early experiment with native `View.setFocusHighlightEnabled(false)`
  was reverted — the stray green box was our own CSS (a missing `position: relative`), not a native
  highlight.
