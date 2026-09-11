# PR 06 — TV navigation engine integration (listeners + dispatcher)

Part of a 10-PR series adding Android TV support to audiobookshelf-app,
replacing the original PR #1843.

## Full series plan

See **[bilbospocketses/abs-app — PR_DECOMPOSITION_PLAN.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/PR_DECOMPOSITION_PLAN.md)**
for the complete 10-PR breakdown, dependency graph, and rationale.

## This PR's scope

Activates the engine that PR5 shipped dormant. Two files under `plugins/tv/`
plus a one-line plugin registration in `nuxt.config.js`:

- `listeners.js` — registers the global keydown listener and the router,
  store, and event-bus subscribers that drive focus.
- `index.js` — the Nuxt plugin entry point. It returns immediately unless
  `isAndroidTv` is set, so on phones and tablets nothing is installed and
  behavior is byte-identical to upstream.
- `nuxt.config.js` — registers `plugins/tv/index.js` as a client-mode plugin.
- `store/index.js` — adds the `resetLastBookshelfScrollData` mutation that
  `listeners.js` commits when a library switch should drop stale scroll
  positions. It lives here rather than in PR1 because its only caller is
  the engine.

This is the commit where D-pad navigation actually starts working on a TV.
Keeping it separate from PR5 means the wiring can be reviewed on its own,
against a function library that was already read.

## Architecture context (fork-hosted)

- [TV_FOCUS_SYSTEM.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/TV_FOCUS_SYSTEM.md) — developer reference for the focus model and dispatch order.
- [TV_USER_GUIDE.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/TV_USER_GUIDE.md) — end-user TV feature documentation.

## Testing

Verified on a Google TV Streamer 4K across a 13-batch manual smoke suite
covering the library grid, item detail, podcast episodes, author pages, logs,
stats, settings, and the player. Also verified on an Android phone that the
plugin no-ops and navigation is unchanged.

## Relationship to the series

- **Depends on:** PR5 (this branch is stacked on it, so the diff includes
  PR5's modules until PR5 merges) and PR1 (`isAndroidTv` detection state).
- **Required by:** PRs 7, 8, and 9, which add features on top of the live engine.
- **Layer:** 2 of 3
