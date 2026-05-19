/**
 * TV Navigation Plugin
 *
 * Provides D-pad spatial navigation for Android TV.
 * Only activates when the android-tv class is present on <html>.
 *
 * Design principles:
 * - Left/Right never leave the current row. At the edges, focus stays put.
 * - Up/Down move to the nearest element in the next row and scroll to show it.
 * - Book cards in a scrollable shelf row are scrolled into view one at a time.
 * - When a modal or drawer is open, navigation is trapped within it.
 */

// ── Vuex store reference ──
// Set by the default export when the plugin initializes.
// Used by handleKeyDown for overlay dismissal (drawer/modal close).
let _store = null

// Valid focus-ring colors (must match TvFocusColorPicker.vue PRESETS)
const VALID_TV_FOCUS_HEXES = ['#1ad691', '#3ea6ff', '#ffb74d', '#ff5252', '#e040fb', '#ffeb3b', '#ffffff']
const DEFAULT_TV_FOCUS_HEX = '#1ad691'

function applyTvFocusColor(value, store) {
  const hex = VALID_TV_FOCUS_HEXES.includes(value) ? value : DEFAULT_TV_FOCUS_HEX
  document.documentElement.style.setProperty('--tv-focus-color', hex)
  // Self-heal: if the stored value was bad, dispatch a corrective save.
  if (value && hex !== value && store) {
    store.dispatch('user/updateUserSettings', { tvFocusColor: DEFAULT_TV_FOCUS_HEX })
  }
}

// ── Focus history stack ──
// Tracks which element was focused before an overlay opened.
// Supports nested overlays (e.g. menu -> submenu).
const focusHistory = []

// ── Vertical navigation guard ──
// Suppresses the focusout recovery handler while a vertical nav scroll is
// settling.  The virtualizer may appendChild a still-focused card (series,
// collection, playlist) which briefly detaches it, firing focusout.  Without
// this guard the 200 ms focusout timer beats the 350 ms recovery timer and
// snaps focus to the first card.
let verticalNavInProgress = false

// ── Last known focus position ──
// Tracks the bounding rect center of the last focused card so that when
// the virtualizer detaches a card during rapid scrolling (focus falls to
// body), findVerticalTarget can still pick the correct column.
let lastFocusRect = null

// CSS.escape polyfill for older Android TV WebViews (Chrome < 46)
const cssEscape = typeof CSS !== 'undefined' && CSS.escape ? CSS.escape : (s) => s.replace(/([^\w-])/g, '\\$1')

// ── Page focus memory ──
// Saves the focused element selector per route so Back navigation
// can restore focus to where the user was. TV only.
const pageFocusMemory = {}

