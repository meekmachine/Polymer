# Polymer Agency Architecture

This document defines what a Polymer agency is, what it owns, how agencies
communicate, and where side effects belong. It is the reference for implementing
new agencies and for porting existing Latticework behavior into Polymer.

## Definition

An agency is a small, stateful service for one character capability. It owns the
state, planning, timing, and stream protocol for that capability. It exposes a
plain JavaScript API for LoomLarge and tests, but its internal logic is written
in ClojureScript data-first style.

An agency is not a React hook, a UI component, a collection of utility
functions, or a host adapter. React may dispatch commands into an agency and may
read snapshots for diagnostics or controls, but React should not become the
runtime router between agencies.

## Constituent Parts

Most agencies should be split into these parts:

- State: pure functions that normalize config, update local state maps, and
  expose serializable snapshots.
- Planner: pure functions that turn commands plus current facts into an ordered
  plan. A planner must not close over JS handles, timers, HTTP clients, audio,
  DOM nodes, or animation runtimes.
- Scheduler: the agency-local queue for timed work and effector operations. It
  owns timers, timer cancellation, and ordered dispatch of side-effect requests.
- Domain transforms: pure data transforms, such as text-to-phoneme,
  provider-viseme normalization, jaw/tongue/lip curve construction, and snippet
  assembly.
- Agency shell: the public object with `dispatch`, `snapshot`, stream ports, and
  `dispose`. It wires state, planner, scheduler, and streams together.
- Effectors: the code that actually touches an external system. Effectors are
  owned by the agency responsible for that external system.

Not every agency needs every file on day one, but these responsibilities should
remain separate. Do not hide timers, provider calls, runtime calls, or React
state inside pure transforms.

## Streams

Every agency can expose three stream ports:

- `input`: commands accepted by the agency. This is useful for tests, workers,
  and debugging.
- `events`: domain facts and cross-agency messages. This is the normal way
  agencies communicate.
- `effects`: compatibility API for host-owned effects. Polymer should avoid
  using this as the primary animation path once a Polymer agency owns the
  relevant runtime side effect.

Cross-agency communication should be data on streams, not direct imports between
domain agencies. A character-level network may subscribe to agency event streams
and route those events to the owning agency.

## Side Effects

Side effects include:

- Timers and animation frames.
- Browser speech synthesis.
- Audio playback and audio clock reads.
- HTTP/fetch/backend calls.
- Storage/profile persistence.
- DOM reads and writes.
- Video, microphone, camera, and LiveKit.
- Embody/Loom3 runtime calls.

Side effects must be owned by the agency responsible for that external system.
When side effects need ordering, delay, cancellation, or replacement behavior,
they should go through that agency's scheduler.

The scheduler is not just a timeout helper. It is the agency-local queue for
timed effector work. For example, LipSync's scheduler should queue the work to
schedule the combined lip/jaw/tongue snippet, seek that snippet when audio or
word-boundary drift is observed, remove it on stop/replacement, and mark the
timeline finished when the utterance should be done.

## TTS And LipSync Boundary

TTS and LipSync are separate agencies.

TTS owns text-to-speech:

- Web Speech API calls.
- Azure TTS request/playback orchestration.
- Voice loading.
- Provider/audio facts such as speech started, speech ended, audio clock, and
  word boundaries.

TTS must not own lip, jaw, tongue, or viseme animation. It should emit plain
events such as `lipSync.command` with commands for LipSync:

- `configure`
- `startText`
- `processAzureVisemes`
- `audioStarted`
- `audioTime`
- `wordBoundary`
- `updateWordTimings`
- `stop`

LipSync owns speech animation:

- Text/phoneme/viseme mapping.
- Azure/SAPI provider-viseme normalization into Polymer's canonical viseme
  timeline.
- Lip, jaw, and tongue articulation.
- Diphthong and stacked-consonant handling.
- Snippet construction.
- Queuing animation requests for the Animation agency.

The intended flow is:

```text
TTS provider/audio facts
  -> TTS event stream
  -> lipSync.command events
  -> LipSync agency
  -> LipSync scheduler queues lip/jaw/tongue animation work
  -> Animation agency
  -> Embody runtime
```

## LipSync Scheduling

LipSync should schedule one utterance-level animation snippet for speech. Lip
visemes, AU 26 jaw motion, and tongue support controls should be channels in
that snippet, not independently managed host calls from TTS or LoomLarge.

The individual articulators may live in separate namespaces for clarity, but the
scheduled output is one coherent timeline. The scheduler owns the effector queue
for that timeline:

- `schedule`: queue the combined snippet for Animation.
- `seek`: queue an Animation seek when audio or word-boundary drift requires
  correction.
- `remove`: queue Animation removal on stop, reset, or replacement.
- `finish`: queue local LipSync completion when the utterance should be done.

The scheduler should expose enough queue state for tests and diagnostics to
prove the ordering without requiring React state subscriptions.

## Animation Boundary

Animation is the agency that owns Embody/Loom3 runtime side effects. Other
agencies emit animation intent as data. They do not call Embody directly, and
LoomLarge should not translate Polymer agency events into Embody calls.

Animation records schedule state, owns clip handles, performs runtime calls, and
cleans up snippets. If a future agency needs animation, it should route intent
to Animation through the character network.

## React Boundary

LoomLarge React components should not create one hook per agency just to move
events around. React should create or obtain the character agency network,
dispatch user commands, and render controls from stable state or snapshots.

High-frequency runtime progress should not drive React renders. Use CSS
animation or direct DOM/runtime mechanisms for scrubbers and progress indicators
that change every frame.

## Transducers

Use Clojure transducers where they simplify pure data transformations:

- Normalize provider rows and drop malformed values.
- Expand tokens into phonemes.
- Map/filter/sum data without intermediate collections.

Do not use transducers to hide side effects, timers, scheduler queues, provider
calls, runtime calls, or order-dependent gesture planning. Sorting, grouping,
coarticulation, cumulative timing, and neighbor-aware articulation should stay
explicit unless a custom transducer is clearly simpler and well tested.

## Migration Rule

While Polymer replaces Latticework agency by agency, avoid one-off integration
paths. New Polymer agencies should use the same command, stream, scheduler, and
side-effect ownership model described here. If the right boundary is unclear,
write down the proposed stream events and scheduler ownership before
implementing code.
