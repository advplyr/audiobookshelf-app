# PR — Android back-button handling (drawer dismiss + TV exit)

A small related PR alongside the 10-PR Android TV series (see
[PR_DECOMPOSITION_PLAN.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/PR_DECOMPOSITION_PLAN.md)).
Self-contained; not part of the numbered wave sequence.

## This PR's scope

Two improvements to the Android hardware **back-button** handler in
`plugins/init.client.js`:

1. **Dismiss the side drawer on Back** (all Android) — if the side drawer is
   open, Back closes it instead of navigating. General UX fix, not TV-specific.
2. **Reliable exit on Android TV** — when logged out and on Home, treat Back as
   "exit app" regardless of history depth. Stale history entries left from
   before logout could otherwise trap the user on a TV with no other way out.
   Gated on the `android-tv` class (set in PR1), so phones are unaffected.

## Testing

Phone: Back closes the drawer when open, otherwise behaves as before. Google TV
Streamer 4K: from a logged-out Home screen, Back prompts the exit dialog.

## Relationship to the series

- **Depends on:** PR1 (the `android-tv` class) for the TV-exit branch; the
  drawer-dismiss part is independent.
- **Layer:** none — a companion PR outside the numbered series, independent of all of it.
