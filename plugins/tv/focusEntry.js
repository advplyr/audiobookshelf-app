/**
 * TV focus entry/restore helpers + content-change refocus poll.
 *
 * Used by pageHandlers/* and listeners.js to land focus on the right
 * element after navigation or content changes.
 *
 * refocusAfterContentChange was previously closure-scoped inside
 * registerTvListeners; its three timer/flag vars are now on tvContext.
 *
 * Extracted from plugins/tv-navigation.js during v1.0.10 refactor.
 */

import { tvContext } from './context.js'
import { isGridPage, scrollParentToReveal } from './scrollHelpers.js'
import { restoreFromFingerprint } from './focusMemory.js'
import { findPlayButton } from './selectors.js'

export function focusFirstContentElement() {
  // Try cards first (bookshelf pages)
  const card = document.querySelector('[id^="book-card-"], [id^="series-card-"], [id^="collection-card-"], [id^="playlist-card-"], [id^="author-book-"], .author-card-wrapper')
  if (card) {
    card.focus({ preventScroll: true })
    // On grid pages, snap the first card to 10px from top immediately
    if (isGridPage()) {
      scrollParentToReveal(card)
    }
    return true
  }
  // On author detail page or stats page, don't focus anything — let content
  // show at top. D-pad scrolling is handled in handleKeyDown.
  if (document.getElementById('author-page-wrapper') || document.getElementById('stats-page')) {
    // Blur anything that might have auto-focused
    if (document.activeElement && document.activeElement !== document.body) {
      document.activeElement.blur()
    }
    return true
  }
  // On detail pages (playlist, collection, item), focus the Play button.
  // Don't scroll — the page starts at the top and the Play button is
  // already visible below the cover art. Scrolling to "reveal" it pushes
  // the cover image out of view.
  const playBtn = findPlayButton()
  if (playBtn) {
    playBtn.focus({ preventScroll: true })
    return true
  }
  // Fallback: any focusable element in content area, excluding nav bars and toolbars
  const contentArea = document.getElementById('bookshelf-wrapper') || document.querySelector('#content > div > .overflow-y-auto') || document.getElementById('content')
  if (contentArea) {
    const fallback = contentArea.querySelector('[tabindex="0"], button, a[href]:not([tabindex="-1"]):not([aria-hidden="true"])')
    if (fallback) {
      fallback.focus({ preventScroll: true })
      scrollParentToReveal(fallback)
      return true
    }
  }
  // Last resort: if logged out with no content on a main nav page,
  // navigate to Home where the Connect button is always visible.
  // Only triggers on standard navigable pages — NOT on connect, settings,
  // account, or other pages where the user is actively trying to log in.
  if (!tvContext.store?.state?.user?.user) {
    const currentPath = window.location.pathname
    const loggedOutRedirectPages = [
      '/bookshelf/library', '/bookshelf/authors', '/bookshelf/collections',
      '/bookshelf/series', '/bookshelf/playlists', '/bookshelf/latest'
    ]
    if (loggedOutRedirectPages.includes(currentPath)) {
      Object.keys(tvContext.pageFocusMemory).forEach((k) => delete tvContext.pageFocusMemory[k])
      tvContext.focusHistory.length = 0
      tvContext.lastFocusRect = null
      // Access toast via Vuex store's internal Vue instance (Vue.prototype.$toast)
      const toast = tvContext.store._vm?.$toast
      if (toast) {
        toast.info('No user logged in. Returning Home. Click Connect to Login.', { timeout: 5000 })
      }
      tvContext.store.app.router.replace('/')
      return true
    }
  }
  return false
}

// Single entry point for all player-close focus-restore paths (Back button,
// MutationObserver on streamContainer class change, store.watch on session
// state). All three previously raced and called focusFirstContentElement
// independently; this helper unifies them so the pre-playback fingerprint
// (saved by the store.watch on playback start) is consulted regardless of
// which path wins the race.
// Tracked in project_tv_playlist_play_button_regression.md
export async function focusAfterPlayerClose() {
  const currentPath = tvContext.store?.app?.router?.currentRoute?.fullPath || window.location.pathname
  const saved = tvContext.pageFocusMemory[currentPath]

  // If we have a saved fingerprint for the current page, ALWAYS restore it.
  // Android TV's native focus engine aggressively re-focuses a "nearby" element
  // (often the primary Play button) the instant the fullscreen player's focused
  // element is unmounted — guarding against that auto-focus is precisely why
  // this function exists. Any non-body activeElement at this point is almost
  // certainly the native engine's recovery guess, not user intent.
  if (saved) {
    const restored = await restoreFromFingerprint(saved)
    if (restored) return
  }

  // No saved fingerprint OR restore failed — only set focus if we genuinely
  // have no active element (don't steal from whatever the user is using).
  if (!document.activeElement || document.activeElement === document.body) {
    focusFirstContentElement()
  }
}

// Re-focus helper — polls for first book card after content changes.
// Was closure-scoped inside registerTvListeners; promoted to module scope
// with state migrated to tvContext during v1.0.10 refactor.
export function refocusAfterContentChange() {
  if (tvContext.fingerprintRestoreActive) return
  // Clear any existing poll to prevent stacking
  clearTimeout(tvContext.refocusTimeoutId)
  clearInterval(tvContext.refocusIntervalId)
  tvContext.refocusTimeoutId = setTimeout(() => {
    if (tvContext.fingerprintRestoreActive) return
    let attempts = 0
    tvContext.refocusIntervalId = setInterval(() => {
      attempts++
      if (tvContext.fingerprintRestoreActive || focusFirstContentElement() || attempts > 10) {
        clearInterval(tvContext.refocusIntervalId)
        tvContext.refocusIntervalId = null
      }
    }, 500)
  }, 500)
}
