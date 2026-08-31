# 07 — Architecture

Recorded as decisions rather than as description, because the reasoning and the
rejected alternatives are the useful part. Each section gives context, the
decision, its consequences including the bad ones, and what would change it.

## Module structure

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="img/07-modules-dark.svg">
  <img alt="Component diagram: module structure and dependency direction" src="img/07-modules-light.svg">
</picture>

Arrows are compile-time dependencies. `:domain` depends on nothing, which is the
property that matters — everything else is arranged to preserve it.

## ADR-01 — Clean architecture, with `:domain` independent of `:protocol`

**Context.** The obvious layering would let `:domain` import `:protocol`, since
both are pure Kotlin and the domain plainly needs to reason about command
outcomes and link state. It would remove a mapping layer and a set of enums that
look duplicated.

**Decision.** `:domain` depends on nothing. `:data` maps protocol types to domain
types at the boundary.

**Consequences.** There are two enums that look like the same enum. `:protocol`
has `CommandOutcome` — a wire concept, with a byte encoding, versioned with the
protocol. `:domain` has `Resolution` — a business concept describing what the app
now knows about a dose.

They are genuinely different despite the overlap, and the case that proves it is
`EVICTED`. On the wire that is one specific value meaning "no record, outside
retention." In the domain it collapses into the same `Indeterminate` as a
`T_RESOLVE` expiry, because the business consequence is identical: block dosing,
require human verification. The domain does not want the protocol's resolution,
and if it imported the protocol's enum it would be forced to carry a distinction
it has no use for, and to change every time the wire format gained a case.

The cost is real: a mapping function to maintain, and a reviewer's first reaction
is that it is ceremony. The benefit is that the protocol can version — new
opcodes, new outcomes, a v2 frame layout — without touching business logic, and
that the layer an iOS port would share is the one with no wire format in it.

**What would change this.** If the protocol were stable and owned by the same
team forever, and the mapping stayed one-to-one across a couple of versions, the
indirection would be costing more than it returns. It is not stable here; it is
the thing under active design.

## ADR-02 — MVI in the UI layer

**Context.** Google's guidance describes unidirectional data flow across UI,
domain, and data layers without endorsing "MVVM" or "MVI" by name. In practice
the industry converged: a single immutable state object exposed as `StateFlow`,
events up, state down. What is still a real choice is whether to formalize
intents into a sealed type routed through one entry point.

For an ordinary CRUD screen, formalizing intents is over-engineering and a good
reviewer knows it.

**Decision.** Full MVI here, deliberately, for a reason specific to this system.

```kotlin
sealed interface BolusIntent {
    data class DoseEntered(val milliunits: Int) : BolusIntent
    data object Confirmed : BolusIntent
    data object Cancelled : BolusIntent
    data object ReissueConfirmed : BolusIntent
    data object ReissueDeclined : BolusIntent
    data object PumpVerifiedByUser : BolusIntent
}

sealed interface BolusUiState {
    data class Entering(val draft: Draft, val pump: PumpSummary) : BolusUiState
    data class Confirming(val dose: Dose, val pump: PumpSummary) : BolusUiState
    data class Delivering(val delivered: Dose, val requested: Dose) : BolusUiState
    data class Delivered(val delivered: Dose) : BolusUiState
    data class PartiallyDelivered(val delivered: Dose, val reason: AbortReason) : BolusUiState
    data class AwaitingReissue(val dose: Dose, val elapsed: Duration) : BolusUiState
    data class Resolving(val dose: Dose) : BolusUiState
    data class Blocked(val dose: Dose) : BolusUiState
    data class Indeterminate(val dose: Dose) : BolusUiState
    data class DosingDisabled(val link: LinkStatus) : BolusUiState
}

fun reduce(state: BolusUiState, intent: BolusIntent): BolusUiState
```

**Why it earns its cost here.** Every transition is a pure function testable
without a radio, and the compiler refuses to build a reducer that adds a state or
an intent and leaves a combination unhandled.

That is the difference between a style preference and a risk control. Each hazard
in [04-hazard-analysis.md](04-hazard-analysis.md) corresponds to a specific
`(state, intent)` pair, and each pair has a test. When `Indeterminate` was added
to handle H-04, the build broke in every place that had not decided what to do
when the app does not know whether insulin was delivered. A compile error is the
correct failure mode for that omission. A runtime `else` branch quietly rendering
a spinner is not.

This is requirements traceability expressed in the type system, and it is the
single strongest reason to choose MVI for this screen and not for most screens.

