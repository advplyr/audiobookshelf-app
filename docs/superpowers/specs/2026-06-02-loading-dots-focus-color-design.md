# Loading-Overlay Dots Follow Focus Color (TV) — Design

**Date:** 2026-06-02
**Owner:** Jamie Chapman
**Status:** Approved (design). Implementation **queued for the v1.0.11 bundle** (post-v1.0.10).

## Problem / Goal
On Android TV the content-loading overlay — `components/ui/LoadingIndicator.vue`, animated dots on top with "Loading…"/text underneath — renders its dots in a hardcoded green (`bg-green-500`). The TV build has a user-selectable focus-ring color ("Focus Ring Color" picker: ABS Green, Sky, Amber, Red, Violet, Yellow, White). The loading dots should match the chosen focus color so the TV UI reads as one consistent accent color.

## Scope
**In:** Every `LoadingIndicator` instance, TV only — bookshelf initial load (the canonical "Loading…"), plus author / item / settings / playlist / collection / add-podcast / comic-reader loads.

**Out (explicit decisions):**
- The circular `la-ball` spinner (`components/widgets/LoadingSpinner.vue`) — **stays white** for guaranteed contrast over cover art and dark dimming overlays.
- Phone / tablet — unchanged (keep `bg-green-500`); the picker is TV-only.
- The duplicate/orphan `.loader-dots` CSS in `components/app/Appbar.vue:115-154` (no matching markup) — separate tech-debt cleanup, tracked independently.

## Approach — A: stable hook + existing CSS variable
Reuse the focus-color single source of truth: `--tv-focus-color`, defined on `:root.android-tv` in `assets/css/tv-focus.css` and live-updated by the picker via `document.documentElement.style.setProperty('--tv-focus-color', value)`.

1. Tag the dots wrapper in `LoadingIndicator.vue` with a dedicated hook class:
   `<div class="loader-dots tv-focus-dots …">`
2. Add one TV-gated rule to `assets/css/tv-focus.css`:
   ```css
   /* Loading-overlay dots follow the chosen focus color (TV only) */
   .android-tv .tv-focus-dots > div { background-color: var(--tv-focus-color) !important; }
   ```

**Rejected alternatives**
- **B — blanket `.android-tv .loader-dots div`**: leans on the shared `.loader-dots` class, which the Appbar orphan CSS proves is not reliably unique. Against the I5 (#8) stable-hook direction this ships beside.
- **C — JS-set inline color in the focusColor apply path**: imperative and timing-fragile; redundant when the color is already a CSS variable.

## Behavior
- **TV-only:** no `.android-tv` class on phone/tablet → dots keep `bg-green-500`.
- **Live-follow:** CSS-variable inheritance → picker changes update the dots instantly, zero JS.
- **Default (no pick):** `--tv-focus-color` defaults to ABS Green `#1ad691`, so on TV the dots become the brand green (vs Tailwind `green-500` today).
- **Init timing:** depends on `.android-tv` + the variable being set at paint — the same boot dependency as all focus styling. The bundle's #5 (CSS-injection-race fix) hardens exactly this. Worst case pre-init: dots briefly show the green default.

## Files touched
- `components/ui/LoadingIndicator.vue` — add `tv-focus-dots` class (1 line).
- `assets/css/tv-focus.css` — add 1 rule (2 lines).

## Landing / sequencing
Fold into the **v1.0.11 bundle** alongside #5 (I2 CSS-injection race), #7 (I4 rect-cache), #8 (I5 data-attr selectors): one branch off post-v1.0.10 `android-tv-dpad-navigation`, single APK, single 13-batch smoke pass. Must land **before** the upstream PR swap (#1843 → decomposed series).

## Test / smoke notes
Rides the v1.0.11 13-batch smoke, plus targeted checks:
1. On TV with a non-default focus color (e.g. Sky), trigger a loading overlay (cold launch → bookshelf initial load; or open an author/item) → dots render in the chosen color.
2. Change the focus color in Settings, re-trigger an overlay → dots reflect the new color (live, no restart).
3. On phone → dots remain green (no `.android-tv`).
4. Default state (ABS Green selected) → dots render brand green `#1ad691`.
