# Android TV Support — 9-PR Decomposition Plan

**Maintainer reference for the upstream PR series replacing #1843**

This document is the canonical reference for how the Android TV support work
(originally submitted to `advplyr/audiobookshelf-app` as PR #1843, ~7,000 LOC)
is being split into a series of 9 smaller, focused PRs.

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
- **Split the upstream code into a 9-PR series across 3 waves.** Average PR
  size: ~290 LOC. Largest PR: ~1,560 LOC across 15 focused files.
- **Refactor the fork to a modular engine structure first** (v1.0.10) so the
  fork and upstream PRs share one code structure.

Net result: the maintainer-facing review unit changes from `~7,000 LOC × 1 PR`
to `~2,530 LOC × 9 PRs`, with the largest single PR being `~1,560 LOC across
15 small files`.

The architectural choice (TV code in dedicated files like `plugins/tv/` with
inline gating in shared components) is preserved deliberately. Analysis of an
inline alternative showed only ~50-100 LOC savings (<4%) with real downsides
(file fragmentation, harder cross-cutting evolution, more files touched per
PR). The detailed inline-vs-external rationale is in Section 9 of the design
spec.

---

## The 9 PRs at a glance

| # | Title | LOC | Wave | Depends on | Files |
|---|---|---|---|---|---|
| 1 | Foundation + TV detection | ~125 + 1 binary | 1 | nothing | 11 files (manifest, Kotlin, Vuex, layouts) + tv_banner.png |
| 2 | Keyboard hygiene (tabindex + keydown.enter.prevent) | ~300 | 1 | nothing | 25+ shared Vue components, additive only |
| 3 | CSS foundation (tv-focus.css + variable) | ~130 | 1 | nothing | 3 files |
| 4 | Engine kit — utility modules + page handlers | ~1,560 | 2 | PR 1 | 15 files in `plugins/tv/` (each ≤250 LOC) |
| 5 | Engine integration — listeners + dispatcher | ~550 | 2 | PR 4 (stacked) | 2 files in `plugins/tv/` + `nuxt.config.js` |
| 6 | Audio player TV behavior | ~100 | 3 | PR 1, PR 5 | `AudioPlayer.vue` (KeepAwake, close-vs-minimize, History gate, auto-fullscreen) |
| 7 | Settings + focus-color picker | ~180 | 3 | PR 3, PR 5 | `settings.vue` TV section + `TvFocusColorPicker.vue` |
| 8 | Server connect form D-pad nav | ~150 | 3 | PR 2, PR 5 | `ServerConnectForm.vue` |
| 9 | Author detail page | ~120 | 3 | PR 2 | `pages/author/_id.vue` + 3 related Vue files |

**Average PR size: ~290 LOC. Largest single file in any PR: ~250 LOC.**

---

## Wave structure

### Wave 1 — foundation (parallel)

PRs 1, 2, and 3 are fully independent. Submit simultaneously off `upstream/master`.
Each PR is small, additive, and has no dependencies. Phone/tablet behavior is
byte-identical to upstream after Wave 1 lands — the foundation enables TV but
activates nothing visible until Wave 2.

### Wave 2 — engine (sequential)

PR 4 (engine kit) opens after Wave 1's PR 1 merges. It ships dormant library
code — modules that export functions but attach to nothing at runtime.

PR 5 (engine integration) stacks on PR 4's branch. It activates the engine by
registering the global keydown listener and wiring router/store/eventBus hooks.

The PR 4/5 split lets the maintainer review the function library separately
from the wiring that turns it on.

### Wave 3 — features (parallel)

PRs 6, 7, 8, and 9 open after Wave 2's PR 5 merges. Each is small, independent,
and adds a TV-specific feature on top of the live engine. Submit in parallel.

PR 9 (author detail page) is arguably non-TV-specific — the page itself adds
general functionality that mobile users could also benefit from. The PR
description flags this for the maintainer's discretion.

---

## The v1.0.10 fork refactor (precedes upstream submission)

Before any upstream PR opens, the fork ships v1.0.10 — a single
behavior-preserving commit that splits the current 1,675-line
`plugins/tv-navigation.js` monolith into 17 focused modules under
`plugins/tv/`, sharing state through a `tvContext` singleton object.

**Why first:** so the fork and upstream PRs share one code structure. Without
this, every upstream PR would require translating between the monolithic fork
structure and the modular upstream submission — ongoing dual-maintenance pain.

**Behavior parity is the success criterion** — the refactor moves code without
changing it. ESLint clean, full 42-item TV manual checklist pass on Google TV
Streamer 4K, plus phone smoke before merge. Any divergence is a bug to fix
before release.

**Release notes:** *"Internal refactor: split `tv-navigation.js` into focused
modules under `plugins/tv/`. No user-visible changes. Foundation for future
upstream PR submission."*

---

## What happens to PR #1843

Closed with a comment redirecting to this plan:

> Closing in favor of a 9-PR series that addresses the volume feedback. See
> [bilbospocketses/abs-app — PR_DECOMPOSITION_PLAN.md](this document) for
> the full plan and rationale. First PR (foundation + TV detection) opening
> at [link] shortly.

---

## Per-PR `docs.md` convention

Each upstream PR adds a single file at `docs/pr-NN-<short-name>.md` (e.g.,
`docs/pr-01-foundation-detection.md`). Each file is ~25 LOC and contains:

- Link back to this plan
- 1-2 paragraphs on the PR's scope
- Links to architecture context on the fork (e.g., `TV_FOCUS_SYSTEM.md`)
- 1 sentence on the testing performed
- Dependency relationships within the series

No PR edits any other PR's docs file. After all 9 PRs merge, upstream `docs/`
contains 9 lightweight context files. The introduction of `docs/` is
deliberate but minimal — maintainer is free to repurpose the directory
later for their own conventions.

---

## Estimated timeline

| Phase | Activity | Estimated effort |
|---|---|---|
| 1 | Publish v1.0.10 fork refactor | ~1 day (mechanical + testing + release) |
| 2 | Close PR #1843 with redirect | ~15 min |
| 3 | Submit Wave 1 PRs (1, 2, 3) in parallel | ~2 hours |
| 4 | Maintainer review/merge of Wave 1 | (variable, days-weeks per PR) |
| 5 | Submit Wave 2 PRs (4 + stacked 5) | ~3 hours |
| 6 | Maintainer review/merge of Wave 2 | (variable) |
| 7 | Submit Wave 3 PRs (6, 7, 8, 9) in parallel | ~3 hours |
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
- **Fork active TODO file:** maintained in `~/.claude/projects/.../memory/todo_abs_tv.md` (private to maintainer)

---

## Questions or concerns

Open a discussion thread on the fork
([`bilbospocketses/abs-app/discussions`](https://github.com/bilbospocketses/abs-app/discussions))
or comment on any of the open PRs in the series.

The plan is a starting point. Maintainer preference adjustments (e.g., combine
some PRs, split a PR further, reorder the waves) are acceptable and the design
spec's Section 10 outlines contingencies for the most likely variations.

---

**Last updated:** 2026-05-18 (plan creation)
