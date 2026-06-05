# TV Focus System — Technical Reference

This document describes the D-pad focus management system for Android TV, implemented in the `plugins/tv/` module suite (split from the former monolithic `plugins/tv-navigation.js` in v1.0.10). It is intended for developers working on the TV navigation PR or extending TV support.

> **Module map:** the `plugins/tv/` suite is summarized in `CHANGELOG.md` under `[1.0.10]` (the v1.0.10 monolith split) and `[Unreleased]` (the v1.0.11 `selectors.js` addition + the I4 perf / I5 selector work). Function-to-module references below are current as of the v1.0.11 bundle.

---

## Architecture Overview

The focus system has three core layers, plus a slim dispatcher and a listener/watcher layer:

1. **Spatial Navigation** (`plugins/tv/spatialNav.js`) — `findVerticalTarget()` / `findHorizontalTarget()` pick the nearest focusable element in the pressed direction using bounding-rect geometry. Each finder snapshots every candidate's rect once per keypress into an ephemeral `Map` to avoid repeated forced reflows (see *Spatial nav rect caching* below).
2. **Fingerprint Restore** (`plugins/tv/focusMemory.js`) — `getElementFingerprint()` / `restoreFromFingerprint()` save and restore focus position across router navigations (Back button).
3. **Overlay Focus Trapping** (`plugins/tv/overlayFocus.js`) — `handleOverlayNavigation()` / `getActiveOverlay()` trap D-pad navigation inside modals and drawers, with a focus-history stack (`tvContext.focusHistory`) for open/close restore.

The Nuxt plugin entry + the slim `handleKeyDown` dispatcher live in `plugins/tv/index.js`; router hooks, player/overlay watchers, the focus-out recovery handler, and the eventBus subscribers are all registered by `registerAllTvListeners()` in `plugins/tv/listeners.js`. Shared singleton state lives in `plugins/tv/context.js` (the `tvContext` object).

All TV behavior is gated behind the `android-tv` CSS class on `<html>` (injected by `MainActivity.kt` at `WebViewClient.onPageStarted` via a Capacitor `WebViewListener`, with a `webView.post` backup and a ~5s JS-side poll in `plugins/tv/index.js` — the I2 race fix) or the `isAndroidTv` Vuex state.

### Spatial nav rect caching (I4, v1.0.11)

`getBoundingClientRect()` forces a synchronous layout. The two finders in `spatialNav.js` previously called it once per candidate *inside* their `filter` and `sort` comparators — O(n log n) forced reflows on every D-pad press, measurable input lag on low-power TV hardware (Chromecast with Google TV, Fire TV Stick). As of the v1.0.11 bundle, each finder snapshots every candidate's rect once into an ephemeral per-call `Map` and reads from the Map thereafter (one reflow per focusable; the Map is per-keypress, so no invalidation is needed). `restoreFromFingerprint()` in `focusMemory.js` applies the same idea — it hoists the invariant scroll-container rect out of its non-unique-ID position-match loop.

---

## Stable Selector Hooks (`data-*` contract, I5)

TV overlay/target detection uses dedicated `data-*` attributes rather than Tailwind utility-class selectors, so maintainer-side class renames don't silently break D-pad navigation. Resolved through `plugins/tv/selectors.js` (single source of truth):

| Hook | Set on | Read by |
|---|---|---|
| `data-tv-overlay="side-drawer"` | side-drawer panel in `components/app/SideDrawer.vue` (bound to `show` — present only while open) | `findVisibleSideDrawer()` → `getActiveOverlay()` (`overlayFocus.js`) + drawer-open watcher (`listeners.js`) |
| `data-tv-target="play-button"` | primary Play `<ui-btn>` on item / episode / playlist / collection detail pages | `findPlayButton()` → `focusFirstContentElement()` (`focusEntry.js`) + redirect handler (`listeners.js`) |

