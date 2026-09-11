/**
 * TV focus-ring color application + preset list.
 *
 * Reads/writes only the DOM (--tv-focus-color CSS variable) and Vuex
 * via the passed store argument. No tvContext access — store is passed
 * by the caller (listeners.js).
 *
 * Extracted from plugins/tv-navigation.js during v1.0.10 refactor.
 */

export const VALID_TV_FOCUS_HEXES = [
  '#1ad691', '#3ea6ff', '#ffb74d', '#ff5252',
  '#e040fb', '#ffeb3b', '#ffffff'
]

export const DEFAULT_TV_FOCUS_HEX = '#1ad691'

export function applyTvFocusColor(value, store) {
  const hex = VALID_TV_FOCUS_HEXES.includes(value) ? value : DEFAULT_TV_FOCUS_HEX
  document.documentElement.style.setProperty('--tv-focus-color', hex)
  // Self-heal: if the stored value was bad, dispatch a corrective save.
  if (value && hex !== value && store) {
    store.dispatch('user/updateUserSettings', { tvFocusColor: DEFAULT_TV_FOCUS_HEX })
  }
}
