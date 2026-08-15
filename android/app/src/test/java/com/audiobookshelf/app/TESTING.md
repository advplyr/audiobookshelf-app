# Android host-JVM test suite — working guide

Read this before adding or changing tests in `android/app/src/test`. It covers the
conventions the suite follows, the shared harness, what the host JVM genuinely
cannot reach, and the defects the suite currently documents.

`android/TESTING.md` covers environment setup and is the shorter entry point. This
file is the detail.

**Arriving cold? Read §1, §5 and §6 first.** §7 is the open work.

---

## 1. Conventions

These are not style preferences. Each one exists because breaking it produced a
test that looked fine and proved nothing.

1. **Host-JVM tests only.** No `src/androidTest`, no Robolectric, no emulator, no
   Espresso. Device-bound contracts are deferred deliberately, not forgotten; §6
   says which ones and why.
2. **Do not change production code to make a test pass.** If a test needs a seam,
   the seam has to be justifiable as a production improvement on its own.
3. **A known defect is an *enabled failing* test, never `@Ignore`.** The suite is
   red on purpose. The failure count is the fix queue. Never weaken an assertion
   to get green — a candidate fix succeeds when its spec goes from failing to
   passing with the assertion untouched.
4. **Assert the contract, not the observed behaviour.** Write what *should*
   happen and let it fail. Never catch the defect's own exception and then assert
   something weaker; that produces a test that would also pass if the bug were
   fixed wrongly.
5. **Test names are load-bearing documentation.** If deleting the assertion would
   leave the name still "true", the name overclaims. Fix one or the other.
6. **Record what you drop.** If a planned case turns out to be unreachable, wrong,
   or not worth it, say so in the PR. Silence is how cases get lost between plan
   and implementation.
7. **Verify the caller before writing a defect spec.** A failing spec against an
   unreachable path is worse than no spec: someone "fixes" it and then discovers
   the method was never reachable. Two specs in this suite were correctly
   downgraded to characterizations this way — see §7.2.
8. **A GitHub issue is evidence, not an assertion.** Capture exact inputs,
   expected result, observed result and the code path in the test KDoc before
   enabling a failure.

### The KDoc shape every defect spec uses

```
Inputs:   the exact fixture, in the terms the report uses
Expected: the contract, and why it is the contract
Observed: what production does today
Path:     File.kt:line -> File.kt:line, the actual chain
```

Characterization tests — ones that pin current behaviour rather than assert a
contract — say so explicitly and explain why, so nobody "fixes" something that was
deliberately recorded.

---

## 2. Running the tests

Prerequisites and SDK setup are in `android/TESTING.md`. In short: JDK 21, Node 20,
Android SDK Platform 35 with Build-Tools 35.0.0, and `ANDROID_HOME` set.

```bash
./android/gradlew :app:testDebugUnitTest -p android --no-daemon --rerun

# Coverage. Depends on the test task, so it always reports on a fresh run.
./android/gradlew :app:jacocoDebugUnitTestReport -p android --no-daemon
```

One suite: `--tests "com.audiobookshelf.app.data.ProgressConflictTest"`.

Reports land in `android/app/build/reports/tests/testDebugUnitTest/index.html` and
`android/app/build/reports/jacoco/jacocoDebugUnitTestReport/`.

Test-only build settings in `android/app/build.gradle`, each load-bearing:

| Setting | Why |
| --- | --- |
| `testImplementation "org.json:json"` | AGP's mockable `android.jar` strips `org.json` method bodies, so `JSONObject` silently returns nulls instead of parsing. Capacitor's `JSObject` extends it. |
| Java 21 `javaLauncher` for unit tests | Capacitor classes are compiled for Java 21; the app toolchain is 17. Without it, any test touching a Capacitor type throws `UnsupportedClassVersionError`. Scoped to test tasks, so `assembleDebug` is unaffected. |
| `--add-opens java.base/{util,lang,math}` | PaperDb's Kryo serializer reflects into the JDK on 21+. |

---

## 3. Current state

**550 tests, 0 failures.** The fix queue is empty.

That is the target state, not a permanent one: §1 rule 3 means a newly found defect *should* make
this number non-zero until its fix lands. If you are reading this and the suite is red, check §7.1
before assuming something is broken.

For a long stretch this section read "497 tests, 46 enabled failures" while the suite actually had
503 and 50, and §7.1 listed sixteen remediations that had all already shipped. If you change the
suite, re-run it and update this section from the run - a reader arriving cold uses these numbers
to decide whether the tree is healthy.

