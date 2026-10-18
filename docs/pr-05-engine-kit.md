# PR 05 — TV navigation engine kit (utility modules + page handlers)

Part of a 10-PR series adding Android TV support to audiobookshelf-app,
replacing the original PR #1843.

## Full series plan

See **[bilbospocketses/abs-app — PR_DECOMPOSITION_PLAN.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/PR_DECOMPOSITION_PLAN.md)**
for the complete 10-PR breakdown, dependency graph, and rationale.

## This PR's scope

The D-pad navigation engine as a **library only**. Seventeen modules under
`plugins/tv/` that export functions and attach to nothing at runtime — no
listener is registered, no router hook is installed, no store is touched. On
its own this PR changes no behavior on any device; it is dormant until PR6
wires it up.

The split is deliberate: it lets the function library be reviewed separately
from the wiring that turns it on. Nine utility modules (focus memory, spatial
navigation, scroll helpers, visibility tests, overlay focus, focus entry,
focus color, selectors, shared context) and eight per-page handlers under
`plugins/tv/pageHandlers/`. Every file is under 235 LOC.

## Architecture context (fork-hosted)

- [TV_FOCUS_SYSTEM.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/TV_FOCUS_SYSTEM.md) — developer reference for the focus model, the fingerprint restore tiers, and the virtualizer interaction.
- [TV_USER_GUIDE.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/TV_USER_GUIDE.md) — end-user TV feature documentation.

## Testing

The modules are exercised end-to-end by PR6 and verified on a Google TV
Streamer 4K across a 13-batch manual smoke suite (library grid, item detail,
podcast episodes, author pages, logs, stats, settings, player). This PR alone
is inert, so it is verified by build only: `npm run generate` succeeds and
phone/tablet behavior is byte-identical to upstream.

## Relationship to the series

- **Depends on:** PR1 (`isAndroidTv` detection state).
- **Required by:** PR6, which registers these functions and activates them.
- **Layer:** 2 of 3
