# Polymer

Clean ClojureScript character agency package for Character Loom.

Polymer is intentionally separate from Latticework and Polyester. It starts with
small, data-driven CLJS agencies that emit host-owned side effects as plain
events.

## Agency Boundary

Polymer agencies own agency-local state and timing. They do not call React,
LoomLarge, Latticework, Loom3/Embody, the DOM, audio, video, storage, or HTTP.

The package exposes one character-level agency system:

```js
const agencies = createCharacterAgencies();
agencies.dispatch({ agency: "blink", command: { type: "enable" } });

agencies.state.subscribe((event) => {});
agencies.events.subscribe((event) => {});
agencies.effects.subscribe((effect) => {});
```

The stream contract is:

- `input`: commands entering the character agency system.
- `state`: renderable agency snapshots.
- `events`: factual agency decisions/signals, such as `blinkPlanned` or
  `blink-fast`.
- `effects`: requested host-owned side effects, such as scheduling or removing
  animation snippets.

LoomLarge interprets `effects` during the migration. Polymer agencies can
consume the same `events` stream directly through the character network.

## Blink Agency

Blink is the first agency. It is split into state, planner, snippet builder, and
scheduler namespaces so the same pattern can scale to animation and prosodic
expression without putting lifecycle glue in UI components.

Automatic blink opportunities can become configurable n-blink bursts. The
default burst is a double blink, controlled by `burstFrequency`, `burstCount`,
and `burstGap`.

## Animation Agency

Animation is the first coordination agency. Blink does not emit host animation
effects directly anymore. Instead, Blink emits an `animation.requestScheduleSnippet`
event; the character system routes that event to the Animation agency; Animation
records the requested snippet in its own state and emits the host-facing
`animation.scheduleSnippet` / `animation.removeSnippet` effects.

That keeps animation side-effect requests in one agency and gives LoomLarge one
place to interpret them into Latticework while Polymer grows toward owning more
runtime scheduling logic.
