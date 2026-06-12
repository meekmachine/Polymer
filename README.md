# Polymer

Clean ClojureScript character agency package for Character Loom.

Polymer is intentionally separate from Latticework and Polyester. It starts with
small, data-driven CLJS agencies that emit host-owned side effects as plain
events.

## Blink Agency

`createBlinkAgency(config?)` is the first agency. It owns Blink-local state and
timers, but it does not call a rendering engine or mutate the DOM. It emits two
plain JS event streams:

- status events for state, planning, and cross-agency signals
- command events for host-owned effects such as scheduling/removing animation snippets

The default burst behavior is a two-blink burst that happens only a small portion
of the time during automatic blinking. Hosts can configure `burstChance`,
`burstCount`, and `burstGap`; manual triggers can pass the same options.
