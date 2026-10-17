/**
 * Distinguishes a deliberate tap from a quick flick used to scroll.
 *
 * iOS WKWebView does not reliably suppress the `click` it synthesizes when a
 * short, fast flick starts on a tappable element. A flick of ~25-33px over
 * ~50-90ms still carries enough velocity to scroll the list with momentum, but
 * stays under WebKit's own (erratic) suppression threshold — so the list scrolls
 * AND the element under the finger is activated. On the bookshelf that opens a
 * book the user never meant to open.
 *
 * Two independent signals, because each catches what the other misses:
 *
 *  - Did anything scroll during the gesture? A flick shorter than the slop below
 *    still scrolls with momentum, and only this catches it.
 *  - Did the finger travel past the tap slop? A drag that scrolls nothing (a
 *    shelf already at its end) fires no scroll event, and only this catches it.
 *
 * Measured on an iPhone 15 Pro (iOS 26.5.2): deliberate taps moved 0px and
 * scrolled nothing, with a worst observed roll of 16px. Accidental flick-clicks
 * moved 25, 29 and 33px and all scrolled. The 20px slop sits in that gap.
 *
 * A tap landing while the list still coasts is treated as a scroll and ignored.
 * That is deliberate: UIKit does the same, where the first tap on a decelerating
 * scroll view stops it without selecting anything.
 *
 * Listeners are passive and capture-phase so they cannot interfere with
 * scrolling or with WebKit's own gesture handling. `scroll` does not bubble, but
 * capture-phase listeners on `document` still observe it from any container.
 */

// Max distance (px) a finger may travel for the gesture to still count as a tap.
const TAP_SLOP_PX = 20

let maxDistance = 0
let startX = 0
let startY = 0
let scrolledDuringGesture = false
let touchIsDown = false
let installed = false

function onTouchStart(e) {
  const touch = e.changedTouches && e.changedTouches[0]
  if (!touch) return
  // Reset per gesture. Both values must survive touchend, since the click only
  // arrives ~35ms later — so only a new touchstart may clear them.
  startX = touch.pageX
  startY = touch.pageY
  maxDistance = 0
  scrolledDuringGesture = false
  touchIsDown = true
}

function onTouchMove(e) {
  const touch = e.changedTouches && e.changedTouches[0]
  if (!touch) return
  const distance = Math.hypot(touch.pageX - startX, touch.pageY - startY)
  if (distance > maxDistance) maxDistance = distance
}

function onTouchEnd() {
  touchIsDown = false
}

// Only a scroll under a finger says anything about the gesture. Without this
// gate a mouse-wheel scroll would latch the flag forever on non-touch devices,
// where no touchstart ever arrives to reset it. Note we cannot reset on
// mousedown instead: iOS synthesizes mousedown after touchend but before click,
// which would clear the flag just before the guard reads it.
function onScroll() {
  if (touchIsDown) scrolledDuringGesture = true
}

function install() {
  if (installed || typeof document === 'undefined') return
  installed = true
  const opts = { capture: true, passive: true }
  document.addEventListener('touchstart', onTouchStart, opts)
  document.addEventListener('touchmove', onTouchMove, opts)
  document.addEventListener('touchend', onTouchEnd, opts)
  document.addEventListener('touchcancel', onTouchEnd, opts)
  document.addEventListener('scroll', onScroll, opts)
}

if (typeof window !== 'undefined') install()

/**
 * True when the gesture that produced the current click moved far enough to be
 * a scroll rather than a tap. Call from a click handler to ignore the click.
 *
 * Always false when there were no touch events (mouse/desktop), so click
 * handling is unaffected there.
 */
export function wasDragGesture() {
  return maxDistance > TAP_SLOP_PX || scrolledDuringGesture
}
