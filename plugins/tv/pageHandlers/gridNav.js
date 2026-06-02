/**
 * General grid D-pad handler — the catch-all for ArrowLeft/Right and
 * ArrowUp/Down when no more specific page handler matched.
 *
 * Includes virtualizer recovery: re-finds and re-focuses the target card
 * after scroll settles, since series/collection/playlist cards can be
 * briefly detached during scroll.
 *
 * Extracted from plugins/tv-navigation.js during v1.0.10 refactor.
 * Always preventDefaults on arrow keys — this is the terminal handler.
 * Returns true (always handled when an arrow key) or false (non-arrow).
 */

import { tvContext } from '../context.js'
import { getScrollBehavior, scrollParentToReveal, findPageScrollContainer } from '../scrollHelpers.js'
import { isVisible } from '../visibility.js'
import { findHorizontalTarget, findVerticalTarget } from '../spatialNav.js'

export function handleGridNav(event, key, activeEl) {
  if (key === 'ArrowLeft' || key === 'ArrowRight') {
    const next = findHorizontalTarget(key)
    if (next) {
      event.preventDefault()
      next.focus({ preventScroll: true })
      tvContext.lastFocusRect = next.getBoundingClientRect()
      scrollParentToReveal(next)
    } else {
      event.preventDefault()
    }
    return true
  }

  if (key === 'ArrowUp' || key === 'ArrowDown') {
    event.preventDefault()
    // Guard: the virtualizer can't remove series/collection/playlist cards
    // (wrong ID prefix) so it re-appends them via appendChild, which briefly
    // detaches the focused element.  Suppress focusout recovery during scroll.
    tvContext.verticalNavInProgress = true
    const clearNavGuard = () => { tvContext.verticalNavInProgress = false }
    const next = findVerticalTarget(key)
    if (next) {
      const targetId = next.id
      next.focus({ preventScroll: true })
      tvContext.lastFocusRect = next.getBoundingClientRect()
      scrollParentToReveal(next)
      // The virtualizer may remount cards during scroll, which can drop focus.
      // Re-find and re-focus the target card after the scroll settles.
      if (targetId) {
        setTimeout(() => {
          try {
            if (!document.activeElement || document.activeElement === document.body) {
              // Prefer the visible instance (stale orphans may share the same ID)
              const matches = Array.from(document.querySelectorAll('#' + tvContext.cssEscape(targetId)))
              const refound = matches.find((m) => isVisible(m))
              if (refound) {
                refound.focus({ preventScroll: true })
                tvContext.lastFocusRect = refound.getBoundingClientRect()
              }
            }
          } finally {
            clearNavGuard()
          }
        }, 500)
      } else {
        setTimeout(clearNavGuard, 500)
      }
    } else {
      // Virtualized rows — scroll to trigger rendering, then retry
      const scrollContainer = document.getElementById('bookshelf-wrapper') || findPageScrollContainer()
      if (scrollContainer) {
        const scrollAmount = key === 'ArrowDown' ? 240 : -240
        scrollContainer.scrollBy({ top: scrollAmount, behavior: getScrollBehavior() })
        setTimeout(() => {
          try {
            const retryTarget = findVerticalTarget(key)
            if (retryTarget) {
              retryTarget.focus({ preventScroll: true })
              tvContext.lastFocusRect = retryTarget.getBoundingClientRect()
              scrollParentToReveal(retryTarget)
            }
          } finally {
            clearNavGuard()
          }
        }, 500)
      } else {
        clearNavGuard()
      }
    }
    return true
  }

  return false
}
