/**
 * TV navigation visibility + focusable-discovery helpers.
 * Pure functions; no module-level state.
 *
 * Extracted from plugins/tv-navigation.js during v1.0.10 refactor.
 */

export function centerOf(rect) {
  return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }
}

export function isVisible(el, options = {}) {
  const style = window.getComputedStyle(el)
  if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false
  const rect = el.getBoundingClientRect()
  if (rect.width <= 0 || rect.height <= 0) return false
  // Reject elements fully off-screen (e.g. translated drawer items).
  // In overlay context we skip this check so long scrollable lists (e.g. sort
  // modal with 13 items in a 70vh-scroll container) stay fully navigable —
  // scrollIntoView on focus brings off-viewport items into view as they activate.
  if (!options.ignoreViewport) {
    if (rect.right < 0 || rect.left > window.innerWidth) return false
    if (rect.bottom < 0 || rect.top > window.innerHeight) return false
  }
  return true
}

export function isSameRow(rectA, rectB) {
  return rectA.bottom > rectB.top && rectA.top < rectB.bottom
}

export function getAllFocusable(container, options = {}) {
  const root = container || document
  return Array.from(
    root.querySelectorAll('[tabindex="0"], button:not([tabindex="-1"]), a[href]:not([tabindex="-1"]):not([aria-hidden="true"]), input, select, textarea, li[tabindex="0"], li[role="option"][tabindex="0"]')
  ).filter((el) => {
    if (!isVisible(el, options)) return false
    // Exclude disabled elements (focus() silently fails on them)
    if (el.disabled) return false
    // Exclude elements with tabindex="-1" (not intended for D-pad navigation)
    if (el.getAttribute('tabindex') === '-1') return false
    // Exclude links inside podcast episode descriptions (RSS feed content
    // with random URLs that aren't meaningful navigation targets on TV)
    if (el.tagName === 'A' && el.closest('.episode-subtitle, .description-container')) return false
    return true
  })
}
