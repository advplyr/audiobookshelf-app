/**
 * TV focus memory / fingerprint system.
 *
 * Tracks focused elements via stable fingerprints (ID + structural path +
 * position) so Back navigation can restore focus to the same logical
 * element even if the virtualizer recycled the DOM node.
 *
 * Reads/writes tvContext.pageFocusMemory and uses tvContext.cssEscape.
 *
 * Extracted from plugins/tv-navigation.js during v1.0.10 refactor.
 */

import { tvContext } from './context.js'
import { isVisible, getAllFocusable } from './visibility.js'
import { findPageScrollContainer, scrollParentToReveal } from './scrollHelpers.js'

export function getElementFingerprint(el) {
  if (!el || el === document.body) return null

  const rect = el.getBoundingClientRect()
  const scrollContainer = findPageScrollContainer(el)
  const scrollTop = scrollContainer?.scrollTop || 0

  const fingerprint = {
    scrollTop,
    relativeTop: scrollContainer ? rect.top - scrollContainer.getBoundingClientRect().top + scrollTop : rect.top,
    relativeLeft: rect.left,
    tagName: el.tagName
  }

  // Prefer ID-based selectors
  if (el.id) {
    fingerprint.selector = '#' + tvContext.cssEscape(el.id)
    fingerprint.idIsUnique = document.querySelectorAll('#' + tvContext.cssEscape(el.id)).length === 1
  }
  // For author cards
  if (el.classList.contains('author-card-wrapper')) {
    const siblings = Array.from(document.querySelectorAll('.author-card-wrapper'))
    fingerprint.authorIndex = siblings.indexOf(el)
  }
  // For buttons with aria-label
  if (el.tagName === 'BUTTON' && el.getAttribute('aria-label')) {
    const label = el.getAttribute('aria-label').replace(/"/g, '\\"')
    fingerprint.selector = `button[aria-label="${label}"]`
  }

  // Build a structural path: find the element's index among same-tag siblings
  // within its parent, walking up to a parent with an ID or a known class
  fingerprint.structuralPath = buildStructuralPath(el)

  // Save index among all focusable elements as last resort
  const allFocusable = getAllFocusable()
  fingerprint.focusableIndex = allFocusable.indexOf(el)
  fingerprint.totalFocusable = allFocusable.length

  return fingerprint
}

// Internal: not exported. Used by getElementFingerprint + restoreFromFingerprint.
function buildStructuralPath(el) {
  const parts = []
  let current = el
  let depth = 0
  while (current && current !== document.body && depth < 10) {
    const parent = current.parentElement
    if (!parent) break
    const siblings = Array.from(parent.children).filter((c) => c.tagName === current.tagName)
    const index = siblings.indexOf(current)
    parts.unshift({ tag: current.tagName, index, id: current.id || null })
    if (current.id) break // Stop at first ancestor with an ID
    current = parent
    depth++
  }
  return parts
}

// Internal: not exported.
function findByStructuralPath(path) {
  if (!path || path.length === 0) return null
  // Start from the element with an ID (if any) and walk down
  let root = null
  let startIdx = 0
  for (let i = 0; i < path.length; i++) {
    if (path[i].id) {
      root = document.getElementById(path[i].id)
      startIdx = i + 1
      break
    }
  }
  if (!root) {
    // No ID anchor — walk from body
    root = document.body
    startIdx = 0
  }
  let current = root
  for (let i = startIdx; i < path.length; i++) {
    const step = path[i]
    const candidates = Array.from(current.children).filter((c) => c.tagName === step.tag)
    if (step.index >= candidates.length) return null
    current = candidates[step.index]
  }
  return current
}

export function restoreFromFingerprint(fingerprint) {
  if (!fingerprint) return false

  // Ensure scroll position is restored before looking for the element.
  // Called synchronously at the start of each tryRestore attempt so the
  // target element is in the viewport when isVisible runs.
  // Looks up the scroll container fresh each time — the page may not have
  // mounted yet on the first call (afterEach fires before Vue renders).
  const ensureScroll = () => {
    if (fingerprint.scrollTop <= 0) return
    const scrollContainer = document.getElementById('bookshelf-wrapper') || document.querySelector('.overflow-y-auto')
    if (scrollContainer && scrollContainer.scrollTop < fingerprint.scrollTop * 0.5) {
      scrollContainer.scrollTop = fingerprint.scrollTop
    }
  }

  // Wait for content to mount after scroll, then find the element.
  // Use multiple attempts to handle slow-loading content (server fetch on first visit).
  // ID-based lookups retry longer before falling through to position fallback.
  return new Promise((resolve) => {
    let attempts = 0
    const maxAttempts = 12
    // Fast-poll first, then slow down for patient retries
    const retryDelay = () => attempts < 5 ? 250 : 500
    const tryRestore = () => {
      attempts++
      // Apply scroll before element lookup so target is in viewport
      ensureScroll()
      // Look up scroll container fresh — page may have just mounted
      const scrollContainer = document.getElementById('bookshelf-wrapper') || document.querySelector('.overflow-y-auto')
      let el = null

      // 1. Try unique ID — but verify visibility (the virtualizer may leave
      //    stale orphan nodes in the DOM with the same ID as the live card)
      if (!el && fingerprint.selector && fingerprint.idIsUnique) {
        const candidates = Array.from(document.querySelectorAll(fingerprint.selector))
        // Prefer the visible one; stale orphans have zero-size bounding rects
        el = candidates.find((c) => isVisible(c)) || null
      }

      // 2. Author cards by index
      if (!el && fingerprint.authorIndex !== undefined) {
        const authors = document.querySelectorAll('.author-card-wrapper')
        el = authors[fingerprint.authorIndex]
      }

      // 3. Non-unique IDs — position match (filter to visible candidates only)
      if (!el && fingerprint.selector && !fingerprint.idIsUnique) {
        const candidates = Array.from(document.querySelectorAll(fingerprint.selector)).filter((c) => isVisible(c))
        if (candidates.length === 1) {
          el = candidates[0]
        } else if (candidates.length > 1) {
          let bestDist = Infinity
          for (const candidate of candidates) {
            const rect = candidate.getBoundingClientRect()
            const containerRect = scrollContainer?.getBoundingClientRect()
            const relTop = containerRect ? rect.top - containerRect.top + (scrollContainer?.scrollTop || 0) : rect.top
            const dist = Math.abs(relTop - fingerprint.relativeTop) + Math.abs(rect.left - fingerprint.relativeLeft)
            if (dist < bestDist) {
              bestDist = dist
              el = candidate
            }
          }
        }
      }

      // 4. Structural path (for elements without IDs, like playlist buttons)
      if (!el && fingerprint.structuralPath && !fingerprint.selector) {
        el = findByStructuralPath(fingerprint.structuralPath)
        if (el && fingerprint.tagName && el.tagName !== fingerprint.tagName) el = null
        if (el && !isVisible(el)) el = null
      }

      // If we have a specific lookup method (ID selector, author index, or
      // structural path) and haven't found the element yet, keep retrying —
      // page content may still be loading after navigation.
      // Only fall through to position fallback on the final attempts.
      const hasSpecificLookup = fingerprint.selector || fingerprint.authorIndex !== undefined || fingerprint.structuralPath
      if (!el && hasSpecificLookup && attempts < maxAttempts - 2) {
        setTimeout(tryRestore, retryDelay())
        return
      }

      // 5. Fallback: position match among content focusable elements (not nav/toolbar)
      if (!el) {
        const allFocusable = getAllFocusable().filter((c) =>
          !c.closest('#appbar') && !c.closest('#bookshelf-navbar') && !c.closest('#bookshelf-toolbar')
        )
        if (allFocusable.length > 0) {
          let bestDist = Infinity
          const expectedTop = fingerprint.relativeTop - fingerprint.scrollTop + (scrollContainer?.getBoundingClientRect()?.top || 0)
          for (const candidate of allFocusable) {
            const cRect = candidate.getBoundingClientRect()
            // Prefer same tag type
            const tagPenalty = (fingerprint.tagName && candidate.tagName !== fingerprint.tagName) ? 200 : 0
            const dist = Math.abs(cRect.left - fingerprint.relativeLeft) + Math.abs(cRect.top - expectedTop) + tagPenalty
            if (dist < bestDist) {
              bestDist = dist
              el = candidate
            }
          }
        }
      }

      if (el) {
        el.focus({ preventScroll: true })
        scrollParentToReveal(el)
        resolve(true)
      } else if (attempts < maxAttempts) {
        setTimeout(tryRestore, retryDelay())
      } else {
        resolve(false)
      }
    }
    setTimeout(tryRestore, 300)
  })
}