Coverage is reported by:

```bash
./android/gradlew :app:jacocoDebugUnitTestReport -p android --no-daemon
```

The report task depends on the test task, so the numbers always come from the run that just
happened. (It used to be invoked with `-x testDebugUnitTest` to dodge a suite that failed by
design, which silently produced a report from whatever stale `.exec` file was on disk.)

**The percentage is not a quality gate.** Ratchet per-package thresholds if you want a gate; a
global number mostly measures how much Android-bound code exists.

## 4. Suite map

| Suite | Covers |
| --- | --- |
| `data/ProgressConflictTest` | Progress-conflict cluster, local direction |
| `data/LargeMediaBoundsTest` | Large/invalid media bounds, track and chapter indices |
| `data/CoverImageTest` | The reported cover-image crash, reader side |
| `data/PlaybackSessionTest`, `PlaybackSessionExtraTest`, `PlaybackSessionServerVersionTest` | Session timing, progress, server-version URI gating |
| `data/AudioBookModelTest`, `DeviceAndMediaTypeTest`, `ProgressAndCollectionTest`, `LocalLibraryItemTest`, `LocalMediaProgressExtraTest`, `PodcastEpisodeTest`, `ItemInProgressTest`, `LibraryHierarchyTest`, `TrackGeometryAndLocalCopyTest`, `LocalFileAndDeviceSettingsTest` | Domain models and edge inputs |
| `device/ConnectivityClassificationTest` | Connectivity state: VPN, captive portal |
| `device/LocalMediaLifecycleTest` | Scan identity, orphaned local items |
| `device/FolderScannerTest` | Internal-storage scan, cover adoption, re-download behaviour |
| `device/DeviceManagerVersionTest` | Server-version gating |
| `managers/DownloadIntegrityTest` | Download integrity with unknown/zero expected size |
| `managers/InternalDownloadManagerTest` | Transfer protocol: truncation, Range 206/200/416, headers |
| `managers/DownloadItemManagerTest`, `IncompleteDownloadCleanupTest` | Queue state, restore, retention |
| `managers/DbManagerPersistenceTest`, `DbManagerCleanupTest` | Paper persistence and cleanup rules |
| `media/ExternalPauseControlTest` | Pause from notification, lock screen, Bluetooth, Android Auto |
| `media/ServerProgressFallbackTest` | Offline and failed-sync recovery for server-hosted items |
| `media/MediaProgressSyncerTest` | Syncer lifecycle: stop, pause, finished, seek, reset, sync |
| `media/MediaManagerTest`, `MediaEventManagerTest`, `IconsTest` | Aggregation, events, icon mapping |
| `player/MediaSessionCallbackTest`, `PlayerListenerTest` | Media-session and player callbacks |
| `player/BrowseTreeTest`, `CastUtilityTest`, `CastTimelineTest` | Browse tree, Cast helpers |
| `plugins/AbsDatabaseTest` | Every `AbsDatabase` method not gated behind `load()` — **read §6.1 before editing** |
| `plugins/AbsDatabaseProgressConflictTest` | Websocket progress push (kept separate; §6.1) |
| `plugins/AbsAudioPlayerTest` | The plugin surface reachable without `Handler` dispatch |
| `plugins/AbsDownloaderTest`, `AbsLoggerTest` | Download-manifest builder, logging |
| `server/ServerApiContractTest` | App/server request contract |
| `server/GoldenResponseFixtureTest` | Real server response bodies vs. the client's models — see below |
| `data/ModelDeserializationTest` | Jackson tolerance: unknown fields, int-vs-double, absent optionals, subtype deduction |
| `server/ApiHandlerCredentialTest` | Credential preservation across transient failures |
| `server/ApiHandlerContractTest`, `ApiHandlerEdgeCaseTest` | Endpoint shapes, failure paths, callback cardinality |
| `support/AbsTestEnvironment` | The shared harness — §5 |
| `support/AbsSingletonRule`, `MockServerRule` | Lifecycle rules — §5 |

