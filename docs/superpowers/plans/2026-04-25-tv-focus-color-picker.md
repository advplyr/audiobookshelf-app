# TV Focus Ring Color Picker — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a TV-only Settings control that lets the user pick the D-pad focus-ring color from a curated set of 7 high-contrast presets, persisted per-user via the existing Vuex / `$localStore` settings pipeline.

**Architecture:** New `TvFocusColorPicker.vue` component (swatch row, ★ default marker, white-outline selection state) wired into a new `v-if="isAndroidTv"` "TV Settings" section at the top of `pages/settings.vue`. New `tvFocusColor` key on `store/user.js`'s `state.settings` rides the existing `updateUserSettings` / `loadUserSettings` pipeline. A small subscriber inside `plugins/tv-navigation.js` listens to the existing `user-settings` event-bus broadcast and writes the chosen hex into the `--tv-focus-color` CSS variable on `<html>`, which `assets/css/tv-focus.css` (already in place from I8) consumes for every focus ring.

**Tech Stack:** Vue 2 / Nuxt 2 (single-file components, Vuex), Capacitor `$localStore`, plain CSS custom properties. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-04-25-tv-focus-color-picker-design.md`

**Branch:** `tv-focus-color-setting` off `android-tv-dpad-navigation`. Do NOT branch off `fire-tv-focus-handling` — that's the diagnostic branch with the `pages/logs.vue` revert pending.

**Build / sideload reality:** This codebase does not have an automated test runner for Vue components. Established precedent (and the spec) is manual checklist validation on real device. Each task verifies via APK build + sideload to the Google TV Streamer 4K (or equivalent ATV) and visual check. Build commands are repeated in each task for portability.

---

## File Structure

| File | Role | Status |
| --- | --- | --- |
| `components/ui/TvFocusColorPicker.vue` | Picker component — swatch row, ★ default marker, white-outline selected state | **NEW** |
| `pages/settings.vue` | Adds TV Settings section at top with `<TvFocusColorPicker v-model>` | Modify |
| `store/user.js` | Adds `tvFocusColor: '#1ad691'` to initial `state.settings` | Modify |
| `plugins/tv-navigation.js` | Adds `user-settings` subscriber inside `registerTvListeners` that writes `--tv-focus-color` CSS variable | Modify |
| `assets/css/tv-focus.css` | (no change — `--tv-focus-color` variable already in place from I8) | Untouched |
| `docs/superpowers/plans/2026-04-25-tv-focus-color-picker-test-checklist.md` | Manual test checklist for QA | **NEW** |

---

## Task 1: Add `tvFocusColor` to Vuex initial state

**Files:**
- Modify: `store/user.js:5-15`

- [ ] **Step 1.1: Create the branch**

```bash
cd C:/Users/jscha/source/repos/abs-app
git checkout android-tv-dpad-navigation
git pull
git checkout -b tv-focus-color-setting
```

- [ ] **Step 1.2: Add `tvFocusColor` to initial settings**

Modify `store/user.js`. Locate the `state` factory near line 5:

```js
export const state = () => ({
  user: null,
  accessToken: null,
  serverConnectionConfig: null,
  settings: {
    mobileOrderBy: 'addedAt',
    mobileOrderDesc: true,
    mobileFilterBy: 'all',
    playbackRate: 1,
    collapseSeries: false,
    collapseBookSeries: false
  }
})
```

Add the new key as the last property of `settings`:

```js
export const state = () => ({
  user: null,
  accessToken: null,
  serverConnectionConfig: null,
  settings: {
    mobileOrderBy: 'addedAt',
    mobileOrderDesc: true,
    mobileFilterBy: 'all',
    playbackRate: 1,
    collapseSeries: false,
    collapseBookSeries: false,
    tvFocusColor: '#1ad691'
  }
})
```

The trailing-comma move on the previous line is intentional — match existing style.

- [ ] **Step 1.3: Verify the key flows through `updateUserSettings`**

Read `store/user.js:95-110` (the `updateUserSettings` action). Confirm the action iterates over `existingSettings` keys and only commits keys present there. Because `tvFocusColor` is now in initial state, dispatching `updateUserSettings({ tvFocusColor: '#abc' })` from anywhere will be picked up. No additional action plumbing needed.

- [ ] **Step 1.4: Build**

```bash
cd C:/Users/jscha/source/repos/abs-app
npm run generate
npx cap sync android
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk" ./android/gradlew -p ./android assembleRelease
```

Expected: build succeeds. APK at `android/app/build/outputs/apk/release/abs-android-tv-release-{version}.apk`.

- [ ] **Step 1.5: Commit**

```bash
git add store/user.js
git commit -m "feat(tv): add tvFocusColor to user settings initial state"
```

---

## Task 2: Add CSS-variable subscriber in `plugins/tv-navigation.js`

**Files:**
- Modify: `plugins/tv-navigation.js` (top of file for constant; ~line 1644-1652 for subscriber + initial apply)

This task wires the runtime path: when settings load OR change, the chosen color flows into the CSS variable. Verifiable in isolation via DevTools console before the picker UI exists.

- [ ] **Step 2.1: Add `VALID_HEXES` constant + `applyFocusColor` helper near top of file**

Locate the top of `plugins/tv-navigation.js` (just below the imports / inside the plugin's outer scope). Add:

```js
// Valid focus-ring colors (must match TvFocusColorPicker.vue PRESETS)
const VALID_TV_FOCUS_HEXES = ['#1ad691', '#3ea6ff', '#ffb74d', '#ff5252', '#e040fb', '#ffeb3b', '#ffffff']
const DEFAULT_TV_FOCUS_HEX = '#1ad691'

