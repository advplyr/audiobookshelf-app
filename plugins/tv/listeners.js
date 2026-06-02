/**
 * TV Navigation — listener + watcher registration.
 *
 * Orchestrates 5 categories of side-effect registration:
 *   1. Router hooks (beforeEach / afterEach for focus memory)
 *   2. Player observer + watchers (auto-expand + close handling)
 *   3. Focus-out recovery handler (DOM removal recovery)
 *   4. Overlay watchers (side drawer + modal open/close)
 *   5. EventBus subscribers (library-changed, bookshelf-total-entities, user-settings)
 *
 * Extracted from plugins/tv-navigation.js's nested registerTvListeners
 * during v1.0.10 refactor. Two cross-function timers (focusLossTimer,
 * pendingScrollToTop) live at module scope here instead of tvContext —
 * they're listener-internal coordination state, not engine state.
 */

import { tvContext } from './context.js'
import { isVisible, getAllFocusable } from './visibility.js'
import { scrollParentToReveal } from './scrollHelpers.js'
import { getElementFingerprint, restoreFromFingerprint } from './focusMemory.js'
import { saveFocusBeforeOverlay, restoreFocusAfterOverlay } from './overlayFocus.js'
import {
  focusFirstContentElement,
  focusAfterPlayerClose,
  refocusAfterContentChange
} from './focusEntry.js'
import { applyTvFocusColor } from './focusColor.js'

// Module-scope state shared between router hooks + focus-out handler.
// (Not on tvContext because these are listener-internal coordination.)
let focusLossTimer = null
let pendingScrollToTop = false

// Main pages where we always want first-card focus, never fingerprint restore
const mainPagePatterns = [
  '/bookshelf',           // Home
  '/bookshelf/library',   // Library
  '/bookshelf/playlists', // Playlists
  '/bookshelf/authors',   // Authors
  '/bookshelf/collections', // Collections
  '/bookshelf/series',    // Series
  '/bookshelf/latest',    // Latest
  '/connect',             // Connect
  '/settings',            // Settings
  '/account',             // Account
  '/search',              // Search
  '/stats',               // Stats
  '/logs',                // Logs
  '/downloads',           // Downloads
]

function isMainPage(path) {
  return mainPagePatterns.some((p) => path === p || path === p + '/')
}