**Golden fixtures.** `GoldenResponseFixtureTest` reads bodies from
`src/test/resources/fixtures/server-<version>/`, derived from the audiobookshelf server's own
serializers at that version and carrying every field it emits — including the ones the client does
not model. Everything else server-shaped in this suite is hand-written inside the test that uses
it, which makes those tests blind to the server changing a serializer: a fixture written to match
the model cannot disagree with it. Adding a server version means adding a directory and an entry in
`serverVersions`; keep the old directories, since they are what prove backward compatibility.
Provenance and a refresh recipe are in that directory's `README.md`.

---

## 5. The shared harness (`support/AbsTestEnvironment`)

Reach for the helper rather than writing a fresh stub. The mockable-`android.jar`
gaps are a known list, and each entry was found by running code and reading the
next `NullPointerException`.

| Helper | Use it for |
| --- | --- |
| `reset()` | Repoints Paper at a fresh temp dir and clears `DeviceManager` / `MediaEventManager` / `AbsLogger` singleton state. **Call from both `@Before` and `@After`** — one Gradle JVM runs every test class, so state leaks forward into unrelated suites. |
| `mockLocalFileStatics()` | `Base64.encodeToString`, `Environment.getExternalStorageDirectory()`, `MimeTypeMap.getSingleton()`, `Uri.parse`/`fromFile`. |
| `mockUriParse()` | `Uri` stubs that retain `toString`/`scheme`/`path`. A bare relaxed mock returns empty strings, which is fine for "was a URI built" but useless for asserting its value. |
| `injectField(target, name, value)` | Set a `private lateinit var` that a Capacitor `load()` would normally assign. Walks the class hierarchy, since a Kotlin backing field is declared on the exact class that owns the property. |
| `withStaticField(cls, name, value) {}` | Scoped override of a `public static final` field. Needed for `Build.MANUFACTURER`/`MODEL`, which the mockable jar nulls. `mockkStatic` cannot help — a field read compiles to `getstatic`, not a method call, so there is nothing to intercept. Always use the scoped form; an unrestored override leaks into every later test class. |
| `withSdkInt(n) {}` | `Build.VERSION.SDK_INT` reads **0** by default, which silently pins every untouched test to whichever branch guards `< 28`. |
| `apiHandler(ctx)` | An `ApiHandler` wired to a mock `Context`; construction alone exercises `SecureStorage`. |
| `withMockServer {}` | Scoped `MockWebServer` for one block inside one test. For a server that lasts the whole class, use `MockServerRule` instead. |
| `RecordingDownloadCallback` | Records `InternalDownloadManager` progress/completion. Atomic counters and a second latch rather than a `Thread.sleep`, so a duplicate completion is actually detected instead of merely usually detected. |

The harness registers a JCA provider named `AndroidKeyStore` backed by JKS, because
`SecureStorage`'s property initializer calls `KeyStore.getInstance("AndroidKeyStore")`
at construction time.

`reset()` also clears Paper's static `mBookMap` by reflection. Paper caches `Book`
instances forever keyed by name, so without this a book opened by the first test
class keeps reading and writing that class's temp directory for the rest of the run.
This was found through an actual cross-test leak.

---

## 6. What the host JVM cannot reach

Confirmed by running code, not assumed. Do not re-litigate these without a spike.

### Android stubs that silently return null or no-op

| Surface | Behaviour |
| --- | --- |
| `Looper.getMainLooper()` | Returns null, so `Handler(...).post {}` **returns false and never runs the body**. The single biggest blocker in the suite. |
| `android.os.Bundle` | No working `put`/`get`. Any contract whose only observable is "what went into a Bundle" is untestable here. |
| `SparseArray` / `SparseIntArray` | `get(key, default)` returns null regardless of the default or of prior `put` calls. Blocks `CastTimeline` and `CastTimelineTracker`. |
| `Build.VERSION.SDK_INT` | Reads `0` — use `withSdkInt`. |
| `Build.MANUFACTURER` / `MODEL` | Null — use `withStaticField`. |
| `Settings.Secure.getString(..., ANDROID_ID)` | Null, which makes `ApiHandler.sendSyncLocalSessions` throw before it sends anything. A static *method*, so `mockkStatic` works. |
| `android.icu.text.DateFormat.getDateInstance()` | Null. |
| `org.json.JSONObject` | Method-stripped — **solved** by the real `org.json` jar on the test classpath. |
| `android.net.Uri` | Same problem, no drop-in replacement — use `mockUriParse()`. |

### Classes that stay unreachable

* `player/PlayerNotificationService` (1,242 lines) and `player/CastPlayer` (519) —
  Android `Service` and ExoPlayer lifecycle.