When adding a new overlay or primary action that TV nav must find, add the documented `data-*` attribute rather than relying on a styling class.

---

## Focus Ring Color (User-Customizable)

The focus ring color is exposed as the `--tv-focus-color` CSS custom property defined on `:root.android-tv` in `assets/css/tv-focus.css`. All eight focus-ring presentations (border overlays, modal left-accents, drawer accents, generic outlines, settings dropdowns, player controls, etc.) reference `var(--tv-focus-color)` so a single property write retints every surface.

The runtime override pipeline:

- `pages/settings.vue` renders a TV-only "TV Settings" section hosting `components/ui/TvFocusColorPicker.vue` (7 curated presets, default `#1ad691`).
- Selection dispatches `user/updateUserSettings` with `{ tvFocusColor }`, which persists via `$localStore.setUserSettings` and emits `user-settings` on `$eventBus`.
- The preset allowlist (`VALID_TV_FOCUS_HEXES`) and the `applyTvFocusColor(value, store)` helper live in `plugins/tv/focusColor.js`. The helper writes the chosen hex into `--tv-focus-color` on `<html>`; a stored value not in the allowlist self-heals back to the `#1ad691` default (it dispatches a corrective `user/updateUserSettings`).
- `registerEventBusSubscribers()` — one of the five registrations orchestrated by `registerAllTvListeners()` in `plugins/tv/listeners.js` — does an initial `applyTvFocusColor(store.state.user?.settings?.tvFocusColor, store)` (covering the case where `loadUserSettings` finishes before TV-nav init runs), then subscribes to the `user-settings` event and re-applies on every later change.

---

## Fingerprint Restore — Navigation Matrix

When the user navigates between pages, the system saves a "fingerprint" of the focused element (ID, structural path, scroll position, bounding rect) and restores it on Back navigation.

### Lookup Methods

| Method | Used For | How It Works |
|---|---|---|
| **ID selector** | Book, series, collection, playlist cards | `document.querySelector('#book-card-5')` — fast and reliable. Visibility check filters out virtualizer orphan nodes. |
| **Author index** | Author cards | nth `.author-card-wrapper` element — author cards don't have unique IDs. |
| **Structural path** | Buttons without IDs (ellipsis, play, etc.) | Walks up the DOM building tag+index pairs from the element to a parent with an ID. Fragile if DOM structure changes. |
| **Position fallback** | Last resort | Finds the nearest focusable element by bounding rect distance to the saved position. |

### Page Navigation Fingerprint Behavior

| Source Page | Action | Destination | Back Restores To | Lookup Method | Scroll Restore | Virtualizer |
|---|---|---|---|---|---|---|
| Home | Click book card | Book detail | Book card | ID (`#book-card-N`) | Yes | No (shelves) |
| Home | Click series card | Series detail | Series card | ID (`#series-card-N`) | Yes | No |
| Home | Click author card | Author detail | Author card | Author index | Yes | No |
| Library | Click book card | Book detail | Book card | ID (`#book-card-N`) | Yes | Yes |
| Series grid | Click series card | Series detail | Series card | ID (`#series-card-N`) | Yes | Yes |
| Collections grid | Click collection | Collection detail | Collection card | ID (`#collection-card-N`) | Yes | Yes |
| Playlists grid | Click playlist | Playlist detail | Playlist card | ID (`#playlist-card-N`) | Yes | Yes |
| Authors grid | Click author | Author detail | Author card | Author index | Yes | No |
| Author detail | Click book card | Book detail | Author book card | ID (`#author-book-N`) | Yes | No |
| Playlist detail | Ellipsis → History | History page | Ellipsis button | Structural path | Yes | No |
| Item detail | Ellipsis → History | History page | Ellipsis button | Structural path | Yes | No |
| Episode detail | Ellipsis → History | History page | Ellipsis button | Structural path | Yes | No |
| Podcast page | Click episode title | Episode detail | Episode row element | Structural path / ID | Yes | No |

