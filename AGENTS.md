# Agent Instructions

These instructions apply to the Polymer repository.

## Required Reading

Before changing agency code, read `docs/agency-architecture.md`. Treat that file
as the source of truth for agency collaboration, local GOAP/planner behavior,
scheduler responsibilities, stream routing, and side-effect placement.

## Agency Architecture

- Model Polymer as a Society of Mind agency system. Do not introduce a central
  planner, central arbitrator, or host-side message bus unless an issue
  explicitly asks for that change.
- Every agency should have the standard shape: state, local GOAP/planner,
  scheduler, incoming/outgoing streams, domain transforms, and effectors where
  needed.
- Agencies collaborate by stream messages. Messages may be facts, goals,
  requests, constraints, priorities, status, or diagnostics.
- Do not add a public generic side-effect stream as the architecture. A request
  may lead another agency to perform a side effect, but the side effect belongs
  inside the responsible agency.
- Keep host applications out of Polymer agency routing. Hosts may dispatch
  external commands and observe outputs, but Polymer agencies should route to
  each other inside the agency system.

## Side Effects

- Treat browser APIs, audio/video I/O, storage, network requests, DOM access,
  worker messages, runtime engines, robotics interfaces, and animation runtimes
  as side effects.
- Side effects should happen only inside the agency responsible for that
  external system, after planned work passes through that agency's scheduler.
- Pure state, planner, scheduler decision logic, and domain transforms must not
  close over external handles, browser globals, runtime objects, or host
  application state.
- Keep runtime targets replaceable. Do not make core agency architecture depend
  on one rendering, game, animation, or robotics library.

## ClojureScript Style

- Prefer pure functions for state, planning, provider normalization, and domain
  transforms.
- Use transducers only for pure map/filter/keep/mapcat/reduce style data
  transformations. Do not use them to hide side effects or control flow.
- Keep comments useful and concrete. Explain boundaries and non-obvious
  scheduler or planning decisions; do not comment every obvious line.

## Pull Requests

- Keep PRs scoped to the agency or architecture layer being changed.
- If requirements are unclear, write down the proposed incoming/outgoing stream
  messages, local GOAP/planner behavior, scheduler responsibility, and
  side-effect boundary before implementing.
- Include tests that prove stream routing, planner decisions, scheduler
  behavior, and side-effect boundaries.
