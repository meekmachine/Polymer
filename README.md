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
agencies.subscribeStatus((event) => {});
agencies.subscribeCommands((event) => {});
agencies.dispatch({ agency: "blink", command: { type: "enable" } });
```

`status` events describe internal state and cross-agency signals. `command`
events request host-owned effects, such as scheduling or removing animation
snippets. LoomLarge interprets those command events during the migration; later
Polymer agencies can consume the same stream directly.

## Blink Agency

Blink is the first agency. It is split into state, planner, snippet builder, and
scheduler namespaces so the same pattern can scale to animation and prosodic
expression without putting lifecycle glue in UI components.

Automatic blink opportunities can become configurable n-blink bursts. The
default burst is a double blink, controlled by `burstFrequency`, `burstCount`,
and `burstGap`.
