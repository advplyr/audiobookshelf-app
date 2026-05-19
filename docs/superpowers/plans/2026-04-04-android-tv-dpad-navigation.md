# Android TV D-Pad Navigation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the audiobookshelf app navigable with a standard Android TV remote (D-pad arrows, select/enter, back) so users can browse their library, select items, and control playback without a touchscreen.

**Architecture:** Since this is a Capacitor WebView app, D-pad events arrive as keyboard events (ArrowUp/Down/Left/Right, Enter). We add a Vue plugin that manages focus state, a global CSS file for focus ring styles scoped to `.android-tv`, and targeted `tabindex`/`@keydown` additions to key components. All TV-specific behavior is gated behind the `android-tv` class on `<html>` (set by PR 1's MainActivity).

**Tech Stack:** Vue 2, Nuxt.js, Tailwind CSS, Capacitor

**Upstream issue:** advplyr/audiobookshelf-app#606

---

## File Structure

| File | Responsibility |
|------|---------------|
| `plugins/tv-navigation.js` | **New.** Vue plugin — registers global keydown listener on TV, provides `$isTV` helper, manages spatial focus navigation for arrow keys |
| `assets/css/tv-focus.css` | **New.** Focus ring styles scoped under `.android-tv` — visible outlines, larger tap targets |
| `nuxt.config.js` | Register the new plugin and CSS |
| `layouts/default.vue` | Load deviceData `isAndroidTv` flag into Vuex on mount |
| `store/index.js` | Add `isAndroidTv` state field |
| `components/cards/LazyBookCard.vue` | Add `@keydown.enter` to trigger click, add TV focus class |
| `components/app/Appbar.vue` | Add tabindex to all interactive elements |
| `components/app/AudioPlayer.vue` | Add tabindex and keydown.enter to playback controls |
| `components/app/SideDrawer.vue` | Add arrow key navigation within drawer items |
| `pages/connect.vue` | Ensure login form is keyboard-navigable |

---

### Task 1: Create TV focus CSS styles

**Files:**
- Create: `assets/css/tv-focus.css`
- Modify: `nuxt.config.js`

- [ ] **Step 1: Create the TV focus CSS file**

Create `assets/css/tv-focus.css`:

```css
/* Focus styles only apply on Android TV devices */
.android-tv *:focus {
  outline: 3px solid #1ad691;
  outline-offset: 2px;
  border-radius: 4px;
}

/* Remove focus ring for mouse/touch users on non-TV */
:not(.android-tv) *:focus {
  outline: none;
}

/* Larger interactive targets on TV for 10-foot UI */
.android-tv .tv-focusable {
  min-height: 48px;
  min-width: 48px;
}

/* Smooth focus transitions */
.android-tv *:focus {
  transition: outline-color 0.15s ease, outline-offset 0.15s ease;
}

/* Highlight book cards more prominently on focus */
.android-tv [id^="book-card-"]:focus {
  outline: 3px solid #1ad691;
  outline-offset: 4px;
  transform: scale(1.05);
  transition: transform 0.15s ease, outline-color 0.15s ease;
  z-index: 100;
}

/* Player controls focus */
.android-tv .play-btn:focus,
.android-tv .jump-icon:focus {
  outline: 3px solid #1ad691;
  outline-offset: 4px;
  border-radius: 50%;
}
```

- [ ] **Step 2: Register the CSS in nuxt.config.js**

In `nuxt.config.js`, find the existing `css` array and add the new file. The `css` array is in the config object. Add `'~/assets/css/tv-focus.css'` to it. If no `css` array exists, add one at the top level:

```js
css: [
  '~/assets/css/tv-focus.css'
],
```

- [ ] **Step 3: Verify no syntax errors**

Read `nuxt.config.js` back to confirm the addition is valid JS.

- [ ] **Step 4: Commit**

```bash
git add assets/css/tv-focus.css nuxt.config.js
git commit -m "feat: add Android TV focus ring styles"
```

---

### Task 2: Add isAndroidTv flag to Vuex store

**Files:**
- Modify: `store/index.js`
- Modify: `layouts/default.vue`

The web layer already receives `isAndroidTv` from the native AbsDatabase plugin (PR 1). We need to store it in Vuex so any component can check it.

- [ ] **Step 1: Add state field and mutation to store/index.js**

In the `state` function, add after `isFirstLoad: true`:

```js
  isAndroidTv: false,
```

In the `mutations` object, add:

```js
  setIsAndroidTv(state, val) {
    state.isAndroidTv = val
  },
```

- [ ] **Step 2: Set the flag from deviceData in layouts/default.vue**

In the `mounted()` method, find the block where `deviceData` is fetched (around line 336):

```js
const deviceData = await this.$db.getDeviceData()
this.$store.commit('setDeviceData', deviceData)
```

Add immediately after:

```js
if (deviceData?.isAndroidTv) {
  this.$store.commit('setIsAndroidTv', true)
}
```

- [ ] **Step 3: Commit**

```bash
git add store/index.js layouts/default.vue
git commit -m "feat: add isAndroidTv flag to Vuex store"
```

---

### Task 3: Create TV navigation plugin

**Files:**
- Create: `plugins/tv-navigation.js`
- Modify: `nuxt.config.js`

This plugin does two things: (1) provides a `$isTV` computed helper via a Vue mixin, and (2) on Android TV, registers a global keydown listener that handles spatial focus navigation for arrow keys so D-pad naturally moves between focusable elements.

- [ ] **Step 1: Create plugins/tv-navigation.js**

```js
/**
 * TV Navigation Plugin
 *
 * Provides D-pad spatial navigation for Android TV.
 * Only activates when the android-tv class is present on <html>.
 */

function getDistance(fromRect, toRect, direction) {
  const fromCenter = {
    x: fromRect.left + fromRect.width / 2,
    y: fromRect.top + fromRect.height / 2
  }
  const toCenter = {
    x: toRect.left + toRect.width / 2,
    y: toRect.top + toRect.height / 2
  }

  // Filter out elements not in the correct direction
  switch (direction) {
    case 'ArrowUp':
      if (toCenter.y >= fromCenter.y) return Infinity
      break
    case 'ArrowDown':
      if (toCenter.y <= fromCenter.y) return Infinity
      break
    case 'ArrowLeft':
      if (toCenter.x >= fromCenter.x) return Infinity
      break
    case 'ArrowRight':
      if (toCenter.x <= fromCenter.x) return Infinity
      break
  }

  // Weighted distance: primary axis matters more
  const dx = toCenter.x - fromCenter.x
  const dy = toCenter.y - fromCenter.y

  if (direction === 'ArrowUp' || direction === 'ArrowDown') {
    return Math.abs(dy) + Math.abs(dx) * 0.5
  }
  return Math.abs(dx) + Math.abs(dy) * 0.5
}

function findNextFocusable(direction) {
  const current = document.activeElement
  if (!current) return null

  const focusables = Array.from(
    document.querySelectorAll('[tabindex="0"], button, a[href], input, select, textarea, [tabindex]:not([tabindex="-1"])')
  ).filter((el) => {
    // Must be visible and not hidden
    const style = window.getComputedStyle(el)
    if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false
    const rect = el.getBoundingClientRect()
    if (rect.width === 0 || rect.height === 0) return false
    return el !== current
  })

  if (focusables.length === 0) return null

  const currentRect = current.getBoundingClientRect()
  let bestElement = null
  let bestDistance = Infinity

  for (const el of focusables) {
    const rect = el.getBoundingClientRect()
    const dist = getDistance(currentRect, rect, direction)
    if (dist < bestDistance) {
      bestDistance = dist
      bestElement = el
    }
  }

  return bestElement
}

function handleKeyDown(event) {
  const { key } = event

  if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(key)) {
    const next = findNextFocusable(key)
    if (next) {
      event.preventDefault()
      next.focus({ preventScroll: false })
      // Scroll into view if needed
      next.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' })
    }
  }
}

export default function ({ store }) {
  // Only activate on Android TV
  if (typeof document === 'undefined') return

  // Check for android-tv class (set by MainActivity in PR 1)
  const checkAndInit = () => {
    if (document.documentElement.classList.contains('android-tv')) {
      document.addEventListener('keydown', handleKeyDown)

      // Focus the first focusable element on page load
      setTimeout(() => {
        const first = document.querySelector('[tabindex="0"], button, a[href], input')
        if (first) first.focus()
      }, 500)
    }
  }

  // Check immediately and also after a short delay (class may be injected after load)
  checkAndInit()
  setTimeout(checkAndInit, 1000)
}
```

- [ ] **Step 2: Register the plugin in nuxt.config.js**

Find the `plugins` array in `nuxt.config.js` and add:

```js
{ src: '~/plugins/tv-navigation.js', mode: 'client' },
```

Add it at the end of the existing plugins array.

- [ ] **Step 3: Commit**

```bash
git add plugins/tv-navigation.js nuxt.config.js
git commit -m "feat: add TV spatial navigation plugin for D-pad support"
```

---

### Task 4: Add tabindex and keydown to Appbar

**Files:**
- Modify: `components/app/Appbar.vue`

The Appbar has buttons and links. Buttons and `<nuxt-link>` are natively focusable, but the back `<a>` tag and some elements need tabindex. Also ensure consistent focus behavior.

- [ ] **Step 1: Add tabindex to the back link**

Change the back link (line 7) from:

```html
<a v-if="showBack" @click="back" aria-label="Back" class="rounded-full h-10 w-10 flex items-center justify-center mr-2 cursor-pointer">
```

To:

```html
<a v-if="showBack" @click="back" @keydown.enter.prevent="back" aria-label="Back" tabindex="0" class="rounded-full h-10 w-10 flex items-center justify-center mr-2 cursor-pointer">
```

- [ ] **Step 2: Commit**

```bash
git add components/app/Appbar.vue
git commit -m "feat: add keyboard accessibility to Appbar back button"
```

---

### Task 5: Add keydown.enter to book cards

**Files:**
- Modify: `components/cards/LazyBookCard.vue`

Book cards already have `tabindex="0"` (line 2) which is great. They need `@keydown.enter` to trigger the same action as click, so pressing the select button on the remote opens the book.

- [ ] **Step 1: Add @keydown.enter to the card root div**

On line 2, add `@keydown.enter="clickCard"` to the existing div. Change:

```html
<div ref="card" tabindex="0" :id="`book-card-${index}`" :style="{ minWidth: width + 'px', maxWidth: width + 'px', height: height + 'px' }" class="rounded-sm z-10 bg-primary cursor-pointer box-shadow-book" @click="clickCard">
```

To:

```html
<div ref="card" tabindex="0" :id="`book-card-${index}`" :style="{ minWidth: width + 'px', maxWidth: width + 'px', height: height + 'px' }" class="rounded-sm z-10 bg-primary cursor-pointer box-shadow-book" @click="clickCard" @keydown.enter="clickCard">
```

- [ ] **Step 2: Commit**

```bash
git add components/cards/LazyBookCard.vue
git commit -m "feat: enable Enter key to activate book cards for TV remote"
```

---

### Task 6: Add tabindex and keydown to AudioPlayer controls

**Files:**
- Modify: `components/app/AudioPlayer.vue`

The player controls are all divs/spans with `@click` handlers. They need `tabindex="0"` and `@keydown.enter` so the TV remote can focus and activate them.

- [ ] **Step 1: Add tabindex and keydown to play/pause button**

Find the play-btn div (around line 77):

```html
<div class="play-btn cursor-pointer shadow-sm flex items-center justify-center rounded-full text-primary mx-4 relative overflow-hidden" :style="{ backgroundColor: coverRgb }" :class="{ 'animate-spin': seekLoading }" @mousedown.prevent @mouseup.prevent @click.stop="playPauseClick">
```

Add `tabindex="0"` and `@keydown.enter.stop="playPauseClick"`:

```html
<div tabindex="0" class="play-btn cursor-pointer shadow-sm flex items-center justify-center rounded-full text-primary mx-4 relative overflow-hidden" :style="{ backgroundColor: coverRgb }" :class="{ 'animate-spin': seekLoading }" @mousedown.prevent @mouseup.prevent @click.stop="playPauseClick" @keydown.enter.stop="playPauseClick">
```

- [ ] **Step 2: Add tabindex and keydown to jump backwards button**

Find the jump backwards div (around line 73):

```html
<div v-show="!playerSettings.lockUi" class="jump-icon text-fg cursor-pointer flex flex-col items-center" :class="isLoading ? 'text-opacity-10' : 'text-opacity-75'" @click.stop="jumpBackwards">
```

Add `tabindex="0"` and `@keydown.enter.stop="jumpBackwards"`:

```html
<div v-show="!playerSettings.lockUi" tabindex="0" class="jump-icon text-fg cursor-pointer flex flex-col items-center" :class="isLoading ? 'text-opacity-10' : 'text-opacity-75'" @click.stop="jumpBackwards" @keydown.enter.stop="jumpBackwards">
```

- [ ] **Step 3: Add tabindex and keydown to jump forward button**

Find the jump forward div (around line 83):

```html
<div v-show="!playerSettings.lockUi" class="jump-icon text-fg cursor-pointer flex flex-col items-center" :class="isLoading ? 'text-opacity-10' : 'text-opacity-75'" @click.stop="jumpForward">
```

Add `tabindex="0"` and `@keydown.enter.stop="jumpForward"`:

```html
<div v-show="!playerSettings.lockUi" tabindex="0" class="jump-icon text-fg cursor-pointer flex flex-col items-center" :class="isLoading ? 'text-opacity-10' : 'text-opacity-75'" @click.stop="jumpForward" @keydown.enter.stop="jumpForward">
```

- [ ] **Step 4: Add tabindex and keydown to chapter start/end buttons**

Find the chapter start span (line 72):

```html
<span v-show="showFullscreen && !playerSettings.lockUi" class="material-symbols next-icon text-fg cursor-pointer" :class="isLoading ? 'text-opacity-10' : 'text-opacity-75'" @click.stop="jumpChapterStart">first_page</span>
```

Add `tabindex="0"` and `@keydown.enter.stop="jumpChapterStart"`:

```html
<span v-show="showFullscreen && !playerSettings.lockUi" tabindex="0" class="material-symbols next-icon text-fg cursor-pointer" :class="isLoading ? 'text-opacity-10' : 'text-opacity-75'" @click.stop="jumpChapterStart" @keydown.enter.stop="jumpChapterStart">first_page</span>
```

Find the next chapter span (line 87):

```html
<span v-show="showFullscreen && !playerSettings.lockUi" class="material-symbols next-icon text-fg cursor-pointer" :class="nextChapter && !isLoading ? 'text-opacity-75' : 'text-opacity-10'" @click.stop="jumpNextChapter">last_page</span>
```

Add `tabindex="0"` and `@keydown.enter.stop="jumpNextChapter"`:

```html
<span v-show="showFullscreen && !playerSettings.lockUi" tabindex="0" class="material-symbols next-icon text-fg cursor-pointer" :class="nextChapter && !isLoading ? 'text-opacity-75' : 'text-opacity-10'" @click.stop="jumpNextChapter" @keydown.enter.stop="jumpNextChapter">last_page</span>
```

- [ ] **Step 5: Add tabindex and keydown to fullscreen collapse button**

Find the collapse button span (line 7):

```html
<span class="material-symbols text-5xl" :class="{ 'text-black text-opacity-75': coverBgIsLight && theme !== 'black' }" @click="collapseFullscreen">keyboard_arrow_down</span>
```

Add `tabindex="0"` and `@keydown.enter="collapseFullscreen"`:

```html
<span tabindex="0" class="material-symbols text-5xl" :class="{ 'text-black text-opacity-75': coverBgIsLight && theme !== 'black' }" @click="collapseFullscreen" @keydown.enter="collapseFullscreen">keyboard_arrow_down</span>
```

- [ ] **Step 6: Commit**

```bash
git add components/app/AudioPlayer.vue
git commit -m "feat: add keyboard/D-pad support to audio player controls"
```

---

### Task 7: Add arrow key navigation to SideDrawer

**Files:**
- Modify: `components/app/SideDrawer.vue`

The SideDrawer already uses dynamic tabindex. Add `@keydown` handling so up/down arrows move between menu items and Enter activates them.

- [ ] **Step 1: Read the full SideDrawer template**

Read `components/app/SideDrawer.vue` to understand the exact template structure before editing.

- [ ] **Step 2: Add @keydown handler to the drawer nav container**

Add a `@keydown` handler on the nav container that handles ArrowUp/ArrowDown to move focus between items. The exact implementation depends on the template structure observed in step 1. The handler should:

- On ArrowDown: focus next sibling focusable element
- On ArrowUp: focus previous sibling focusable element
- Prevent default scroll behavior

- [ ] **Step 3: Commit**

```bash
git add components/app/SideDrawer.vue
git commit -m "feat: add arrow key navigation to side drawer menu"
```

---

### Task 8: Build, test, and verify

- [ ] **Step 1: Generate web assets**

```bash
cd C:/Users/jscha/source/repos/abs-app
npm run generate
npx cap sync android
```

- [ ] **Step 2: Build debug APK**

```bash
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
cd android
./gradlew assembleDebug --no-daemon
```

APK at: `android/app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 3: Sideload and test on Android TV**

Test checklist:
- [ ] D-pad arrows move focus between book cards on the bookshelf
- [ ] Green focus ring visible on focused elements
- [ ] Enter/select button opens a book card's detail page
- [ ] Appbar buttons are focusable and activatable with Enter
- [ ] Audio player play/pause, skip buttons work with D-pad + Enter
- [ ] Side drawer items navigable with up/down arrows
- [ ] Back button on remote navigates back
- [ ] No regressions on mobile (focus rings should not appear on touch)

- [ ] **Step 4: Commit any fixes found during testing**