### Back Arrow vs Remote Back Button

Both the on-screen back arrow (Appbar) and the remote's hardware back button call `window.history.back()`. However, the `beforeEach` hook uses `document.activeElement` to determine the navigation context:

- **Remote back button**: Focus is on a content card when it fires (Capacitor listener, not a DOM element). The card passes the `isContentElement` check, and the fingerprint is preserved.
- **On-screen back arrow**: Focus is on the back arrow itself, which is inside `#appbar`. Without special handling, this would be classified as `isExplicitNavElement` (forward navigation from a nav link), causing the destination's saved fingerprint to be **deleted**.

The back arrow is excluded from the `isExplicitNavElement` check via `aria-label="Back"`, ensuring it behaves identically to the remote back button for fingerprint restoration.

### Scroll Restore Timing

Scroll position is restored **synchronously before each element lookup attempt** via `ensureScroll()`. This ensures the target element is in the viewport when `isVisible()` checks it. Previous approach (parallel timers for scroll and lookup) caused a regression where off-screen elements were rejected before scroll completed.

```
ensureScroll() → element lookup → isVisible() check → focus or retry
```

The function retries up to 12 times (250ms fast-poll, then 500ms slow-poll) to handle slow-loading content and virtualizer re-renders.

### Virtualizer Considerations

Grid pages (Library, Series, Collections, Playlists) use a virtualizer that only renders cards near the current scroll position. On fingerprint restore:

1. `ensureScroll()` sets scroll position to where the card was
2. The virtualizer begins rendering cards at that position
3. First lookup attempt may fail (card not rendered yet)
4. Retry at 250ms succeeds after virtualizer renders

During rapid vertical scrolling, the virtualizer `el.remove()`s the currently focused card, which drops focus to `<body>` — and the **native Android-TV focus engine then re-homes focus to an edge column** (deterministically the last). This slips past the JS recovery: the focus-out handler only acts when focus falls to `<body>`, but the engine parks focus on a real, wrong-column card. Reading the column from live `activeElement` therefore drifts to that edge column. Column stability is maintained structurally instead (next section); `lastFocusRect` now only feeds the legacy geometric `findVerticalTarget` fallback.

### Column stability during fast scroll (native focus engine)

Shelf-grid cards encode their position in their id (`book-card-42` → absolute index 42; column = `index % itemsPerRow`), so vertical nav does not trust live geometry:

- **Intended column** (`tvContext.gridIntendedCol`) — the user's chosen column, set **only** on Left/Right + first focus, never from live focus, so a native hijack can't corrupt it. `gridItemsPerRow` is read off the in-view shelf's card count (monotonic max).
- **Vertical nav** (`findShelfVerticalTarget` in `spatialNav.js`, tried before the geometric finder in `gridNav.js`) takes the **row** from where focus actually is but re-asserts the **intended column**: target = `(row ± 1) × itemsPerRow + intendedCol`, focused by id — so a hijack to the last column is undone on the next keypress.
- **Resting correction** — a `focusin` watcher in `listeners.js` re-asserts the intended column every 100 ms for ~2 s after a hijack, out-persisting the engine. Each tick no-ops once focus is already correct, only runs at rest (the keydown re-assert owns the column mid-scroll), and is time-boxed by `tvContext.lastVerticalNavAt` so it never overrides a deliberate first-card reset.
- The geometric `findVerticalTarget` fallback (grid-exit ArrowUp → toolbar, non-shelf Authors flex-grid) **must not write `gridIntendedCol`** — doing so poisoned the intended column with the engine's hijacked column, which was the original bug.

All grid state (`gridIntendedCol`, `lastGridIndex`, `lastGridPrefix`, `gridItemsPerRow`, `lastVerticalNavAt`, `gridCorrectionInterval`/`Until`) lives on `tvContext` and resets on route change.

---

## Overlay Focus Management

### Focus History Stack