* `managers/SleepTimerManager` (209) — live service plus a main-looper `Handler`.
  **Its consumers are not blocked**: `AbsAudioPlayer`'s sleep-timer methods test
  fine with the manager mocked.
* `plugins/AbsFileSystem` (122) and `FolderScanner`'s external half — SAF.
* `SecureStorage`'s crypto path (46 of 57) — `KeyGenParameterSpec` is a stub, and a
  JCA test double cannot supply it.
* `MainActivity`, `MediaPlayerWidget`, `DownloadService` / `DownloadServiceHost`.
* `ApiHandler`'s 401 → refresh → **retry-success** path — needs a real
  `AndroidKeyStore`. The failure branches are covered.
* **Twelve of `AbsAudioPlayer`'s seventeen `@PluginMethod`s** — they wrap the
  delegation *and* the `call.resolve` in `Handler(Looper.getMainLooper()).post {}`,
  so they return without throwing while doing nothing at all. A `verify` would
  fail; a "did not throw" assertion would pass vacuously forever. Write neither.
* `MediaSessionCallback.handleMediaButtonClickCount` and `MediaProgressSyncer.start`'s
  15-second tick body — `Timer` and `Handler` bound.

### 6.1 `AbsDatabaseTest` is fragile — read before editing it

**Adding any test to `plugins/AbsDatabaseTest` can deterministically break two
unrelated tests in it.** Its two `setCurrentServerConnectionConfig` "new config"
tests are the only ones there that reach `DeviceManager.getBase64Id`, so the only
ones that depend on `mockLocalFileStatics()`'s `mockkStatic(Base64::class)` being
live.

Probing `Base64.encodeToString` from that class's `@Before` shows the stub returning
its mocked value in all 40 tests as the class stands, and `null` in all 43 once three
tests are added — the static mock goes inert **for the whole class, before any test
body runs**. `setCurrentServerConnectionConfig` then dies inside its
`GlobalScope.launch(Dispatchers.IO)` body on a `NullPointerException` and never
resolves its call, which surfaces as a five-second timeout in a test that has
nothing to do with the change.

Bisected: **not** ordering (`@FixMethodOrder(NAME_ASCENDING)` does not help),
**not** test count (a trivial extra `@Test` is harmless), and **not** the content of
the added tests (the same three cases under different method names leave the mock
working). The trigger is name-dependent and reproducible; the mechanism inside MockK
was not identified. `AbsDatabaseProgressConflictTest` exists as a separate class for
exactly this reason. Remediation in §7.3.

### 6.2 Do not call `MediaProgressSyncer.start()` in a test

It schedules on `Timer("ListeningTimer", false)` — that `false` is `isDaemon`, so it
is a **non-daemon** thread on a 15-second repeat. Neither `reset()` nor `pause()`
reaps it; both cancel the `TimerTask`, not the `Timer`. Every test that calls it
leaks a thread for the rest of the JVM, and enough of them can stop the Gradle test
JVM from exiting. Its body posts to a main-looper `Handler`, so it never executes
anyway — `start()` buys almost no coverage for its cost.

`listeningTimerRunning` is a public `var`, so the branches that matter can be reached
by setting it directly. `ExternalPauseControlTest` and `MediaProgressSyncerTest` both
do this.

---

## 7. Known remediations

### 7.1 Production fixes — the queue is empty

**A fix belongs in its own change, separate from the tests** — a test that moves with the code it
tests proves nothing. When this queue is non-empty, that is what it looks like: an enabled failing
spec here, and the fix on a `fix-*` branch cut from master.

#### What the queue has bought

Everything previously listed here has shipped. Each item was written as an enabled failing spec
first, fixed on a separate branch, and went green **with its assertion untouched** — which is the
whole point of rule 3 and the reason to keep working this way.

The first sixteen closed the progress-conflict cluster, the download-integrity cluster, the
auth/transient-HTTP cluster, connectivity classification, the cover-image crash, and the orphaned
local item.

Three more came out of a full audit of the suite itself, and are worth recording because of *how*
they were found — none by adding a new subject, all by sharpening what was already there:

