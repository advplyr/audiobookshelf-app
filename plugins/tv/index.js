/**
 * TV Navigation Plugin
 *
 * Provides D-pad spatial navigation for Android TV.
 * Only activates when the android-tv class is present on <html>.
 *
 * Design principles:
 * - Left/Right never leave the current row. At the edges, focus stays put.
 * - Up/Down move to the nearest element in the next row and scroll to show it.
 * - Book cards in a scrollable shelf row are scrolled into view one at a time.
 * - When a modal or drawer is open, navigation is trapped within it.
 */

import { tvContext } from './context.js'
import { findPageScrollContainer } from './scrollHelpers.js'
import { getActiveOverlay, handleOverlayNavigation } from './overlayFocus.js'
import { focusFirstContentElement } from './focusEntry.js'

import { handlePlayerNav } from './pageHandlers/playerNav.js'
import { handleEpisodeRow } from './pageHandlers/episodeRow.js'
import { handleLogsContainer } from './pageHandlers/logsContainer.js'
import { handleNavBarEscape } from './pageHandlers/navBarEscape.js'
import { handleStatsPage } from './pageHandlers/statsPage.js'
import { handleItemPage } from './pageHandlers/itemPage.js'
import { handleAuthorPage } from './pageHandlers/authorPage.js'
import { handleGridNav } from './pageHandlers/gridNav.js'

import { registerAllTvListeners } from './listeners.js'

// All shared mutable state lives on tvContext.
// See plugins/tv/context.js for the full schema.

// ── Event handler ──

function handleKeyDown(event) {
  const key = event.key
  const activeEl = document.activeElement

  // Modal/drawer is open — trap focus within it + handle Escape/back close
  const overlay = getActiveOverlay()
  if (overlay) {
    if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter'].includes(key)) {
      handleOverlayNavigation(event, overlay)
    }
    // Close the overlay on Escape/GoBack/Back, then refocus first card
    if (key === 'Escape' || key === 'GoBack' || event.keyCode === 4) {
      event.preventDefault()
      event.stopImmediatePropagation()
      const refocusAfterClose = () => setTimeout(() => focusFirstContentElement(), 300)
      // Close side drawer
      if (tvContext.store?.state?.showSideDrawer) {
        tvContext.store.commit('setShowSideDrawer', false)
        refocusAfterClose()
        return
      }
      // Close any open modal via the event bus (Modal.vue listens for this)
      if (document.documentElement.classList.contains('modal-open')) {
        tvContext.store?.app?.$eventBus?.$emit('close-modal')
        refocusAfterClose()
        return
      }
      // Close library modal (uses Vuex directly, not the modal base)
      if (tvContext.store?.state?.libraries?.showModal) {
        tvContext.store.commit('libraries/setShowModal', false)
        refocusAfterClose()
        return
      }
    }
    return
  }

  // Audio player navigation + close
  const streamContainer = document.getElementById('streamContainer')
  if (streamContainer && handlePlayerNav(event, key, activeEl, streamContainer)) return

  // Episode row (podcast)
  const episodeRow = activeEl?.closest?.('.border-b')
  if (episodeRow && (key === 'ArrowDown' || key === 'ArrowUp')) {
    if (handleEpisodeRow(event, key, activeEl, episodeRow)) return
  }

  // Logs page scrolling
  const logsContainer = document.getElementById('logs-container')
  if (logsContainer && ['ArrowDown', 'ArrowUp', 'ArrowLeft', 'ArrowRight'].includes(key)) {
    if (handleLogsContainer(event, key, activeEl, logsContainer)) return
  }

  // ArrowUp from page content with no target above → escape to appbar
  if (handleNavBarEscape(event, key, activeEl)) return

  // Stats page (scroll-through with focus pickup)
  const statsPage = document.getElementById('stats-page')
  if (statsPage && handleStatsPage(event, key, activeEl, statsPage)) return

  // Item detail page (scroll page when no focusable target)
  const itemPage = findPageScrollContainer()
  if (itemPage && handleItemPage(event, key, activeEl, itemPage)) return

  // Author bio page
  const authorWrapper = document.getElementById('author-page-wrapper')
  if (authorWrapper && (key === 'ArrowDown' || key === 'ArrowUp')) {
    if (handleAuthorPage(event, key, activeEl, authorWrapper)) return
  }

  // Generic grid navigation (terminal handler)
  handleGridNav(event, key, activeEl)
}

// ── Initialization ──

export default function ({ store }) {
  if (typeof document === 'undefined') return

  tvContext.store = store
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

    registerAllTvListeners(store)
  }

  checkAndInit()
  // Fallback for the CSS-injection race (I2): if the native android-tv class
  // lands after this plugin first runs, keep re-checking for up to ~5s.
  // checkAndInit no-ops once initialized, so this is idempotent.
  if (!initialized) {
    let initAttempts = 0
    const initPoll = setInterval(() => {
      checkAndInit()
      if (initialized || ++initAttempts >= 50) clearInterval(initPoll)
    }, 100)
  }
}
