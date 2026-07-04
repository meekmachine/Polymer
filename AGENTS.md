# Agent Instructions

These instructions apply to the Polymer repository.

## Required Reading

Before changing agency code, read `docs/agency-architecture.md`. Treat that file
as the source of truth for agency boundaries, stream ownership, scheduler
responsibilities, and side-effect placement.

## Agency Boundaries

- Keep TTS and LipSync separate. TTS owns speech synthesis and provider/audio
  facts. LipSync owns lip, jaw, tongue, viseme mapping, and speech animation
  scheduling.
- Agencies communicate through command/event streams. Do not make LoomLarge or a
  React hook translate Polymer events into Polymer agency calls.
- Do not add a one-off React hook for each agency as routing glue. React may
  dispatch UI commands and read stable snapshots; it should not become the
  runtime message bus.
- Animation is the only agency that calls Embody/Loom3. Other agencies emit
  animation intent as data.

## Side Effects

- Treat timers, animation frames, browser APIs, audio, HTTP, storage, DOM,
  LiveKit, and Embody runtime calls as side effects.
- Timed or ordered side effects should go through the owning agency scheduler.
  Do not scatter `setTimeout`, `requestAnimationFrame`, or cleanup timers across
  domain logic.
- Pure state, planner, and articulation namespaces must not close over JS
  handles, browser globals, runtime objects, or React state.

## LipSync Rules

- LipSync should produce one utterance-level snippet for speech. Lip visemes,
  jaw AU 26, and tongue controls are channels in that snippet.
- The articulator namespaces may be separate for clarity, but their output must
  converge through the LipSync scheduler before Animation sees it.
- TTS should trigger LipSync by emitting `lipSync.command` events, not by
  constructing mouth animation itself.
- Preserve provider-specific facts at the TTS boundary, then normalize them into
  LipSync's canonical timeline inside the LipSync path.

## ClojureScript Style

- Prefer pure functions for state, planning, provider normalization, and
  articulation transforms.
- Use transducers only for pure map/filter/keep/mapcat/reduce style data
  transformations. Do not use them to hide side effects or control flow.
- Keep comments useful and concrete. Explain boundaries and non-obvious timing
  decisions; do not comment every obvious line.

## Pull Requests

- Keep PRs scoped to the agency or architecture layer being changed.
- If requirements are unclear, write down the proposed stream events and
  scheduler ownership before implementing.
- Include tests that prove stream routing and scheduler ownership, not just
  generated shape snapshots.