| Fix | Found by |
| --- | --- |
| Range-check `DeviceSettings.autoSleepTimer*Hour`/`*Minute`. `"0600".split(":")` is `["0600"]`, which parses to the *integer* 600, so the `?:` fallback never fired — the missing guard was a range check, not a parse check. `SleepTimerManager` never matches 600, so the auto sleep timer silently stopped working. | Tightening a spec whose expected *minute* of `0` happened to equal the fallback default, and so could not distinguish a parse from a fallback. |
| `BookMetadata.getAuthorDisplayName` falls back to the `authors` collection. `authorName` is a flat field the server adds only in its *minified* and *expanded* serializers; the plain `toOldJSON()` shape sends `authors` and no `authorName`, so every author rendered as "Unknown". | `GoldenResponseFixtureTest` (§4), on its first run against a real server body — the class of defect a hand-written fixture cannot surface. |
| `PlaybackSession.getCurrentTrackIndex` returns the *first* track for a position before the first track's start. The fallthrough returned `size - 1` unconditionally — right past the end, wrong before the start — while `getNextTrackIndex` answered `0` for the same input. | Tightening `assertTrue(index in 0..1)`, which admitted both answers while the test's name claimed the first track. |

The lesson worth carrying: **a loose assertion is not merely untidy, it conceals defects.** Two of
these three sat behind assertions that were passing, in tests named for the behaviour they were
failing to check.

### 7.2 Characterized on purpose — do not "fix" without a decision

These tests pin current behaviour so that changing it is visible. Each has a reason:

* **`LocalMediaProgress.updateFromServerMediaProgress`** is unguarded, but one of
  its two callers guards it. The defect spec therefore lives against the *unguarded*
  caller (remediation 8); the method's own behaviour is only pinned.
* **`MediaProgressSyncer.syncFromServerProgress`** has no comparison, but its only
  caller guards on the same object it mutates, so an older record cannot reach it.
  Two things worth knowing: the `// Currently unused` comment above it is **stale**
  — it *is* called — and the guard lives in the caller, so any future second caller
  inherits no protection.
* **Server-item offline progress.** Recovery is *queue-based*: the session is
  persisted on every sync attempt and flushed on reconnect, so the position is **not
  lost**. A downloaded item writes `LocalMediaProgress` immediately, so the two media
  types differ offline. Surfacing unsynced server progress in the UI is a product
  decision, not a bug fix.
* **Local item identity ignores the server** (`local_${libraryItemId}`), so two
  servers sharing an item id collide on one record. Changing the scheme would orphan
  every record already on disk.
* **`cancelSleepTimer` resolves outside its `Handler` post**, unlike its eleven
  siblings. A sequencing quirk on device, and the reason that one method is
  observable in tests at all.
* **`pause` and `finished` return without invoking their callback** when nothing is
  playing, unlike `stop`. A caller that awaited them would wait forever.

### 7.3 Test-infrastructure work

Open:

1. **Add `Settings.Secure.getString` to `mockLocalFileStatics()`.** It blocks
   `sendSyncLocalSessions` and everything that calls it. One `mockkStatic` line.
2. **Defuse the `AbsDatabaseTest` fragility (§6.1).** Cheapest useful step, independent of root
   cause: assert in `@Before` that the `Base64` stub is live, so the failure is immediate and
   legible instead of a timeout somewhere unrelated. Better: stop depending on a static mock inside
   a `GlobalScope` coroutine.
3. **Measure `forkEvery`.** Gradle runs the whole suite in one JVM, and that single fact is the
   root cause of `reset()`, the Paper `mBookMap` reflection hack, the `withStaticField` scoping
   rule, and the `PlayerListener` companion resets. `unitTests.all { forkEvery = 1 }` would delete
   cross-class leakage as a category; it is a wall-time decision, so measure before deciding.

Done — recorded because the reasoning is still load-bearing:

* **A `@Rule` replaced the hand-copied `reset()` pair.** `AbsSingletonRule` (§5) is now the only
  way the suite resets singleton state. The pair had been re-derived by hand in ~30 classes and
  **four had got it wrong**, resetting on the way in but not on the way out. `MediaManagerTest` was
  the worst: it pointed `DeviceManager.serverConnectionConfig` at a `MockWebServer` and then shut
  that server down, leaving the global singleton aimed at a dead socket for whichever class ran
  next. That is invisible at the point of the mistake and fails somewhere unrelated, which is
  exactly why it is now structural rather than conventional.
* **`withMockServer` was adopted rather than dropped**, and joined by `MockServerRule` (§5) for the
  class-scoped case the seven hand-rolling suites actually needed.
* **`reset()` no longer leaks a temp directory per call** (it created ~800 per run) and no longer
  returns an unused `File`.

