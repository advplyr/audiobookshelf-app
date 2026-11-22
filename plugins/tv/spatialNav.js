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

  // Snapshot every candidate's rect ONCE. getBoundingClientRect forces a
  // synchronous layout; calling it inside the filter + sort comparator below
  // (once per candidate, O(n log n) in the sort) thrashes layout on every
  // keypress. Reading from the Map instead collapses that to one reflow per
  // focusable. The Map is ephemeral per call — no invalidation needed (I4).
  const focusables = getAllFocusable()
  const rectMap = new Map()
  for (const el of focusables) rectMap.set(el, el.getBoundingClientRect())

  const candidates = focusables.filter((el) => {
    if (el === current) return false
    // Don't navigate between player and page content via horizontal nav
    if (!currentInPlayer && playerContainer?.contains(el)) return false
    if (currentInPlayer && !playerContainer?.contains(el)) return false
    const rect = rectMap.get(el)
    if (!isSameRow(currentRect, rect)) return false
    return goingRight ? rect.left > currentRect.left : rect.right < currentRect.right
  })

  if (candidates.length === 0) return null

  candidates.sort((a, b) => {
    const aRect = rectMap.get(a)
    const bRect = rectMap.get(b)
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

  // Snapshot every candidate's rect ONCE — see findHorizontalTarget (I4).
  const focusables = getAllFocusable()
  const rectMap = new Map()
  for (const el of focusables) rectMap.set(el, el.getBoundingClientRect())

  const candidates = focusables.filter((el) => {
    if (el === current) return false
    // Don't navigate between player and page content via generic vertical nav.
    // The player entry/exit is handled explicitly in handleKeyDown.
    if (!currentInPlayer && playerContainer?.contains(el)) return false
    if (currentInPlayer && !playerContainer?.contains(el)) return false
    const rect = rectMap.get(el)
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
    const aDy = Math.abs(centerOf(rectMap.get(a)).y - currentCenter.y)
    const bDy = Math.abs(centerOf(rectMap.get(b)).y - currentCenter.y)
    return aDy - bDy
  })

  const nearestRect = rectMap.get(candidates[0])
  const nearestRow = candidates.filter((el) => isSameRow(nearestRect, rectMap.get(el)))

  nearestRow.sort((a, b) => {
    const aDx = Math.abs(centerOf(rectMap.get(a)).x - currentCenter.x)
    const bDx = Math.abs(centerOf(rectMap.get(b)).x - currentCenter.x)
    return aDx - bDx
  })

  return nearestRow[0]
}

/**
 * Column-stable grid vertical target — immune to the native TV focus engine.
 *
 * Root problem: the LazyBookshelf virtualizer `el.remove()`s the focused card
 * mid-scroll, and the native Android-TV focus engine then re-homes focus to an
 * edge column (deterministically the last). Reading the column from wherever
 * focus currently is (live `activeElement`) therefore drifts to that edge.
 *
 * Fix: take the ROW from where focus actually is (so we follow the scroll), but
 * the COLUMN from tvContext.gridIntendedCol — the user's chosen column, set only
 * on Left/Right + first focus, never from live focus — and re-assert it every
 * press. The target is then `(row ± 1) * itemsPerRow + intendedCol`, focused by
 * id, so a native hijack to the last column is undone on the very next keypress.
 *
 * Returns `{ card, index, col, prefix }` on a hit, or null (not a shelf grid /
 * nothing remembered / past the first row / target not mounted) — caller falls
 * back to geometry or its scroll-and-retry.
 */
export function findShelfVerticalTarget(direction) {
  const current = document.activeElement
  const liveCard =
    current && current !== document.body && current.closest && current.closest('[id^="shelf-"]')
      ? current
      : null

  let index
  let prefix
  let currentShelf = null
  if (liveCard) {
    const m = liveCard.id.match(/^(.*-card-)(\d+)$/)
    if (!m) return null
    prefix = m[1]
    index = parseInt(m[2], 10)
    currentShelf = liveCard.closest('[id^="shelf-"]')
  } else if (tvContext.lastGridIndex != null && tvContext.lastGridPrefix) {
    index = tvContext.lastGridIndex
    prefix = tvContext.lastGridPrefix
    const known = document.getElementById(prefix + index)
    currentShelf = known ? known.closest('[id^="shelf-"]') : null
  } else {
    return null
  }
  if (Number.isNaN(index) || !prefix) return null

  const itemsPerRow = gridItemsPerRow(currentShelf)
  if (!itemsPerRow) return null

  // ROW follows where focus actually is (so we track the scroll, even after a
  // native hijack); COLUMN is the sticky intended column — immune to the
  // engine's edge-column hijack. Before any Left/Right has set an intended
  // column, derive it once from the live card.
  const currentRow = Math.floor(index / itemsPerRow)
  const intendedCol = tvContext.gridIntendedCol != null ? tvContext.gridIntendedCol : index % itemsPerRow

  const targetRow = direction === 'ArrowDown' ? currentRow + 1 : currentRow - 1
  if (targetRow < 0) return null

  const targetIndex = targetRow * itemsPerRow + intendedCol
  const targetCard = document.getElementById(prefix + targetIndex)
  if (!targetCard) return null // not mounted yet / past the end — caller handles it

  return { card: targetCard, index: targetIndex, col: intendedCol, prefix }
}

// Record a focused card's column as the INTENDED column (plus index + prefix).
// Called on Left/Right and first focus — deliberate column choices — so
// gridIntendedCol tracks user intent and is never set from a native hijack.
export function rememberGridCol(card) {
  if (!card || !card.id) return
  const m = card.id.match(/^(.*-card-)(\d+)$/)
  if (!m) return
  const epp = gridItemsPerRow(card.closest ? card.closest('[id^="shelf-"]') : null)
  if (!epp) return
  tvContext.lastGridPrefix = m[1]
  tvContext.lastGridIndex = parseInt(m[2], 10)
  tvContext.gridIntendedCol = tvContext.lastGridIndex % epp
}

// Record only a card's index + prefix — the ROW anchor — WITHOUT touching
// gridIntendedCol. Used on the vertical geometry fallback, where focus may land
// on the engine's hijacked column: we must follow the row but must NOT let that
// wrong column overwrite the user's intended column (doing so poisoned the
// intended column, so the correction poll treated the hijacked column as
// "correct" and never fired).
export function rememberGridRow(card) {
  if (!card || !card.id) return
  const m = card.id.match(/^(.*-card-)(\d+)$/)
  if (!m) return
  tvContext.lastGridPrefix = m[1]
  tvContext.lastGridIndex = parseInt(m[2], 10)
}

// Items per grid row (entitiesPerShelf), read off the current in-view shelf —
// a full row gives the true value, kept as a monotonic max so a short final row
// never shrinks it. Cached on tvContext; reset on route change.
function gridItemsPerRow(currentShelf) {
  const observed = currentShelf ? shelfCards(currentShelf).length : 0
  if (observed > (tvContext.gridItemsPerRow || 0)) tvContext.gridItemsPerRow = observed
  return tvContext.gridItemsPerRow || 0
}

// Focusable card children of a shelf row, in DOM order — which equals visual
// column order because mountEntityCard appends cards ascending by index. The
// only other shelf child is the non-focusable .bookshelfDivider.
function shelfCards(shelfEl) {
  return Array.from(shelfEl.children).filter((el) => el.getAttribute('tabindex') === '0')
}
