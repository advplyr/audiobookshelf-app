# Audit Bug Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all HIGH and actionable MEDIUM findings from the full code audit of the Android TV support in abs-app.

**Architecture:** All fixes are surgical edits to existing files. No new files needed. Changes are grouped by risk/coupling so each task produces a self-contained, testable improvement. No test framework exists in this project -- verification is build + manual QA on Android TV.

**Tech Stack:** Nuxt 2 (Vue 2), Vuex 3, Capacitor, Android/Kotlin, Tailwind CSS

**Build command (run from repo root):**
```bash
cd C:/Users/jscha/source/repos/abs-app
npm run generate && npx cap sync android && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk" ./android/gradlew -p ./android assembleRelease
```

---

### Task 1: Critical safety -- try/finally guards + CSS.escape polyfill

Prevents permanent navigation death if an exception occurs during vertical nav recovery, and ensures older Android TV WebViews don't crash on `CSS.escape`.

**Files:**
- Modify: `plugins/tv-navigation.js:1084-1098` (vertical nav setTimeout bodies)
- Modify: `plugins/tv-navigation.js:1106-1114` (virtualized row retry setTimeout)
- Modify: `plugins/tv-navigation.js:58-60` (getElementFingerprint ID selector)
- Modify: `plugins/tv-navigation.js:69` (getElementFingerprint aria-label selector)

- [ ] **Step 1: Add CSS.escape polyfill helper at top of file**

After the `let lastFocusRect = null` declaration (line 35), add:

```javascript
// CSS.escape polyfill for older Android TV WebViews (Chrome < 46)
const cssEscape = typeof CSS !== 'undefined' && CSS.escape ? CSS.escape : (s) => s.replace(/([^\w-])/g, '\\$1')
```

- [ ] **Step 2: Replace all `CSS.escape` calls with `cssEscape`**

At line 1088, change:
```javascript
const matches = Array.from(document.querySelectorAll('#' + CSS.escape(targetId)))
```
to:
```javascript
const matches = Array.from(document.querySelectorAll('#' + cssEscape(targetId)))
```

- [ ] **Step 3: Wrap vertical nav setTimeout bodies in try/finally**

At lines 1085-1096, change to:
```javascript
setTimeout(() => {
  try {
    if (!document.activeElement || document.activeElement === document.body) {
      const matches = Array.from(document.querySelectorAll('#' + cssEscape(targetId)))
      const refound = matches.find((m) => isVisible(m))
      if (refound) {
        refound.focus({ preventScroll: true })
        lastFocusRect = refound.getBoundingClientRect()
      }
    }
  } finally {
    clearNavGuard()
  }
}, 500)
```

At lines 1106-1114, change to:
```javascript
setTimeout(() => {
  try {
    const retryTarget = findVerticalTarget(key)
    if (retryTarget) {
      retryTarget.focus({ preventScroll: true })
      lastFocusRect = retryTarget.getBoundingClientRect()
      scrollParentToReveal(retryTarget)
    }
  } finally {
    clearNavGuard()
  }
}, 500)
```

- [ ] **Step 4: Fix unescaped selectors in getElementFingerprint**

At lines 58-60, change:
```javascript
if (el.id) {
  fingerprint.selector = '#' + el.id
  fingerprint.idIsUnique = document.querySelectorAll('#' + el.id).length === 1
}
```
to:
```javascript
if (el.id) {
  fingerprint.selector = '#' + cssEscape(el.id)
  fingerprint.idIsUnique = document.querySelectorAll('#' + cssEscape(el.id)).length === 1
}
```

At line 69, change:
```javascript
fingerprint.selector = `button[aria-label="${el.getAttribute('aria-label')}"]`
```
to:
```javascript
const label = el.getAttribute('aria-label').replace(/"/g, '\\"')
fingerprint.selector = `button[aria-label="${label}"]`
```

- [ ] **Step 5: Commit**