**Consequences.** More types and more boilerplate than a `ViewModel` with a few
`StateFlow`s. Ten UI states for one screen looks excessive until you notice that
five of them are the terminal states from
[01-feature-flow.md](01-feature-flow.md#terminal-states) and two of those block
dosing.

The rule that makes it work: **never add `else` to a `when` over a sealed type in
this codebase.** A missing branch is the compiler doing its job. This is stated
in `.cursor/rules/architecture.mdc` as well, because it is exactly the kind of
thing a code assistant will helpfully "fix."

**What would change this.** A screen where states are independent rather than
mutually exclusive — a settings page, a list with filters — is better served by a
single state object without a formal intent hierarchy. MVI is not the house
style; it is the choice for this screen.

## ADR-03 — One-off events are modeled as state

**Context.** The established pattern for "show a snackbar" or "navigate away" has
been a `Channel` or `SharedFlow` of one-shot effects, often behind an `Event`
wrapper.

**Decision.** No `Channel`, no `SharedFlow`, no `Event` wrapper for
ViewModel-to-UI communication. Everything is `UiState`.

Google's current guidance says
[ViewModel events should always result in a UI state update](https://developer.android.com/topic/architecture/ui-layer/events),
and the recommendations table lists "do not send events from the ViewModel to the
UI" as strongly recommended, because when the producer outlives the consumer
those APIs do not guarantee delivery.

**Why it is not a close call here.** The general argument is that an event may be
dropped if the UI is backgrounded at the wrong moment. In this app the dropped
event would be *"we do not know whether 4.5 units were delivered."*

There is no fire-and-forget notification in an insulin delivery application. If
the pump's state is unknown, that is a property of the system that persists until
someone resolves it, and it must survive backgrounding, rotation, and process
death. It is state by nature; representing it as an event was always a category
error, and the platform guidance simply happens to agree.

**Consequences.** Transient messages need an explicit acknowledgement intent to
clear them, which is more code than firing and forgetting. For states that block
dosing, that is not a drawback — being unable to dismiss the indeterminate state
without resolving it is the point.

## ADR-04 — Two state machines, kept separate

**Context.** There is a connection state machine in `:protocol` and a UI state
hierarchy in `:app`. They are correlated. Merging them removes an apparent
duplication.

**Decision.** They stay separate. `BolusUiState` is a **projection**:

```kotlin
fun project(
    link: LinkStatus,
    journal: JournalSnapshot,
    pump: PumpSummary?,
    draft: Draft?,
): BolusUiState
```

Pure, in `:domain`, tested by feeding it combinations and asserting the result.

**Why.** The two have different alphabets, in both directions.

`LinkState` distinguishes `Connecting`, `Bonding`, `Discovering`, and
`Configuring`; the UI renders one spinner for all four, and should, because the
user cannot act differently on any of them. Conversely `BolusUiState` has
`Entering` and `Confirming`, which the transport has no business knowing exist.

Merging them produces the standard BLE application failure: a UI state enum that
grows a case every time the transport learns a new failure mode, and a transport
that cannot be tested without instantiating a UI. Once that has happened, the
protocol layer cannot be ported, reused, or fuzzed independently, which forfeits
most of the value of having written it carefully.

**Consequences.** A projection function to maintain, and one more place to look
when tracing why the screen shows what it shows. In exchange, the projection is
where the interesting logic concentrates and it is exhaustively table-testable —
`Ready` plus an unresolved journal entry must never project to a state with an
enabled dose button, and that is one assertion rather than an audit of the UI.

## ADR-05 — Kotlin Multiplatform considered and not adopted

**Context.** `:protocol`, `:domain`, and `:simulator` are pure Kotlin with no
Android dependencies. Converting them to KMP targets would let an iOS app consume
the identical framing, session, state machine, and business logic, which is the
strongest possible answer to the parity problem in
[05-parity-contract.md](05-parity-contract.md).

**Decision.** Structure for it. Do not build it.

The modules are already free of Android imports and would move to
`kotlin("multiplatform")` with a source-set reshuffle and no logic changes. That
is a deliberate property, maintained by the module boundaries and enforced by the
build.

**Why not build it.** It would consume the majority of the time available and
demonstrate KMP toolchain configuration, which is not the skill this project
exists to show. Worse, it would weaken the parity document: if the protocol layer
is literally shared, the eight platform divergences that make parity hard do not
disappear — they move into the thin platform layer where they are easier to
overlook. The divergences are in bonding order, notification enablement, device
identity, and background execution, and no amount of shared Kotlin removes them.

Sharing the protocol layer is a real answer to part of the problem. Believing it
is the whole answer is the trap.

**What would change this.** A second platform actually being built, and a team
willing to own the KMP toolchain in a regulated build and release process —
which is a change-control question at least as much as a technical one.

## Where the safety argument lives

| Hazard | Enforced in | Android needed to test? |
| --- | --- | --- |
| H-01, H-04, H-13 | `:simulator` record store and limits | No |
| H-02, H-03, H-07, H-14 | `:domain` use cases and projection | No |
| H-06, H-08, H-09, H-10 | `:protocol` codec and session | No |
| H-05, H-11 | `:protocol` state machine, `:data` platform mapping | Partly |
| H-12 | `:data` journal and foreground service | Yes |

Twelve of fourteen hazards have their controls in pure Kotlin and are verified in
CI on every push, in seconds, with no device attached. That is the practical
payoff of the module boundaries, and it is the argument to make when someone
proposes collapsing them.

## Conventions

- Kotlin, coroutines, Gradle Kotlin DSL, version catalog
- `compileSdk 36`, `targetSdk 34`, `minSdk 31` — rationale in
  [00-overview.md](00-overview.md#verification-boundary)
- ViewModel exposes `StateFlow` via `stateIn(WhileSubscribed(5_000))`
- Compose collects with `collectAsStateWithLifecycle()`
- Composables take `(uiState, onIntent)` and hold no ViewModel reference, so
  every state including the ones that are hard to reach is previewable
- No `else` branches on `when` over sealed types
- `:protocol`, `:domain`, and `:simulator` have no Android dependencies, checked
  by the build rather than by review
