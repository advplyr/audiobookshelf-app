/**
 * Stats page D-pad handler.
 *
 * Scroll-through content like author bio pages — always scroll first,
 * then transfer focus to elements entering the viewport.
 *
 * Extracted from plugins/tv-navigation.js during v1.0.10 refactor.
 * Returns true if handled, false to fall through.
 */

import { getScrollBehavior } from '../scrollHelpers.js'
import { getAllFocusable } from '../visibility.js'
import { findHorizontalTarget } from '../spatialNav.js'
import { focusFirstContentElement } from '../focusEntry.js'

export function handleStatsPage(event, key, activeEl, statsPage) {
  const focusInStats = activeEl && activeEl !== document.body && statsPage.contains(activeEl)
  const focusInAppbar = activeEl && activeEl !== document.body && activeEl.closest('#appbar')

  // Only handle keys when focus is in stats page, on body (no focus), or
  // in appbar (just escaped to nav bar). Let other handlers run otherwise.
  if (!(focusInStats || focusInAppbar || activeEl === document.body)) return false
  if (!['ArrowDown', 'ArrowUp', 'ArrowLeft', 'ArrowRight'].includes(key)) return false

  // Left/Right in appbar: use generic handler (don't trap) — fall through
  if (focusInAppbar && (key === 'ArrowLeft' || key === 'ArrowRight')) {
    return false
  }
  // Left/Right in stats: horizontal nav within focused row
  if (key === 'ArrowLeft' || key === 'ArrowRight') {
    event.preventDefault()
    if (focusInStats) {
      const next = findHorizontalTarget(key)
      if (next && statsPage.contains(next)) {
        next.focus({ preventScroll: true })
      }
    }
    return true
  }
  // Down from appbar: return to stats page
  if (focusInAppbar && key === 'ArrowDown') {
    event.preventDefault()
    statsPage.scrollTo({ top: 0 })
    focusFirstContentElement()
    return true
  }
  // Up at top of stats: escape to nav bar
  if (key === 'ArrowUp' && statsPage.scrollTop < 50 && !focusInAppbar) {
    event.preventDefault()
    const appbar = document.getElementById('appbar')
    if (appbar) {
      const appbarFocusable = appbar.querySelector('a[tabindex="0"], button, a[href]:not([tabindex="-1"])')
      if (appbarFocusable) {
        appbarFocusable.focus({ preventScroll: true })
        return true
      }
    }
  }
  if (key === 'ArrowDown' || key === 'ArrowUp') {
    event.preventDefault()
    // Blur and scroll
    if (focusInStats) document.activeElement.blur()
    statsPage.scrollBy({ top: key === 'ArrowDown' ? 150 : -150, behavior: getScrollBehavior() })

    // After scroll, find focusable elements and pick the best one
    setTimeout(() => {
      const cr = statsPage.getBoundingClientRect()
      const visible = getAllFocusable().filter((c) => {
        if (!statsPage.contains(c) || c.closest('#appbar')) return false
        const r = c.getBoundingClientRect()
        return r.top >= cr.top && r.bottom <= cr.bottom
      })
      if (!visible.length) return
      // Prefer Next button, then any non-show/hide button
      const nextBtn = visible.find((el) => {
        const txt = el.textContent?.trim()
        return txt?.includes('Next') || txt?.includes('chevron_right')
      })
      if (nextBtn) { nextBtn.focus({ preventScroll: true }); return }
      // Pick last visible (down) or first visible (up), but skip
      // the See Year in Review button if other buttons are available
      const nonToggle = visible.filter((el) => {
        const txt = el.textContent?.trim()
        return !txt?.includes('Year in Review') || visible.length === 1
      })
      const pool = nonToggle.length ? nonToggle : visible
      const target = key === 'ArrowDown' ? pool[pool.length - 1] : pool[0]
      if (target) target.focus({ preventScroll: true })
    }, 250)
    return true
  }

  return false
}
