/**
 * Nav bar escape handler.
 *
 * On any page, when pressing Up and no focusable element exists above
 * within the page content, focus the appbar back button or first appbar
 * element. Handles pages like Account, Stats, Settings where content
 * is inside a scroll container separate from the appbar.
 *
 * Extracted from plugins/tv-navigation.js during v1.0.10 refactor.
 * Returns true if handled, false to fall through.
 */

import { findPageScrollContainer } from '../scrollHelpers.js'
import { findVerticalTarget } from '../spatialNav.js'

export function handleNavBarEscape(event, key, activeEl) {
  if (key !== 'ArrowUp') return false
  if (!activeEl || activeEl === document.body) return false

  const pageContainer = findPageScrollContainer()
  if (!pageContainer || !pageContainer.contains(activeEl) || activeEl.closest('#appbar')) return false

  const next = findVerticalTarget(key)
  // Only escape to appbar if there's truly no target above at all.
  // If there IS a target outside the container (e.g. bookshelf-navbar),
  // let the normal handlers deal with it.
  if (next) return false
  if (pageContainer.scrollTop >= 50) return false

  event.preventDefault()
  const appbar = document.getElementById('appbar')
  if (!appbar) return false
  const appbarFocusable = appbar.querySelector('a[tabindex="0"], button, a[href]:not([tabindex="-1"])')
  if (!appbarFocusable) return false

  appbarFocusable.focus({ preventScroll: true })
  return true
}
