/**
 * Podcast episode row D-pad handler.
 *
 * Handles intra-episode navigation (title ↔ controls) and inter-episode
 * navigation (snap-to-top on next/previous episode).
 *
 * Extracted from plugins/tv-navigation.js during v1.0.10 refactor.
 * Returns true if handled, false to fall through.
 */

import { findPageScrollContainer, getScrollBehavior, scrollParentToReveal } from '../scrollHelpers.js'
import { findVerticalTarget } from '../spatialNav.js'

export function handleEpisodeRow(event, key, activeEl, episodeRow) {
  const isOnEpisodeTitle = activeEl?.id?.startsWith('episode-')

  if (isOnEpisodeTitle && key === 'ArrowDown') {
    // Title → Play button (first focusable in the controls row)
    const playBtn = episodeRow.querySelector('div[tabindex="0"]')
    if (playBtn) {
      event.preventDefault()
      playBtn.focus({ preventScroll: true })
      return true
    }
  }
  if (!isOnEpisodeTitle && key === 'ArrowUp') {
    // Controls → back up to episode title (only if we're below the title)
    const title = episodeRow.querySelector('p[id^="episode-"]')
    if (title) {
      const titleRect = title.getBoundingClientRect()
      const activeRect = activeEl.getBoundingClientRect()
      if (activeRect.top >= titleRect.bottom - 5) {
        // We're below the title (controls area) — go up to title
        // and scroll to show the full episode row
        event.preventDefault()
        title.focus({ preventScroll: true })
        const scrollContainer = findPageScrollContainer(title)
        if (scrollContainer) {
          const rowRect = episodeRow.getBoundingClientRect()
          const containerRect = scrollContainer.getBoundingClientRect()
          if (rowRect.top < containerRect.top) {
            scrollContainer.scrollBy({ top: rowRect.top - containerRect.top + 5, behavior: getScrollBehavior() })
          }
        }
        return true
      }
      // We're above the title (podcast name link) — go to previous episode's play button
      const next = findVerticalTarget(key)
      if (next) {
        event.preventDefault()
        const prevRow = next.closest?.('.border-b')
        let target = next
        if (prevRow && prevRow !== episodeRow) {
          const playBtn = prevRow.querySelector('div[tabindex="0"]')
          if (playBtn) target = playBtn
        }
        target.focus({ preventScroll: true })
        // Snap the previous episode row to top
        const targetRow = target.closest?.('.border-b')
        if (targetRow) {
          const scrollContainer = findPageScrollContainer(target)
          if (scrollContainer) {
            const rowRect = targetRow.getBoundingClientRect()
            const containerRect = scrollContainer.getBoundingClientRect()
            const offset = rowRect.top - containerRect.top + 5
            if (offset < 0) scrollContainer.scrollBy({ top: offset, behavior: getScrollBehavior() })
          }
        } else {
          scrollParentToReveal(target)
        }
        return true
      }
    }
  }
  if (isOnEpisodeTitle && key === 'ArrowUp') {
    // Title → previous episode's play button or page content above
    const next = findVerticalTarget(key)
    if (next) {
      event.preventDefault()
      // If landing in another episode row, force focus to its play button
      const nextRow = next.closest?.('.border-b')
      let target = next
      if (nextRow && nextRow !== episodeRow) {
        const playBtn = nextRow.querySelector('div[tabindex="0"]')
        if (playBtn) target = playBtn
      }
      target.focus({ preventScroll: true })
      // Scroll to show the episode row
      if (nextRow) {
        const scrollContainer = findPageScrollContainer(target)
        if (scrollContainer) {
          const rowRect = nextRow.getBoundingClientRect()
          const containerRect = scrollContainer.getBoundingClientRect()
          const offset = rowRect.top - containerRect.top + 5
          if (offset < 0) scrollContainer.scrollBy({ top: offset, behavior: getScrollBehavior() })
        }
      } else {
        scrollParentToReveal(target)
      }
      return true
    }
  }
  if (!isOnEpisodeTitle && key === 'ArrowDown') {
    // From controls, move to next episode title — snap to top
    const next = findVerticalTarget(key)
    if (next) {
      event.preventDefault()
      next.focus({ preventScroll: true })
      // Snap the next episode row to the top of the viewport
      const nextRow = next.closest?.('.border-b')
      const scrollTarget = nextRow || next
      const scrollContainer = findPageScrollContainer(next)
      if (scrollContainer) {
        const targetRect = scrollTarget.getBoundingClientRect()
        const containerRect = scrollContainer.getBoundingClientRect()
        const offset = targetRect.top - containerRect.top + 5
        if (Math.abs(offset) > 10) scrollContainer.scrollBy({ top: offset, behavior: getScrollBehavior() })
      }
      return true
    }
  }

  return false
}
