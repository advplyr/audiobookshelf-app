# PR 02 — Keyboard hygiene + foundational TV DOM markup

Part of a 10-PR series adding Android TV support to audiobookshelf-app,
replacing the original PR #1843.

## Full series plan

See **[bilbospocketses/abs-app — PR_DECOMPOSITION_PLAN.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/PR_DECOMPOSITION_PLAN.md)**
for the complete 10-PR breakdown, dependency graph, and rationale.

## This PR's scope

Additive, behavior-neutral DOM changes across ~25 shared components and pages so
the existing UI is keyboard/D-pad navigable. **Every change is inert on
touch/pointer devices** — `tabindex` and `@keydown.enter.prevent` only affect
keyboard focus, and the data-attribute / `id` hooks are read by nothing until
the navigation engine (later PRs) is present. Phone/tablet behavior is byte-identical.

Two kinds of change, both safe to land before anything that consumes them:

- **Keyboard hygiene** — `tabindex` (focusability) plus `@keydown.enter.prevent`
  (Enter fires the same handler as click) on cards, tables, toolbars, modal
  options, UI inputs/toggles, side-drawer items, the audio-player controls, and
  detail-page actions.
- **Inert TV DOM hooks** — stable `data-tv-target="play-button"` /
  `data-tv-overlay="side-drawer"` attributes and scroll-container `id`s
  (`#settings-page`, `#stats-page`, `#logs-container`, `#account-page`,
  `#episode-page`, `#item-cover`, `#manage-files-page`). These are a stable
  contract the navigation engine reads later — in place of fragile CSS-class
  selectors — and have no effect on their own.

## Architecture context (fork-hosted)

- [TV_FOCUS_SYSTEM.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/TV_FOCUS_SYSTEM.md) — the focus/navigation architecture and the `data-*` contract these hooks satisfy.

## Testing

Verified on an Android phone that every modified element behaves exactly as
before via tap, and on a Google TV Streamer 4K that Enter activates focusable
elements without double-firing.

## Relationship to the series

- **Depends on:** nothing
- **Blocks:** PR9 (connect-form D-pad nav builds on this hygiene), PR10 (author
  cards); the inert hooks are later consumed by PR5/PR6 (the engine).
- **Wave:** 1 of 3
