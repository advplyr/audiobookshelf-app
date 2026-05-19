# Android TV PR Decomposition and Fork Modularization — Design Spec

**Date:** 2026-05-18
**Author:** Jamie Chapman (jchapz30@gmail.com) with Claude
**Status:** Design approved; awaiting execution authorization
**Supersedes:** ad-hoc plan to keep `tv-navigation.js` monolithic on upstream PR #1843

---

## 1. Context & problem

The fork's Android TV support is currently delivered to the upstream maintainer as PR #1843 — a single ~7,000-line submission covering v1.0.0 through v1.0.9 of the fork's Android TV work. The maintainer signaled overwhelm at the volume.

Diagnosis: the 7,000-line perception breaks down as:

| Category | LOC | Notes |
|---|---|---|
| `plugins/tv-navigation.js` (the engine) | 1,675 | Single 1,675-line plugin file |
| Inline `tabindex` + `keydown.enter.prevent` additions across 25+ shared Vue components | ~390 | Already passive on phones |
| `assets/css/tv-focus.css` (focus ring system) | 127 | Scoped under `.android-tv` |
| `pages/author/_id.vue` (new author detail page) | 99 | Arguably non-TV-specific |
| `components/ui/TvFocusColorPicker.vue` (v1.0.8) | 84 | Used on TV but TV-agnostic component |
| Native Kotlin/Android (`MainActivity`, `DeviceManager`, `AbsDatabase`, `CastOptionsProvider`, manifest, `build.gradle`) | ~95 | |
| Build config + Vuex/store + plugin glue | ~60 | |
| **— upstreamable code subtotal —** | **~2,530** | |
| Internal `docs/superpowers/plans/*` + `specs/*` (dev scaffolding) | 2,632 | Not appropriate for upstream |
| User-facing docs (`PR_DESCRIPTION.md`, `TV_FOCUS_SYSTEM.md`, `TV_USER_GUIDE.md`) | 756 | Stays on fork |
| `docs/TV_USER_GUIDE.pdf` | 12.7 MB binary | Stays on fork |
| 9 PNG screenshots | ~9 MB binary | Stays on fork |
| `CHANGELOG.md` (fork format, not upstream's convention) | 83 | Stays on fork |
| `.gitattributes` (LF normalization) | 47 | Optional separate PR |
| **— non-code / fork-only subtotal —** | **~3,518 + 22 MB binaries** | |

**Two distinct problems compounded into the "7k overwhelm" perception:**

1. **Non-code content was bundled with the PR.** ~3,500 LOC of docs and ~22 MB of binaries are appropriate fork artifacts but inappropriate upstream PR contents.
2. **The actual code change is one mega-PR** — even at 2,530 LOC, presenting it as a single review unit is a large ask of any maintainer.

The architectural choice — TV code in dedicated files (`plugins/tv-navigation.js`, `assets/css/tv-focus.css`) with inline gating in shared components — was deliberate and remains correct. A separate analysis (see Section 9) confirms inlining the engine into shared files would save only ~50-100 LOC while introducing real downsides (file fragmentation, harder cross-cutting evolution, more files-touched-per-PR).

The decomposition strategy therefore addresses the two real problems and explicitly preserves the architecture.

---

## 2. Design overview

Three coordinated changes:

1. **Strip non-code from upstream contributions.** Docs, plans, specs, PDF, screenshots, fork CHANGELOG stay on the fork. Each upstream PR contributes one slim `docs/pr-NN-<short-name>.md` file linking to fork-hosted context. Estimated reduction: ~3,500 LOC + 22 MB binaries.

2. **Refactor the fork to a modular engine structure before upstream submission.** Land v1.0.10 = behavior-preserving split of `plugins/tv-navigation.js` (1,675 LOC monolith) into 17 focused modules under `plugins/tv/`. Single source of truth; future fork work + upstream PRs both consume the same structure.

3. **Split the upstream contribution into a 9-PR series across 3 waves.** Each PR averages ~290 LOC; largest is the engine kit (PR4) at ~1,560 LOC split across 15 files. The engine integration PR (PR5) stacks on PR4. All other PRs are independent within their wave.

The combination takes the maintainer-facing review unit from ~7,000 LOC × 1 PR to ~2,530 LOC × 9 PRs, with the largest single PR being ~1,560 LOC split across 15 small files.

---

## 3. Modularization plan for `plugins/tv-navigation.js` → `plugins/tv/`

### 3.1 Shared state strategy

The current engine uses module-level mutable state (`focusHistory`, `verticalNavInProgress`, `lastFocusRect`, `_store`, `pageFocusMemory`, `cssEscape`, closure-scoped timers). Splitting across multiple files cannot rely on ESM live bindings (read-only across module boundaries) for mutable state.

**Chosen pattern: singleton context object.**

```js
// plugins/tv/context.js  (~30 LOC)
export const tvContext = {
  store: null,                    // Vuex store ref, set by index.js init
  focusHistory: [],
  verticalNavInProgress: false,
  lastFocusRect: null,
  pageFocusMemory: {},
  cssEscape: typeof CSS !== 'undefined' && CSS.escape
    ? CSS.escape
    : (s) => s.replace(/([^\w-])/g, '\\$1')
}
```

Every other module imports `tvContext` and reads/writes via `tvContext.lastFocusRect = ...`. Maps current code 1:1 with no behavior change.

**Alternatives considered and rejected:**
- *Class-based engine wrapper:* large refactor surface; changes Nuxt plugin init shape from `function (context, inject)` to `new TvNavEngine(context).attach()`. Behavior parity hard to verify mechanically.
- *ESM live bindings of `let` exports:* read-only across modules; doesn't fit mutable state needs.
- *Pass context as first arg to every helper:* extremely verbose; every call site changes.

### 3.2 File layout in `plugins/tv/` (17 files, ~2,100 LOC total)

| File | LOC | Contents | PR |
|---|---|---|---|
| `context.js` | ~30 | Singleton `tvContext` object | 4 |
| `visibility.js` | ~150 | `isVisible`, `getAllFocusable`, `centerOf`, `isSameRow` — pure DOM/geometry helpers | 4 |
| `scrollHelpers.js` | ~100 | `findPageScrollContainer`, `findScrollableParent`, `scrollParentToReveal`, `getScrollBehavior`, `isDetailScrollContainer` | 4 |
| `focusMemory.js` | ~250 | `getElementFingerprint`, `buildStructuralPath`, `findByStructuralPath`, `restoreFromFingerprint` — fingerprint system | 4 |
| `spatialNav.js` | ~200 | `findHorizontalTarget`, `findVerticalTarget` — beam-model navigators | 4 |
| `overlayFocus.js` | ~200 | `getActiveOverlay`, `saveFocusBeforeOverlay`, `restoreFocusAfterOverlay`, `handleOverlayNavigation` | 4 |
| `focusColor.js` | ~30 | `applyTvFocusColor`, `VALID_TV_FOCUS_HEXES`, `DEFAULT_TV_FOCUS_HEX` | 4 |
| `focusEntry.js` | ~200 | `focusFirstContentElement`, `isGridPage`, `focusAfterPlayerClose`, `refocusAfterContentChange` | 4 |
| `pageHandlers/episodeRow.js` | ~110 | `handleEpisodeRow` — podcast/audiobook track row keydown | 4 |
| `pageHandlers/logsContainer.js` | ~55 | `handleLogsContainer` — logs scroll-page keydown | 4 |
| `pageHandlers/statsPage.js` | ~85 | `handleStatsPage` — stats page keydown + chart focus | 4 |
| `pageHandlers/itemPage.js` | ~25 | `handleItemPage` — book/podcast detail keydown | 4 |
| `pageHandlers/authorPage.js` | ~55 | `handleAuthorPage` — author detail keydown | 4 |
| `pageHandlers/streamContainer.js` | ~15 | `handleStreamContainer` — stream/transcode page keydown | 4 |
| `pageHandlers/gridNav.js` | ~95 | `handleGridArrowUp` + `handleGridArrowLeftRight` — grid-page horizontal/vertical | 4 |
| `listeners.js` | ~400 | `registerRouterHooks`, `registerPlayerWatchers`, `registerModalWatcher`, `registerDrawerWatcher`, `registerEventBusSubscribers` | 5 |
| `index.js` | ~150 | Nuxt plugin default export, `checkAndInit`, global keydown listener, dispatcher to pageHandlers | 5 |

**Total: ~2,150 LOC** (current: 1,675 + ~475 of import statements, exports, JSDoc, and module-level scaffolding). Trade-off accepted — per-file reviewability >> the ~28% LOC growth from modularization overhead.

### 3.3 Dependency graph

```
context.js  ────────────────────────────────────┐
                                                │
visibility.js ──→ spatialNav.js                 │
visibility.js ──→ overlayFocus.js               │
visibility.js ──→ pageHandlers/* ──→ index.js   │
visibility.js ──→ focusEntry.js                 │
                                                │
scrollHelpers.js ──→ pageHandlers/* ──→ index.js│
scrollHelpers.js ──→ focusEntry.js              │
                                                │
focusMemory.js ──→ overlayFocus.js              │
focusMemory.js ──→ focusEntry.js                │
focusMemory.js ──→ listeners.js ──→ index.js    │
                                                │
overlayFocus.js ──→ pageHandlers/* ──→ index.js │
overlayFocus.js ──→ listeners.js ──→ index.js   │
                                                │
focusColor.js ──→ listeners.js ──→ index.js     │
                                                │
focusEntry.js ──→ pageHandlers/* ──→ index.js   │
focusEntry.js ──→ listeners.js ──→ index.js     │
                                                │
pageHandlers/* ──→ index.js (dispatcher)        │
                                                │
                                          (all)─┴─→ context.js (state)
```

No cycles. `context.js` is imported by everything that touches state.

### 3.4 PR4 vs PR5 boundary rationale

**PR4 (engine kit, 15 files, ~1,560 LOC)** ships **dormant code** — a function library. Without `index.js` registered in `nuxt.config.js`, the kit modules export functions that nothing calls. The kit is testable in isolation (unit tests on `findVerticalTarget`, `getElementFingerprint`, etc. work without complex DOM mocking).

**PR5 (engine integration, 2 files, ~550 LOC)** ships **the activation** — once `index.js` is registered, the global keydown listener attaches and the engine goes live. PR5 also includes the registration entry in `nuxt.config.js`.

This split lets a reviewer of PR4 think "are these utility functions correct?" without worrying about runtime behavior. PR5's review then asks "does the wiring make sense?" — separable concerns.

---

## 4. v1.0.10 fork refactor (precedes upstream submission)

The fork must adopt the modular structure FIRST so that future fork releases and upstream PRs share one code structure (no dual maintenance).

### 4.1 Branch & commit

- **Branch:** `refactor/modularize-tv-navigation` off `android-tv-dpad-navigation` (HEAD `18442d76`)
- **Single squash commit** with message:
  ```
  refactor(tv): modularize tv-navigation.js into plugins/tv/

  Behavior-preserving file split. No new functionality, no behavior changes.
  Replaces the 1,675-line plugins/tv-navigation.js monolith with 17 focused
  modules under plugins/tv/, sharing state through a tvContext singleton.

  See docs/superpowers/specs/2026-05-18-pr-decomposition-and-fork-
  modularization-design.md for the full file-by-file mapping and dependency
  graph.
  ```

### 4.2 Mechanical refactor rules (non-negotiable)

- Every function moves to its new file with the SAME name and SAME signature
- Module-level `let`/`const` state → `tvContext.*` references; no logic changes
- New `import { ... } from './...'` lines are the ONLY new code in module bodies
- ESLint clean, `npm run generate` clean, `npx cap sync android` clean
- `npm run lint` if present
- Behavioral parity is the success criterion — any divergence is a bug to fix BEFORE merging

### 4.3 Pre-merge test gate

1. Build APK: `./android/gradlew -p ./android assembleRelease` → confirm `abs-android-tv-release-1.0.10.apk` produced
2. Sideload on Google TV Streamer 4K
3. Run the 42-item TV manual checklist (`docs/superpowers/plans/2026-04-20-v1.0.5-v1.0.6-combined-test-checklist.md`)
4. Run the 26-item color-picker checklist (`docs/superpowers/plans/2026-04-25-tv-focus-color-picker-test-checklist.md`)
5. Smoke test on phone (Android 16) → no TV-only behavior should appear
6. Any single failure blocks merge

### 4.4 Release

- Tag `android-tv-v1.0.10` on the merged commit
- GitHub release notes: *"Internal refactor: split `tv-navigation.js` into focused modules under `plugins/tv/`. No user-visible changes. Foundation for future upstream PR submission."*
- APK uploaded per existing convention (`abs-android-tv-release-1.0.10.apk`)
- Estimated effort: ~4 hours mechanical + ~3 hours testing = ~1 day

### 4.5 Why one squash commit, not 17 per-file commits

Per-file move commits would fragment `git blame` (blame would point to the move commit instead of the original-author line). One squash keeps blame archaeology clean and presents the v1.0.10 → v1.0.9 diff as one clear "split into modules" operation.

---

## 5. Upstream PR branch & stacking strategy (post-v1.0.10)

### 5.1 Base for every upstream PR

`upstream/master` (fresh fetch at the time the PR opens).

### 5.2 Wave-based stacking

**Wave 1 — fully independent, submit all 3 simultaneously off `upstream/master`:**

| PR | Branch | LOC | Files |
|---|---|---|---|
| PR1 | `tv/foundation-detection` | ~125 + 1 binary | manifest, build.gradle, DeviceManager.kt, AbsDatabase.kt, MainActivity.kt, CastOptionsProvider.kt, tv_banner.png, store/index.js, plugins/capacitor/AbsDatabase.js, layouts/default.vue, pages/connect.vue, docs/pr-01-foundation-detection.md |
| PR2 | `tv/keyboard-hygiene` | ~300 | 25+ Vue file edits: tabindex + keydown.enter.prevent additions across cards, tables, toolbar, drawer, modals, settings dropdowns, info icons, page wrappers, ServerConnectForm subset; docs/pr-02-keyboard-hygiene.md |
| PR3 | `tv/css-foundation` | ~130 | assets/css/tv-focus.css, nuxt.config.js (register), store/user.js (tvFocusColor key), docs/pr-03-css-foundation.md |

Each is small, additive, and independent. Maintainer reviews/merges in any order.

**Wave 2 — opens AFTER `tv/foundation-detection` (PR1) merges:**

| PR | Branch | LOC | Stacked? |
|---|---|---|---|
| PR4 | `tv/engine-kit` (off `upstream/master`) | ~1,560 across 15 files | No |
| PR5 | `tv/engine-integration` (off `tv/engine-kit`) | ~550 across 2 files + nuxt.config edit | Yes — stacked on PR4 |

PR5 stacks because review requires PR4 as context. Use GitHub's native stacked PRs or `gh-stack`/`git-spr`. When PR4 merges, rebase PR5 onto fresh `upstream/master`.

**Wave 3 — opens AFTER `tv/engine-integration` (PR5) merges:**

| PR | Branch | LOC |
|---|---|---|
| PR6 | `tv/audio-player-behavior` | ~100 |
| PR7 | `tv/settings-color-picker` | ~180 |
| PR8 | `tv/connect-form-nav` | ~150 |
| PR9 | `tv/author-detail-page` | ~120 |

All 4 submit in parallel, each independent. PR9 (author detail page) is arguably non-TV-specific and can be tagged for the maintainer's consideration as general functionality.

### 5.3 Rebase discipline

- When `upstream/master` advances, rebase any open PR branches onto fresh master within ~48 hours
- Review comments → push fixup commits; squash before merge
- Never force-push to a stacked-parent branch while the child PR is open (breaks the child)

### 5.4 PR #1843 withdrawal

Close PR #1843 BEFORE submitting new PRs. Comment template:

> "Closing in favor of a 9-PR series that addresses the volume feedback. See [our-fork-link to `docs/PR_DECOMPOSITION_PLAN.md`] for the full plan and rationale. First PR (foundation + TV detection) opening at [link] shortly."

---

## 6. Per-PR `docs.md` format

### 6.1 File location & naming

`docs/pr-NN-<short-name>.md` at the upstream repo's docs root.

- PR1 → `docs/pr-01-foundation-detection.md`
- PR2 → `docs/pr-02-keyboard-hygiene.md`
- … through PR9 → `docs/pr-09-author-detail-page.md`

### 6.2 Why this pattern

- Each PR adds exactly ONE new file — no edits to existing files = zero merge conflicts between parallel-wave PRs
- After all 9 PRs merge, upstream `docs/` has 9 lightweight context files in numerical order — predictable, skim-friendly
- Each file is independently editable post-merge (e.g., updating broken links) without touching others
- Introduces a `docs/` directory that upstream doesn't have yet, but does so with minimal contribution (9 small files); maintainer can repurpose `docs/` later for their own conventions without conflict

### 6.3 Template (~25 LOC per file, with longer Architecture sections for PRs 4-5)

```markdown
# PR NN — <PR title>

Part of a 9-PR series adding Android TV support to audiobookshelf-app.
Replaces and supersedes the original PR #1843 (closed in favor of this series).

## Full series plan

See **[bilbospocketses/abs-app — PR_DECOMPOSITION_PLAN.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/PR_DECOMPOSITION_PLAN.md)**
for the complete 9-PR breakdown, rationale, and dependency graph.

## This PR's scope

<1-2 paragraphs>

## Architecture context (fork-hosted)

- [TV_FOCUS_SYSTEM.md](...) — full architecture overview (relevant to PRs 4-5)
- [TV_USER_GUIDE.md](...) — end-user feature documentation (QA reference)

## Testing

<1 sentence: devices + checklist coverage>

## Relationship to the series

- **Depends on:** <PR numbers or "nothing">
- **Blocks:** <PR numbers or "nothing">
- **Wave:** <1, 2, or 3>
```

Total upstream addition across all 9 PRs from docs alone: ~225 LOC. Compared to the ~3,500 LOC of docs NOT being pushed, this is a rounding error.

---

## 7. Per-PR concrete summaries

### PR 1 — Foundation + TV detection

- **Wave:** 1 of 3
- **Depends on:** nothing
- **Blocks:** PR 4, 6, 7, 8, 9
- **LOC:** ~125 + 1 binary
- **Files:**
  - `android/app/src/main/AndroidManifest.xml` — leanback intent filter, `uses-feature` declarations (touchscreen + leanback both `required="false"`), banner attribute (11 LOC)
  - `android/app/build.gradle` — banner asset reference; revert to versioned APK naming (18 LOC; partially see PR-13 in fork TODO list)
  - `android/app/src/main/java/com/audiobookshelf/app/device/DeviceManager.kt` — `isAndroidTV()` (10 LOC)
  - `android/app/src/main/java/com/audiobookshelf/app/plugins/AbsDatabase.kt` — exposes flag to WebView (4 LOC)
  - `android/app/src/main/java/com/audiobookshelf/app/MainActivity.kt` — injects `android-tv` class on `<html>`; `finishAndRemoveTask` + `killProcess` on TV `onStop` (25 LOC)
  - `android/app/src/main/java/com/audiobookshelf/app/CastOptionsProvider.kt` — Google Play Services guard (27 LOC)
  - `android/app/src/main/res/drawable-xhdpi/tv_banner.png` — leanback banner (binary)
  - `store/index.js` — `isAndroidTv` Vuex state + mutation (7 LOC)
  - `plugins/capacitor/AbsDatabase.js` — reads `isAndroidTv` from device info (3 LOC)
  - `layouts/default.vue` — sets `isAndroidTv` from device data on mount (3 LOC)
  - `pages/connect.vue` — sets `isAndroidTv` on mount (connect bypasses default layout) (17 LOC)
  - `docs/pr-01-foundation-detection.md` (~25 LOC)
- **What it does:** App appears in TV launcher with banner. App reads as Android TV via `MainActivity.onStart`. WebView gets `android-tv` class. Vuex `isAndroidTv` flag available. No navigation behavior yet. Phone/tablet behavior byte-identical to upstream.
- **Test:** Sideload on Google TV Streamer 4K + phone; verify launcher appearance, Vuex flag value, no behavior changes on phone.

### PR 2 — Keyboard hygiene

- **Wave:** 1 of 3
- **Depends on:** nothing
- **Blocks:** PR 8, 9 (and any future TV PR using these elements)
- **LOC:** ~300 across 25+ files
- **Files (Vue component edits, tabindex + keydown.enter.prevent additions):**
  - `components/app/Appbar.vue`, `SideDrawer.vue`, `BookshelfToolbar.vue`, `LibrariesModal.vue`
  - `components/cards/LazyBookCard.vue`, `LazySeriesCard.vue`, `LazyCollectionCard.vue`, `LazyPlaylistCard.vue`, `AuthorCard.vue`, `AuthorImage.vue`
  - `components/tables/ChaptersTable.vue`, `TracksTable.vue`, `podcast/EpisodeRow.vue`, `podcast/EpisodesTable.vue`, `podcast/LatestEpisodeRow.vue`
  - `components/ui/TextInput.vue`, `ToggleSwitch.vue`
  - `pages/account.vue`, `stats.vue`, `logs.vue`, `localMedia/item/_id.vue`, `item/_id/index.vue`, `item/_id/_episode/index.vue`
  - `pages/settings.vue` — dropdowns + info icons subset (NOT the new TV Settings section — that's PR7)
  - `components/app/AudioPlayer.vue` — tabindex additions only (NOT the KeepAwake / close-vs-minimize / History gate — that's PR6)
  - `components/connection/ServerConnectForm.vue` — subset; rest is PR8
  - `docs/pr-02-keyboard-hygiene.md`
- **What it does:** All focusable UI elements gain proper `tabindex` and `@keydown.enter.prevent` handlers. Inert on touch users; foundational for any keyboard-driven device (TV, accessibility tooling, future keyboard support). No TV gates — additions are universally safe.
- **Test:** Verify no behavior changes on phone (manual: tap every modified element, confirm same behavior as before). On TV with a debug build: confirm Enter key fires once (no double-fire).

### PR 3 — CSS foundation

- **Wave:** 1 of 3
- **Depends on:** nothing
- **Blocks:** PR 7
- **LOC:** ~130
- **Files:**
  - `assets/css/tv-focus.css` — 127 LOC of `.android-tv`-scoped focus ring styles, including `--tv-focus-color` CSS custom property defined on `:root.android-tv`
  - `nuxt.config.js` — register `tv-focus.css` (2 LOC)
  - `store/user.js` — `tvFocusColor: '#1ad691'` initial setting (3 LOC)
  - `docs/pr-03-css-foundation.md`
- **What it does:** Focus ring styles available; CSS variable defined. Nothing drives them yet (no JS to attach focus changes), so phone behavior unchanged.
- **Test:** Phone smoke — no visual artifacts. TV with manual focus inspection: variable is set on `:root.android-tv`.

### PR 4 — Engine kit (plugins/tv/ utility modules + page handlers)

- **Wave:** 2 of 3
- **Depends on:** PR 1 (consumes `isAndroidTv` Vuex state implicitly via tvContext + future listeners.js)
- **Blocks:** PR 5
- **LOC:** ~1,560 across 15 files
- **Files:** see Section 3.2 table — all rows marked PR 4 (context, visibility, scrollHelpers, focusMemory, spatialNav, overlayFocus, focusColor, focusEntry, 7 pageHandlers/* files)
- **What it does:** Ships dormant library code. No global listeners attached yet; engine sits cold until PR5 activates it.
- **Test:** Build succeeds; lint clean; phone + TV both load without errors. No behavior changes anywhere.
- **Note:** This is the largest PR by LOC. Each file is bounded (max ~250 LOC, most under 150). Reviewer can address one file at a time. Recommend the PR description orders files in dependency order (context → visibility/scrollHelpers → focusMemory/focusColor → spatialNav/overlayFocus → focusEntry → pageHandlers/*).

### PR 5 — Engine integration

- **Wave:** 2 of 3
- **Depends on:** PR 4 (stacks on `tv/engine-kit` branch)
- **Blocks:** PR 6, 7, 8 (Wave 3 starts after PR5 merges)
- **LOC:** ~550 + nuxt.config.js edit
- **Files:**
  - `plugins/tv/listeners.js` (~400 LOC) — `registerRouterHooks`, `registerPlayerWatchers`, `registerModalWatcher`, `registerDrawerWatcher`, `registerEventBusSubscribers`
  - `plugins/tv/index.js` (~150 LOC) — Nuxt plugin default export, `checkAndInit`, global keydown listener, dispatcher to pageHandlers
  - `nuxt.config.js` — register `plugins/tv/index.js` (1 LOC change to plugins array)
  - `docs/pr-05-engine-integration.md`
- **What it does:** Activates the engine. Once merged, D-pad navigation works on Android TV across all pages that PR2 added keyboard hygiene to. Phone behavior unchanged (plugin entry point gates on `android-tv` class).
- **Test:** Full 42-item TV manual checklist. Phone smoke. No regression in any existing TV feature.

### PR 6 — Audio player TV behavior

- **Wave:** 3 of 3
- **Depends on:** PR 1 (isAndroidTv state), PR 5 (D-pad nav active)
- **LOC:** ~100
- **Files:**
  - `components/app/AudioPlayer.vue` — KeepAwake wiring into `onPlayingUpdate` and `endPlayback`, close-vs-minimize gate in `collapseFullscreen`, auto-expand to fullscreen on playback start, History menu item hidden on TV
  - `docs/pr-06-audio-player-behavior.md`
- **What it does:** TV-specific audio player behaviors. Screen wake lock during playback (Ambient Mode workaround), full close on collapse (mini player not navigable on TV), auto-expand on start (so user lands in fullscreen instead of mini), History route disabled (would collapse fullscreen player).
- **Test:** 8 audio-player items from the 42-item checklist; phone smoke (history menu still works on phone, no KeepAwake on phone).

### PR 7 — Settings + focus-color picker

- **Wave:** 3 of 3
- **Depends on:** PR 3 (CSS variable), PR 5 (D-pad nav for the picker UI)
- **LOC:** ~180
- **Files:**
  - `pages/settings.vue` — new TV Settings section at top (`v-if="isAndroidTv"`) hosting `<TvFocusColorPicker>`, plus `setTvFocusColor` method dispatch
  - `components/ui/TvFocusColorPicker.vue` — 7-swatch horizontal picker
  - `docs/pr-07-settings-color-picker.md`
- **What it does:** User can pick from 7 high-contrast focus ring colors via Settings → TV Settings. Phone settings page unchanged.
- **Test:** 26-item color-picker checklist; phone smoke (settings page byte-identical).

### PR 8 — Server connect form D-pad nav

- **Wave:** 3 of 3
- **Depends on:** PR 2 (some hygiene), PR 5 (D-pad nav)
- **LOC:** ~150
- **Files:**
  - `components/connection/ServerConnectForm.vue` — server config rows / edit / delete icons / back arrows: tabindex + keydown.enter.prevent
  - `docs/pr-08-connect-form-nav.md`
- **What it does:** Connect form fully D-pad navigable. Phone behavior unchanged (tabindex inert on touch).
- **Test:** Connect-form items from 42-item checklist; phone smoke (form still works via tap).

### PR 9 — Author detail page

- **Wave:** 3 of 3
- **Depends on:** PR 2 (AuthorCard tabindex)
- **LOC:** ~120
- **Files:**
  - `pages/author/_id.vue` — new author detail page with bio, image, and book grid
  - `components/cards/AuthorCard.vue` — navigates to `/author/{id}` on TV (click already navigates on phone via existing modal; this adds a route alternative)
  - `pages/bookshelf/authors.vue` — responsive column count for TV-class screen widths
  - `components/covers/AuthorImage.vue` — `tabindex="-1"` to prevent double-focus with parent AuthorCard
  - `docs/pr-09-author-detail-page.md`
- **What it does:** New author detail page accessible from AuthorCard. The page itself is non-TV-specific — phone users could benefit if a routing change were applied. PR description flags this for the maintainer's consideration.
- **Test:** Author flow on TV; phone smoke (existing author modal still works).

---

## 8. Test strategy per PR

| PR | Manual test required | Existing checklist |
|---|---|---|
| 1 | Launcher appearance + Vuex flag verification (TV + phone) | New ~10-item check |
| 2 | Phone tap regression across modified elements + TV Enter no-double-fire | New ~20-item check |
| 3 | Phone visual smoke + TV `:root.android-tv` variable presence | Light, ~5-item |
| 4 | Build + lint + load (no behavior change) | Light, ~3-item |
| 5 | Full 42-item TV checklist + phone smoke | `2026-04-20-v1.0.5-v1.0.6-combined-test-checklist.md` (42 items) |
| 6 | 8 audio-player items + phone smoke | Subset of 42-item |
| 7 | 26-item color-picker checklist + phone smoke | `2026-04-25-tv-focus-color-picker-test-checklist.md` (26 items) |
| 8 | Connect-form items + phone smoke | Subset of 42-item |
| 9 | Author flow on TV + phone smoke | New ~10-item check |

**Playwright (Tier 1) and Maestro (Tier 2) test harnesses** are queued in `todo_abs_tv.md` item 12. Not blocking for this PR series — manual testing on Google TV Streamer 4K and Android 16 phone is the established practice and is sufficient. If the test harnesses land before the PR series completes, retrofit as additive coverage.

---

## 9. Why we're not inlining the engine (rationale captured for the record)

The maintainer's PR #1843 feedback raised the question whether TV-specific code could be "inlined" into the maintainer's existing files rather than kept in dedicated files like `plugins/tv-navigation.js`. Analysis:

| What inlining changes | LOC delta | Notes |
|---|---|---|
| `handleKeyDown` dispatch indirection collapse | ~50-100 LOC saved | The 554-line monster has some scroll-helper duplication between page-type branches that distribution would eliminate |
| Engine helpers (spatial finders, fingerprint, geometry, overlay, scroll) | **0 LOC** | Cross-cutting global concerns. Must live in one shared module regardless of where it lives — distribution doesn't shrink. |
| `tv-focus.css` → distributed into component `<style>` blocks | ~0 LOC | Same selector count, just spread across files. Loses centralized `--tv-focus-color` location. |
| Per-page handlers (~600 LOC of `handleKeyDown` branches) move inline | **0 LOC** | Same code, different file. Distributes LOC across ~10-15 Vue files. |
| **Total honest savings from inlining** | **~50-100 LOC** | <4% of the 2,530-LOC code total |

Real costs of inlining:
- ~15 Vue files get longer (200-400 LOC each grows by 40-80 LOC)
- Cross-cutting changes (e.g., evolving focus-memory model) require touching every page instead of one file
- PR diff TOUCHES MORE FILES (a different kind of maintainer-overwhelm trigger)
- TV-specific tests can't co-locate with engine

We also already use inline gating where it's appropriate — `tabindex` additions are unconditional, `KeepAwake` is an early-return-gated method inside AudioPlayer's `methods:` block, History menu hiding is an inline `&& !isAndroidTv` gate. The hybrid is already deployed. The only code in `tv-navigation.js` is code that genuinely benefits from centralization.

**Decision:** Keep the architecture. Address volume via the 9-PR split + docs strip + engine modularization. The inline option was withdrawn from the maintainer conversation on 2026-05-18.

---

## 10. Risk + contingency

### 10.1 Risks during v1.0.10 refactor

| Risk | Mitigation |
|---|---|
| Behavior regression during file split | 42 + 26 item manual test pass before merge; rule that ANY divergence is a bug |
| Lint or build errors from import rewrites | ESLint runs as part of pre-merge check; build runs via assembleRelease |
| Subtle state-sharing bug from singleton context migration | Singleton object replaces module-level vars 1:1; reviewer scrutiny on `tvContext.*` access patterns |

### 10.2 Risks during upstream PR series

| Risk | Mitigation |
|---|---|
| Maintainer rejects a Wave 1 PR | Wave 1 PRs are independent; only the rejected one is blocked. Address feedback, resubmit. |
| Maintainer rejects PR 4 (engine kit) | Wave 2 + Wave 3 blocked. If rejection is structural (e.g., "I don't want a plugins/tv/ directory"), reconsider modularization approach. If rejection is style (e.g., "rename these helpers"), address and resubmit. |
| Upstream `master` advances mid-series, breaking rebase | Rebase open PR branches within ~48h. If a Wave 3 PR conflicts with a recently-merged upstream change, resolve in PR branch. |
| Maintainer asks to combine some PRs (e.g., "PR 6, 7, 8, 9 are too granular, merge them") | Combine on a single branch. The plan is a starting point; maintainer preference adjustments are acceptable. |
| Maintainer asks to split a PR further (e.g., "PR 4 is too big, split engine kit into 3 PRs") | Take Approach 1 (pure layered) PR splitting for engine kit: 4a (visibility + scrollHelpers + context), 4b (focusMemory + spatialNav + overlayFocus + focusColor), 4c (focusEntry + pageHandlers/*). |
| Engine integration (PR 5) reveals a bug | Treat as a normal bug fix on PR 5's branch. Fix, push, request re-review. |

### 10.3 Contingency: maintainer wants something fundamentally different

If maintainer's response to the new plan is "I want X completely different approach":
- Listen
- Don't argue from sunk cost
- Re-brainstorm with the new constraint
- The fork still benefits from the v1.0.10 modularization regardless

---

## 11. Execution timeline (sequenced)

| Phase | Activity | Estimated effort |
|---|---|---|
| 1 | Write external `docs/PR_DECOMPOSITION_PLAN.md` | ~1 hour |
| 2 | Commit + publish v1.0.10 fork refactor | ~1 day (4h mechanical + 3h testing + release) |
| 3 | Close PR #1843 with redirect to plan doc | ~15 min |
| 4 | Submit Wave 1 PRs (1, 2, 3) in parallel | ~2 hours total (branch prep + PR descriptions) |
| 5 | Await Wave 1 review/merge | Variable (days to weeks per PR) |
| 6 | Submit Wave 2 PRs (4 + stacked 5) | ~3 hours (PR 4 is the meatiest description) |
| 7 | Await Wave 2 review/merge | Variable |
| 8 | Submit Wave 3 PRs (6, 7, 8, 9) in parallel | ~3 hours total |
| 9 | Await Wave 3 review/merge | Variable |
| 10 | Post-merge cleanup: archive `android-tv-dpad-navigation` branch as historical, switch fork users to upstream releases | ~1 day |

**Total active effort (excluding maintainer-wait time): ~3-4 days.**

---

## 12. Open questions / TBD

None at design approval. Items that emerge during execution will be tracked in `todo_abs_tv.md`.

---

## Appendix A: file-by-file mapping (`tv-navigation.js` → `plugins/tv/*`)

Approximate line ranges in current `plugins/tv-navigation.js` → target file:

| Source line range | Function(s) | Target file |
|---|---|---|
| 14-21 | `_store`, `VALID_TV_FOCUS_HEXES`, `DEFAULT_TV_FOCUS_HEX` | `context.js` (for `_store` → `tvContext.store`) + `focusColor.js` (for hex constants) |
| 23-30 | `applyTvFocusColor` | `focusColor.js` |
| 32-57 | `focusHistory`, `verticalNavInProgress`, `lastFocusRect`, `cssEscape`, `pageFocusMemory` | `context.js` |
| 59-100 | `getElementFingerprint` | `focusMemory.js` |
| 101-117 | `buildStructuralPath` | `focusMemory.js` |
| 118-144 | `findByStructuralPath` | `focusMemory.js` |
| 145-262 | `restoreFromFingerprint` | `focusMemory.js` |
| 263-287 | `saveFocusBeforeOverlay`, `restoreFocusAfterOverlay` | `overlayFocus.js` |
| 288-307 | `centerOf`, `isVisible` | `visibility.js` |
| 308-319 | `isSameRow` | `visibility.js` |
| 320-343 | `getActiveOverlay` | `overlayFocus.js` |
| 344-366 | `getAllFocusable` | `visibility.js` |
| 367-402 | `findPageScrollContainer`, `isDetailScrollContainer`, `getScrollBehavior`, `findScrollableParent` | `scrollHelpers.js` |
| 403-472 | `scrollParentToReveal` | `scrollHelpers.js` |
| 473-500 | `handleOverlayNavigation` | `overlayFocus.js` |
| 501-534 | `findHorizontalTarget` | `spatialNav.js` |
| 535-600 | `findVerticalTarget` | `spatialNav.js` |
| 601-639 | `handleKeyDown` overlay routing + setup | `index.js` (dispatcher) |
| 640-731 | streamContainer branch | `pageHandlers/streamContainer.js` |
| 732-843 | episodeRow branch | `pageHandlers/episodeRow.js` |
| 844-898 | logsContainer branch | `pageHandlers/logsContainer.js` |
| 899-924 | ArrowUp special case | `pageHandlers/gridNav.js` (handleGridArrowUp) |
| 925-1008 | statsPage branch | `pageHandlers/statsPage.js` |
| 1009-1031 | itemPage branch | `pageHandlers/itemPage.js` |
| 1032-1084 | authorWrapper branch | `pageHandlers/authorPage.js` |
| 1085-1155 | ArrowLeft / ArrowRight | `pageHandlers/gridNav.js` (handleGridArrowLeftRight) |
| 1156-1160 | `isGridPage` | `focusEntry.js` |
| 1161-1203 | `focusFirstContentElement` | `focusEntry.js` |
| 1204-1232 | (helper for user-logged-in checks) | `focusEntry.js` |
| 1233-end | `focusAfterPlayerClose`, `registerTvListeners`, `checkAndInit`, default export | `focusEntry.js` (focusAfterPlayerClose) + `listeners.js` (registerTvListeners split into 5 functions) + `index.js` (checkAndInit + default export) |

---

**End of design spec.**
