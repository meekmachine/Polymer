# Polymer

Clean ClojureScript character agency package for Character Loom.

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

Polymer currently includes early Blink, Animation, Gaze, TTS, LipSync, and
Eye/Head Tracking, TTS, LipSync, and Prosodic Expression agencies, plus first
slices for Conversation, Camera Context, Hair, and Transcription. These are
implementation milestones, not the limits of the architecture. Each agency
should follow the same local GOAP/planner, scheduler, stream, transform, and
effector pattern as the package grows.

Runtime-specific work belongs at the edge. The current animation path can target
the existing web runtime, but Polymer core should remain able to support other
runtime targets such as Babylon, Unity, Godot, robotics, or future animation
libraries through separate runtime-specific boundaries.

## Git Install Contract

Polymer Git-SHA consumers should import checked-in JavaScript artifacts from
`dist`. They should not need Java, Shadow CLJS, or a local Polymer build during
application install.

The package intentionally has no `prepare`, `install`, or `postinstall` build
lifecycle. Polymer CI runs the CLJS build, verifies the checked-in `dist`
artifacts are current, and `prepublishOnly` repeats the build/test check before
an npm publish.
