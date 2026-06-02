/**
 * TV navigation scroll-helper functions + page-type detection.
 *
 * Uses tvContext.lastKeyTime for rapid-keypress detection.
 * `isGridPage` lives here (not in focusEntry) to avoid a circular
 * dependency: scrollParentToReveal needs isGridPage, focusEntry needs
 * both. Having both here keeps the DAG clean.
 *
 * Extracted from plugins/tv-navigation.js during v1.0.10 refactor.
 */

import { tvContext } from './context.js'

// ── Page scroll container lookup ──
// Centralized lookup for the main scrollable container on the current page.
const pageScrollContainerIds = [
  'bookshelf-wrapper', 'author-page-wrapper', 'item-page', 'episode-page',
  'manage-files-page', 'settings-page', 'stats-page', 'account-page', 'logs-container'
]

export function findPageScrollContainer(fallbackEl) {
  for (const id of pageScrollContainerIds) {
    const el = document.getElementById(id)
    if (el) return el
  }
  return fallbackEl ? findScrollableParent(fallbackEl) : null
}

// Detail-like pages where we suppress snap-to-top scrolling
export function isDetailScrollContainer(id) {
  return id === 'item-page' || id === 'episode-page' || id === 'manage-files-page' || id === 'settings-page' || id === 'stats-page' || id === 'account-page'
}

export function isGridPage() {
  const path = window.location.pathname
  return path.startsWith('/bookshelf/') && path !== '/bookshelf'
}

export function getScrollBehavior() {
  const now = Date.now()
  const rapid = (now - tvContext.lastKeyTime) < 250
  tvContext.lastKeyTime = now
  return rapid ? 'auto' : 'smooth'
}

export function findScrollableParent(el) {
  let parent = el.parentElement
  while (parent && parent !== document.body) {
    const style = window.getComputedStyle(parent)
    if (style.overflowY === 'auto' || style.overflowY === 'scroll') {
      return parent
    }
    parent = parent.parentElement
  }
  return null
}

export function scrollParentToReveal(el) {
  const behavior = getScrollBehavior()

  // Horizontal: scroll the shelf row to reveal the card
  let parent = el.parentElement
  while (parent) {
    const style = window.getComputedStyle(parent)
    if (style.overflowX === 'auto' || style.overflowX === 'scroll') {
      const parentRect = parent.getBoundingClientRect()
      const elRect = el.getBoundingClientRect()

      if (elRect.right > parentRect.right) {
        parent.scrollBy({ left: elRect.right - parentRect.right + 20, behavior })
      } else if (elRect.left < parentRect.left) {
        parent.scrollBy({ left: elRect.left - parentRect.left - 20, behavior })
      }
      break
    }
    parent = parent.parentElement
  }

  // Vertical: find the scrollable container and scroll to show the focused element
  const scrollContainer = findPageScrollContainer(el)
  if (scrollContainer) {
    const elRect = el.getBoundingClientRect()
    const containerRect = scrollContainer.getBoundingClientRect()

    const gridPage = isGridPage()

    // On non-grid pages (home), if the element is near the top of
    // the content (within first 300px), scroll to very top for a clean look.
    // Skip for item detail pages — the cover image occupies the top area and
    // snapping to it creates erratic scrolling.
    const isItemPage = isDetailScrollContainer(scrollContainer.id)
    const elOffsetInContent = elRect.top - containerRect.top + scrollContainer.scrollTop
    if (!gridPage && !isItemPage && scrollContainer.scrollTop > 0 && elOffsetInContent < 300) {
      scrollContainer.scrollTo({ top: 0, behavior })
    } else {
      // Grid pages snap card rows tight to the top (10px clearance).
      // Other pages (home, detail, etc.) keep more breathing room for headers.
      // Item detail pages: only scroll if the element is actually off-screen,
      // so cover ↔ Play button transitions don't cause unnecessary scrolling.
      const topMargin = gridPage ? 10 : 60
      const targetOffset = elRect.top - containerRect.top - topMargin
      if (isItemPage) {
        // Only scroll if the element is not fully visible in the container.
        // This prevents unnecessary scrolling when navigating between elements
        // that are both on-screen (e.g. cover ↔ Play button at scroll=0).
        const fullyVisible = elRect.top >= containerRect.top && elRect.bottom <= containerRect.bottom
        if (!fullyVisible) {
          scrollContainer.scrollBy({ top: targetOffset, behavior })
        }
      } else {
        const deadZone = gridPage ? 10 : (scrollContainer.scrollTop < 10 ? 80 : 10)
        if (Math.abs(targetOffset) > deadZone) {
          scrollContainer.scrollBy({ top: targetOffset, behavior })
        }
      }
    }
  } else {
    const elRect = el.getBoundingClientRect()
    const viewportHeight = window.innerHeight || document.documentElement.clientHeight
    if (elRect.top < 0 || elRect.bottom > viewportHeight) {
      el.scrollIntoView({ block: 'nearest', behavior })
    }
  }
}
