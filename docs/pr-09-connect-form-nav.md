# PR 09 — Server connect form D-pad navigation

Part of a 10-PR series adding Android TV support to audiobookshelf-app,
replacing the original PR #1843.

## Full series plan

See **[bilbospocketses/abs-app — PR_DECOMPOSITION_PLAN.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/PR_DECOMPOSITION_PLAN.md)**
for the complete 10-PR breakdown, dependency graph, and rationale.

## This PR's scope

D-pad navigation for `components/connection/ServerConnectForm.vue`, the first
screen a new user sees. On a TV this screen is unusually demanding: it mixes
text inputs, a saved-server list, and action buttons, and it is reached before
any library UI exists, so the generic page handlers have nothing to work with.

The changes are one cohesive feature rather than a set of independent tweaks:
- Directional movement between the URL/username/password inputs, the saved
  server entries, and the submit/cancel actions.
- Auto-focus with the on-screen keyboard on every form transition and on error
  recovery, so a TV user is never left with focus nowhere.
- Username labels above each saved server entry, so switching users on a shared
  TV does not require opening each one.
- The server list is positioned below the logo in a scrollable column, since a
  TV screen is wide but the form is narrow.

All of it is TV-gated; phone and tablet rendering and behavior are unchanged.

## Architecture context (fork-hosted)

- [TV_USER_GUIDE.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/TV_USER_GUIDE.md) — end-user TV feature documentation, including first-run connect flow.

## Testing

Verified on a Google TV Streamer 4K: fresh install through to a connected
library using only the remote, including a deliberate bad-URL error and
recovery, and switching between two saved users. Verified on an Android phone
that the connect form is unchanged.

## Relationship to the series

- **Depends on:** PR2 (keyboard hygiene on the shared input components) and
  PR6 (live engine).
- **Layer:** 3 of 3
