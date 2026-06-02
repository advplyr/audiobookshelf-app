/**
 * Overlay (modal / side-drawer / menu) focus save + restore + navigation.
 *
 * Reads/writes tvContext.focusHistory. Uses getAllFocusable for trap-within-overlay.
 *
 * Extracted from plugins/tv-navigation.js during v1.0.10 refactor.
 */

import { tvContext } from './context.js'
import { getAllFocusable } from './visibility.js'

export function saveFocusBeforeOverlay() {
  const active = document.activeElement
  if (active && active !== document.body) {
    tvContext.focusHistory.push(active)
  }
}

export function restoreFocusAfterOverlay() {
  if (tvContext.focusHistory.length === 0) return false
  const previous = tvContext.focusHistory.pop()
  if (!previous || !document.contains(previous)) return false

  // Focus immediately, then re-focus after a short delay to ensure
  // the focus ring renders after the overlay close animation completes
  previous.focus()
  setTimeout(() => {
    if (document.activeElement !== previous) {
      previous.focus()
    }
  }, 100)
  return true
}

/**
 * Check if a modal or overlay is currently open.
 * Returns the overlay element if found, null otherwise.
 *
 * Detection methods:
 * 1. modal-open class on <html> (set by modals-modal base component via Vuex)
 * 2. Side drawer panel without translate-x-64 class (visible drawer)
 */
export function getActiveOverlay() {
  // Check for side drawer first (it doesn't use the modal system)
  const drawerPanel = document.querySelector('.fixed.z-50 .bg-bg.transition-transform:not(.translate-x-64)')
  if (drawerPanel) return drawerPanel

  // Check for any open modal (the Modal.vue base component adds modal-open to <html>
  // and appends the modal to document.body)
  if (document.documentElement.classList.contains('modal-open')) {
    // Find the visible modal in document.body
    const modals = document.querySelectorAll('body > .modal')
    for (const modal of modals) {
      if (modal.style.opacity !== '0') {
        // Dynamically add tabindex to li[role="option"] elements so they're focusable
        modal.querySelectorAll('li[role="option"]:not([tabindex])').forEach((li) => {
          li.setAttribute('tabindex', '0')
        })
        return modal
      }
    }
  }

  return null
}

export function handleOverlayNavigation(event, overlay) {
  const { key } = event
  // Inside overlays, include scrolled-off items in the focusable set so long
  // lists (e.g. OrderModal's 13 sort options in a 70vh scroll container) wrap
  // correctly — scrollIntoView below brings the newly-focused item into view.
  const focusables = getAllFocusable(overlay, { ignoreViewport: true })
  const currentIndex = focusables.indexOf(document.activeElement)

  if (key === 'ArrowDown' || key === 'ArrowRight') {
    event.preventDefault()
    const nextIndex = currentIndex < focusables.length - 1 ? currentIndex + 1 : 0
    focusables[nextIndex]?.focus({ preventScroll: true })
    focusables[nextIndex]?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  } else if (key === 'ArrowUp' || key === 'ArrowLeft') {
    event.preventDefault()
    const prevIndex = currentIndex > 0 ? currentIndex - 1 : focusables.length - 1
    focusables[prevIndex]?.focus({ preventScroll: true })
    focusables[prevIndex]?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  } else if (key === 'Enter') {
    // Trigger a click on the focused element (modal items use @click, not @keydown)
    event.preventDefault()
    document.activeElement?.click()
    return
  }
}