function registerRouterHooks(store) {
  if (!store?.app?.router) return

  store.app.router.beforeEach((to, from, next) => {
    // Cancel any pending focusout recovery — we're navigating away
    clearTimeout(focusLossTimer)

    // Determine the element to save — prefer focus history stack
    let elementToSave = null
    if (tvContext.focusHistory.length > 0) {
      elementToSave = tvContext.focusHistory[0]
    }
    if (!elementToSave || elementToSave === document.body) {
      elementToSave = document.activeElement
    }

    // Only save fingerprint for content items (cards, playlist buttons, etc.)
    // NOT for nav bar links, hamburger menu, library selector, or toolbar items.
    // This ensures Back from nav always focuses the first card, not the nav link.
    const isContentElement = elementToSave && (
      elementToSave.id?.startsWith('book-card-') ||
      elementToSave.id?.startsWith('series-card-') ||
      elementToSave.id?.startsWith('collection-card-') ||
      elementToSave.id?.startsWith('playlist-card-') ||
      elementToSave.classList?.contains('author-card-wrapper') ||
      elementToSave.closest?.('#content')
    ) && !elementToSave.closest?.('#appbar') && !elementToSave.closest?.('#bookshelf-navbar') && !elementToSave.closest?.('#bookshelf-toolbar')

    const leavingMainPage = isMainPage(from.path)
    const goingToMainPage = isMainPage(to.path)

    if (leavingMainPage && !goingToMainPage) {
      // Main → Detail: save fingerprint
      if (isContentElement) {
        const fingerprint = getElementFingerprint(elementToSave)
        if (fingerprint) {
          tvContext.pageFocusMemory[from.fullPath] = fingerprint
        }
      }
    } else if (!leavingMainPage && !goingToMainPage) {
      // Detail → Detail: save fingerprint
      if (isContentElement) {
        const fingerprint = getElementFingerprint(elementToSave)
        if (fingerprint) {
          tvContext.pageFocusMemory[from.fullPath] = fingerprint
        }
      }
    } else if (leavingMainPage && goingToMainPage) {
      // Main → Main: clear both and scroll to top
      delete tvContext.pageFocusMemory[from.fullPath]
      delete tvContext.pageFocusMemory[to.fullPath]
      pendingScrollToTop = true
      // Clear LazyBookshelf's stored scroll position so it doesn't restore
      // the old position after our scroll-to-top fires.
      store.commit('resetLastBookshelfScrollData')
    } else if (!leavingMainPage && goingToMainPage) {
      // Detail → Main: clear the detail page fingerprint
      delete tvContext.pageFocusMemory[from.fullPath]
      // Clear destination if navigating from an actual nav/toolbar element
      // (forward navigation like clicking a library tab), but NOT for the
      // back arrow — it's a back navigation and should preserve fingerprints.
      const isBackArrow = elementToSave?.getAttribute?.('aria-label') === 'Back'
      const isExplicitNavElement = elementToSave &&
        elementToSave !== document.body &&
        !isBackArrow &&
        (elementToSave.closest?.('#appbar') || elementToSave.closest?.('#bookshelf-navbar') || elementToSave.closest?.('#bookshelf-toolbar'))
      if (isExplicitNavElement) {
        delete tvContext.pageFocusMemory[to.fullPath]
      }
    }

    // Author bio pages have no meaningful focus to restore — always clear
    // on leave so we get a fresh start on re-entry.
    if (from.path.startsWith('/author/')) {
      delete tvContext.pageFocusMemory[from.fullPath]
    }

    tvContext.focusHistory.length = 0
    tvContext.lastFocusRect = null
    next()
  })

  store.app.router.afterEach(async (to) => {
    // Reset the flag on every navigation so a previous page's restore
    // doesn't block focus setup on the new page
    tvContext.fingerprintRestoreActive = false

    // Main → Main: scroll to top so the page starts fresh.
    // LazyBookshelf restores its own scroll position from lastBookshelfScrollData
    // after loading data, so we fire twice: once early and once after the
    // maintainer's restore has likely completed, to ensure we win the race.
    if (pendingScrollToTop) {
      pendingScrollToTop = false
      const scrollToTop = () => {
        const sc = document.getElementById('bookshelf-wrapper')
        if (sc) sc.scrollTop = 0
      }
      setTimeout(scrollToTop, 200)
      setTimeout(scrollToTop, 800)
    }

    const saved = tvContext.pageFocusMemory[to.fullPath]
    if (saved) {
      tvContext.fingerprintRestoreActive = true
      const restored = await restoreFromFingerprint(saved)
      setTimeout(() => { tvContext.fingerprintRestoreActive = false }, 6000)
      if (!restored) {
        tvContext.fingerprintRestoreActive = false
        refocusAfterContentChange()
      }
    } else {
      refocusAfterContentChange()
    }
  })
}

