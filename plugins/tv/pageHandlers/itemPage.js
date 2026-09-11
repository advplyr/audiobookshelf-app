/**
 * Item detail page D-pad handler.
 *
 * When no focusable target exists in the pressed direction, scroll the
 * page so users can browse non-focusable content (cover, metadata,
 * description). When a focusable element scrolls into view, transfer focus.
 *
 * Grid pages (Series, Collections, etc.) skip this — they use the general
 * grid handler which has virtualizer recovery for re-appended cards.
 *
 * Extracted from plugins/tv-navigation.js during v1.0.10 refactor.
 * Returns true if handled, false to fall through.
 */

import { getScrollBehavior, scrollParentToReveal, isGridPage } from '../scrollHelpers.js'
import { findVerticalTarget } from '../spatialNav.js'

export function handleItemPage(event, key, activeEl, itemPage) {
  if (isGridPage()) return false
  if (key !== 'ArrowDown' && key !== 'ArrowUp') return false

  const next = findVerticalTarget(key)
  if (next) {
    event.preventDefault()
    next.focus({ preventScroll: true })
    scrollParentToReveal(next)
    return true
  }
  // No focusable target — scroll the page and check for newly visible elements
  event.preventDefault()
  itemPage.scrollBy({ top: key === 'ArrowDown' ? 150 : -150, behavior: getScrollBehavior() })
  setTimeout(() => {
    const retryTarget = findVerticalTarget(key)
    if (retryTarget) {
      retryTarget.focus({ preventScroll: true })
      scrollParentToReveal(retryTarget)
    }
  }, 300)
  return true
}
