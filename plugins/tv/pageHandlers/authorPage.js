/**
 * Author bio page D-pad handler.
 *
 * Handles all vertical navigation on author detail pages. Book cards
 * within the page get prioritized focus when in view; otherwise the
 * page scrolls and looks for newly visible cards.
 *
 * Extracted from plugins/tv-navigation.js during v1.0.10 refactor.
 * Returns true if handled, false to fall through.
 */

import { scrollParentToReveal } from '../scrollHelpers.js'
import { findVerticalTarget } from '../spatialNav.js'

export function handleAuthorPage(event, key, activeEl, authorWrapper) {
  if (key !== 'ArrowDown' && key !== 'ArrowUp') return false

  const focusedIsBookCard = activeEl?.id?.startsWith('author-book-')

  if (focusedIsBookCard) {
    // Currently on a book card — try to find another card in the direction
    const next = findVerticalTarget(key)
    if (next && next.id?.startsWith('author-book-')) {
      event.preventDefault()
      next.focus({ preventScroll: true })
      scrollParentToReveal(next)
      return true
    }
    // No more book cards in this direction — blur and scroll
    event.preventDefault()
    document.activeElement.blur()
    authorWrapper.scrollBy({ top: key === 'ArrowDown' ? 150 : -150, behavior: 'smooth' })
    // When scrolling down past books, check for more cards
    if (key === 'ArrowDown') {
      setTimeout(() => {
        const wrapperRect = authorWrapper.getBoundingClientRect()
        const children = authorWrapper.querySelectorAll('[id^="author-book-"]')
        for (const child of children) {
          const childRect = child.getBoundingClientRect()
          if (childRect.top >= wrapperRect.top && childRect.bottom <= wrapperRect.bottom) {
            child.focus({ preventScroll: true })
            return
          }
        }
      }, 250)
    }
    return true
  }

  // No book card focused — scroll the page
  event.preventDefault()
  authorWrapper.scrollBy({ top: key === 'ArrowDown' ? 150 : -150, behavior: 'smooth' })
  // After scrolling down, check if book cards are now visible
  if (key === 'ArrowDown') {
    setTimeout(() => {
      const wrapperRect = authorWrapper.getBoundingClientRect()
      const children = authorWrapper.querySelectorAll('[id^="author-book-"]')
      for (const child of children) {
        const childRect = child.getBoundingClientRect()
        if (childRect.top >= wrapperRect.top && childRect.bottom <= wrapperRect.bottom) {
          child.focus({ preventScroll: true })
          return
        }
      }
    }, 250)
  }
  return true
}