function registerPlayerWatchers(store) {
  // Watch for audio player leaving fullscreen — on TV the player closes
  // entirely, so focus a content element. The observer handles edge cases
  // where closePlayback triggers the class change before the DOM is cleaned up.
  const playerObserver = new MutationObserver((mutations) => {
    for (const mutation of mutations) {
      if (mutation.attributeName === 'class') {
        const sc = mutation.target
        if (sc.id === 'streamContainer' && !sc.classList.contains('fullscreen')) {
          setTimeout(() => focusAfterPlayerClose(), 400)
        }
      }
    }
  })

  // Observe streamContainer when it appears (it's conditionally rendered)
  const watchForPlayer = () => {
    const sc = document.getElementById('streamContainer')
    if (sc) {
      playerObserver.observe(sc, { attributes: true, attributeFilter: ['class'] })
    }
  }
  watchForPlayer()
  // Re-check periodically since the player mounts dynamically
  setInterval(watchForPlayer, 5000)

  // Save fingerprint of the play-triggering element as early as possible.
  // playerStartingPlaybackMediaId is committed synchronously inside the click
  // handler (playClick in ItemTableRow.vue), so activeElement is guaranteed
  // to still be the button that was pressed. By contrast, currentPlaybackSession
  // changes later after the native round-trip, when focus may have shifted.
  // Tracked in project_tv_playlist_play_button_regression.md
  store.watch(
    (state) => state.playerStartingPlaybackMediaId,
    (newVal, oldVal) => {
      if (!newVal || newVal === oldVal) return
      const activeEl = document.activeElement
      if (!activeEl || activeEl === document.body) return
      const fp = getElementFingerprint(activeEl)
      if (fp) {
        const currentPath = store.app?.router?.currentRoute?.fullPath || window.location.pathname
        tvContext.pageFocusMemory[currentPath] = fp
      }
    }
  )

  // Auto-expand player to fullscreen when playback starts on TV
  store.watch(
    (state) => state.currentPlaybackSession,
    (newVal, oldVal) => {
      if (newVal && !oldVal) {
        // Playback just started — wait for the player to mount, then expand
        setTimeout(() => {
          const sc = document.getElementById('streamContainer')
          if (sc && !sc.classList.contains('fullscreen')) {
            watchForPlayer() // Ensure observer is attached
            const playerContent = document.getElementById('playerContent')
            if (playerContent) playerContent.click()
            setTimeout(() => {
              const playBtn = sc.querySelector('.play-btn')
              if (playBtn) playBtn.focus({ preventScroll: true })
            }, 400)
          }
        }, 800)
      } else if (!newVal && oldVal) {
        // Player closed — unified restore via focusAfterPlayerClose (tries
        // the fingerprint saved above on playback-start, then falls back).
        setTimeout(async () => {
          await focusAfterPlayerClose()
          // Scroll the focused element into view
          setTimeout(() => {
            if (document.activeElement && document.activeElement !== document.body) {
              scrollParentToReveal(document.activeElement)
            }
          }, 300)
        }, 400)
      }
    }
  )
}

function registerFocusOutHandler(store) {
  // Recover from focus loss when a focused element is removed from DOM
  // (e.g. download button disappears after download completes via v-if).
  // Redirect focus to the Play/Stream button or nearest content element.
  document.addEventListener('focusout', () => {
    clearTimeout(focusLossTimer)
    focusLossTimer = setTimeout(() => {
      // Only act if focus truly fell to body (element was removed)
      if (document.activeElement && document.activeElement !== document.body) return
      // Don't interfere during vertical navigation — the virtualizer may
      // briefly detach a focused card via appendChild; the nav handler's
      // own recovery timer will restore focus.
      if (tvContext.verticalNavInProgress) return
      // Don't interfere during navigation or fingerprint restore
      if (tvContext.fingerprintRestoreActive) return
      // Don't interfere when overlays are open
      if (document.documentElement.classList.contains('modal-open')) return
      if (store?.state?.showSideDrawer) return
      // Stats page: when a button loses focus (e.g. Next gets disabled during
      // processing), wait for processing to finish and re-focus the Next button.
      // Don't settle for the See/Close YIR toggle — keep retrying until the
      // real buttons are re-enabled.
      const sp = document.getElementById('stats-page')
      if (sp) {
        const retryFocus = (retries) => {
          const cr = sp.getBoundingClientRect()
          const btns = getAllFocusable().filter((c) => {
            if (!sp.contains(c)) return false
            const r = c.getBoundingClientRect()
            return r.top >= cr.top && r.bottom <= cr.bottom
          })
          // Look for Next button specifically (year-in-review nav)
          const nextBtn = btns.find((el) => {
            const txt = el.textContent?.trim()
            return txt?.includes('Next') || txt?.includes('chevron_right')
          })
          if (nextBtn) { nextBtn.focus({ preventScroll: true }); return }
          // Look for Refresh or Previous (also year-in-review nav)
          const yirBtn = btns.find((el) => {
            const txt = el.textContent?.trim()
            return txt?.includes('Refresh') || txt?.includes('refresh') ||
                   txt?.includes('Previous') || txt?.includes('chevron_left')
          })
          if (yirBtn) { yirBtn.focus({ preventScroll: true }); return }
          // Buttons still disabled — keep waiting
          if (retries > 0) setTimeout(() => retryFocus(retries - 1), 500)
        }
        setTimeout(() => retryFocus(10), 300)
        return
      }
      // Redirect to Play button on detail pages, or first content element
      const playBtn = document.querySelector('.btn.bg-success, button.bg-success')
      if (playBtn && isVisible(playBtn)) {
        playBtn.focus({ preventScroll: true })
      } else {
        focusFirstContentElement()
      }
    }, 200)
  })
}

