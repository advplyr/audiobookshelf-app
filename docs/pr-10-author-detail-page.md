# PR 10 — Author detail page

Part of a 10-PR series adding Android TV support to audiobookshelf-app,
replacing the original PR #1843.

## Full series plan

See **[bilbospocketses/abs-app — PR_DECOMPOSITION_PLAN.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/PR_DECOMPOSITION_PLAN.md)**
for the complete 10-PR breakdown, dependency graph, and rationale.

## This PR's scope

Adds `pages/author/_id.vue`, an author detail page listing that author's books,
and makes author cards navigate to it.

- `pages/author/_id.vue` — new page: author image, name, and a grid of the
  author's items.
- `components/cards/AuthorCard.vue` — the card becomes navigable to the new
  page and gains a `tabindex` so a D-pad can reach it.
- `components/covers/AuthorImage.vue` — a one-line adjustment for the new page's
  layout.
- `pages/bookshelf/authors.vue` — routes card activation to the detail page.

**A note on scope:** this one is arguably not TV-specific. The app currently has
no author detail page on any platform — tapping an author on a phone does not
open one either. The page was built for TV because browsing by author with a
remote is common, but mobile users would get the same feature. Flagging it
explicitly for your discretion: it can ship as-is, be reworked as a general
(non-TV) feature, or be dropped from the series without affecting PRs 1-9.

## Architecture context (fork-hosted)

- [TV_USER_GUIDE.md](https://github.com/bilbospocketses/abs-app/blob/android-tv-dpad-navigation/docs/TV_USER_GUIDE.md) — end-user TV feature documentation.

## Testing

Verified on a Google TV Streamer 4K: author cards are reachable and open the
detail page, the item grid is navigable, and Back returns to the author
bookshelf with focus restored to the originating card. Verified on an Android
phone that the new page renders correctly when reached by tap.

## Relationship to the series

- **Depends on:** PR2 (keyboard hygiene) for the shared card components.
- **Independent of:** PRs 5-9 — this one does not require the engine to be
  useful, though focus restore on Back does come from PR6.
- **Layer:** 3 of 3
