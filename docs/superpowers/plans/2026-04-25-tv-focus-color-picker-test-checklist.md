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

- [x] T1. Open Settings on TV → "TV SETTINGS" section visible at top with "Focus Ring Color" row.
- [x] T2. Open Settings on phone/tablet → no "TV SETTINGS" section.
- [x] T3. Fresh-install state on TV → ABS Green swatch (leftmost) has `★` glyph; no other swatch has `★`. No outer ring on any swatch unless focused.
- [x] T4. All 7 swatches render in the documented colors: ABS Green `#1ad691`, Sky `#3ea6ff`, Amber `#ffb74d`, Red `#ff5252`, Violet `#e040fb`, Yellow `#ffeb3b`, White `#ffffff`.

## D-pad navigation

- [x] T5. From Settings, D-pad Right traverses all 7 swatches in order.
- [x] T6. D-pad Left traverses backward through all 7 swatches.
- [x] T7. Focused swatch shows a visible ring (black inner edge + outer band in the *current* focus color, default ABS Green). Ring is readable on every swatch including the one whose fill matches the focus color.
- [x] T8. D-pad Down from any swatch moves focus to the first row of the next section ("User Interface Settings"); D-pad Up returns to the picker.

## Selection and live update

- [x] T9. Press Enter on Sky swatch → `★` moves to Sky; focus ring outer band on the focused swatch retints to Sky immediately.
- [x] T10. Without leaving Settings, D-pad Down to a button or row in another section → focus ring renders in Sky.
- [x] T11. Re-select ABS Green → `★` moves back to ABS Green; focus rings transition back to default green.

## Persistence

- [x] T12. Pick Sky → kill the app (force stop) → relaunch → Settings shows Sky selected; focus rings render in Sky.
- [x] T13. The chosen color is **device-wide**, matching every other entry in `state.settings` (sort order, playback rate, etc.). Verify: with User A set to Amber, log out → log in as User B → User B's picker shows Amber selected (★ on Amber), focus rings render in Amber. Log back in as User A → still Amber. (No per-user separation by design — single `userSettings` Capacitor Preferences key.)

## Reset behavior

- [x] T14. Pick Red → pick ABS Green → restart app → ABS Green is the saved state, not Red. (Confirms the implicit-reset path persists correctly.)

## Visual regression — re-run I8 surfaces with non-default color

Set focus color to **Sky `#3ea6ff`**, then verify each of the 8 focus surfaces from the I8 visual checklist renders in Sky (not green) with shape preserved:

- [x] T15. Library card focus ring (Home page): 3px Sky border overlay, 2px radius.
- [x] T16. Author card focus (Authors page): 3px Sky border on inner card.
- [x] T17. Item detail cover art (open a book → focus cover): 3px Sky border overlay.
- [x] T18. Modal list item (open Filter or Sort modal, focus an option): 3px Sky **left accent** + faint white tint, no outline.
- [x] T19. Side drawer item (open hamburger drawer, focus a row): 3px Sky left accent + tint.
- [x] T20. Generic button/input (Settings page button, Connect page input): 2px tight Sky outline, -2px offset.
- [x] T21. Settings dropdown row (e.g. Theme): 2px Sky border on inner input, 4px radius.
- [x] T22. Player controls (start a book, focus play button + jump icons): 2px **round** Sky outline.

Negative cases (should NOT show Sky outline):

- [x] T23. Cover art with fullscreen-cover modal open → focus ring hidden (modal-open suppression rule).
- [x] T24. Modal items show only Sky left accent, not full Sky outline.
- [x] T25. (Optional, if podcasts present) Episode description hyperlink → no Sky outline.

After T25, restore ABS Green for downstream regression tests.

## Adjacent-code regression smoke

- [x] T26. Run the full 42-item TV checklist at `docs/superpowers/plans/2026-04-20-v1.0.5-v1.0.6-combined-test-checklist.md`. All items pass with the picker landed.

## Sign-off

- [x] All T1-T26 pass on Google TV Streamer 4K.
- [x] T2 passes on at least one phone or tablet.
