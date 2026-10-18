# PR 08 — TV settings section + focus-ring color picker

Part of a 10-PR series adding Android TV support to audiobookshelf-app,
replacing the original PR #1843.

## Full series plan

See **[bilbospocketses/abs-app — PR_DECOMPOSITION_PLAN.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/PR_DECOMPOSITION_PLAN.md)**
for the complete 10-PR breakdown, dependency graph, and rationale.

## This PR's scope

Adds a "TV Settings" section to `pages/settings.vue`, shown only when
`isAndroidTv` is set, containing a single control: a focus-ring color picker.

- `components/ui/TvFocusColorPicker.vue` — a new swatch-row component with a
  default marker and a white-outline selection state. It is D-pad navigable
  and emits the chosen hex.
- `pages/settings.vue` — the TV-only section plus an `isAndroidTv` computed, a
  `tvFocusColor` computed, and a `setTvFocusColor` method that dispatches
  `user/updateUserSettings`. The `mt-10` spacer on the next section header is
  TV-gated, so the phone layout is byte-identical to before.

The chosen color is written into the `--tv-focus-color` CSS variable that
`assets/css/tv-focus.css` (PR4) consumes for every focus ring. Focus rings are
the only way to tell where you are on a TV, and the default green does not read
well against every cover art or theme — hence making it configurable.

**On i18n:** the section header and row label are hardcoded English here rather
than routed through `$strings`, to avoid touching `strings/en-us.json` and the
translation workflow in a TV PR. Happy to move them into the string catalog if
you would prefer that before merge.

## Architecture context (fork-hosted)

- [TV_FOCUS_SYSTEM.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/TV_FOCUS_SYSTEM.md) — how the focus color propagates to the CSS variable.
- [TV_USER_GUIDE.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/TV_USER_GUIDE.md) — end-user TV feature documentation.

## Testing

Verified on a Google TV Streamer 4K: the section appears only on TV, each
swatch is reachable with the D-pad, and the selected color applies to focus
rings immediately and survives an app restart. Verified on an Android phone
that the settings page is unchanged.

## Relationship to the series

- **Depends on:** PR4 (the `tvFocusColor` settings key and `tv-focus.css`) and
  PR6 (live engine to apply the color and to navigate the picker).
- **Note:** also edits `pages/settings.vue`, which PR2 touches in different
  regions; whichever merges second may need a trivial rebase.
- **Layer:** 3 of 3