```bash
git add plugins/tv-navigation.js
git commit -m "fix: add try/finally guards and CSS.escape polyfill for nav safety"
```

---

### Task 2: Add `.prevent` to all `@keydown.enter` handlers

Prevents double-fire on Android TV where Enter triggers both keydown and a synthesized click event.

**Files:**
- Modify: `components/cards/LazyBookCard.vue:2`
- Modify: `components/cards/AuthorCard.vue:2`
- Modify: `components/cards/LazyCollectionCard.vue:2`
- Modify: `components/cards/LazyPlaylistCard.vue:2`
- Modify: `components/cards/LazySeriesCard.vue:2`
- Modify: `components/modals/LibrariesModal.vue:13`
- Modify: `components/app/SideDrawer.vue:11`
- Modify: `components/home/BookshelfToolbar.vue:8,10,14,16,17`

- [ ] **Step 1: Fix card components**

In each of the 5 card files, change `@keydown.enter="clickCard"` to `@keydown.enter.prevent="clickCard"`.

`LazyBookCard.vue` line 2:
```html
@keydown.enter.prevent="clickCard"
```

`AuthorCard.vue` line 2:
```html
@keydown.enter.prevent="clickCard"
```

`LazyCollectionCard.vue` line 2:
```html
@keydown.enter.prevent="clickCard"
```

`LazyPlaylistCard.vue` line 2:
```html
@keydown.enter.prevent="clickCard"
```

`LazySeriesCard.vue` line 2:
```html
@keydown.enter.prevent="clickCard"
```

- [ ] **Step 2: Fix LibrariesModal**

`LibrariesModal.vue` line 13, change:
```html
@keydown.enter="clickedOption(library)"
```
to:
```html
@keydown.enter.prevent="clickedOption(library)"
```

- [ ] **Step 3: Fix SideDrawer**

`SideDrawer.vue` line 11, change:
```html
@keydown.enter="clickAction(item.action)"
```
to:
```html
@keydown.enter.prevent="clickAction(item.action)"
```

- [ ] **Step 4: Fix BookshelfToolbar**

For all 5 `@keydown.enter` handlers in `BookshelfToolbar.vue`, add `.prevent`. Each line should read `@keydown.enter.prevent="..."`.

- [ ] **Step 5: Commit**

```bash
git add components/cards/LazyBookCard.vue components/cards/AuthorCard.vue components/cards/LazyCollectionCard.vue components/cards/LazyPlaylistCard.vue components/cards/LazySeriesCard.vue components/modals/LibrariesModal.vue components/app/SideDrawer.vue components/home/BookshelfToolbar.vue
git commit -m "fix: add .prevent to @keydown.enter handlers to prevent double-fire on TV"
```

---

### Task 3: Set `isAndroidTv` in Vuex from connect.vue

The connect page uses `layout: 'blank'`, so `default.vue` never mounts and the Vuex `isAndroidTv` state never gets set. All TV-specific JavaScript behavior in ServerConnectForm.vue is disabled on first launch.

**Files:**
- Modify: `pages/connect.vue:43-47`

- [ ] **Step 1: Add setIsAndroidTv commit to connect.vue init()**

At `pages/connect.vue`, in the `init()` method (line 43-47), after `this.$store.commit('setDeviceData', this.deviceData)`, add the TV flag commit. The method should read:

```javascript
async init() {
  await this.$store.dispatch('setupNetworkListener')
  this.deviceData = await this.$db.getDeviceData()
  this.$store.commit('setDeviceData', this.deviceData)
  if (this.deviceData?.isAndroidTv) {
    this.$store.commit('setIsAndroidTv', true)
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add pages/connect.vue
git commit -m "fix: set isAndroidTv in Vuex store from connect page init"
```

---

### Task 4: Replace direct Vuex state mutation with proper mutation

`store.state.lastBookshelfScrollData = {}` bypasses Vue reactivity. Add a proper reset mutation and use it.

**Files:**
- Modify: `store/index.js:129` (add mutation)
- Modify: `plugins/tv-navigation.js:1295-1296`