function registerOverlayWatchers(store) {
  // Side drawer open/close
  store.watch(
    (state) => state.showSideDrawer,
    (newVal) => {
      if (newVal) {
        saveFocusBeforeOverlay()
        setTimeout(() => {
          const drawer = document.querySelector('.fixed.z-50 .bg-bg.transition-transform:not(.translate-x-64)')
          if (drawer) {
            const first = drawer.querySelector('button, a')
            if (first) first.focus()
          }
        }, 300)
      } else {
        setTimeout(() => {
          if (!restoreFocusAfterOverlay()) focusFirstContentElement()
        }, 300)
      }
    }
  )

  // Track modal state with debouncing to handle rapid open/close transitions
  // (e.g. "More Info" closes one modal and opens another in quick succession)
  let modalDebounceTimer = null
  let modalWasOpen = false
  store.watch(
    (state) => state.globals.isModalOpen,
    (newVal) => {
      clearTimeout(modalDebounceTimer)
      if (newVal && !modalWasOpen) {
        // First modal opening — save focus immediately
        modalWasOpen = true
        saveFocusBeforeOverlay()
        setTimeout(() => {
          const modal = document.querySelector('body > .modal')
          if (modal) {
            modal.querySelectorAll('li[role="option"]:not([tabindex])').forEach((li) => {
              li.setAttribute('tabindex', '0')
            })
            const first = modal.querySelector('li[tabindex="0"], li[role="option"], button, a, input')
            if (first) first.focus()
          }
        }, 350)
      } else if (newVal && modalWasOpen) {
        // Modal transition (one closed, another opened) — focus the new modal's first item
        setTimeout(() => {
          const modal = document.querySelector('body > .modal')
          if (modal) {
            modal.querySelectorAll('li[role="option"]:not([tabindex])').forEach((li) => {
              li.setAttribute('tabindex', '0')
            })
            const first = modal.querySelector('li[tabindex="0"], li[role="option"], button, a, input')
            if (first) first.focus()
          }
        }, 350)
      } else {
        // Modal closed — wait a beat to see if another opens immediately
        modalDebounceTimer = setTimeout(() => {
          if (!store.state.globals.isModalOpen) {
            modalWasOpen = false
            if (!restoreFocusAfterOverlay()) focusFirstContentElement()
          }
        }, 400)
      }
    }
  )
}

function registerEventBusSubscribers(store) {
  if (!store?.app?.$eventBus) return

  store.app.$eventBus.$on('library-changed', () => {
    refocusAfterContentChange()
  })
  // Fires when bookshelf content changes (after filter, sort, or data load)
  store.app.$eventBus.$on('bookshelf-total-entities', () => {
    refocusAfterContentChange()
  })
  // TV focus ring color — apply current value, then react to user-settings broadcasts
  applyTvFocusColor(store.state.user?.settings?.tvFocusColor, store)
  store.app.$eventBus.$on('user-settings', (settings) => {
    applyTvFocusColor(settings?.tvFocusColor, store)
  })
}

export function registerAllTvListeners(store) {
  registerRouterHooks(store)
  registerPlayerWatchers(store)
  registerFocusOutHandler(store)
  registerOverlayWatchers(store)
  registerEventBusSubscribers(store)
}
