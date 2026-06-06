# Android TV Support — 10-PR Decomposition Plan

**Maintainer reference for the upstream PR series replacing #1843**

This document is the canonical reference for how the Android TV support work
(originally submitted to `advplyr/audiobookshelf-app` as PR #1843, ~7,000 LOC)
is being split into a series of 10 smaller, focused PRs.

It is hosted on the **fork** (`bilbospocketses/abs-app`) so that upstream PR
descriptions can link to a single durable plan without bloating the upstream
repository.

---

## Why decompose?

PR #1843 surfaced two valid concerns from the maintainer's review experience:

1. **Volume** — a single 7,000-LOC PR is a large ask of any maintainer's review
   time, even for additive work.
2. **Mixed content** — the original PR included ~3,500 LOC of internal
   development docs, plans, specs, and a 12.7 MB PDF, inflating the perceived
   code change.

This plan addresses both:

- **Strip non-code from upstream contributions.** Docs, plans, specs, PDF, and
  screenshots stay on the fork. Each upstream PR contributes a single
  ~25-line `docs/pr-NN-<short-name>.md` linking back to fork-hosted context.
- **Split the upstream code into a 10-PR series across 3 waves.** Average PR
  size: ~335 LOC. Largest PR: ~1,630 LOC across 17 focused files.
- **Refactor the fork to a modular engine structure first** (shipped in v1.0.10)
  so the fork and upstream PRs share one code structure.

Net result: the maintainer-facing review unit changes from `~7,000 LOC × 1 PR`
to ~10 small PRs averaging ~335 LOC, with the largest single PR being
`~1,630 LOC across 17 small files`.

The architectural choice (TV code in dedicated files like `plugins/tv/` with
inline gating in shared components) is preserved deliberately. Analysis of an
inline alternative showed only ~50-100 LOC savings (<4%) with real downsides
(file fragmentation, harder cross-cutting evolution, more files touched per
PR). The detailed inline-vs-external rationale is in Section 9 of the design
spec.

---

## The 10 PRs at a glance

| # | Title | LOC | Wave | Depends on | Files |
|---|---|---|---|---|---|
| 1 | Foundation + TV detection | ~125 + 1 binary | 1 | nothing | 11 files (manifest, Kotlin, Vuex, layouts) + tv_banner.png |
| 2 | Keyboard hygiene (tabindex + keydown.enter.prevent) | ~300 | 1 | nothing | 25+ shared Vue components, additive only |
| 3 | Side drawer: hide "Go to Web Client" on TV | ~19 | 1 | nothing (gate inert until PR1) | `SideDrawer.vue` — **shares this file with PR2** |
| 4 | CSS foundation (tv-focus.css + variable) | ~132 | 1 | nothing | 3 files |
| 5 | Engine kit — utility modules + page handlers | ~1,630 | 2 | PR 1 | 17 files in `plugins/tv/` (each ≤235 LOC) |
| 6 | Engine integration — listeners + dispatcher | ~615 | 2 | PR 5 (stacked) | 2 files in `plugins/tv/` + `nuxt.config.js` |
| 7 | Audio player TV behavior | ~100 | 3 | PR 1, PR 6 | `AudioPlayer.vue` (KeepAwake, close-vs-minimize, History gate, auto-fullscreen) |
| 8 | Settings + focus-color picker | ~180 | 3 | PR 4, PR 6 | `settings.vue` TV section + `TvFocusColorPicker.vue` |
| 9 | Server connect form D-pad nav | ~150 | 3 | PR 2, PR 6 | `ServerConnectForm.vue` |
| 10 | Author detail page | ~120 | 3 | PR 2 | `pages/author/_id.vue` + 3 related Vue files |

**Average PR size: ~335 LOC. Largest single PR: ~1,630 LOC across 17 files
(each file ≤235 LOC).**

The total upstreamable code (~3,100 LOC) is higher than the original estimate
of ~2,530 because the engine is now counted in its modular form (PR5 + PR6 ≈
2,245 LOC vs. the old 1,675-LOC monolith — the ~28% modularization overhead the
design spec predicted) plus the v1.0.11 fast-scroll fixes that landed in the
engine. Per-PR review size is what matters, and it stays small.

---

## Wave structure

### Wave 1 — foundation (parallel)

PRs 1, 2, 3, and 4 are independent and submit simultaneously off
`upstream/master`. Each is small and additive; phone/tablet behavior is
byte-identical to upstream after Wave 1 lands — the foundation enables TV but
activates no navigation until Wave 2.

**One file-overlap note:** PR2 (keyboard hygiene) and PR3 (hide "Go to Web
Client" on TV) both touch `components/app/SideDrawer.vue`. They edit different
regions, but whichever merges second may need a trivial rebase. Everything else
in Wave 1 is fully disjoint.

PR3 hides the "Go to Web Client" side-drawer item on Android TV (a TV has no
browser to hand off to). It is gated on `isAndroidTv`, so it is inert until
PR1's detection state merges — and harmless before then (the item simply
remains visible, i.e. upstream's current behavior).

### Wave 2 — engine (sequential)

PR 5 (engine kit) opens after Wave 1's PR 1 merges. It ships dormant library
code — modules that export functions but attach to nothing at runtime.

PR 6 (engine integration) stacks on PR 5's branch. It activates the engine by
registering the global keydown listener and wiring router/store/eventBus hooks.

The PR 5/6 split lets the maintainer review the function library separately
from the wiring that turns it on.

### Wave 3 — features (parallel)

PRs 7, 8, 9, and 10 open after Wave 2's PR 6 merges. Each is small, independent,
and adds a TV-specific feature on top of the live engine. Submit in parallel.

PR 10 (author detail page) is arguably non-TV-specific — the page itself adds
general functionality that mobile users could also benefit from. The PR
description flags this for the maintainer's discretion.

---

## The v1.0.10 fork refactor (preceded upstream submission)

Before any upstream PR opens, the fork shipped v1.0.10 — a single
behavior-preserving commit that split the former 1,675-line
`plugins/tv-navigation.js` monolith into focused modules under `plugins/tv/`,
sharing state through a `tvContext` singleton object. As of v1.0.11 the modular
tree is **19 files** (the refactor plus the fast-scroll column-drift fix, which
added `selectors.js` and extended `spatialNav.js`/`gridNav.js`/`listeners.js`).

**Why first:** so the fork and upstream PRs share one code structure. Without
this, every upstream PR would require translating between the monolithic fork
structure and the modular upstream submission — ongoing dual-maintenance pain.

**Behavior parity was the success criterion** — the refactor moved code without
changing it. ESLint clean, full TV manual checklist pass on Google TV Streamer
4K, plus phone smoke before merge.

**Release notes:** *"Internal refactor: split `tv-navigation.js` into focused
modules under `plugins/tv/`. No user-visible changes. Foundation for future
upstream PR submission."*

---

## What happens to PR #1843

Closed with a comment redirecting to this plan. Draft below — it acknowledges
the gap since the 2026-05-25 "incoming shortly" comment, and corrects that
note's "each PR gated on the next" framing (Wave 1 is actually independent):

> Apologies for the gap since my last note here. I used the time to land a few
> stability fixes on the fork first — most notably a long-standing fast-scroll
> focus bug — so the upstream series starts from a solid base rather than
> chasing known issues across PRs.
>
> Closing this in favor of the 10-PR series we discussed, which addresses the
> volume feedback. Two changes from this PR: the internal docs/specs/PDF are
> stripped out (each PR links back to a plan on my fork instead of carrying
> them), and the code is split into small, focused PRs. The first wave —
> foundation + TV detection, keyboard hygiene, a one-item side-drawer gate, and
> the CSS foundation — is **fully independent: each can be reviewed and merged
> on its own, in any order** (my earlier "gated on the next" note was wrong for
> this wave). Later waves build on the foundation. Full breakdown, dependency
> graph, and rationale: [PR_DECOMPOSITION_PLAN.md](link). First PRs opening
> shortly.

---

## Per-PR `docs.md` convention

Each upstream PR adds a single file at `docs/pr-NN-<short-name>.md` (e.g.,
`docs/pr-01-foundation-detection.md`). Each file is ~25 LOC and contains:

- Link back to this plan
- 1-2 paragraphs on the PR's scope
- Links to architecture context on the fork (e.g., `TV_FOCUS_SYSTEM.md`)
- 1 sentence on the testing performed
- Dependency relationships within the series

No PR edits any other PR's docs file. After all 10 PRs merge, upstream `docs/`
contains 10 lightweight context files. The introduction of `docs/` is
deliberate but minimal — maintainer is free to repurpose the directory
later for their own conventions.

---

## Estimated timeline

| Phase | Activity | Estimated effort |
|---|---|---|
| 1 | Publish v1.0.10 fork refactor | ✅ done (shipped) |
| 2 | Close PR #1843 with redirect | ~15 min |
| 3 | Submit Wave 1 PRs (1, 2, 3, 4) in parallel | ~2.5 hours |
| 4 | Maintainer review/merge of Wave 1 | (variable, days-weeks per PR) |
| 5 | Submit Wave 2 PRs (5 + stacked 6) | ~3 hours |
| 6 | Maintainer review/merge of Wave 2 | (variable) |
| 7 | Submit Wave 3 PRs (7, 8, 9, 10) in parallel | ~3 hours |
| 8 | Maintainer review/merge of Wave 3 | (variable) |
| 9 | Post-merge cleanup | ~1 day |

**Total active effort (excluding maintainer-wait time): ~3-4 days.**

---

## Where to find more detail

- **Full design spec** (file-by-file mapping, dependency graph, alternatives
  considered, risk + contingency planning): [`docs/superpowers/specs/2026-05-18-pr-decomposition-and-fork-modularization-design.md`](superpowers/specs/2026-05-18-pr-decomposition-and-fork-modularization-design.md)
- **TV focus system architecture overview:** [`docs/TV_FOCUS_SYSTEM.md`](TV_FOCUS_SYSTEM.md)
- **End-user TV feature documentation:** [`docs/TV_USER_GUIDE.md`](TV_USER_GUIDE.md)
- **TV user guide PDF (12.7 MB):** [`docs/TV_USER_GUIDE.pdf`](TV_USER_GUIDE.pdf)
- **Fork active TODO file:** maintained in `~/.claude/projects/.../memory/todo_abs_app.md` (private to maintainer)

---

## Questions or concerns

Open a discussion thread on the fork
([`bilbospocketses/abs-app/discussions`](https://github.com/bilbospocketses/abs-app/discussions))
or comment on any of the open PRs in the series.

The plan is a starting point. Maintainer preference adjustments (e.g., combine
some PRs, split a PR further, reorder the waves) are acceptable and the design
spec's Section 10 outlines contingencies for the most likely variations.

---

**Last updated:** 2026-06-06 — re-validated after v1.0.11 shipped. Now a
**10-PR series**: a new PR3 (hide "Go to Web Client" on TV) was inserted in
Wave 1, shifting the former PRs 3-9 to 4-10. Per-PR LOC refreshed against the
post-v1.0.11 tree — the engine kit (PR5) is now ~1,630 LOC / 17 files and
integration (PR6) ~615 LOC, reflecting the column-drift fix. Plan originally
created 2026-05-18.