- [ ] **Step 1: Add resetLastBookshelfScrollData mutation to store**

In `store/index.js`, after the `setLastBookshelfScrollData` mutation (line 138), add:

```javascript
resetLastBookshelfScrollData(state) {
  state.lastBookshelfScrollData = {}
},
```

- [ ] **Step 2: Use the new mutation in tv-navigation.js**

At `plugins/tv-navigation.js` lines 1295-1296, replace:
```javascript
store.commit('setLastBookshelfScrollData', { scrollTop: 0, path: '', name: '' })
store.state.lastBookshelfScrollData = {}
```
with:
```javascript
store.commit('resetLastBookshelfScrollData')
```

- [ ] **Step 3: Commit**

```bash
git add store/index.js plugins/tv-navigation.js
git commit -m "fix: replace direct Vuex state mutation with proper reset mutation"
```

---

### Task 5: Gate TV-only listeners behind initialized check

The focusout listener, store watchers, and event bus listeners currently run on ALL devices. On phones, the focusout handler calls `focusFirstContentElement()` when focus falls to body (e.g., modal close), potentially scrolling the page.

**Files:**
- Modify: `plugins/tv-navigation.js:1194-1581` (restructure the export default function)

- [ ] **Step 1: Move all TV-only code inside the initialized block**

The `checkAndInit()` function at line 1200 currently only registers the keydown listener. Restructure so that ALL TV-specific listeners are registered inside `checkAndInit()` after `initialized = true`.

Move the following blocks inside `checkAndInit()`, after the pollForCards interval (line 1214):
- `fingerprintRestoreActive` declaration (line 1221)
- `refocusAfterContentChange` function (lines 1224-1236)
- `pendingScrollToTop` declaration (line 1239)
- Router `beforeEach` and `afterEach` hooks (lines 1242-1380)
- `playerObserver` and `watchForPlayer` (lines 1385-1408)
- `store.watch` for `currentPlaybackSession` (lines 1411-1443)
- `focusLossTimer` and focusout listener (lines 1448-1503)
- `store.watch` for `showSideDrawer` (lines 1506-1523)
- Modal debounce and `store.watch` for `isModalOpen` (lines 1527-1569)
- `$eventBus.$on` listeners (lines 1572-1580)

The result: the entire export default function becomes:
```javascript
export default function ({ store }) {
  if (typeof document === 'undefined') return

  _store = store
  let initialized = false

  const checkAndInit = () => {
    if (initialized) return
    if (!document.documentElement.classList.contains('android-tv')) return

    initialized = true
    document.addEventListener('keydown', handleKeyDown)

    // Poll for the first book card
    let attempts = 0
    const pollForCards = setInterval(() => {
      attempts++
      if (focusFirstContentElement() || attempts > 30) {
        clearInterval(pollForCards)
      }
    }, 500)

    // === ALL the TV-only code moves here ===
    // (fingerprintRestoreActive, refocusAfterContentChange, pendingScrollToTop,
    //  router hooks, player observer, store watchers, focusout listener,
    //  eventBus listeners -- everything that was at lines 1220-1580)
  }

  checkAndInit()
  setTimeout(checkAndInit, 1000)
}
```

**Important:** The `router.beforeEach` call at line 1243 checks `if (store?.app?.router)` -- keep that guard inside checkAndInit. Same for `$eventBus` guard at line 1572.

- [ ] **Step 2: Commit**

```bash
git add plugins/tv-navigation.js
git commit -m "fix: gate all TV-only listeners behind android-tv initialization check"
```

---

### Task 6: Fix refocusAfterContentChange interval stacking + clear focusLossTimer on navigation

Two related issues: (1) `refocusAfterContentChange` can spawn parallel polling intervals that fight over focus, (2) the focusout timer fires during route changes causing a focus flash.

**Files:**
- Modify: `plugins/tv-navigation.js` (refocusAfterContentChange function + beforeEach hook)