---

## 8. Traps that have actually caused bad tests here

* **`?.x() != false` is a null-*accepting* assertion.** `uri?.contains("x") != false`
  is `true` when `uri` is null — the one outcome such a test usually means to
  exclude. `== true` is safe. Ask of every assertion: *would this still pass if the
  method returned `null`, `0`, `""`, or an empty list?*
* **A green test named after a bug.** An assertion written around observed behaviour
  instead of the intended contract will go green on a *wrong* fix.
* **`assertNull(thrown)` alone under-tests.** "Does not crash" and "still tells the
  UI it finished" are different contracts, and only the second one clears a spinner.
* **Constants inline; fields do not.** `NetworkCapabilities.TRANSPORT_*` and
  `NET_CAPABILITY_*` are compile-time constants and work correctly on both sides.
  `Build.VERSION.SDK_INT` is a real field read and returns 0.
* **When an Android stub blocks a contract, document the gap in the class KDoc
  rather than asserting around it.** `PodcastEpisodeTest` is the model: a few
  sentences on why `Bundle` extras are not covered, worth more than four assertions
  that would have passed vacuously — one of them against a constant whose value is
  `0`.
* **Reachability needs a spike, not a reading.** `AbsAudioPlayer` was once assumed
  testable on evidence that only proved a method *returned without throwing*; its
  `Handler` body never ran. Conversely `FolderScanner` and `AbsDownloader` were
  listed as blocked while being fully reachable.

---

## 9. Where the remaining risk is

Ranked by user impact, not by uncovered line count.

1. **`PlayerNotificationService`** (1,242 lines, 1 covered). The largest single risk
   in the app and structurally unreachable from a host JVM. It needs either a policy
   extraction or a device suite; nothing else will move it.
2. **Android-bound policy that has no seam yet** — playback interruption, media
   control state, download destination, the Android Auto browse tree, startup state.
   All need a policy object extracted from a service or plugin before there is
   anything host-testable. Blocked on a refactor decision, not on technique.
3. **Response-shape drift — partly closed.** `GoldenResponseFixtureTest` now pins the five
   highest-traffic payloads against bodies derived from the server's own serializers, and it
   immediately found one defect (`getAuthorDisplayName`, §7.1). The remaining exposure is the
   endpoints it does not yet cover — search results, personalized shelves, collections, playback
   sessions — and the fact that the fixtures track one server version at a time.
4. **`MediaManager`** — the largest *reachable* gap left.
5. **Transport policy is triplicated.** The Nuxt Axios client, the Capacitor HTTP
   client and native OkHttp each implement auth, refresh, retry and parsing
   differently, and only the Kotlin one has coverage.

---

## 10. Issue coverage

Suites carrying regression specs for reported issues. Each spec's KDoc has the
inputs, expected and observed behaviour, and the code path.

| Cluster | Issues | Suite |
| --- | --- | --- |
| Progress conflict | #1945, #1940, #1852, #1516, #1510, #1442, #1416, #1338 | `ProgressConflictTest`, `AbsDatabaseProgressConflictTest` |
| Download integrity and resume | #1838, #1827, #1709, #1479, #1428 | `DownloadIntegrityTest`, `InternalDownloadManagerTest` |
| Auth and transient HTTP | #1908, #1900, #1901 | `ApiHandlerCredentialTest` |
| Pause and external control | #1847, #1828, #1491 | `ExternalPauseControlTest` |
| Connectivity state | #1702, #1560, #1802 | `ConnectivityClassificationTest` |
| Local media lifecycle | #1680, #1630, #1392 | `LocalMediaLifecycleTest`, `FolderScannerTest` |
| Large and invalid media | #1684, #1650, #1731 | `LargeMediaBoundsTest` |
| Cover image crash | #1817 | `CoverImageTest`, `FolderScannerTest` |

Twenty-nine issues in total. Keep #1945 and #1940 on separate fixtures: they share a
data-integrity invariant but not a cause, and an Android-side fix must not read as
solving the cross-client one.

Several defects in §7.1 have no open issue — notably the first-listen finished flag,
both `prepareLibraryItem` inputs, the empty-track-list index, and the orphaned local
item. They were found by writing these tests.

One known response-shape mismatch is recorded rather than fixed: `GET /ping` returns
`{"success": true}` and `ApiHandler.pingServer` reads it with `getString`.
