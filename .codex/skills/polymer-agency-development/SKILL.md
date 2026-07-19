---
name: polymer-agency-development
description: Use for Polymer repository work involving ClojureScript character agencies, Society-of-Mind agency architecture, local GOAP/planner behavior, scheduler and stream boundaries, side-effect placement, package release checks, or LoomLarge integration with the Polymer package.
---

# Polymer Agency Development

Use this skill for implementation, review, porting, and integration work in the
Polymer repository.

## Required context

1. Locate the Polymer repository root with `git rev-parse --show-toplevel`.
2. Read `AGENTS.md`.
3. For agency code changes, read `docs/agency-architecture.md` before editing.
4. Inspect the closest existing agency files under `src/polymer/<agency>/`.

## Core rules

- Polymer is the ClojureScript-first character agency package for Character
  Loom.
- Keep agencies as local Society-of-Mind actors: state, local GOAP/planner,
  scheduler, incoming/outgoing streams, domain transforms, and effectors where
  needed.
- Do not introduce a central planner, central arbitrator, host-side message bus,
  or generic public side-effect stream unless the issue explicitly asks for it.
- Streams carry plain data. Hosts may dispatch commands and observe outputs, but
  Polymer owns normal cross-agency routing.
- Keep pure state, planner, scheduler decision logic, and domain transforms free
  of browser globals, runtime handles, storage, network, DOM, worker, rendering,
  game-engine, robotics, and animation side effects.
- Prefer ClojureScript data-first code: pure functions, immutable maps, and
  transducers only for pure map/filter/keep/mapcat/reduce style transforms.
- Do not add Effect, Most, RxJS, XState, or similar JavaScript runtime
  frameworks to Polymer core. When replacing Latticework, Polyester, or
  LoomLarge runtime glue, prefer small ClojureScript primitives: immutable state
  transitions, plain data messages, local planners, local schedulers, and
  Polymer-owned stream boundaries.

## Implementation workflow

1. Classify the task:
   - new or changed agency behavior
   - cross-agency routing
   - runtime effector boundary
   - worker/API/package surface
   - LoomLarge dependency or integration work
2. Write down the intended incoming messages, outgoing messages, planner
   decision, scheduler responsibility, and side-effect boundary when requirements
   are ambiguous.
3. Keep changes scoped to one agency or architecture layer unless the user asks
   for a coordinated package/integration change.
4. Add or update tests for stream routing, planner decisions, scheduler behavior,
   side-effect boundaries, and JS-facing package behavior.
5. If LoomLarge must consume the Polymer change, inspect the committed
   dependency spec and lockfile in LoomLarge before claiming preview or
   production will pick it up.

## Validation

Prefer the narrowest relevant checks first:

```bash
pnpm test
pnpm build
pnpm check:dist
pnpm check:install-surface
```

For docs-only or skill-source-only changes, at least run:

```bash
git diff --check
```

Report any checks skipped and why.

## Review workflow

- Use `$loom-architect-pr-review` for cross-repo architecture, LoomLarge,
  Loom3, dependency pin, or release-boundary review.
- Use `$loomlarge-pr-dev-server` when a Polymer change must be validated through
  a LoomLarge PR worktree or local preview.
- Use `$loomlarge-react-scan-pr-review` only when the LoomLarge integration
  changes rendered React behavior or performance-sensitive UI paths.