- [ ] **Step 1: Track and clear the refocus interval**

Replace the `refocusAfterContentChange` function (lines 1224-1236) with:

```javascript
let refocusIntervalId = null
let refocusTimeoutId = null
function refocusAfterContentChange() {
  if (fingerprintRestoreActive) return
  // Clear any existing poll to prevent stacking
  clearTimeout(refocusTimeoutId)
  clearInterval(refocusIntervalId)
  refocusTimeoutId = setTimeout(() => {
    if (fingerprintRestoreActive) return
    let attempts = 0
    refocusIntervalId = setInterval(() => {
      attempts++
      if (fingerprintRestoreActive || focusFirstContentElement() || attempts > 10) {
        clearInterval(refocusIntervalId)
        refocusIntervalId = null
      }
    }, 500)
  }, 500)
}
```

- [ ] **Step 2: Clear focusLossTimer in beforeEach**

In the `router.beforeEach` handler, add at the very start (before the fingerprint save logic):

```javascript
// Cancel any pending focusout recovery -- we're navigating away
clearTimeout(focusLossTimer)
```

Note: `focusLossTimer` must be declared before the router hooks (it currently is declared at line 1448 -- after Task 5 restructuring, ensure it's declared before the beforeEach hook uses it).

- [ ] **Step 3: Commit**

```bash
git add plugins/tv-navigation.js
git commit -m "fix: prevent refocus interval stacking and clear focusLossTimer on navigation"
```

---

### Task 7: Fix AudioPlayer v-show tabindex + remove blanket focus outline

Two unrelated but simple fixes: (1) hidden player controls are focusable via D-pad because `v-show` doesn't remove from tab order, (2) the `:not(.android-tv) *:focus { outline: none }` rule strips focus indicators from phone/tablet accessibility users.

**Files:**
- Modify: `components/app/AudioPlayer.vue:72,87`
- Modify: `assets/css/tv-focus.css:8-11`

- [ ] **Step 1: Dynamic tabindex on AudioPlayer jump controls**

At `AudioPlayer.vue` line 72, change:
```html
<span v-show="showFullscreen && !playerSettings.lockUi" tabindex="0"
```
to:
```html
<span v-show="showFullscreen && !playerSettings.lockUi" :tabindex="showFullscreen && !playerSettings.lockUi ? 0 : -1"
```

At line 87, make the same change:
```html
<span v-show="showFullscreen && !playerSettings.lockUi" tabindex="0"
```
to:
```html
<span v-show="showFullscreen && !playerSettings.lockUi" :tabindex="showFullscreen && !playerSettings.lockUi ? 0 : -1"
```

- [ ] **Step 2: Remove blanket focus outline removal for non-TV**

In `assets/css/tv-focus.css`, delete lines 8-11:
```css
/* Remove focus ring for mouse/touch users on non-TV */
:not(.android-tv) *:focus {
  outline: none;
}
```

- [ ] **Step 3: Commit**

```bash
git add components/app/AudioPlayer.vue assets/css/tv-focus.css
git commit -m "fix: dynamic tabindex on hidden player controls, remove blanket focus suppression"
```

---

### Task 8: Build and verify

- [ ] **Step 1: Run the full build**

```bash
cd C:/Users/jscha/source/repos/abs-app
npm run generate && npx cap sync android && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk" ./android/gradlew -p ./android assembleRelease
```

Expected: BUILD SUCCESSFUL, APK at `android/app/build/outputs/apk/release/abs-android-tv-release.apk`

- [ ] **Step 2: Verify no regressions**

Upload APK to Android TV device. Smoke test:
1. Fresh launch (no saved server) -- connect page should have TV layout
2. Navigate between library tabs -- no double-fire on cards
3. Hold ArrowDown rapidly on bookshelf -- no permanent focus loss
4. Open/close modals and drawer -- focus restores correctly
5. Play audiobook, collapse player -- controls not focusable when hidden
