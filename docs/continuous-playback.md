# Downloaded audiobook continuous playback

## Inspection before implementation

Base: PR #1923, `bb5f30c706d3ca76d35ceaa5cb619bd1eab6f29b`. The separately fetched `reference/pr-1810-rebased` lacks the PR's Bluetooth fix and includes debug icon changes. No draft patch was supplied or applied.

- `store/index.js` holds nullable `playlistQueue` with playlistId, items and currentIndex. The playlist page and `tables/playlist/ItemTableRow.vue` duplicate queue mapping and native bridge calls.
- `AudioPlayerContainer.vue` increments the JS index on ENDED, independent of whether native advancement succeeds. Manual playback matches server IDs, updates only the JS index, and can retain an old queue when the same item is played outside its source.
- `AbsAudioPlayer.kt.setPlaylistQueue` accepts only items and currentIndex. Each native `PlaylistQueueItem` is a libraryItemId and nullable episodeId. Neither playlist ID nor media type is required.
- `PlayerListener` handles ExoPlayer STATE_ENDED and calls service advancement without the WebView. `PlayerNotificationService.advancePlaylistQueue` retains the PR's wake/network locks and server retry handling.
- Local IDs beginning with `local` use `DbManager.getLocalLibraryItem`: a Paper `localLibraryItems` book keyed by actual local item ID, not SQLite. Server IDs take a separate streaming branch. Do not manufacture a local ID from a server ID.
- With a null episode ID, advancement skips podcast lookup and calls `LocalLibraryItem.getPlaybackSession(null, deviceInfo)`. For books this supplies book chapters, all local audio tracks, local progress and PLAYMETHOD_LOCAL. This is the same session factory as manual audiobook playback. The proposed local-book assumption was supported by source inspection and subsequently by the user-reported device tests below.
- Manual local preparation checks `hasTracks`, including file existence. The PR's auto-advance path does not check it.
- Collections already have a Play button and an ordered `collection.books` response. `CollectionBooksTable` copies that order into `BookTableRow`; rows currently play independently.
- The series page delegates to `LazyBookshelf`. The shelf fetches pages of 20 and can collapse subseries. `bookshelfCardsHelpers` creates cards with `new ComponentClass`, without a parent; source context must be passed explicitly in props.
- `LazyListBookCard.play` starts audiobooks. `LazyBookCard.play` is empty and its audiobook Play button is disabled; podcast episode playback is separate. Item detail and other playback emitters have no source context and should clear a queue.
- Complete series retrieval uses the existing `/api/libraries/:id/items` endpoint, `filter=series.<encoded ID>`, `sort=sequence`, `desc=0`, without collapse. Fetch every page, independent of shelf state. The server supports sequence sorting and places absent sequences last; no client numeric sort is needed.

