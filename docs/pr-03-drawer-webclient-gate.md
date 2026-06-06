# PR 03 — Hide "Go to Web Client" on Android TV

Part of a 10-PR series adding Android TV support to audiobookshelf-app,
replacing the original PR #1843.

## Full series plan

See **[bilbospocketses/abs-app — PR_DECOMPOSITION_PLAN.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/PR_DECOMPOSITION_PLAN.md)**
for the complete 10-PR breakdown, dependency graph, and rationale.

## This PR's scope

A one-item behavior gate in the side drawer (`components/app/SideDrawer.vue`).
The "Go to Web Client" action opens the server URL in an external browser;
Android TV devices typically have no browser, so the action dead-ends. This PR
hides that single nav item when running on a TV, gated on the `isAndroidTv`
Vuex flag. Phone/tablet behavior is unchanged — the item still appears and works.

~19 LOC: an `isAndroidTv` computed plus wrapping the existing web-client
`items.push(...)` in `if (!this.isAndroidTv)`.

## Architecture context (fork-hosted)

- [TV_USER_GUIDE.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/TV_USER_GUIDE.md) — end-user TV feature documentation.

## Testing

Verified on a Google TV Streamer 4K (the item is hidden) and an Android phone
(the item is present and opens the web client as before).

## Relationship to the series

- **Depends on:** PR1 (`isAndroidTv` flag); the gate is inert until that lands.
- **Note:** also edits `components/app/SideDrawer.vue`, which PR2 touches in
  different regions — whichever of the two merges second may need a trivial rebase.
- **Wave:** 1 of 3