When a modal or drawer opens, the currently focused element is pushed to `focusHistory[]`. On close, the element is popped and re-focused. Supports nested overlays.

| Trigger | Overlay Type | Open Behavior | Close Behavior |
|---|---|---|---|
| Book/episode ellipsis (⋮) | More menu modal | Save focus, focus first modal option | Pop focus history, restore to ellipsis |
| Library selector | Library list modal | Save focus, focus first library item | Pop focus history, restore to selector |
| Hamburger menu (☰) | Side drawer (Vuex) | Save focus, focus first drawer item | Pop focus history, restore to hamburger |
| Sleep timer / Chapters | Modal | Save focus, focus first option | Pop focus history, restore to player button |
| More Info (from ellipsis) | Modal → Modal transition | Debounced — keeps original focus saved | Pop returns to original ellipsis, not intermediate modal |

### Modal Transitions

When one modal closes and another opens in quick succession (e.g., ellipsis → More Info), a debounce timer prevents the focus history from being corrupted. The original pre-modal element is preserved.

---

## Main → Main Navigation

When switching between top-level sections via the nav bar (e.g., Library → Authors):

1. Delete fingerprints for both source and destination pages
2. Set `pendingScrollToTop = true`
3. Clear `lastBookshelfScrollData` in Vuex (prevents LazyBookshelf from restoring old scroll position)
4. In `afterEach`: scroll to top (twice, at 200ms and 800ms, to beat LazyBookshelf's restore race)
5. `refocusAfterContentChange()` polls for the first card and focuses it

---

## Server/User Screen Focus (TV-only)

The server connection screen (`pages/connect.vue` + `components/connection/ServerConnectForm.vue`) has TV-specific focus management:

| Transition | Focus Target | Mechanism |
|---|---|---|
| Open server list (initial) | First server entry row | `$nextTick` → querySelector for first `.border-b` row |
| Add New Server | Server address `<input>` | `$nextTick` → `$refs.serverAddressInput.focus()` |
| Submit server address | Username `<input>` | `$nextTick` → `$refs.usernameInput.focus()` |
| Edit server address (pencil) | Server address `<input>` | `$nextTick` → `$refs.serverAddressInput.focus()` |
| Back to server list | First server entry row | `$nextTick` → querySelector |
| Delete server entry | Entry above deleted (or top) | `$nextTick` → querySelector by index |

### TV Layout Adjustments

- Server list box uses column flow layout (`flex-col`) instead of vertical centering — prevents the box from overlapping the logo when entries grow
- Logo section is in-flow (not absolute positioned) on TV
- Outer container has `overflow-y-auto` for scrolling when entries exceed viewport
- Username label (`config.username`) displayed above server URL inside the focusable entry row, matching URL styling (`text-base text-fg`)
- Focus box has 6px breathing room on left/right (`-mx-1.5 px-1.5`) to prevent text cutoff at focus ring edges
- GitHub links container uses normal document flow on TV (not `fixed`) so D-pad can scroll to them

### Server Entry D-pad Navigation

The Android TV WebView's native D-pad spatial navigation has several limitations inside nested focusable containers:
- **Left cannot navigate from child to parent** — pressing left on the first icon cannot find the parent entry row
- **Vertical navigation targets sibling icons** — pressing up/down from an icon focuses the same icon on the adjacent row instead of the entry row
- **Native engine overrides programmatic focus** — `.focus()` calls in `@keydown` handlers execute before the native engine, which then overrides the result
- **Event bubbling causes double-scheduling** — keydown events on icons bubble to the parent entry row, triggering competing deferred focus calls

These are solved with a layered approach:

| Mechanism | Purpose |
|---|---|
| `dpadFocus(el)` | Defers `.focus({ preventScroll: true })` via `setTimeout(0)` to run after native engine; saves/restores scroll position to prevent oscillation |
| `handleIconFocus(@focus)` | Intercepts cross-row focus on icons (up/down D-pad) and redirects to the entry row; skipped when `_dpadFocusActive` flag is set |
| `@keydown.*.prevent.stop` on icons | Explicit left/right handlers with `.stop` to prevent event bubbling to entry row |
| `lockScroll()` | Prevents scroll oscillation on dead-end key presses (e.g., right from delete) |
| `onEntryRowFocus(index)` | Deferred scroll-to-top when first entry receives focus, ensuring back arrow stays visible |

**D-pad navigation map for server entries:**

```
                    ┌─────────────────────────────────────────┐
                    │  Entry Row (tabindex=0)                 │
  ←(stay on row)    │  [Username]        [⋮ edit] [🗑 delete] │
                    └───────┬──────────────┬──────────┬───────┘
                            │   right→     │  right→  │
                            ├──────────────►──────────►
                            │   ←left      │  ←left   │
                            ◄──────────────◄──────────┘
                            │
                     ↑↓ (adjacent entry row, never icon-to-icon)
```

---

## Logged-Out State Handling

When the user is logged out and lands on a page with no content (e.g., navigating away from the server connect screen via Back), the focus system has no cards, buttons, or content elements to focus. This triggers a special recovery flow:

1. `focusFirstContentElement()` exhausts all lookup options (cards, play button, content area fallback)
2. Detects logged-out state via `_store.state.user.user === null`
3. Clears all stale focus state (`pageFocusMemory`, `focusHistory`, `lastFocusRect`)
4. Shows a toast notification: "No user is logged in. Returning to Home to connect." (5 seconds)
5. Navigates to Home (`/`) using `router.replace` (not `push`) to avoid growing the history stack
6. Home page renders with the maintainer's Connect button, which receives focus

### Back Button Exit (Android TV only)

The Capacitor `backButton` handler in `init.client.js` has a special case: if on Android TV, logged out, and on the Home page, pressing Back shows the exit confirmation dialog regardless of remaining history entries. Without this, stale history entries from the pre-logout session would prevent the user from exiting — Back would cycle through old pages that all redirect back to Home.

```
backButton handler:
  if (isTV && isLoggedOut && isHome) → show exit dialog
  else if (!canGoBack) → show exit dialog
  else → window.history.back()
```

---

## isVisible() Function

`isVisible(el)` is used throughout the focus system to filter focusable elements. It checks:

1. `display !== 'none'`, `visibility !== 'hidden'`, `opacity !== '0'`
2. Bounding rect width and height > 0
3. Element is within the viewport bounds (not off-screen)

The viewport check (step 3) is critical for preventing the side drawer's Disconnect button (translated off-screen via CSS transform) from being included in D-pad navigation targets. This check must NOT be weakened — instead, fix timing issues that cause legitimate elements to be temporarily off-screen (see Scroll Restore Timing above).

---

## Key Design Decisions

| Decision | Rationale |
|---|---|
| All TV code gated behind `android-tv` class | Zero impact on phone/tablet builds |
| Spatial "beam model" navigation | Horizontal stays in row, vertical finds nearest row — feels natural on grid layouts |
| Focus memory uses fingerprints, not element references | Elements are destroyed and recreated on page navigation; fingerprints survive re-renders |
| Structural column re-assert during fast scroll | The native focus engine hijacks focus to an edge column when the virtualizer unmounts the focused card; tracking the intended column separately and re-asserting it (keydown + a `focusin` watcher) beats the engine where live-geometry reads cannot |
| `ensureScroll` before lookup (not parallel) | Prevents timing race where elements are rejected as off-screen before scroll applies |
| Strict `isVisible` with viewport check | Prevents translated/off-screen drawer elements from stealing focus |
| `verticalNavInProgress` guard | Prevents `focusout` recovery from fighting virtualizer card re-attachment during scroll |
| Player isolated from page navigation | Player controls have their own D-pad handler; `findVerticalTarget` excludes player elements |