Server source inspected: [LibraryController](https://github.com/advplyr/audiobookshelf/blob/master/server/controllers/LibraryController.js), [libraryItemsBookFilters](https://github.com/advplyr/audiobookshelf/blob/master/server/utils/queries/libraryItemsBookFilters.js).

## Design

Nullable Vuex `playbackQueue`: `{ sourceType, sourceId, items, currentIndex }`. Queue items preserve server identity and optional local identity; one helper maps them to the existing native format. Source-aware `play-item` requests establish the queue centrally before preparation. Requests without a source clear it. Series/collection construction is Android-only and filters current local database records for playable downloaded books belonging to the current server connection. Playlist streaming behavior remains supported.

Native names and advancement remain unchanged. A queue-state read supports authoritative JS index synchronization, and local audiobook advancement gains the existing manual file validation. Resolve queue mutations on the main thread before starting playback.

## Implemented behavior

- The series toolbar has an Android Play entry point. Both list and grid book-card Play controls pass the series source explicitly. Queue retrieval requests every page of server sequence order with no collapsed series. The visible shelf's pagination does not limit playback.
- Collection Play and its book rows pass the same full collection order. Play All selects the first unfinished downloaded book, preferring local progress over server progress. If none remain, it reports that fact; an individual finished book can still be selected.
- `utils/playbackQueue.js` builds source-independent queues, filters downloaded books against the active server connection, and translates actual local item/episode IDs for the bridge. Missing, invalid, ebook-only and detectably partial downloads are excluded. No series/collection queue contains server playback IDs.
- A manually selected undownloaded book retains the existing single-item behavior, including the normal streaming permission check, and has no queue. Automatic advancement never selects it.
- The central player clears prior Vue/native queues for each explicit play request, resolves the source, waits for permission/cast decisions, installs the native queue, then prepares playback. An item launched without source context clears the old queue even if it also appears in that queue.
- Vue reads the native queue on ENDED, new sessions and foreground return. It does not increment its own index or initiate auto-advance. A late read cannot replace a newer Vue queue.
- Native names, local session factory, wake locks, network locks and the Doze advancement path are retained. New checks stop on deleted/missing audiobook files, clear queues on player close or unrelated native preparation, and ignore old podcast retry results after queue replacement. These checks prevent queue leakage without moving advancement into JavaScript.

## Changed files

| File                                                                                   | Purpose                                                                             |
| -------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| `utils/playbackQueue.js`                                                               | Source resolution, complete series fetch, downloaded-book filter and bridge mapping |
| `store/index.js`                                                                       | Generic nullable queue state and mutations                                          |
| `components/app/AudioPlayerContainer.vue`                                              | Queue setup/clearing, native synchronization and playback ordering                  |
| `pages/playlist/_id.vue`                                                               | Existing playlist Play uses shared source abstraction                               |
| `components/tables/playlist/ItemTableRow.vue`                                          | Playlist row source context                                                         |
| `pages/bookshelf/series/_id.vue`                                                       | Handles series Play                                                                 |
| `components/home/BookshelfToolbar.vue`                                                 | Android series Play entry point                                                     |
| `mixins/bookshelfCardsHelpers.js`                                                      | Explicit source props for dynamically instantiated cards                            |
| `components/cards/LazyListBookCard.vue`                                                | Series source on list-card playback                                                 |
| `components/cards/LazyBookCard.vue`                                                    | Enables series grid-card playback                                                   |
| `pages/collection/_id.vue`                                                             | Existing Collection Play builds downloaded queue                                    |
| `components/tables/collection/CollectionBooksTable.vue`                                | Passes full collection order to rows                                                |
| `components/tables/collection/BookTableRow.vue`                                        | Individual collection book source context                                           |
| `android/app/src/main/java/com/audiobookshelf/app/plugins/AbsAudioPlayer.kt`           | Main-thread queue mutation completion and queue-state read                          |
| `android/app/src/main/java/com/audiobookshelf/app/player/PlayerNotificationService.kt` | File checks and queue cancellation guards                                           |
| `tests/playbackQueue.test.mjs`                                                         | Automated queue and component-boundary regressions                                  |
| `docs/continuous-playback.md`                                                          | Inspection, design and validation report                                            |

## Device acceptance results

Updated September 9, 2026 from the user's reports after installing the debug APK. These are user-performed tests, not tests observed through ADB by the agent. Device model, Android version, logs and exact idle duration were not supplied. The user confirmed expected behavior after each walkthrough.

| Scenario                 | Device result         | Evidence and scope                                                                                                                                                                                              |
| ------------------------ | --------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| A. Podcast playlist      | Not tested            | User has no podcasts. Automated local/server episode mapping and queue-boundary tests passed; actual podcast playback and Bluetooth-disconnect regression remain unverified.                                    |
| B. Downloaded series     | Passed, user-reported | User reported the APK works as intended, including playback with screen on/off and player foreground/background.                                                                                                |
| C. Start in middle       | Passed, user-reported | User explicitly confirmed that starting book 3 continues to book 4. Separate coverage of both list and grid views was not recorded.                                                                             |
| D. Collection            | Passed, user-reported | User confirmed the collection Play and middle-book walkthrough: advancement follows displayed collection order.                                                                                                 |
| E. Clear queue           | Passed, user-reported | User confirmed the walkthrough for starting a series/collection, playing an unrelated audiobook, and stopping at its completion without resuming the old queue.                                                 |
| F. Download protection   | Passed, user-reported | User confirmed the walkthrough with books 1 and 3 downloaded, book 2 absent, and airplane mode enabled after starting: advancement skips the absent book. Deleting a download after queue setup was not tested. |
| G. Background/screen off | Passed, user-reported | User tested screen on/off and foreground/background, then confirmed the extended locked-screen, unplugged, stationary-device walkthrough. Android's actual Doze state was not measured.                         |

Remaining optional device checks: explicitly confirm Doze state with ADB/logcat, remove a downloaded file after queue setup, and exercise both series card layouts separately. Podcast playlist playback and Bluetooth pause remain untested because no podcast fixtures are available. The earlier build-host ADB check found no connected device; subsequent user testing took place independently.

## Remaining limitations

- Android only. No iOS continuous playback was added.
- Series setup still needs server connectivity for canonical full membership/order, as do the existing source pages. Once installed, the local queue advances without JS or server playback requests. No offline source catalog or queue persistence across process death was added.
- Undownloaded entries are omitted, so the next downloaded entry may skip a gap in the series/collection.
- If a queued download disappears after setup, playback stops rather than streaming or trying another item.
- Source edits during playback do not rebuild the queue. A new source Play request refreshes it. Interrupted/duplicate pagination fails instead of starting an incomplete queue.
- Item-detail playback has no source context and clears the queue; use the series/collection Play controls to start continuous playback.
- Audiobook device acceptance checks passed as reported above. Podcast regression, explicit Doze-state confirmation and behavior across other devices/OEMs remain unverified.

## Build and automated validation

Tools were installed under ignored `.cache/` because the host initially lacked npm, Java and the Android SDK. No dependency manifests or lockfiles were changed. Commands below abbreviate the workspace-local npm executable as `npm` and the Capacitor executable as `cap`.

| Command | Result |
| --- | --- |
| `npm ci --cache .cache/npm --no-audit --no-fund` | Passed: 1,502 packages. Initial sandbox attempt failed with registry EACCES; retry with network permission passed. |
| `node --test tests/playbackQueue.test.mjs` | Passed: 14 tests, including mocked container behavior and HTTP/DB/bridge boundaries. |
| `node node_modules/prettier/bin-prettier.js --check <changed Vue/JS files> utils/playbackQueue.js tests/playbackQueue.test.mjs docs/continuous-playback.md` | Passed. No project ESLint/ktlint command is configured. |
| `npm run generate` | Passed, repeated after final JS changes. Existing bundle-size and stale Browserslist warnings remain. |
| `cap sync android` | Passed. Existing warning: screen-orientation Cordova plugin declares missing es6-promise-plugin. |
| `gradlew.bat --gradle-user-home ../.cache/gradle --no-daemon -Dorg.gradle.java.installations.paths=<JDK17> assembleDebug` | Passed: BUILD SUCCESSFUL, 402 tasks, Kotlin compilation and APK packaging. |
| `gradlew.bat --gradle-user-home ../.cache/gradle --no-daemon -Porg.gradle.java.installations.paths=<JDK17> :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | Final APK packaging passed. Task chain then failed at the unchanged example unit test: `package org.junit does not exist`. The base app build.gradle has no testImplementation dependency for its example test. This was not caused by queue changes; lint was rerun separately. |
| `adb devices -l` | No devices/emulators attached to the build host. Subsequent user-performed device results are recorded above. |
| `gradlew.bat --gradle-user-home ../.cache/gradle --no-daemon -Porg.gradle.java.installations.paths=<JDK17> :app:lintDebug` | Passed separately: BUILD SUCCESSFUL, 499 tasks. |
| `git diff --check` | Passed. |

Java 21 ran Gradle; the project's declared Java 17 toolchain was provided separately. Android SDK platform/build-tools 35 and Gradle 8.11.1 were used. Build output is `android/app/build/outputs/apk/debug/app-debug.apk`.

The implementation was initially delivered as an uncommitted review patch; the user subsequently authorized committing, pushing to their fork and opening a draft pull request. The review patch includes the new helper, tests and this report in addition to tracked edits.

## git diff --stat

Tracked-file diff (new files are listed above and included in the exported patch):

```text
 .../app/player/PlayerNotificationService.kt        | 27 ++++++-
 .../audiobookshelf/app/plugins/AbsAudioPlayer.kt   | 14 +++-
 components/app/AudioPlayerContainer.vue            | 88 ++++++++++++++--------
 components/cards/LazyBookCard.vue                  | 15 +++-
 components/cards/LazyListBookCard.vue              |  3 +-
 components/home/BookshelfToolbar.vue               |  1 +
 components/tables/collection/BookTableRow.vue      |  4 +
 .../tables/collection/CollectionBooksTable.vue     |  4 +-
 components/tables/playlist/ItemTableRow.vue        | 23 +-----
 mixins/bookshelfCardsHelpers.js                    | 11 ++-
 pages/bookshelf/series/_id.vue                     |  7 ++
 pages/collection/_id.vue                           |  6 ++
 pages/playlist/_id.vue                             | 24 ++----
 store/index.js                                     | 10 +--
 14 files changed, 147 insertions(+), 90 deletions(-)
```
