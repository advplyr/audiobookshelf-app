/**
 * TV Navigation shared state singleton.
 *
 * Replaces the module-level mutable vars from the original
 * plugins/tv-navigation.js. All other plugins/tv/* modules import
 * tvContext and read/write through it.
 *
 * The previous module-level vars were:
 *   let _store = null
 *   const focusHistory = []
 *   let verticalNavInProgress = false
 *   let lastFocusRect = null
 *   const pageFocusMemory = {}
 *   const cssEscape = ...
 *
 * Plus three closure-scoped vars promoted from inside registerTvListeners:
 *   let refocusIntervalId, refocusTimeoutId, fingerprintRestoreActive
 */
export const tvContext = {
  // Vuex store reference. Set by index.js during plugin init.
  // Used by handleKeyDown for overlay dismissal (drawer/modal close).
  store: null,

  // Focus history stack. Tracks which element was focused before an overlay opened.
  // Supports nested overlays (e.g. menu -> submenu).
  focusHistory: [],

  // Vertical navigation guard. Suppresses the focusout recovery handler while a
  // vertical nav scroll is settling. The virtualizer may appendChild a still-focused
  // card (series, collection, playlist) which briefly detaches it, firing focusout.
  // Without this guard the 200 ms focusout timer beats the 350 ms recovery timer
  // and snaps focus to the first card.
  verticalNavInProgress: false,

  // Last known focus position. Tracks the bounding rect center of the last
  // focused card so that when the virtualizer detaches a card during rapid
  // scrolling (focus falls to body), findVerticalTarget can still pick the
  // correct column.
  lastFocusRect: null,

  // Page focus memory. Saves the focused element selector per route so Back
  // navigation can restore focus to where the user was. TV only.
  pageFocusMemory: {},

  // CSS.escape polyfill for older Android TV WebViews (Chrome < 46).
  cssEscape:
    typeof CSS !== 'undefined' && CSS.escape
      ? CSS.escape
      : (s) => s.replace(/([^\w-])/g, '\\$1'),

  // Set by focusEntry helpers + listeners.js when fingerprint restoration is
  // in flight. Causes refocusAfterContentChange to no-op so polling doesn't
  // fight the restore. Promoted from closure inside registerTvListeners.
  fingerprintRestoreActive: false,

  // Timer + interval handles for refocusAfterContentChange's poll loop.
  // Tracked so reentrant calls can cancel a stale loop before starting a new one.
  // Promoted from closure inside registerTvListeners.
  refocusTimeoutId: null,
  refocusIntervalId: null,

  // Last keypress timestamp. Used by getScrollBehavior to switch from smooth
  // to instant scrolling on rapid sustained-hold D-pad bursts.
  lastKeyTime: 0
}
