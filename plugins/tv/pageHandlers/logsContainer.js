/**
 * Logs page D-pad handler.
 *
 * No focusable elements in the log itself — just scroll. Header buttons
 * (copy, share, ellipsis) are reachable only when scrolled to the top.
 *
 * Extracted from plugins/tv-navigation.js during v1.0.10 refactor.
 * Returns true if handled, false to fall through.
 */

import { getScrollBehavior } from '../scrollHelpers.js'

export function handleLogsContainer(event, key, activeEl, logsContainer) {
  const logsPage = logsContainer.parentElement
  const focusOnHeader = activeEl && activeEl !== document.body && logsPage?.contains(activeEl) && !logsContainer.contains(activeEl)
  const focusInAppbar = activeEl && activeEl !== document.body && activeEl.closest('#appbar')

  // Left/Right on header: use generic horizontal nav — fall through
  if (focusOnHeader && (key === 'ArrowLeft' || key === 'ArrowRight')) {
    return false
  }
  // Left/Right elsewhere: ignore
  if (key === 'ArrowLeft' || key === 'ArrowRight') {
    event.preventDefault()
    return true
  }
  // Down from appbar: return to logs
  if (focusInAppbar && key === 'ArrowDown') {
    event.preventDefault()
    const headerBtn = logsPage?.querySelector('button')
    if (headerBtn) headerBtn.focus({ preventScroll: true })
    return true
  }
  // Up from header: escape to nav bar — fall through
  if (focusOnHeader && key === 'ArrowUp') {
    return false
  }
  // Down from header: blur and enter log scrolling
  if (focusOnHeader && key === 'ArrowDown') {
    event.preventDefault()
    document.activeElement.blur()
    logsContainer.scrollBy({ top: 200, behavior: getScrollBehavior() })
    return true
  }
  // Up: scroll log up. When at top, move to header buttons
  if (key === 'ArrowUp') {
    event.preventDefault()
    if (logsContainer.scrollTop < 10) {
      const headerBtn = logsPage?.querySelector('button')
      if (headerBtn) headerBtn.focus({ preventScroll: true })
    } else {
      logsContainer.scrollBy({ top: -200, behavior: getScrollBehavior() })
    }
    return true
  }
  // Down: scroll log down
  if (key === 'ArrowDown') {
    event.preventDefault()
    logsContainer.scrollBy({ top: 200, behavior: getScrollBehavior() })
    return true
  }

  return false
}
