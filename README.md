# Polymer

Clean ClojureScript character agency package for Character Loom.

Continuous PR releases are published via [pkg.pr.new](https://pkg.pr.new/). Install the [pkg-pr-new GitHub App](https://github.com/apps/pkg-pr-new) on this repository so pull requests can publish preview packages. Stable npm publishing is deferred until we have a release candidate; until then consumers should use full git SHA pins or pkg.pr.new URLs.

Polymer is intentionally separate from Latticework and Polyester. It is built as
a Society of Mind agency system: small CLJS agencies collaborate through streams,
plan locally, schedule their own work, and keep side effects inside the agencies
responsible for performing them.

See [docs/agency-architecture.md](docs/agency-architecture.md) for the agency
collaboration, scheduler, side-effect, stream, and runtime-target rules.

## Agency API

The package exposes an agency system:

```js
const agencies = createCharacterAgencies();
agencies.dispatch({ agency: "blink", command: { type: "enable" } });

agencies.events.subscribe((message) => {});
agencies.snapshot();
```

Agencies communicate through incoming and outgoing streams of plain data.
Messages may be facts, goals, requests, constraints, priorities, status, or
diagnostics. Requests that imply side effects are handled by the receiving
agency's local planner, scheduler, and effector path.

Host applications may dispatch external commands, observe outgoing streams, and
read snapshots for diagnostics or configuration. Host applications should not
serve as the normal message bus between Polymer agencies.

## Current Agencies

Polymer currently includes early Blink, Animation, Gaze, Eye/Head Tracking,
TTS, LipSync, Prosodic Expression, and Gesture agencies, plus first slices for
Conversation, Camera Context, Hair, and Transcription. These are implementation
milestones, not the limits of the architecture. Each agency should follow the
same local GOAP/planner, scheduler, stream, transform, and effector pattern as
the package grows.

Runtime-specific work belongs at the edge. The current animation path can target
the existing web runtime, but Polymer core should remain able to support other
runtime targets such as Babylon, Unity, Godot, robotics, or future animation
libraries through separate runtime-specific boundaries.

## Gesture Agency

Gesture is the arm/hand gesticulation agency. It accepts LoomLarge-authored
gesture snapshots as plain data and turns them into typed bone-channel animation
snippets. It does not call Three.js bones, React, storage, or the Embody runtime.
Gesture snapshots are compatible with the Gestures tab profile shape: id/name,
optional description or text representation, emoji trigger, left/right/both/custom
scope, captured source metadata, duration, priority, affected bones, static bone
targets, and optional time-based keyframes.

Host integrations should prefer the character agency network so Gesture's
animation requests are routed to Animation inside Polymer:

```js
const agencies = createCharacterAgencies({
  animation: { engine },
  gesture: {
    gestures: profile.characterGestures,
    emojiMappings: profile.gestureEmojiMappings,
  },
});

agencies.dispatch({ agency: "gesture", command: { type: "playEmoji", emoji: "👋" } });
```

The host supplies the gesture library and may observe streams for diagnostics,
but Gesture's own scheduler is responsible for replacement, cancellation, and
completion cleanup before Animation owns the runtime side effect.

## Character host (Embody scene + model loading)

Polymer re-exports Embody's character host so LoomLarge and other hosts can set
up a character without depending on Embody directly.

Default path — pass a container element and a character URL:

```js
import {
  createCharacterHost,
  createCharacterAgencies,
} from '@lovelace_lol/polymer';

const host = await createCharacterHost({
  container: document.getElementById('viewport'),
  character: {
    modelUrl: '/characters/jonathan.glb',
    presetType: 'cc4',
  },
});

const agencies = createCharacterAgencies({
  animation: { engine: host.engine },
});
```

Named scene types (`studio`, `showcase`, `inspection`, `void`) bundle
background, lighting preset, and shadow-plane defaults; enumerate them via
`CHARACTER_SCENE_TYPES` / `CHARACTER_SCENE_TYPE_IDS`:

```js
const host = await createCharacterHost({
  container,
  character: { modelUrl: '/characters/jonathan.glb' },
  scene: { type: 'showcase' },
});
```

Advanced path — inject an existing Three.js scene:

```js
const host = await createCharacterHost({
  container: mountEl,
  character: { modelUrl: '/characters/jonathan.glb', presetType: 'cc4' },
  external: { scene, renderer, camera },
});
```

Polymer stays agency-oriented: it does not own the render loop. Use
`createCharacterHost` / `loadCharacterModel` for scene+model setup, then inject
`host.engine` into `createCharacterAgencies`.

## Git Install Contract

Polymer Git-SHA consumers should import checked-in JavaScript artifacts from
`dist`. They should not need Java, Shadow CLJS, or a local Polymer build during
application install.

The package intentionally has no `prepare`, `install`, or `postinstall` build
lifecycle. Polymer CI runs the CLJS build, verifies that `dist` exposes the
browser ESM API that Git-SHA consumers import, and `prepublishOnly` repeats the
build/test/API check before an npm publish.
