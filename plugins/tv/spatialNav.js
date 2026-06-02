/**
 * Spatial D-pad target finders.
 *
 * Beam-model navigation: horizontal stays in row at edges; vertical finds
 * nearest element in the next row.
 *
 * Reads tvContext.lastFocusRect to maintain column when focus is briefly
 * lost during virtualizer card detach/reattach cycles.
 *
 * Extracted from plugins/tv-navigation.js during v1.0.10 refactor.
 */

import { tvContext } from './context.js'
import { centerOf, isSameRow, getAllFocusable } from './visibility.js'
import { findPageScrollContainer } from './scrollHelpers.js'

export function findHorizontalTarget(direction) {
  const current = document.activeElement
  if (!current) return null

  const currentRect = current.getBoundingClientRect()
  const goingRight = direction === 'ArrowRight'

  // Exclude player controls from page navigation (player has its own handler)
  const playerContainer = document.getElementById('streamContainer')
  const currentInPlayer = playerContainer?.contains(current)

  const candidates = getAllFocusable().filter((el) => {
    if (el === current) return false
    // Don't navigate between player and page content via horizontal nav
    if (!currentInPlayer && playerContainer?.contains(el)) return false
    if (currentInPlayer && !playerContainer?.contains(el)) return false
    const rect = el.getBoundingClientRect()
    if (!isSameRow(currentRect, rect)) return false
    return goingRight ? rect.left > currentRect.left : rect.right < currentRect.right
  })

  if (candidates.length === 0) return null

  candidates.sort((a, b) => {
    const aRect = a.getBoundingClientRect()
    const bRect = b.getBoundingClientRect()
    const aDist = goingRight ? aRect.left - currentRect.left : currentRect.right - aRect.right
    const bDist = goingRight ? bRect.left - currentRect.left : currentRect.right - bRect.right
    return aDist - bDist
  })

  return candidates[0]
}

export function findVerticalTarget(direction) {
  const current = document.activeElement
  if (!current) return null

  // When the virtualizer detaches the focused card during rapid scrolling,
  // focus falls to body. Use the last known card position to maintain column.
  const focusLost = !current || current === document.body
  const currentRect = focusLost && tvContext.lastFocusRect ? tvContext.lastFocusRect : current.getBoundingClientRect()
  const currentCenter = centerOf(currentRect)
  const goingDown = direction === 'ArrowDown'

  // If focus is lost and we have no saved position, we can't navigate
  if (focusLost && !tvContext.lastFocusRect) return null

  // Find the scrollable container the current element lives in
  const scrollContainer = findPageScrollContainer(focusLost ? null : current)
  const isInScrollable = !focusLost && scrollContainer?.contains(current)

  // Exclude player controls from page navigation (player has its own handler)
  const playerContainer = document.getElementById('streamContainer')
  const currentInPlayer = !focusLost && playerContainer?.contains(current)

  const candidates = getAllFocusable().filter((el) => {
    if (el === current) return false
    // Don't navigate between player and page content via generic vertical nav.
    // The player entry/exit is handled explicitly in handleKeyDown.
    if (!currentInPlayer && playerContainer?.contains(el)) return false
    if (currentInPlayer && !playerContainer?.contains(el)) return false
    const rect = el.getBoundingClientRect()
    if (isSameRow(currentRect, rect)) return false
    const center = centerOf(rect)
    const isCorrectDirection = goingDown ? center.y > currentCenter.y : center.y < currentCenter.y
    if (!isCorrectDirection) return false

    // When navigating up from inside a scrollable area, only allow jumping
    // to elements outside it (e.g. nav bar) if scrolled to the very top
    if (isInScrollable && !goingDown && !scrollContainer.contains(el)) {
      if (scrollContainer.scrollTop > 50) return false
    }

    return true
  })

  if (candidates.length === 0) return null

  // Find the nearest row, then closest element horizontally
  candidates.sort((a, b) => {
    const aDy = Math.abs(centerOf(a.getBoundingClientRect()).y - currentCenter.y)
    const bDy = Math.abs(centerOf(b.getBoundingClientRect()).y - currentCenter.y)
    return aDy - bDy
  })

  const nearestRect = candidates[0].getBoundingClientRect()
  const nearestRow = candidates.filter((el) => isSameRow(nearestRect, el.getBoundingClientRect()))

  nearestRow.sort((a, b) => {
    const aDx = Math.abs(centerOf(a.getBoundingClientRect()).x - currentCenter.x)
    const bDx = Math.abs(centerOf(b.getBoundingClientRect()).x - currentCenter.x)
    return aDx - bDx
  })

  return nearestRow[0]
}
