# PR 04 — CSS foundation (focus-ring system)

Part of a 10-PR series adding Android TV support to audiobookshelf-app,
replacing the original PR #1843.

## Full series plan

See **[bilbospocketses/abs-app — PR_DECOMPOSITION_PLAN.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/PR_DECOMPOSITION_PLAN.md)**
for the complete 10-PR breakdown, dependency graph, and rationale.

## This PR's scope

The visual focus-ring system for TV — all scoped under `:root.android-tv` / `.android-tv`,
so it is **completely inert on phone/tablet** (the `android-tv` class is only
present on TV, added in PR1):

- **`assets/css/tv-focus.css`** — focus-ring styles plus the `--tv-focus-color`
  CSS custom property (default `#1ad691`), defined on `:root.android-tv`.
- **`nuxt.config.js`** — register `tv-focus.css` in the `css` array.
- **`store/user.js`** — a `tvFocusColor` user setting (default `#1ad691`) that
  drives the CSS variable (the picker UI to change it lands in a later PR).
- **`components/ui/LoadingIndicator.vue`** — a `tv-focus-dots` class hook so the
  loading-overlay dots adopt the chosen focus color on TV (the rule lives in
  tv-focus.css; inert elsewhere).

Nothing drives the focus rings yet — no JS attaches focus changes until the
engine PRs — so there is no visible change until then.

## Architecture context (fork-hosted)

- [TV_FOCUS_SYSTEM.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/TV_FOCUS_SYSTEM.md) — focus-system architecture.

## Testing

Phone smoke: no visual change (rules are `.android-tv`-scoped). On a Google TV
Streamer 4K: the `--tv-focus-color` variable is present on `:root.android-tv`.

## Relationship to the series

- **Depends on:** nothing (inert until PR1's `android-tv` class and the engine).
- **Blocks:** PR8 (the settings color picker reads/writes `tvFocusColor` and the CSS variable).
- **Wave:** 1 of 3
