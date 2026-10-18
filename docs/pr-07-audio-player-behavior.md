# PR 07 — Audio player behavior on Android TV

Part of a 10-PR series adding Android TV support to audiobookshelf-app,
replacing the original PR #1843.

## Full series plan

See **[bilbospocketses/abs-app — PR_DECOMPOSITION_PLAN.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/PR_DECOMPOSITION_PLAN.md)**
for the complete 10-PR breakdown, dependency graph, and rationale.

## This PR's scope

Four TV-gated behavior changes in `components/app/AudioPlayer.vue`, each
guarded on the `isAndroidTv` Vuex flag so phone and tablet behavior is
unchanged:

- **Close instead of minimize.** On TV, leaving fullscreen closes the player
  rather than collapsing it to the mini bar. The mini player is very hard to
  navigate back to with a D-pad — there is no taskbar or system tray to reach
  it from. A user setting to choose between the two behaviors is planned.
- **Keep-awake during playback.** Holds a screen-wake lock while audio is
  playing so the Ambient Mode screensaver does not kick in, which otherwise
  kills playback on Chromecast with Google TV. Uses the
  `@capacitor-community/keep-awake` plugin already in `package.json`.
- **Hide History in the player menu.** The History item routes to a separate
  page, which collapses the fullscreen player into the mini-player state that
  TV no longer uses. History stays reachable from book detail pages.
- Screen-wake state is released when playback closes.

## Architecture context (fork-hosted)

- [TV_USER_GUIDE.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/TV_USER_GUIDE.md) — end-user TV feature documentation, including player behavior.

## Testing

Verified on a Google TV Streamer 4K: playback survives an idle period that
previously triggered the screensaver, Back and the collapse control both stop
playback and close the player, and the menu no longer offers History. Verified
on an Android phone that minimize, History, and sleep behavior are unchanged.

## Relationship to the series

- **Depends on:** PR1 (`isAndroidTv` state) and PR6 (live engine for focus
  restore after the player closes).
- **Note:** also edits `components/app/AudioPlayer.vue`, which PR2 touches in
  different regions (keyboard hygiene); whichever merges second may need a
  trivial rebase.
- **Layer:** 3 of 3