function getElementFingerprint(el) {
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
    fingerprint.selector = '#' + cssEscape(el.id)
    fingerprint.idIsUnique = document.querySelectorAll('#' + cssEscape(el.id)).length === 1
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

function restoreFromFingerprint(fingerprint) {
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

function saveFocusBeforeOverlay() {
  const active = document.activeElement
  if (active && active !== document.body) {
    focusHistory.push(active)
  }
}

function restoreFocusAfterOverlay() {
  if (focusHistory.length === 0) return false
  const previous = focusHistory.pop()
  if (!previous || !document.contains(previous)) return false

  // Focus immediately, then re-focus after a short delay to ensure
  // the focus ring renders after the overlay close animation completes
  previous.focus()
  setTimeout(() => {
    if (document.activeElement !== previous) {
      previous.focus()
    }
  }, 100)
  return true
}

// ── Helpers ──

function centerOf(rect) {
  return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }
}

function isVisible(el, options = {}) {
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

function isSameRow(rectA, rectB) {
  return rectA.bottom > rectB.top && rectA.top < rectB.bottom
}

/**
 * Check if a modal or overlay is currently open.
 * Returns the overlay element if found, null otherwise.
 *
 * Detection methods:
 * 1. modal-open class on <html> (set by modals-modal base component via Vuex)
 * 2. Side drawer panel without translate-x-64 class (visible drawer)
 */
function getActiveOverlay() {
  // Check for side drawer first (it doesn't use the modal system)
  const drawerPanel = document.querySelector('.fixed.z-50 .bg-bg.transition-transform:not(.translate-x-64)')
  if (drawerPanel) return drawerPanel

  // Check for any open modal (the Modal.vue base component adds modal-open to <html>
  // and appends the modal to document.body)
  if (document.documentElement.classList.contains('modal-open')) {
    // Find the visible modal in document.body
    const modals = document.querySelectorAll('body > .modal')
    for (const modal of modals) {
      if (modal.style.opacity !== '0') {
        // Dynamically add tabindex to li[role="option"] elements so they're focusable
        modal.querySelectorAll('li[role="option"]:not([tabindex])').forEach((li) => {
          li.setAttribute('tabindex', '0')
        })
        return modal
      }
    }
  }

  return null
}

function getAllFocusable(container, options = {}) {
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

// ── Page scroll container lookup ──
// Centralized lookup for the main scrollable container on the current page.
const pageScrollContainerIds = [
  'bookshelf-wrapper', 'author-page-wrapper', 'item-page', 'episode-page',
  'manage-files-page', 'settings-page', 'stats-page', 'account-page', 'logs-container'
]
function findPageScrollContainer(fallbackEl) {
  for (const id of pageScrollContainerIds) {
    const el = document.getElementById(id)
    if (el) return el
  }
  return fallbackEl ? findScrollableParent(fallbackEl) : null
}

// Detail-like pages where we suppress snap-to-top scrolling
function isDetailScrollContainer(id) {
  return id === 'item-page' || id === 'episode-page' || id === 'manage-files-page' || id === 'settings-page' || id === 'stats-page' || id === 'account-page'
}

// ── Scrolling ──

// Track rapid keypresses to switch from smooth to instant scrolling
let lastKeyTime = 0
function getScrollBehavior() {
  const now = Date.now()
  const rapid = (now - lastKeyTime) < 250
  lastKeyTime = now
  return rapid ? 'auto' : 'smooth'
}

function findScrollableParent(el) {
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

function scrollParentToReveal(el) {
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

// ── Overlay navigation (modals, drawers) ──

function handleOverlayNavigation(event, overlay) {
  const { key } = event
  // Inside overlays, include scrolled-off items in the focusable set so long
  // lists (e.g. OrderModal's 13 sort options in a 70vh scroll container) wrap
  // correctly — scrollIntoView below brings the newly-focused item into view.
  const focusables = getAllFocusable(overlay, { ignoreViewport: true })
  const currentIndex = focusables.indexOf(document.activeElement)

  if (key === 'ArrowDown' || key === 'ArrowRight') {
    event.preventDefault()
    const nextIndex = currentIndex < focusables.length - 1 ? currentIndex + 1 : 0
    focusables[nextIndex]?.focus({ preventScroll: true })
    focusables[nextIndex]?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  } else if (key === 'ArrowUp' || key === 'ArrowLeft') {
    event.preventDefault()
    const prevIndex = currentIndex > 0 ? currentIndex - 1 : focusables.length - 1
    focusables[prevIndex]?.focus({ preventScroll: true })
    focusables[prevIndex]?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  } else if (key === 'Enter') {
    // Trigger a click on the focused element (modal items use @click, not @keydown)
    event.preventDefault()
    document.activeElement?.click()
    return
  }
}

// ── Main page navigation ──

function findHorizontalTarget(direction) {
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

function findVerticalTarget(direction) {
  const current = document.activeElement
  if (!current) return null

  // When the virtualizer detaches the focused card during rapid scrolling,
  // focus falls to body. Use the last known card position to maintain column.
  const focusLost = !current || current === document.body
  const currentRect = focusLost && lastFocusRect ? lastFocusRect : current.getBoundingClientRect()
  const currentCenter = centerOf(currentRect)
  const goingDown = direction === 'ArrowDown'

  // If focus is lost and we have no saved position, we can't navigate
  if (focusLost && !lastFocusRect) return null

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

// ── Event handler ──

function handleKeyDown(event) {
  const { key } = event

  // Check if a modal/drawer is open — trap focus within it
  const overlay = getActiveOverlay()
  if (overlay) {
    if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter'].includes(key)) {
      handleOverlayNavigation(event, overlay)
    }
    // Close the overlay on Escape/GoBack/Back, then refocus first card
    if (key === 'Escape' || key === 'GoBack' || event.keyCode === 4) {
      event.preventDefault()
      event.stopImmediatePropagation()
      const refocusAfterClose = () => setTimeout(() => focusFirstContentElement(), 300)
      // Close side drawer
      if (_store?.state?.showSideDrawer) {
        _store.commit('setShowSideDrawer', false)
        refocusAfterClose()
        return
      }
      // Close any open modal via the event bus (Modal.vue listens for this)
      if (document.documentElement.classList.contains('modal-open')) {
        _store?.app?.$eventBus?.$emit('close-modal')
        refocusAfterClose()
        return
      }
      // Close library modal (uses Vuex directly, not the modal base)
      if (_store?.state?.libraries?.showModal) {
        _store.commit('libraries/setShowModal', false)
        refocusAfterClose()
        return
      }
    }
    return
  }

  // Audio player: when fullscreen, collapse on Back/Escape and refocus content.
  // When navigating to the minimized player, auto-expand to fullscreen on TV.
  const streamContainer = document.getElementById('streamContainer')
  if (streamContainer) {
    const isFullscreen = streamContainer.classList.contains('fullscreen')
    const focusInPlayer = streamContainer.contains(document.activeElement)

    // Back/Escape while player is fullscreen: close the player entirely.
    // collapseFullscreen() on TV calls closePlayback() instead of minimizing.
    if (isFullscreen && (key === 'Escape' || key === 'GoBack' || event.keyCode === 4)) {
      event.preventDefault()
      event.stopImmediatePropagation()
      document.activeElement?.blur()
      const collapseSpan = streamContainer.querySelector('.top-4.left-4 [tabindex="0"]')
      if (collapseSpan) collapseSpan.click()
      // Player closes — restore pre-playback fingerprint if one was saved,
      // else fall back to first content element.
      setTimeout(() => focusAfterPlayerClose(), 500)
      return
    }

    // Mini player navigation removed — on TV the player always closes
    // entirely instead of minimizing. A future user setting will allow
    // toggling between minimize and close behavior.

    // Navigation within fullscreen player — explicit row-based nav since
    // absolute positioning makes generic findVerticalTarget unreliable.
    // Rows: top (collapse, more_vert) ↔ controls (transport) ↔ utility (bookmark, speed, etc.)
    if (isFullscreen && focusInPlayer) {
      if (key === 'ArrowLeft' || key === 'ArrowRight') {
        event.preventDefault()
        const inTopRow = document.activeElement.closest('.top-4.left-4') ||
          document.activeElement.closest('.top-6.right-4')
        if (inTopRow) {
          // Top row: explicit toggle between collapse and more_vert
          const collapseSpan = streamContainer.querySelector('.top-4.left-4 [tabindex="0"]')
          const moreSpan = streamContainer.querySelector('.top-6.right-4 [tabindex="0"]')
          if (document.activeElement === collapseSpan && moreSpan) {
            moreSpan.focus({ preventScroll: true })
          } else if (document.activeElement === moreSpan && collapseSpan) {
            collapseSpan.focus({ preventScroll: true })
          }
        } else {
          const next = findHorizontalTarget(key)
          if (next && streamContainer.contains(next)) {
            next.focus({ preventScroll: true })
          }
        }
        return
      }
      if (key === 'ArrowUp' || key === 'ArrowDown') {
        event.preventDefault()
        const playerControls = document.getElementById('playerControls')
        const inControls = playerControls?.contains(document.activeElement)
        const inTopRow = document.activeElement.closest('.top-4.left-4') ||
          document.activeElement.closest('.top-6.right-4')
        const inUtility = !inControls && !inTopRow

        if (key === 'ArrowUp') {
          if (inUtility) {
            // Utility → Controls: focus play/pause
            const playBtn = streamContainer.querySelector('.play-btn')
            if (playBtn) playBtn.focus({ preventScroll: true })
          } else if (inControls) {
            // Controls → Top row: focus collapse button
            const collapseSpan = streamContainer.querySelector('.top-4.left-4 [tabindex="0"]')
            if (collapseSpan) collapseSpan.focus({ preventScroll: true })
          }
          // From top row, Up does nothing
        } else {
          // ArrowDown
          if (inTopRow) {
            // Top row → Controls: focus play/pause
            const playBtn = streamContainer.querySelector('.play-btn')
            if (playBtn) playBtn.focus({ preventScroll: true })
          } else if (inControls) {
            // Controls → Utility: focus first utility button
            const next = findVerticalTarget(key)
            if (next && streamContainer.contains(next)) {
              next.focus({ preventScroll: true })
            }
          }
          // From utility, Down does nothing
        }
        return
      }
    }
  }

  // Episode list navigation: explicit routing between episode elements.
  // Down from title → play button. Within an episode, suppress scrolling.
  // Moving to next/previous episode snaps it to the top of the viewport.
  const activeEl = document.activeElement
  const episodeRow = activeEl?.closest?.('.border-b')
  const isOnEpisodeTitle = activeEl?.id?.startsWith('episode-')
  if (episodeRow && (key === 'ArrowDown' || key === 'ArrowUp')) {
    if (isOnEpisodeTitle && key === 'ArrowDown') {
      // Title → Play button (first focusable in the controls row)
      const playBtn = episodeRow.querySelector('div[tabindex="0"]')
      if (playBtn) {
        event.preventDefault()
        playBtn.focus({ preventScroll: true })
        return
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
          return
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
          return
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
        return
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
        return
      }
    }
  }

  // Logs page: scroll through log entries with D-pad. No focusable elements
  // in the log itself — just scroll. Header buttons (copy, share, ellipsis)
  // are reachable only when scrolled to the top of the log.
  const logsContainer = document.getElementById('logs-container')
  if (logsContainer && ['ArrowDown', 'ArrowUp', 'ArrowLeft', 'ArrowRight'].includes(key)) {
    const logsPage = logsContainer.parentElement
    const focusOnHeader = activeEl && activeEl !== document.body && logsPage?.contains(activeEl) && !logsContainer.contains(activeEl)
    const focusInAppbar = activeEl && activeEl !== document.body && activeEl.closest('#appbar')

    // Left/Right on header: use generic horizontal nav
    if (focusOnHeader && (key === 'ArrowLeft' || key === 'ArrowRight')) {
      // Fall through to generic handler
    }
    // Left/Right elsewhere: ignore
    else if (key === 'ArrowLeft' || key === 'ArrowRight') {
      event.preventDefault()
      return
    }
    // Down from appbar: return to logs
    else if (focusInAppbar && key === 'ArrowDown') {
      event.preventDefault()
      const headerBtn = logsPage?.querySelector('button')
      if (headerBtn) headerBtn.focus({ preventScroll: true })
      return
    }
    // Up from header: escape to nav bar
    else if (focusOnHeader && key === 'ArrowUp') {
      // Fall through to nav bar escape handler
    }
    // Down from header: blur and enter log scrolling
    else if (focusOnHeader && key === 'ArrowDown') {
      event.preventDefault()
      document.activeElement.blur()
      logsContainer.scrollBy({ top: 200, behavior: getScrollBehavior() })
      return
    }
    // Up: scroll log up. When at top, move to header buttons
    else if (key === 'ArrowUp') {
      event.preventDefault()
      if (logsContainer.scrollTop < 10) {
        const headerBtn = logsPage?.querySelector('button')
        if (headerBtn) headerBtn.focus({ preventScroll: true })
      } else {
        logsContainer.scrollBy({ top: -200, behavior: getScrollBehavior() })
      }
      return
    }
    // Down: scroll log down
    else if (key === 'ArrowDown') {
      event.preventDefault()
      logsContainer.scrollBy({ top: 200, behavior: getScrollBehavior() })
      return
    }
  }

  // Nav bar escape: on any page, when pressing Up and no focusable element
  // exists above within the page content, focus the appbar back button or
  // first appbar element. This handles pages like Account, Stats, Settings
  // where the content is inside a scroll container separate from the appbar.
  if (key === 'ArrowUp' && activeEl && activeEl !== document.body) {
    const pageContainer = findPageScrollContainer()
    if (pageContainer && pageContainer.contains(activeEl) && !activeEl.closest('#appbar')) {
      const next = findVerticalTarget(key)
      // Only escape to appbar if there's truly no target above at all.
      // If there IS a target outside the container (e.g. bookshelf-navbar),
      // let the normal handlers deal with it.
      if (!next) {
        if (pageContainer.scrollTop < 50) {
          event.preventDefault()
          const appbar = document.getElementById('appbar')
          if (appbar) {
            const appbarFocusable = appbar.querySelector('a[tabindex="0"], button, a[href]:not([tabindex="-1"])')
            if (appbarFocusable) {
              appbarFocusable.focus({ preventScroll: true })
              return
            }
          }
        }
      }
    }
  }

  // Stats page: scroll through content like author bio pages — always scroll
  // first, then transfer focus to elements entering the viewport.
  const statsPage = document.getElementById('stats-page')
  if (statsPage) {
    const focusInStats = activeEl && activeEl !== document.body && statsPage.contains(activeEl)
    const focusInAppbar = activeEl && activeEl !== document.body && activeEl.closest('#appbar')

    // Only handle keys when focus is in stats page, on body (no focus), or
    // in appbar (just escaped to nav bar). Let other handlers run otherwise.
    if ((focusInStats || focusInAppbar || activeEl === document.body) && ['ArrowDown', 'ArrowUp', 'ArrowLeft', 'ArrowRight'].includes(key)) {

      // Left/Right in appbar: use generic handler (don't trap)
      if (focusInAppbar && (key === 'ArrowLeft' || key === 'ArrowRight')) {
        // Fall through to generic handler below
      }
      // Left/Right in stats: horizontal nav within focused row
      else if (key === 'ArrowLeft' || key === 'ArrowRight') {
        event.preventDefault()
        if (focusInStats) {
          const next = findHorizontalTarget(key)
          if (next && statsPage.contains(next)) {
            next.focus({ preventScroll: true })
          }
        }
        return
      }
      // Down from appbar: return to stats page
      else if (focusInAppbar && key === 'ArrowDown') {
        event.preventDefault()
        statsPage.scrollTo({ top: 0 })
        focusFirstContentElement()
        return
      }
      // Up at top of stats: escape to nav bar
      else if (key === 'ArrowUp' && statsPage.scrollTop < 50 && !focusInAppbar) {
        event.preventDefault()
        const appbar = document.getElementById('appbar')
        if (appbar) {
          const appbarFocusable = appbar.querySelector('a[tabindex="0"], button, a[href]:not([tabindex="-1"])')
          if (appbarFocusable) {
            appbarFocusable.focus({ preventScroll: true })
            return
          }
        }
      }
      else if (key === 'ArrowDown' || key === 'ArrowUp') {
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
        return
      }
    }
  }

  // Item detail page: when no focusable target exists in the pressed direction,
  // scroll the page so users can browse non-focusable content (cover, metadata,
  // description). When a focusable element scrolls into view, transfer focus.
  // Grid pages (Series, Collections, etc.) skip this — they use the general
  // handler below which has virtualizer recovery for re-appended cards.
  const itemPage = findPageScrollContainer()
  if (itemPage && !isGridPage() && (key === 'ArrowDown' || key === 'ArrowUp')) {
    const next = findVerticalTarget(key)
    if (next) {
      event.preventDefault()
      next.focus({ preventScroll: true })
      scrollParentToReveal(next)
      return
    }
    // No focusable target — scroll the page and check for newly visible elements
    event.preventDefault()
    itemPage.scrollBy({ top: key === 'ArrowDown' ? 150 : -150, behavior: getScrollBehavior() })
    setTimeout(() => {
      const retryTarget = findVerticalTarget(key)
      if (retryTarget) {
        retryTarget.focus({ preventScroll: true })
        scrollParentToReveal(retryTarget)
      }
    }, 300)
    return
  }

  // Author bio page: handle all vertical navigation
  const authorWrapper = document.getElementById('author-page-wrapper')
  if (authorWrapper && (key === 'ArrowDown' || key === 'ArrowUp')) {
    const focusedIsBookCard = activeEl?.id?.startsWith('author-book-')

    if (focusedIsBookCard) {
      // Currently on a book card — try to find another card in the direction
      const next = findVerticalTarget(key)
      if (next && next.id?.startsWith('author-book-')) {
        event.preventDefault()
        next.focus({ preventScroll: true })
        scrollParentToReveal(next)
        return
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
      return
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
    return
  }

  if (key === 'ArrowLeft' || key === 'ArrowRight') {
    const next = findHorizontalTarget(key)
    if (next) {
      event.preventDefault()
      next.focus({ preventScroll: true })
      lastFocusRect = next.getBoundingClientRect()
      scrollParentToReveal(next)
    } else {
      event.preventDefault()
    }
  } else if (key === 'ArrowUp' || key === 'ArrowDown') {
    event.preventDefault()
    // Guard: the virtualizer can't remove series/collection/playlist cards
    // (wrong ID prefix) so it re-appends them via appendChild, which briefly
    // detaches the focused element.  Suppress focusout recovery during scroll.
    verticalNavInProgress = true
    const clearNavGuard = () => { verticalNavInProgress = false }
    const next = findVerticalTarget(key)
    if (next) {
      const targetId = next.id
      next.focus({ preventScroll: true })
      lastFocusRect = next.getBoundingClientRect()
      scrollParentToReveal(next)
      // The virtualizer may remount cards during scroll, which can drop focus.
      // Re-find and re-focus the target card after the scroll settles.
      if (targetId) {
        setTimeout(() => {
          try {
            if (!document.activeElement || document.activeElement === document.body) {
              // Prefer the visible instance (stale orphans may share the same ID)
              const matches = Array.from(document.querySelectorAll('#' + cssEscape(targetId)))
              const refound = matches.find((m) => isVisible(m))
              if (refound) {
                refound.focus({ preventScroll: true })
                lastFocusRect = refound.getBoundingClientRect()
              }
            }
          } finally {
            clearNavGuard()
          }
        }, 500)
      } else {
        setTimeout(clearNavGuard, 500)
      }
    } else {
      // Virtualized rows — scroll to trigger rendering, then retry
      const scrollContainer = document.getElementById('bookshelf-wrapper') || findPageScrollContainer()
      if (scrollContainer) {
        const scrollAmount = key === 'ArrowDown' ? 240 : -240
        scrollContainer.scrollBy({ top: scrollAmount, behavior: getScrollBehavior() })
        setTimeout(() => {
          try {
            const retryTarget = findVerticalTarget(key)
            if (retryTarget) {
              retryTarget.focus({ preventScroll: true })
              lastFocusRect = retryTarget.getBoundingClientRect()
              scrollParentToReveal(retryTarget)
            }
          } finally {
            clearNavGuard()
          }
        }, 500)
      } else {
        clearNavGuard()
      }
    }
  }
}

// ── Initialization ──

function isGridPage() {
  const path = window.location.pathname
  return path.startsWith('/bookshelf/') && path !== '/bookshelf'
}

function focusFirstContentElement() {
  // Try cards first (bookshelf pages)
  const card = document.querySelector('[id^="book-card-"], [id^="series-card-"], [id^="collection-card-"], [id^="playlist-card-"], [id^="author-book-"], .author-card-wrapper')
  if (card) {
    card.focus({ preventScroll: true })
    // On grid pages, snap the first card to 10px from top immediately
    if (isGridPage()) {
      scrollParentToReveal(card)
    }
    return true
  }
  // On author detail page or stats page, don't focus anything — let content
  // show at top. D-pad scrolling is handled in handleKeyDown.
  if (document.getElementById('author-page-wrapper') || document.getElementById('stats-page')) {
    // Blur anything that might have auto-focused
    if (document.activeElement && document.activeElement !== document.body) {
      document.activeElement.blur()
    }
    return true
  }
  // On detail pages (playlist, collection, item), focus the Play button.
  // Don't scroll — the page starts at the top and the Play button is
  // already visible below the cover art. Scrolling to "reveal" it pushes
  // the cover image out of view.
  const playBtn = document.querySelector('.btn.bg-success, ui-btn[color="success"], button.bg-success')
  if (playBtn) {
    playBtn.focus({ preventScroll: true })
    return true
  }
  // Fallback: any focusable element in content area, excluding nav bars and toolbars
  const contentArea = document.getElementById('bookshelf-wrapper') || document.querySelector('#content > div > .overflow-y-auto') || document.getElementById('content')
  if (contentArea) {
    const fallback = contentArea.querySelector('[tabindex="0"], button, a[href]:not([tabindex="-1"]):not([aria-hidden="true"])')
    if (fallback) {
      fallback.focus({ preventScroll: true })
      scrollParentToReveal(fallback)
      return true
    }
  }
  // Last resort: if logged out with no content on a main nav page,
  // navigate to Home where the Connect button is always visible.
  // Only triggers on standard navigable pages — NOT on connect, settings,
  // account, or other pages where the user is actively trying to log in.
  if (!_store?.state?.user?.user) {
    const currentPath = window.location.pathname
    const loggedOutRedirectPages = [
      '/bookshelf/library', '/bookshelf/authors', '/bookshelf/collections',
      '/bookshelf/series', '/bookshelf/playlists', '/bookshelf/latest'
    ]
    if (loggedOutRedirectPages.includes(currentPath)) {
      Object.keys(pageFocusMemory).forEach((k) => delete pageFocusMemory[k])
      focusHistory.length = 0
      lastFocusRect = null
      // Access toast via Vuex store's internal Vue instance (Vue.prototype.$toast)
      const toast = _store._vm?.$toast
      if (toast) {
        toast.info('No user logged in. Returning Home. Click Connect to Login.', { timeout: 5000 })
      }
      _store.app.router.replace('/')
      return true
    }
  }
  return false
}

// Single entry point for all player-close focus-restore paths (Back button,
// MutationObserver on streamContainer class change, store.watch on session
// state). All three previously raced and called focusFirstContentElement
// independently; this helper unifies them so the pre-playback fingerprint
// (saved by the store.watch on playback start) is consulted regardless of
// which path wins the race.
// Tracked in project_tv_playlist_play_button_regression.md
async function focusAfterPlayerClose() {
  const currentPath = _store?.app?.router?.currentRoute?.fullPath || window.location.pathname
  const saved = pageFocusMemory[currentPath]

  // If we have a saved fingerprint for the current page, ALWAYS restore it.
  // Android TV's native focus engine aggressively re-focuses a "nearby" element
  // (often the primary Play button) the instant the fullscreen player's focused
  // element is unmounted — guarding against that auto-focus is precisely why
  // this function exists. Any non-body activeElement at this point is almost
  // certainly the native engine's recovery guess, not user intent.
  if (saved) {
    const restored = await restoreFromFingerprint(saved)
    if (restored) return
  }

  // No saved fingerprint OR restore failed — only set focus if we genuinely
  // have no active element (don't steal from whatever the user is using).
  if (!document.activeElement || document.activeElement === document.body) {
    focusFirstContentElement()
  }
}

export default function ({ store }) {
  if (typeof document === 'undefined') return

  _store = store
  let initialized = false

  const checkAndInit = () => {
    if (initialized) return
    if (!document.documentElement.classList.contains('android-tv')) return

    initialized = true
    document.addEventListener('keydown', handleKeyDown)

    // Poll for the first book card
    let attempts = 0
    const pollForCards = setInterval(() => {
      attempts++
      if (focusFirstContentElement() || attempts > 30) {
        clearInterval(pollForCards)
      }
    }, 500)
    registerTvListeners()
  }

  function registerTvListeners() {
  // Flag to suppress content-change refocus when fingerprint restore is active
  let fingerprintRestoreActive = false

  // Re-focus helper — polls for first book card after content changes
  let refocusIntervalId = null
  let refocusTimeoutId = null
  function refocusAfterContentChange() {
    if (fingerprintRestoreActive) return
    // Clear any existing poll to prevent stacking
    clearTimeout(refocusTimeoutId)
    clearInterval(refocusIntervalId)
    refocusTimeoutId = setTimeout(() => {
      if (fingerprintRestoreActive) return
      let attempts = 0
      refocusIntervalId = setInterval(() => {
        attempts++
        if (fingerprintRestoreActive || focusFirstContentElement() || attempts > 10) {
          clearInterval(refocusIntervalId)
          refocusIntervalId = null
        }
      }, 500)
    }, 500)
  }

  // Flag for Main → Main navigation: scroll to top on arrival
  let pendingScrollToTop = false

  // Declared here so beforeEach can clear it on navigation
  let focusLossTimer = null

  // Save focus before navigating away, restore after navigating back
  if (store?.app?.router) {
    store.app.router.beforeEach((to, from, next) => {
      // Cancel any pending focusout recovery — we're navigating away
      clearTimeout(focusLossTimer)

      // Determine the element to save — prefer focus history stack
      let elementToSave = null
      if (focusHistory.length > 0) {
        elementToSave = focusHistory[0]
      }
      if (!elementToSave || elementToSave === document.body) {
        elementToSave = document.activeElement
      }

      // Only save fingerprint for content items (cards, playlist buttons, etc.)
      // NOT for nav bar links, hamburger menu, library selector, or toolbar items.
      // This ensures Back from nav always focuses the first card, not the nav link.
      const isContentElement = elementToSave && (
        elementToSave.id?.startsWith('book-card-') ||
        elementToSave.id?.startsWith('series-card-') ||
        elementToSave.id?.startsWith('collection-card-') ||
        elementToSave.id?.startsWith('playlist-card-') ||
        elementToSave.classList?.contains('author-card-wrapper') ||
        elementToSave.closest?.('#content')
      ) && !elementToSave.closest?.('#appbar') && !elementToSave.closest?.('#bookshelf-navbar') && !elementToSave.closest?.('#bookshelf-toolbar')

      const leavingMainPage = isMainPage(from.path)
      const goingToMainPage = isMainPage(to.path)

      // Detect if this is a forward navigation (nav link click) vs Back button.
      // If the focused element is a nav/toolbar element, it's a forward nav.
      const isForwardNavFromMenu = !isContentElement

      if (leavingMainPage && !goingToMainPage) {
        // Main → Detail (e.g. Home → book card): save fingerprint
        if (isContentElement) {
          const fingerprint = getElementFingerprint(elementToSave)
          if (fingerprint) {
            pageFocusMemory[from.fullPath] = fingerprint
          }
        }
      } else if (!leavingMainPage && !goingToMainPage) {
        // Detail → Detail (e.g. playlist → history): save fingerprint
        if (isContentElement) {
          const fingerprint = getElementFingerprint(elementToSave)
          if (fingerprint) {
            pageFocusMemory[from.fullPath] = fingerprint
          }
        }
      } else if (leavingMainPage && goingToMainPage) {
        // Main → Main (e.g. Home → Library via nav): clear both and scroll to top
        delete pageFocusMemory[from.fullPath]
        delete pageFocusMemory[to.fullPath]
        pendingScrollToTop = true
        // Clear LazyBookshelf's stored scroll position so it doesn't restore
        // the old position after our scroll-to-top fires.
        store.commit('resetLastBookshelfScrollData')
      } else if (!leavingMainPage && goingToMainPage) {
        // Detail → Main (e.g. Back from playlist to playlists list):
        // Clear the detail page fingerprint so it starts fresh next visit.
        delete pageFocusMemory[from.fullPath]
        // Clear destination if navigating from an actual nav/toolbar element
        // (forward navigation like clicking a library tab), but NOT for the
        // back arrow — it's a back navigation and should preserve fingerprints.
        const isBackArrow = elementToSave?.getAttribute?.('aria-label') === 'Back'
        const isExplicitNavElement = elementToSave &&
          elementToSave !== document.body &&
          !isBackArrow &&
          (elementToSave.closest?.('#appbar') || elementToSave.closest?.('#bookshelf-navbar') || elementToSave.closest?.('#bookshelf-toolbar'))
        if (isExplicitNavElement) {
          delete pageFocusMemory[to.fullPath]
        }
      }

      // Author bio pages have no meaningful focus to restore (bio text
      // scrolls freely, book cards only appear partway down). Always clear
      // the fingerprint when leaving so we get a fresh start on re-entry.
      if (from.path.startsWith('/author/')) {
        delete pageFocusMemory[from.fullPath]
      }

      focusHistory.length = 0
      lastFocusRect = null
      next()
    })

    // Main pages where we always want first-card focus, never fingerprint restore
    const mainPagePatterns = [
      '/bookshelf',           // Home
      '/bookshelf/library',   // Library
      '/bookshelf/playlists', // Playlists
      '/bookshelf/authors',   // Authors
      '/bookshelf/collections', // Collections
      '/bookshelf/series',    // Series
      '/bookshelf/latest',    // Latest
      '/connect',             // Connect
      '/settings',            // Settings
      '/account',             // Account
      '/search',              // Search
      '/stats',               // Stats
      '/logs',                // Logs
      '/downloads',           // Downloads
    ]

    function isMainPage(path) {
      return mainPagePatterns.some((p) => path === p || path === p + '/')
    }

    store.app.router.afterEach(async (to) => {
      // Reset the flag on every navigation so a previous page's restore
      // doesn't block focus setup on the new page
      fingerprintRestoreActive = false

      // Main → Main: scroll to top so the page starts fresh.
      // LazyBookshelf restores its own scroll position from lastBookshelfScrollData
      // after loading data, so we fire twice: once early and once after the
      // maintainer's restore has likely completed, to ensure we win the race.
      if (pendingScrollToTop) {
        pendingScrollToTop = false
        const scrollToTop = () => {
          const sc = document.getElementById('bookshelf-wrapper')
          if (sc) sc.scrollTop = 0
        }
        setTimeout(scrollToTop, 200)
        setTimeout(scrollToTop, 800)
      }

      const saved = pageFocusMemory[to.fullPath]
      if (saved) {
        fingerprintRestoreActive = true
        const restored = await restoreFromFingerprint(saved)
        setTimeout(() => { fingerprintRestoreActive = false }, 6000)
        if (!restored) {
          fingerprintRestoreActive = false
          refocusAfterContentChange()
        }
      } else {
        refocusAfterContentChange()
      }
    })
  }

  // Watch for audio player leaving fullscreen — on TV the player closes
  // entirely, so focus a content element. The observer handles edge cases
  // where closePlayback triggers the class change before the DOM is cleaned up.
  const playerObserver = new MutationObserver((mutations) => {
    for (const mutation of mutations) {
      if (mutation.attributeName === 'class') {
        const sc = mutation.target
        if (sc.id === 'streamContainer' && !sc.classList.contains('fullscreen')) {
          setTimeout(() => focusAfterPlayerClose(), 400)
        }
      }
    }
  })
  // Observe streamContainer when it appears (it's conditionally rendered)
  const watchForPlayer = () => {
    const sc = document.getElementById('streamContainer')
    if (sc) {
      playerObserver.observe(sc, { attributes: true, attributeFilter: ['class'] })
    }
  }
  watchForPlayer()
  // Re-check periodically since the player mounts dynamically
  setInterval(watchForPlayer, 5000)

  // Save fingerprint of the play-triggering element as early as possible.
  // playerStartingPlaybackMediaId is committed synchronously inside the click
  // handler (playClick in ItemTableRow.vue), so activeElement is guaranteed
  // to still be the button that was pressed. By contrast, currentPlaybackSession
  // changes later after the native round-trip, when focus may have shifted.
  // Tracked in project_tv_playlist_play_button_regression.md
  store.watch(
    (state) => state.playerStartingPlaybackMediaId,
    (newVal, oldVal) => {
      if (!newVal || newVal === oldVal) return
      const activeEl = document.activeElement
      if (!activeEl || activeEl === document.body) return
      const fp = getElementFingerprint(activeEl)
      if (fp) {
        const currentPath = store.app?.router?.currentRoute?.fullPath || window.location.pathname
        pageFocusMemory[currentPath] = fp
      }
    }
  )

  // Auto-expand player to fullscreen when playback starts on TV
  store.watch(
    (state) => state.currentPlaybackSession,
    (newVal, oldVal) => {
      if (newVal && !oldVal) {
        // Playback just started — wait for the player to mount, then expand
        setTimeout(() => {
          const sc = document.getElementById('streamContainer')
          if (sc && !sc.classList.contains('fullscreen')) {
            watchForPlayer() // Ensure observer is attached
            const playerContent = document.getElementById('playerContent')
            if (playerContent) playerContent.click()
            setTimeout(() => {
              const playBtn = sc.querySelector('.play-btn')
              if (playBtn) playBtn.focus({ preventScroll: true })
            }, 400)
          }
        }, 800)
      } else if (!newVal && oldVal) {
        // Player closed — unified restore via focusAfterPlayerClose (tries
        // the fingerprint saved above on playback-start, then falls back).
        setTimeout(async () => {
          await focusAfterPlayerClose()
          // Scroll the focused element into view
          setTimeout(() => {
            if (document.activeElement && document.activeElement !== document.body) {
              scrollParentToReveal(document.activeElement)
            }
          }, 300)
        }, 400)
      }
    }
  )

  // Recover from focus loss when a focused element is removed from DOM
  // (e.g. download button disappears after download completes via v-if).
  // Redirect focus to the Play/Stream button or nearest content element.
  document.addEventListener('focusout', (e) => {
    clearTimeout(focusLossTimer)
    focusLossTimer = setTimeout(() => {
      // Only act if focus truly fell to body (element was removed)
      if (document.activeElement && document.activeElement !== document.body) return
      // Don't interfere during vertical navigation — the virtualizer may
      // briefly detach a focused card via appendChild; the nav handler's
      // own recovery timer will restore focus.
      if (verticalNavInProgress) return
      // Don't interfere during navigation or fingerprint restore
      if (fingerprintRestoreActive) return
      // Don't interfere when overlays are open
      if (document.documentElement.classList.contains('modal-open')) return
      if (store?.state?.showSideDrawer) return
      // Stats page: when a button loses focus (e.g. Next gets disabled during
      // processing), wait for processing to finish and re-focus the Next button.
      // Don't settle for the See/Close YIR toggle — keep retrying until the
      // real buttons are re-enabled.
      const sp = document.getElementById('stats-page')
      if (sp) {
        const retryFocus = (retries) => {
          const cr = sp.getBoundingClientRect()
          const btns = getAllFocusable().filter((c) => {
            if (!sp.contains(c)) return false
            const r = c.getBoundingClientRect()
            return r.top >= cr.top && r.bottom <= cr.bottom
          })
          // Look for Next button specifically (year-in-review nav)
          const nextBtn = btns.find((el) => {
            const txt = el.textContent?.trim()
            return txt?.includes('Next') || txt?.includes('chevron_right')
          })
          if (nextBtn) { nextBtn.focus({ preventScroll: true }); return }
          // Look for Refresh or Previous (also year-in-review nav)
          const yirBtn = btns.find((el) => {
            const txt = el.textContent?.trim()
            return txt?.includes('Refresh') || txt?.includes('refresh') ||
                   txt?.includes('Previous') || txt?.includes('chevron_left')
          })
          if (yirBtn) { yirBtn.focus({ preventScroll: true }); return }
          // Buttons still disabled — keep waiting
          if (retries > 0) setTimeout(() => retryFocus(retries - 1), 500)
        }
        setTimeout(() => retryFocus(10), 300)
        return
      }
      // Redirect to Play button on detail pages, or first content element
      const playBtn = document.querySelector('.btn.bg-success, button.bg-success')
      if (playBtn && isVisible(playBtn)) {
        playBtn.focus({ preventScroll: true })
      } else {
        focusFirstContentElement()
      }
    }, 200)
  })

  // Focus management when overlays open/close
  store.watch(
    (state) => state.showSideDrawer,
    (newVal) => {
      if (newVal) {
        saveFocusBeforeOverlay()
        setTimeout(() => {
          const drawer = document.querySelector('.fixed.z-50 .bg-bg.transition-transform:not(.translate-x-64)')
          if (drawer) {
            const first = drawer.querySelector('button, a')
            if (first) first.focus()
          }
        }, 300)
      } else {
        setTimeout(() => {
          if (!restoreFocusAfterOverlay()) focusFirstContentElement()
        }, 300)
      }
    }
  )
  // Track modal state with debouncing to handle rapid open/close transitions
  // (e.g. "More Info" closes one modal and opens another in quick succession)
  let modalDebounceTimer = null
  let modalWasOpen = false
  store.watch(
    (state) => state.globals.isModalOpen,
    (newVal) => {
      clearTimeout(modalDebounceTimer)
      if (newVal && !modalWasOpen) {
        // First modal opening — save focus immediately
        modalWasOpen = true
        saveFocusBeforeOverlay()
        setTimeout(() => {
          const modal = document.querySelector('body > .modal')
          if (modal) {
            modal.querySelectorAll('li[role="option"]:not([tabindex])').forEach((li) => {
              li.setAttribute('tabindex', '0')
            })
            const first = modal.querySelector('li[tabindex="0"], li[role="option"], button, a, input')
            if (first) first.focus()
          }
        }, 350)
      } else if (newVal && modalWasOpen) {
        // Modal transition (one closed, another opened) — focus the new modal's first item
        setTimeout(() => {
          const modal = document.querySelector('body > .modal')
          if (modal) {
            modal.querySelectorAll('li[role="option"]:not([tabindex])').forEach((li) => {
              li.setAttribute('tabindex', '0')
            })
            const first = modal.querySelector('li[tabindex="0"], li[role="option"], button, a, input')
            if (first) first.focus()
          }
        }, 350)
      } else {
        // Modal closed — wait a beat to see if another opens immediately
        modalDebounceTimer = setTimeout(() => {
          if (!store.state.globals.isModalOpen) {
            modalWasOpen = false
            if (!restoreFocusAfterOverlay()) focusFirstContentElement()
          }
        }, 400)
      }
    }
  )

  // Re-focus after library changes or content reloads (same route, new content)
  if (store?.app?.$eventBus) {
    store.app.$eventBus.$on('library-changed', () => {
      refocusAfterContentChange()
    })
    // Fires when bookshelf content changes (after filter, sort, or data load)
    store.app.$eventBus.$on('bookshelf-total-entities', () => {
      refocusAfterContentChange()
    })
    // TV focus ring color — apply current value, then react to user-settings broadcasts
    applyTvFocusColor(store.state.user?.settings?.tvFocusColor, store)
    store.app.$eventBus.$on('user-settings', (settings) => {
      applyTvFocusColor(settings?.tvFocusColor, store)
    })
  }
  }

  checkAndInit()
  setTimeout(checkAndInit, 1000)
}
