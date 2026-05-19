# TV Focus Ring Color Picker — Design

**Date:** 2026-04-25
**Status:** Design approved, ready for implementation plan
**Related TODO:** `todo_abs_tv.md` #10
**Depends on:** I8 (extract focus color to CSS variable) — shipped

## Summary

Add a TV-only Settings control that lets the user pick the D-pad focus-ring color from a curated set of 7 high-contrast presets. Selection persists device-wide (same model as every other `state.settings` key in this app), applies live with no reload, and is invisible on phone / tablet.

## Goals

- Give TV users a way to change the focus-ring color from the default `#1ad691` to one of 6 alternates suited to color-vision needs and personal preference.
- Apply changes live (no app restart, no page reload).
- Persist across app restarts on this device. (Note: `userSettings` is a single device-wide key in Capacitor Preferences — every `state.settings` entry is shared across users on the same device. `tvFocusColor` inherits that contract; it is NOT per-user.)
- Stay zero-footprint on phone / tablet.
- Stay zero-touch on the server (no schema change, no maintainer coordination).

## Non-goals

- Free-form color choice (no native `<input type="color">`, no hex entry).
- Cross-device sync (server-side persistence is out of scope; tracked separately under TODO #11's coordination pattern).
- Theme-wide color changes (this controls the focus ring only, not Tailwind `accent`, not button colors).

## Requirements (locked during brainstorm)

### Control type — curated preset swatches

A horizontal row of 7 color swatches. D-pad arrows traverse, Enter selects. The selected swatch is marked with a white 2px outline at +2px offset. The default swatch additionally carries a `★` glyph so the original is identifiable.

### Preset palette

| Hex | Name | Default? |
| --- | --- | --- |
| `#1ad691` | ABS Green | ✓ |
| `#3ea6ff` | Sky | |
| `#ffb74d` | Amber | |
| `#ff5252` | Red | |
| `#e040fb` | Violet | |
| `#ffeb3b` | Yellow | |
| `#ffffff` | White | |

All 7 hexes pass ≥4.5:1 contrast against the ABS dark background `#232323`.

### Storage — Vuex `store/user.js` settings

`tvFocusColor` is a new key on `state.settings`. It rides the existing `updateUserSettings` / `loadUserSettings` / `$localStore.setUserSettings` pipeline alongside `playbackRate`, `collapseSeries`, etc. Persistence is local-storage-backed by Capacitor under the single device-wide `'userSettings'` key — same contract every other `state.settings` entry in this app uses. The setting therefore persists across app restarts but is **not** namespaced per user; switching users on the same device leaves the chosen color in place.

### Settings page placement — top of `pages/settings.vue`

A new `<template v-if="isAndroidTv">` block is added at the top of `pages/settings.vue`, before the existing "User Interface Settings" section. The block has its own section header following the established `uppercase text-xs font-semibold text-fg-muted` pattern. Section header text: `TV Settings`.

### Reset behavior — implicit

No separate "Reset to default" button. Re-selecting the ABS Green swatch is the reset path; the `★` marker makes it discoverable.

### TV-only gating — `v-if="isAndroidTv"`

The TV Settings section, including the picker, is gated by `v-if="isAndroidTv"` in `pages/settings.vue`. Phone and tablet users never see it. The CSS variable `--tv-focus-color` is already scoped under `:root.android-tv` in `assets/css/tv-focus.css`, so even a stale stored value cannot leak onto a non-TV device.

## Architecture

### Files touched

| File | Change |
| --- | --- |
| `components/ui/TvFocusColorPicker.vue` | **NEW** — picker component (swatch row, ★ marker, selected outline) |
| `pages/settings.vue` | Add TV Settings section at top, render picker with v-model |
| `store/user.js` | Add `tvFocusColor: '#1ad691'` to initial `state.settings` |
| `plugins/tv-navigation.js` | Add `user-settings` event subscriber that writes `--tv-focus-color` CSS variable on documentElement |
| `assets/css/tv-focus.css` | (no change — variable already in place from I8) |

### Component contract — `TvFocusColorPicker.vue`

**Props**

- `value: String` — currently selected hex color (v-model compatible via Vue 2 `value` / `input`).

**Emits**

- `input(hex: String)` — fired when the user selects a swatch.

**Internal data**

```js
PRESETS: [
  { hex: '#1ad691', name: 'ABS Green', isDefault: true },
  { hex: '#3ea6ff', name: 'Sky' },
  { hex: '#ffb74d', name: 'Amber' },
  { hex: '#ff5252', name: 'Red' },
  { hex: '#e040fb', name: 'Violet' },
  { hex: '#ffeb3b', name: 'Yellow' },
  { hex: '#ffffff', name: 'White' }
]
```

**Markup shape**

- Row container holding 7 swatch `<button>` elements.
- Each swatch: `tabindex="0"`, `:style="{ backgroundColor: preset.hex }"`, `:aria-label="`Focus color: ${preset.name}`"`, `@click` and `@keydown.enter.prevent` → `$emit('input', preset.hex)`.
- Selected swatch (`value === preset.hex`): white 2px outline + 2px offset.
- Default swatch (`preset.isDefault === true`): `★` glyph rendered as overlay (small, top-right, semi-transparent on swatch background).
- Each swatch picks up the existing `.android-tv button:focus` outline rule from `tv-focus.css` for free — D-pad focus shows the *current* `--tv-focus-color`, which updates live as the user selects.

### Data flow

**On app start (load)**

1. `store/user.js` `loadUserSettings` action runs as part of normal app boot.
2. Reads `$localStore.getUserSettings()` → merges `tvFocusColor` into `state.settings` (falls back to `#1ad691` initial state if absent).
3. Commits `setSettings`, emits `user-settings` on `$eventBus`.
4. New subscriber inside `plugins/tv-navigation.js` (only attaches when `android-tv` class is present on `<html>`) listens for `user-settings`. The subscriber validates the incoming value against a hard-coded `VALID_HEXES` array (the 7 preset hex strings); if the value isn't in the list, it falls back to `#1ad691`:
   ```js
   const VALID_HEXES = ['#1ad691', '#3ea6ff', '#ffb74d', '#ff5252', '#e040fb', '#ffeb3b', '#ffffff']
   const hex = VALID_HEXES.includes(settings.tvFocusColor) ? settings.tvFocusColor : '#1ad691'
   document.documentElement.style.setProperty('--tv-focus-color', hex)
   ```
5. CSS variable now overrides the `:root.android-tv` default in `tv-focus.css`. Every focus ring renders the chosen color.

**On user picks a swatch**

1. `TvFocusColorPicker` emits `input` with the new hex.
2. `pages/settings.vue` v-model handler dispatches `user/updateUserSettings` with `{ tvFocusColor: hex }`.
3. `updateUserSettings` detects the change, commits, persists via `$localStore.setUserSettings`, emits `user-settings`.
4. Subscriber from step 4 above fires, CSS variable updates, focus ring on the picker itself updates live in front of the user.

**On user switch (logout / login)**

- `loadUserSettings` re-reads the single device-wide `'userSettings'` key, so the previous user's `tvFocusColor` carries over to the new user. This matches how every other `state.settings` entry behaves (sort order, playback rate, sleep timer length, etc.) and is the existing app contract. The CSS variable therefore stays at whatever was last picked on this device.

## Error handling and edge cases

| Case | Behavior |
| --- | --- |
| Invalid hex in storage | Subscriber validates against `VALID_HEXES`; if not in the list, applies `#1ad691` to the CSS variable and dispatches a corrective `updateUserSettings({ tvFocusColor: '#1ad691' })` to overwrite the bad value. |
| Setting absent on first run (existing user upgrading) | `state.settings.tvFocusColor` defaults to `#1ad691` in initial Vuex state. `loadUserSettings` only overwrites keys that exist in local storage, so first-run users get the default with no migration. |
| `user-settings` event fires before subscriber attaches | `:root.android-tv { --tv-focus-color: #1ad691 }` in `tv-focus.css` already provides a default, so focus rings render correctly in the gap. Worst case is one render cycle of the default before the user's color applies — invisible on cold start. |
| Phone/tablet briefly toggles into TV emulation (developer scenario) | Picker not rendered (no `isAndroidTv`), but the variable applies if `android-tv` class lands. Acceptable; not a user-facing path. |

No error states need a user-visible message. All edge cases self-recover silently.

## Testing

This codebase does not have an automated test runner wired up for Vue components — established precedent is manual checklist validation, mirrored here.

A new manual checklist will be authored at `docs/superpowers/plans/2026-04-25-tv-focus-color-picker-test-checklist.md`. Items:

1. Picker renders only on TV (no section on phone / tablet, present on TV).
2. Default state — fresh install: ABS Green selected, `★` marker visible.
3. D-pad Right/Left traverses all 7 swatches; focus ring on swatches matches the *current* selected color.
4. Enter on a non-default swatch → focus ring color updates immediately on the picker itself.
5. Live propagation — without leaving Settings, D-pad Down to a button below; that button's focus ring uses the newly-chosen color.
6. Persistence across app restart — pick Sky → relaunch → Sky still selected, rings render in Sky.
7. Persistence across user switch — User A picks Amber, logs out; User B logs in, sees default Green; A logs back in, Amber restored.
8. Reset by re-selecting default — pick Red → pick ABS Green → confirms implicit reset works.
9. Visual regression on all focus surfaces with non-default color (e.g. Sky): re-run the I8 8-row checklist (cards, modals, side drawer, cover art, settings dropdowns, player controls, author cards, item-detail cover) with Sky chosen; all 8 surfaces render in Sky, no surface stays green, all shapes preserved (round outlines stay round, left-accent stays left-accent).
10. Reset persistence — pick Yellow → pick ABS Green → restart → confirms Green is the saved value.

**Devices**: Google TV Streamer 4K (primary). One phone or tablet to confirm picker invisibility.

**Pre-merge smoke**: full pass of `docs/superpowers/plans/2026-04-20-v1.0.5-v1.0.6-combined-test-checklist.md` (42 items) on Google TV Streamer 4K. Small change but it adds Settings UI and a new event-bus subscriber that runs in TV nav code; smoke covers regressions in adjacent nav.

## Out of scope

- Cross-device sync via server-side user profile (blocked by maintainer relationship; same blocker as TODO #11).
- Theme-wide color customization (separate, larger feature).
- Free-form color picker / custom hex entry (rejected during brainstorm — D-pad ergonomics + invisible-color risk).
- Adding `★`-marker styling for non-default swatches (only ABS Green carries the marker; YAGNI for "favorites" or similar).

## Open questions

None at design time. Implementation plan should resolve final markup details (exact `★` placement, swatch sizing) when wiring up the component.
