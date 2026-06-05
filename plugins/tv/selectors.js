/**
 * Shared DOM-target finders for TV navigation.
 *
 * Single source of truth for the stable data-* hooks that replaced fragile
 * Tailwind utility-class selectors (I5). Maintainer-side class renames no
 * longer break TV nav; the contract is the data-attribute, documented here.
 *
 * Contract (set in Vue components):
 * - [data-tv-target="play-button"]  — the primary Play button on a detail
 *   page (item / episode / playlist / collection). Static attribute.
 * - [data-tv-overlay="side-drawer"] — the side-drawer panel, present ONLY
 *   while the drawer is open (bound to its `show` state in SideDrawer.vue).
 */

export function findPlayButton() {
  return document.querySelector('[data-tv-target="play-button"]')
}

export function findVisibleSideDrawer() {
  return document.querySelector('[data-tv-overlay="side-drawer"]')
}