function applyTvFocusColor(value, store) {
  const hex = VALID_TV_FOCUS_HEXES.includes(value) ? value : DEFAULT_TV_FOCUS_HEX
  document.documentElement.style.setProperty('--tv-focus-color', hex)
  // Self-heal: if the stored value was bad, dispatch a corrective save.
  if (value && hex !== value && store) {
    store.dispatch('user/updateUserSettings', { tvFocusColor: DEFAULT_TV_FOCUS_HEX })
  }
}
```

Place this above the line that begins the exported plugin function (the function that takes `({ store, app })` or similar). If the imports section is at the very top, place this immediately after the imports.

- [ ] **Step 2.2: Subscribe to `user-settings` event inside `registerTvListeners`**

Locate the existing `if (store?.app?.$eventBus)` block in `plugins/tv-navigation.js` (around line 1644). It currently looks like:

```js
  // Re-focus after library changes or content reloads (same route, new content)
  if (store?.app?.$eventBus) {
    store.app.$eventBus.$on('library-changed', () => {
      refocusAfterContentChange()
    })
    // Fires when bookshelf content changes (after filter, sort, or data load)
    store.app.$eventBus.$on('bookshelf-total-entities', () => {
      refocusAfterContentChange()
    })
  }
```

Add a third subscriber + an initial-apply call inside the same `if` block:

```js
  // Re-focus after library changes or content reloads (same route, new content)
  if (store?.app?.$eventBus) {
    store.app.$eventBus.$on('library-changed', () => {
      refocusAfterContentChange()
    })
    // Fires when bookshelf content changes (after filter, sort, or data load)
    store.app.$eventBus.$on('bookshelf-total-entities', () => {
      refocusAfterContentChange()
    })
    // TV focus ring color — apply current value, then react to user-settings broadcasts
    applyTvFocusColor(store.state.user?.settings?.tvFocusColor, store)
    store.app.$eventBus.$on('user-settings', (settings) => {
      applyTvFocusColor(settings?.tvFocusColor, store)
    })
  }
