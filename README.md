# Polymer

Clean ClojureScript character agency package for Character Loom.

Polymer is intentionally separate from Latticework and Polyester. It starts with
small, data-driven CLJS agencies that exchange plain events and keep runtime
side effects inside the agencies that own them.

## Agency Boundary

Polymer agencies own agency-local state and timing. Domain agencies do not call
React, LoomLarge, Latticework, the DOM, audio, video, storage, or HTTP.
Animation is the runtime boundary: it is the only agency that talks directly to
the Embody/Loom3 animation runtime.

The package exposes one character-level agency system:

```js
const agencies = createCharacterAgencies();
agencies.dispatch({ agency: "blink", command: { type: "enable" } });

agencies.events.subscribe((event) => {});
agencies.effects.subscribe((effect) => {});
agencies.snapshot();
```

The stream contract is:

- `input`: commands entering the character agency system.
- `events`: factual agency decisions/signals, such as `blinkPlanned` or
  `blink-fast`, plus low-frequency config-change events.
- `effects`: reserved for host-owned side effects. Blink and Vocal currently do
  not use it for animation playback because the character network routes their
  animation events directly to Polymer Animation.
- `snapshot()`: pull-based diagnostic/config reads. Hosts should not wire this
  to a live React subscription for runtime playback ticks.

LoomLarge can still feed commands into Polymer while other legacy agencies live
in Latticework. It should not subscribe to Polymer playback events and translate
them into animation calls; the Polymer character network routes those to
Animation internally.

## Blink Agency

Blink is the first agency. It is split into state, planner, snippet builder, and
scheduler namespaces so the same pattern can scale to animation and prosodic
expression without putting lifecycle glue in UI components.

Automatic blink opportunities can become configurable n-blink bursts. The
default burst is a double blink, controlled by `burstFrequency`, `burstCount`,
and `burstGap`.

## Animation Agency

Animation is the first coordination agency. Blink and Vocal do not emit host
animation effects directly. They emit `animation.requestScheduleSnippet`,
`animation.requestRemoveSnippet`, or `animation.requestSeekSnippet` events; the
character system routes those events to the Animation agency; Animation records
the requested snippet in its own state and calls the Embody/Loom3 runtime.

That keeps animation side effects in one agency while Polymer grows toward
replacing the remaining Latticework runtime services.

## Vocal/LipSync Agency

Vocal is the second migrated domain agency. It accepts provider/text timing data
as commands and schedules one utterance-level typed animation snippet through
Polymer Animation. Viseme channels target `{ type: "viseme" }`; jaw motion uses
an explicit AU 26 channel, so Polymer does not rely on `snippetCategory` for
normal namespace routing.

The vocal planner treats lip shape and jaw opening as separate controls. Text
fallback and Azure timelines can expand diphthongs into two lip targets while
keeping one jaw arc, and stacked consonants collapse to a low jaw target instead
of reopening AU 26 for every consonant.

Supported inputs:

- `startText`: fallback deterministic text-to-viseme planning.
- `startTimeline`: already-normalized canonical viseme timelines.
- `processAzureVisemes`: Azure/SAPI 0-21 viseme events, including LiveKit-style
  `{ id, time }` payloads.
- `updateWordTimings`: provider word-boundary metadata that arrives after the
  viseme timeline has already started.
- `wordBoundary`: drift correction. When provider word timing and observed
  playback time diverge, Vocal requests an Animation seek instead of reaching
  through LoomLarge.

Vocal intentionally does not own Azure credentials, audio playback, LiveKit
connections, browser speech APIs, or backend HTTP. Those systems should feed
plain command data into the agency.

## Git Install Contract

Polymer Git-SHA consumers should import checked-in JavaScript artifacts from
`dist`. They should not need Java, Shadow CLJS, or a local Polymer build during
application install.

The package intentionally has no `prepare`, `install`, or `postinstall` build
lifecycle. Polymer CI runs the CLJS build, verifies the checked-in `dist`
artifacts are current, and `prepublishOnly` repeats the build/test check before
an npm publish.
