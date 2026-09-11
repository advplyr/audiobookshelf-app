/**
 * Audio player D-pad navigation handler.
 *
 * Handles:
 * - Back/Escape while fullscreen → close player + restore focus
 * - Fullscreen player left/right nav (intra-row, including top-row toggle)
 * - Fullscreen player up/down nav (top row ↔ controls ↔ utility)
 *
 * Extracted from plugins/tv-navigation.js during v1.0.10 refactor.
 * Returns true if the event was handled (caller should return), false to fall through.
 */

import { findHorizontalTarget, findVerticalTarget } from '../spatialNav.js'
import { focusAfterPlayerClose } from '../focusEntry.js'

export function handlePlayerNav(event, key, activeEl, streamContainer) {
  const isFullscreen = streamContainer.classList.contains('fullscreen')
  const focusInPlayer = streamContainer.contains(document.activeElement)

  // Back/Escape while player is fullscreen: close the player entirely.
  // collapseFullscreen() on TV calls closePlayback() instead of minimizing.
  if (isFullscreen && (key === 'Escape' || key === 'GoBack' || event.keyCode === 4)) {
    event.preventDefault()
    event.stopImmediatePropagation()
    document.activeElement?.blur()
    const collapseSpan = streamContainer.querySelector('.top-4.left-4 [tabindex="0"]')
    if (collapseSpan) collapseSpan.click()
    // Player closes — restore pre-playback fingerprint if one was saved,
    // else fall back to first content element.
    setTimeout(() => focusAfterPlayerClose(), 500)
    return true
  }

  // Mini player navigation removed — on TV the player always closes
  // entirely instead of minimizing. A future user setting will allow
  // toggling between minimize and close behavior.

  // Navigation within fullscreen player — explicit row-based nav since
  // absolute positioning makes generic findVerticalTarget unreliable.
  // Rows: top (collapse, more_vert) ↔ controls (transport) ↔ utility (bookmark, speed, etc.)
  if (isFullscreen && focusInPlayer) {
    if (key === 'ArrowLeft' || key === 'ArrowRight') {
      event.preventDefault()
      const inTopRow = document.activeElement.closest('.top-4.left-4') ||
        document.activeElement.closest('.top-6.right-4')
      if (inTopRow) {
        // Top row: explicit toggle between collapse and more_vert
        const collapseSpan = streamContainer.querySelector('.top-4.left-4 [tabindex="0"]')
        const moreSpan = streamContainer.querySelector('.top-6.right-4 [tabindex="0"]')
        if (document.activeElement === collapseSpan && moreSpan) {
          moreSpan.focus({ preventScroll: true })
        } else if (document.activeElement === moreSpan && collapseSpan) {
          collapseSpan.focus({ preventScroll: true })
        }
      } else {
        const next = findHorizontalTarget(key)
        if (next && streamContainer.contains(next)) {
          next.focus({ preventScroll: true })
        }
      }
      return true
    }
    if (key === 'ArrowUp' || key === 'ArrowDown') {
      event.preventDefault()
      const playerControls = document.getElementById('playerControls')
      const inControls = playerControls?.contains(document.activeElement)
      const inTopRow = document.activeElement.closest('.top-4.left-4') ||
        document.activeElement.closest('.top-6.right-4')
      const inUtility = !inControls && !inTopRow

      if (key === 'ArrowUp') {
        if (inUtility) {
          // Utility → Controls: focus play/pause
          const playBtn = streamContainer.querySelector('.play-btn')
          if (playBtn) playBtn.focus({ preventScroll: true })
        } else if (inControls) {
          // Controls → Top row: focus collapse button
          const collapseSpan = streamContainer.querySelector('.top-4.left-4 [tabindex="0"]')
          if (collapseSpan) collapseSpan.focus({ preventScroll: true })
        }
        // From top row, Up does nothing
      } else {
        // ArrowDown
        if (inTopRow) {
          // Top row → Controls: focus play/pause
          const playBtn = streamContainer.querySelector('.play-btn')
          if (playBtn) playBtn.focus({ preventScroll: true })
        } else if (inControls) {
          // Controls → Utility: focus first utility button
          const next = findVerticalTarget(key)
          if (next && streamContainer.contains(next)) {
            next.focus({ preventScroll: true })
          }
        }
        // From utility, Down does nothing
      }
      return true
    }
  }

  return false
}