```

The initial `applyTvFocusColor(...)` call handles the case where `loadUserSettings` already finished before TV nav init runs (common — settings load early in app boot). The subsequent `$on('user-settings', ...)` handles all later changes (picker selection, user switch).

- [ ] **Step 2.3: Build**

```bash
cd C:/Users/jscha/source/repos/abs-app
npm run generate
npx cap sync android
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk" ./android/gradlew -p ./android assembleRelease
```

- [ ] **Step 2.4: Sideload + verify in DevTools**

Sideload the APK. On Google TV Streamer 4K (or equivalent ATV):

1. Connect via `chrome://inspect` (Chrome DevTools remote debug). Navigate the app to any page with focusable elements (e.g., the Library list).
2. In DevTools console, run: `getComputedStyle(document.documentElement).getPropertyValue('--tv-focus-color')`
3. Expected: `" #1ad691"` (with leading space — that's normal for `getPropertyValue`).
4. Now manually dispatch a settings change: `window.$nuxt.$store.dispatch('user/updateUserSettings', { tvFocusColor: '#3ea6ff' })`
5. Re-run `getComputedStyle(...)`. Expected: `" #3ea6ff"`.
6. Press D-pad to focus a card. The focus ring should render in Sky blue.
7. Dispatch an invalid value: `window.$nuxt.$store.dispatch('user/updateUserSettings', { tvFocusColor: 'not-a-hex' })`
8. Re-run `getComputedStyle(...)`. Expected: `" #1ad691"` (self-heal kicked in).
9. Reset: `window.$nuxt.$store.dispatch('user/updateUserSettings', { tvFocusColor: '#1ad691' })` so the next session starts at default.

- [ ] **Step 2.5: Commit**

```bash
git add plugins/tv-navigation.js
git commit -m "feat(tv): apply tvFocusColor to --tv-focus-color CSS var on user-settings event"
```

---

## Task 3: Build `TvFocusColorPicker.vue` component

**Files:**
- Create: `components/ui/TvFocusColorPicker.vue`

- [ ] **Step 3.1: Scaffold the component file**

Create `components/ui/TvFocusColorPicker.vue` with the full content below. This is the complete file — no further additions needed.

```vue
<template>
  <div class="tv-focus-color-picker flex items-center gap-3">
    <button
      v-for="preset in PRESETS"
      :key="preset.hex"
      type="button"
      tabindex="0"
      class="swatch"
      :class="{ selected: preset.hex === value }"
      :style="{ backgroundColor: preset.hex }"
      :aria-label="`Focus ring color: ${preset.name}`"
      @click="select(preset.hex)"
      @keydown.enter.prevent="select(preset.hex)"
    >
      <span v-if="preset.isDefault" class="default-marker" aria-hidden="true">★</span>
    </button>
  </div>
</template>

<script>
export default {
  props: {
    value: {
      type: String,
      default: '#1ad691'
    }
  },
  data() {
    return {
      PRESETS: [
        { hex: '#1ad691', name: 'ABS Green', isDefault: true },
        { hex: '#3ea6ff', name: 'Sky' },
        { hex: '#ffb74d', name: 'Amber' },
        { hex: '#ff5252', name: 'Red' },
        { hex: '#e040fb', name: 'Violet' },
        { hex: '#ffeb3b', name: 'Yellow' },
        { hex: '#ffffff', name: 'White' }
      ]
    }
  },
  methods: {
    select(hex) {
      if (hex !== this.value) {
        this.$emit('input', hex)
      }
    }
  }
}
</script>

<style scoped>
.swatch {
  position: relative;
  width: 44px;
  height: 44px;
  border-radius: 6px;
  border: none;
  cursor: pointer;
  padding: 0;
  /* Reserve outline space so selected/focused states don't shift layout */
  outline: 2px solid transparent;
  outline-offset: 2px;
}

/* Selected swatch: white outline marker */
.swatch.selected {
  outline-color: #ffffff;
}

/* The .android-tv button:focus rule from tv-focus.css applies here for free —
   D-pad focus shows the current --tv-focus-color, which is exactly what we want
   while the user is selecting. */

/* Default-marker star, top-right corner */
.default-marker {
  position: absolute;
  top: 2px;
  right: 4px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.55);
  text-shadow: 0 0 2px rgba(255, 255, 255, 0.4);
  pointer-events: none;
}
</style>
```

Notes:
- The component is intentionally TV-agnostic — no `isAndroidTv` check inside. The parent (`pages/settings.vue`) handles TV-only gating via `v-if`.
- `tabindex="0"` is explicit on each `<button>` to be safe even though buttons are natively focusable; consistent with the rest of the codebase's TV-aware components.
- The `@keydown.enter.prevent` handler matches the established pattern in `pages/settings.vue` (e.g., the existing `settings-dropdown` rows). Native `<button>` already activates on Enter, but `.prevent` blocks the global TV nav handler from also processing the press.
- `outline: 2px solid transparent` + `outline-offset: 2px` reserves the layout space the white selected outline will occupy, preventing a shift when selection moves between swatches.

- [ ] **Step 3.2: Build**

```bash
cd C:/Users/jscha/source/repos/abs-app
npm run generate
npx cap sync android
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk" ./android/gradlew -p ./android assembleRelease
```

Expected: build succeeds. The component is not yet rendered anywhere, so visual verification waits until Task 4. The point of building here is to catch syntax / template errors early.

- [ ] **Step 3.3: Commit**

```bash
git add components/ui/TvFocusColorPicker.vue
git commit -m "feat(tv): add TvFocusColorPicker component"
```

---

## Task 4: Wire picker into `pages/settings.vue`

**Files:**
- Modify: `pages/settings.vue` (template top, script computed/methods)

- [ ] **Step 4.1: Add the TV Settings section to the top of the template**

Open `pages/settings.vue`. The template currently starts with:

```vue
<template>
  <div id="settings-page" class="w-full h-full px-4 py-8 overflow-y-auto">
    <!-- Display settings -->
    <p class="uppercase text-xs font-semibold text-fg-muted mb-2">{{ $strings.HeaderUserInterfaceSettings }}</p>
```

Insert a new section immediately after the opening `<div id="settings-page" ...>` and before the existing Display settings comment:

```vue
<template>
  <div id="settings-page" class="w-full h-full px-4 py-8 overflow-y-auto">
    <!-- TV settings (Android TV only) -->
    <template v-if="isAndroidTv">
      <p class="uppercase text-xs font-semibold text-fg-muted mb-2">TV Settings</p>
      <div class="py-3 flex items-center">
        <p class="pr-4 w-36">Focus Ring Color</p>
        <TvFocusColorPicker :value="tvFocusColor" @input="setTvFocusColor" />
      </div>
    </template>

    <!-- Display settings -->
    <p class="uppercase text-xs font-semibold text-fg-muted mb-2 mt-10">{{ $strings.HeaderUserInterfaceSettings }}</p>
```

Two notes:
- Section header text is hard-coded `"TV Settings"` and label text is hard-coded `"Focus Ring Color"`. They are NOT routed through `$strings` because this is an unreleased TV-only feature on a fork; adding new i18n keys touches `strings/en-us.json` and risks merge conflicts with upstream. If the maintainer accepts the upstream PR later, those strings can be migrated.
- The existing "User Interface Settings" header gets a new `mt-10` class to keep the visual gap between sections consistent.

- [ ] **Step 4.2: Add the import and component registration**

Locate the `<script>` block in `pages/settings.vue`. At or near the top of the `export default {...}` object (typically after the `name` key if present, else inside `components: {}`), add the import and registration:

```vue
<script>
import TvFocusColorPicker from '@/components/ui/TvFocusColorPicker.vue'

export default {
  components: {
    TvFocusColorPicker
  },
  // ... rest of the existing component options
```

If `pages/settings.vue` already has a `components: {}` block, add `TvFocusColorPicker` to it instead of creating a new one.

- [ ] **Step 4.3: Add `isAndroidTv` and `tvFocusColor` computeds**

In the `computed: { ... }` block of `pages/settings.vue`, add two new computed properties (alongside whatever already lives there):

```js
computed: {
  // ... existing computeds
  isAndroidTv() {
    return this.$store.state.isAndroidTv
  },
  tvFocusColor() {
    return this.$store.state.user.settings.tvFocusColor || '#1ad691'
  }
}
```

The `|| '#1ad691'` fallback handles the moment between Vuex hydration phases (state factory → loadUserSettings) where the key could theoretically be undefined.

- [ ] **Step 4.4: Add `setTvFocusColor` method**

In the `methods: { ... }` block, add:

```js
methods: {
  // ... existing methods
  setTvFocusColor(hex) {
    this.$store.dispatch('user/updateUserSettings', { tvFocusColor: hex })
  }
}
```

- [ ] **Step 4.5: Build**

```bash
cd C:/Users/jscha/source/repos/abs-app
npm run generate
npx cap sync android
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk" ./android/gradlew -p ./android assembleRelease
```

- [ ] **Step 4.6: Sideload + visual smoke check**

Sideload to the Google TV Streamer 4K. Quick smoke (full QA is Task 6):

1. Open the app, log in, navigate to Settings.
2. Expected: a "TV SETTINGS" header appears at the top with a "Focus Ring Color" row showing 7 swatches; ABS Green (first) has a white outline and a `★` glyph.
3. D-pad Right through the swatches. Expected: each swatch shows a green focus outline (the current `--tv-focus-color`).
4. Press Enter on the Sky (second) swatch. Expected: white outline jumps to Sky; the focus ring on the swatches transitions to Sky blue.
5. D-pad Down to the next button below ("Use Bookshelf View" toggle area). Expected: focus ring renders in Sky blue.
6. D-pad back up to the picker; pick ABS Green; restart the app. Expected: ABS Green still selected after restart.
7. Open the app on a phone or tablet. Expected: no "TV SETTINGS" section.

If any step fails, stop and debug before committing.

- [ ] **Step 4.7: Commit**

```bash
git add pages/settings.vue
git commit -m "feat(tv): add TV Settings section with focus-ring color picker"
```

---

## Task 5: Author manual test checklist

**Files:**
- Create: `docs/superpowers/plans/2026-04-25-tv-focus-color-picker-test-checklist.md`

- [ ] **Step 5.1: Write the checklist**

Create `docs/superpowers/plans/2026-04-25-tv-focus-color-picker-test-checklist.md` with the full content below. This becomes the artifact that gates merge.

```markdown
# TV Focus Color Picker — Manual Test Checklist

**Feature:** TV-only Settings control for D-pad focus-ring color
**Spec:** `docs/superpowers/specs/2026-04-25-tv-focus-color-picker-design.md`
**Plan:** `docs/superpowers/plans/2026-04-25-tv-focus-color-picker.md`
**Primary device:** Google TV Streamer 4K
**Secondary device:** Any phone or tablet (to confirm picker invisibility)

## Setup

1. Sideload the latest `tv-focus-color-setting` branch APK to both devices.
2. On TV: log in to a fresh user OR clear app data first.
3. On phone/tablet: log in to any user.

## Picker visibility and default state

- [ ] T1. Open Settings on TV → "TV SETTINGS" section visible at top with "Focus Ring Color" row.
- [ ] T2. Open Settings on phone/tablet → no "TV SETTINGS" section.
- [ ] T3. Fresh-install state on TV → ABS Green swatch (leftmost) has white outline AND `★` glyph; no other swatch has white outline.
- [ ] T4. All 7 swatches render in the documented colors: ABS Green `#1ad691`, Sky `#3ea6ff`, Amber `#ffb74d`, Red `#ff5252`, Violet `#e040fb`, Yellow `#ffeb3b`, White `#ffffff`.

## D-pad navigation

- [ ] T5. From Settings, D-pad Right traverses all 7 swatches in order.
- [ ] T6. D-pad Left traverses backward through all 7 swatches.
- [ ] T7. While moving across swatches, the green focus ring on the focused swatch matches the *current* selection color (default: ABS Green).
- [ ] T8. D-pad Down from any swatch moves focus to the first row of the next section ("User Interface Settings"); D-pad Up returns to the picker.

## Selection and live update

- [ ] T9. Press Enter on Sky swatch → white outline moves to Sky; focus ring on swatches transitions to Sky immediately.
- [ ] T10. Without leaving Settings, D-pad Down to a button or row in another section → focus ring renders in Sky.
- [ ] T11. Re-select ABS Green → white outline moves back; focus rings transition back to default green.

## Persistence

- [ ] T12. Pick Sky → kill the app (force stop) → relaunch → Settings shows Sky selected; focus rings render in Sky.
- [ ] T13. With User A set to Amber, log out. Log in as User B (fresh) → ABS Green selected (default for new user), not Amber. Log back in as User A → Amber restored.

## Reset behavior

- [ ] T14. Pick Red → pick ABS Green → restart app → ABS Green is the saved state, not Red. (Confirms the implicit-reset path persists correctly.)

## Visual regression — re-run I8 surfaces with non-default color

Set focus color to **Sky `#3ea6ff`**, then verify each of the 8 focus surfaces from the I8 visual checklist renders in Sky (not green) with shape preserved:

- [ ] T15. Library card focus ring (Home page): 3px Sky border overlay, 2px radius.
- [ ] T16. Author card focus (Authors page): 3px Sky border on inner card.
- [ ] T17. Item detail cover art (open a book → focus cover): 3px Sky border overlay.
- [ ] T18. Modal list item (open Filter or Sort modal, focus an option): 3px Sky **left accent** + faint white tint, no outline.
- [ ] T19. Side drawer item (open hamburger drawer, focus a row): 3px Sky left accent + tint.
- [ ] T20. Generic button/input (Settings page button, Connect page input): 2px tight Sky outline, -2px offset.
- [ ] T21. Settings dropdown row (e.g. Theme): 2px Sky border on inner input, 4px radius.
- [ ] T22. Player controls (start a book, focus play button + jump icons): 2px **round** Sky outline.

Negative cases (should NOT show Sky outline):

- [ ] T23. Cover art with fullscreen-cover modal open → focus ring hidden (modal-open suppression rule).
- [ ] T24. Modal items show only Sky left accent, not full Sky outline.
- [ ] T25. (Optional, if podcasts present) Episode description hyperlink → no Sky outline.

After T25, restore ABS Green for downstream regression tests.

## Adjacent-code regression smoke

- [ ] T26. Run the full 42-item TV checklist at `docs/superpowers/plans/2026-04-20-v1.0.5-v1.0.6-combined-test-checklist.md`. All items pass with the picker landed.

## Sign-off

- [ ] All T1-T26 pass on Google TV Streamer 4K.
- [ ] T2 passes on at least one phone or tablet.
```

- [ ] **Step 5.2: Commit**

```bash
git add docs/superpowers/plans/2026-04-25-tv-focus-color-picker-test-checklist.md
git commit -m "docs(tv): add focus-color-picker manual test checklist"
```

---

## Task 6: Final QA + release prep

**Files:**
- (None — this task is QA execution and any fixes that fall out of it.)

- [ ] **Step 6.1: Walk through the manual test checklist**

Open `docs/superpowers/plans/2026-04-25-tv-focus-color-picker-test-checklist.md`. Execute all T1-T26 items in order. Check each box as it passes. If any item fails, stop, fix the underlying bug on this branch, rebuild, and re-run from that item forward.

- [ ] **Step 6.2: Update CHANGELOG.md**

Add an entry to `CHANGELOG.md` under the `[Unreleased]` section (or create the section if absent), following Keep-a-Changelog format:

```markdown
### Added

- TV-only Settings control to choose the D-pad focus-ring color from 7 high-contrast presets (ABS Green default, Sky, Amber, Red, Violet, Yellow, White). Selection persists per user across app restarts.
```

If the file doesn't have an `[Unreleased]` section yet, add one above the most recent version header.

- [ ] **Step 6.3: Refresh `docs/TV_FOCUS_SYSTEM.md`**

Open `docs/TV_FOCUS_SYSTEM.md`. Add (or update) a section noting the runtime-overridable focus color:

> **User-customizable focus color (v1.0.8+):** The `--tv-focus-color` CSS variable defined in `assets/css/tv-focus.css` is now overridable at runtime. `pages/settings.vue` exposes a TV-only "Focus Ring Color" picker (`components/ui/TvFocusColorPicker.vue`); selection persists in `state.user.settings.tvFocusColor` and is applied by a `user-settings` event-bus subscriber inside `plugins/tv-navigation.js`.

Wording is approximate — match the existing tone of `TV_FOCUS_SYSTEM.md`.

- [ ] **Step 6.4: Commit doc updates**

```bash
git add CHANGELOG.md docs/TV_FOCUS_SYSTEM.md
git commit -m "docs(tv): document focus-color picker in CHANGELOG and TV_FOCUS_SYSTEM"
```

- [ ] **Step 6.5: Final clean build**

```bash
cd C:/Users/jscha/source/repos/abs-app
npm run generate
npx cap sync android
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk" ./android/gradlew -p ./android assembleRelease
```

The release APK `abs-android-tv-release-{version}.apk` is the merge candidate.

- [ ] **Step 6.6: Stop here for user decision**

Do NOT push, merge, bump version, or cut a release. The user will decide whether to roll this into the next versioned release. Versioning convention on this fork: patch bump (e.g. 1.0.7 → 1.0.8) for bug fixes, minor bump (e.g. 1.0.7 → 1.1.0) for new features such as this one.

---

## Out of scope (do not implement)

- Cross-device sync via server schema (blocked by maintainer relationship — same blocker as TODO #11).
- Free-form color picker / custom hex entry (rejected during brainstorm).
- `★` markers on non-default swatches.
- i18n routing of "TV Settings" / "Focus Ring Color" strings (deferred until after upstream PR acceptance).
