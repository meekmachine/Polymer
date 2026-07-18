# Polymer Agency Architecture

This document defines the general Polymer agency model. It is the reference for
new agencies and for porting character behavior into Polymer.

## Core Model

Polymer follows a Society of Mind model. A character is not controlled by one
central brain. Character behavior emerges from many small agencies collaborating
through streams.

Each agency has local competence. It keeps local state, interprets incoming
messages, proposes goals, plans actions, schedules its own work, and emits
outgoing messages that other agencies may react to. The agency system routes
messages between agencies, but it should not become a central planner or
arbitrator.

Responsibility is local and operational. An agency is responsible for its own
state, planner, scheduler, stream API, and any external side effects it directly
performs. That does not mean the agency controls the character behavior.
Behavior is the result of agencies influencing each other.

## Agency Parts

Every agency should follow the same shape, even when the first version is small:

- State: pure functions that normalize config, update local state maps, and
  expose serializable snapshots.
- GOAP/planner: local goal-oriented planning that turns incoming messages,
  facts, goals, constraints, and current state into proposed actions.
- Scheduler: the local queue that orders, cancels, replaces, delays, coalesces,
  or merges planned work before it reaches another agency or an effector.
- Domain transforms: pure data transforms for agency-specific mapping,
  normalization, scoring, or signal construction.
- Streams: incoming and outgoing observable streams carrying plain data.
- Effectors: the boundary code that touches an external system.

The scheduler is the architectural primitive for ordered work. Low-level clock
or delay mechanisms may exist underneath a scheduler, but they are implementation
details and should not be treated as the agency design.

## Streams

Agencies collaborate through incoming and outgoing streams. Stream names may be
agency-specific; the important rule is that messages are plain data and the
receiving agency decides what to do with them through its own planner and
scheduler.

Outgoing messages may include facts, goals, requests, constraints, priorities,
status, or diagnostics. A request can ask another agency to do something that may
eventually cause a side effect, but the side effect is not a global stream type.
The receiving agency accepts or rejects the request through its own local logic.

Avoid direct imports between domain agencies for coordination. If one agency
needs another to act, it should emit a stream message and let the agency system
route that data.

## Planning And Scheduling

GOAP/planning is agency-local. Each agency decides how its local state and
incoming stream messages become planned actions. There is no central GOAP brain
in the base architecture.

Schedulers are also agency-local. A scheduler is responsible for that agency's
planned work, including ordering, replacement, cancellation, coalescing, and
deferral. Cross-agency coordination happens through stream messages, not by a
global scheduler reaching into agency internals.

If two agencies need to influence the same behavior, neither agency silently
takes control. They exchange stream data so each local planner can decide how to
respond. The resulting behavior should come from that negotiation.

## Side Effects And Runtime Targets

Side effects include browser APIs, audio/video I/O, storage, network requests,
DOM access, worker messages, rendering engines, game engines, robotics
interfaces, and animation runtimes.

Pure state, planner, scheduler decision logic, and domain transforms must not
close over external handles or perform side effects. Side effects should happen
only inside the agency responsible for that external system, after planned work
has passed through that agency's scheduler.

Polymer core should not be coupled to one runtime target. A runtime-specific
agency or package may target Embody/Three.js today, but the same agency model
should be able to target Babylon, Unity, Godot, robotics, or other effectors
through separate runtime-specific boundaries.

## Host Integration

Polymer exposes a JavaScript API for host applications and tests. Hosts may
create the agency system, dispatch external commands, subscribe to outgoing
streams, and read snapshots for diagnostics or configuration.

Hosts should not become the message bus between Polymer agencies. They should
not translate one Polymer agency's outgoing messages into another Polymer
agency's incoming calls as normal runtime behavior. That routing belongs inside
the agency system.

High-frequency runtime progress should stay inside the responsible agency and
its scheduler/effector path. It should not be exported as host application state
that changes every frame.

## ClojureScript Style

Use pure functions for state transitions, planner scoring, goal selection,
domain normalization, and stream message construction.

Use Clojure transducers where they simplify pure map/filter/keep/mapcat/reduce
style transformations. Do not use transducers to hide side effects, scheduler
queues, provider calls, runtime calls, or order-dependent planning.

When requirements are unclear, write down the proposed incoming/outgoing stream
messages, local GOAP/planner behavior, scheduler responsibilities, and
side-effect boundary before implementing code.
