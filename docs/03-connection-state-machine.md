# 03 — Connection State Machine

Owns the link lifecycle from cold start to a session that is permitted to dose.
Lives in `:protocol` as pure Kotlin with no Android imports, and is a **pure
function**:

```kotlin
fun reduce(state: LinkState, event: LinkEvent): Transition
data class Transition(val state: LinkState, val effects: List<LinkEffect>)
```

Effects are returned as data and executed by the caller. Nothing inside the
reducer performs I/O, starts a coroutine, or reads a clock — timeouts arrive as
events. That is what allows every transition in this document, including every
timeout and every error class, to be tested on the JVM in milliseconds with no
radio and no device attached.

## Diagram

The machine is drawn at two levels. At the top level, the six states that get a
link up are collapsed into the composite state `Active.Linking`, and a single
transition off the `Active` boundary carries the disconnection and timeout cases
that apply to every substate. Drawing those as eleven separate edges is
technically equivalent and considerably harder to read.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="img/03-connection-state-dark.svg">
  <img alt="Connection state machine, top level" src="img/03-connection-state-light.svg">
</picture>

`Linking` refined:

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="img/03-linking-substates-dark.svg">
  <img alt="Linking composite state, refined into its substates" src="img/03-linking-substates-light.svg">
</picture>

The implementation is one flat `when (state, event)` reducer over the leaf
states listed below. The composite is a presentation device for the diagram, not
a nested state machine in the code — hierarchical states would buy nothing here
except a second dispatch mechanism to get wrong.

## `Reconciling` is the point of the diagram

Everything else here is a conventional BLE lifecycle. The load-bearing detail is
that `Reconciling` sits **between** `Authenticating` and `Ready`, and that the
bolus operation is reachable only from `Ready`.

This makes invariant I-5 — no delivery command on a session with unresolved
in-flight history — a property of the state machine rather than a rule someone
has to remember. There is no ordering of calls that sends a bolus before
reconciliation, because the function that sends a bolus takes a `Ready` state as
a parameter and no other state can be widened to it.

`Suspended` is the case where reconciliation ran and could not resolve
everything. The link is healthy and status is readable, but dosing is refused.
Distinguishing it from `Recovering` matters: `Recovering` is "we cannot talk to
the pump," `Suspended` is "we are talking to the pump and it told us something we
must not act through."

`GET_STATUS` cannot resolve a command. It carries reservoir, battery,
`deliveryActive`, `recordEpoch`, and `storeInstanceId`. The operation that
answers "what happened to `CommandId` N" is `QUERY_COMMAND_OUTCOME_REQ`, which
is what `BeginReconcile` already sends. `Suspended` therefore leaves by
re-entering `Reconciling`, not by polling status.

## States

| State | Meaning | Dosing |
| --- | --- | --- |
| `Idle` | No link, none wanted | No |
| `Scanning` | Advertising filter active on the service UUID | No |
| `Connecting` | GATT connect issued | No |
| `Bonding` | Waiting for bond, or confirming an existing bond | No |
| `Discovering` | Service and characteristic discovery | No |
| `Configuring` | MTU settled and notifications enabled on `RSP` | No |
| `Subscribed` | Transport usable; no application session yet | No |
| `Authenticating` | Challenge/response in progress | No |
| `Reconciling` | Resolving every journaled in-flight command | No |
| `Ready` | Session established, nothing unresolved | **Yes** |
| `Suspended` | Session established, something unresolved | No |
| `Recovering` | Backing off before another attempt | No |
| `Failed` | Attempts exhausted; awaiting user action | No |
| `Unpaired` | Bond or pairing secret invalid; re-pairing required | No |

## Events

| Event | Source |
| --- | --- |
| `StartRequested`, `StopRequested` | Application |
| `ScanMatch(logicalDeviceId)` | Scanner |
| `ScanTimeout` | Timer |
| `Connected`, `ConnectFailed(code)` | Platform GATT |
| `Disconnected(code)` | Platform GATT, or the adapter itself |
| `Bonded`, `BondFailed`, `BondLost` | Platform bonding |
| `ServicesDiscovered`, `DiscoveryFailed(code)` | Platform GATT |
| `ServiceChanged` | Peripheral indication |
| `MtuSettled(mtu)` | Platform GATT |
| `CccdConfirmed` | Platform GATT |
| `StatusRead(logicalDeviceId, protocolVersion, recordEpoch)` | `STATUS` characteristic |
| `AuthSucceeded`, `AuthFailed(reason)` | `:protocol` L4 |
| `ReconcileDone(unresolvedCount)` | `:domain` |
| `UserVerifiedAtPump` | Application — a human closed an Indeterminate row against the pump |
| `ReconcileRequested` | Application — the 5 s tick, or an explicit re-check. Not a human attestation. |
| `Timeout(state)` | Timer |

`Disconnected` is also produced when the phone's Bluetooth adapter is powered
down. Several stacks, including the one on this project's A32, do not reliably
deliver a GATT `onConnectionStateChange` for that case: the client handle
stays "connected" and a write is accepted locally against a radio that is
already off. The adapter broadcast is the signal that is actually delivered,
and Ready must not survive it. A new delivery command is not journaled while
the adapter is off — journal-before-transmit is for an ambiguous radio, not
for one the platform has already declared gone.

`ScanMatch` carries the **logical device identity** from the advertisement when
the advertisement has one, and `null` when it does not. Matching on a MAC
address would work on Android and be impossible on iOS; see
[02-protocol.md](02-protocol.md#advertising-and-logical-identity). The Mac
harness cannot put the identity in manufacturer data at all
([05-parity-contract.md](05-parity-contract.md#d-10--advertising-payload)),
which is why the field is optional and why `StatusRead` is not.

`StatusRead` is the first L0 read after `Subscribed`. It is not an L3
`GET_STATUS_REQ`. Authenticating is unreachable until the identity in that
value equals the paired one.

## Guards

| Transition | Guard |
| --- | --- |
| `Scanning → Connecting` | Advertised logical device identity equals the paired one, **when present**. If the advertisement carries no identity, the service UUID is sufficient to connect; confirmation waits for `StatusRead`. |
| `Subscribed → Authenticating` | `StatusRead` logical device identity equals the paired one |
| `Subscribed → Recovering` | `StatusRead` identity is missing or does not match |
| `Reconciling → Ready` | `unresolvedCount == 0` |
| `Reconciling → Suspended` | `unresolvedCount > 0` |
| `Suspended → Reconciling` | `UserVerifiedAtPump` or `ReconcileRequested` |
| `Recovering → Scanning` | `attempts < maxAttempts` and backoff elapsed |
| `Recovering → Failed` | `attempts >= maxAttempts` |
| `Authenticating → Unpaired` | Failure reason is bond loss or key mismatch, not timeout |

## Timeouts

| State | Timeout | On expiry |
| --- | --- | --- |
| `Scanning` | 30 s | `Idle` |
| `Connecting` | 10 s | `Recovering` |
| `Bonding` | 30 s | `Recovering` |
| `Discovering` | 10 s | `Recovering` |
| `Configuring` | 5 s | `Recovering` |
| `Subscribed` | 5 s | `Recovering` — `STATUS` read did not complete |
| `Authenticating` | 5 s (`T_AUTH`) | `Recovering` |
| `Reconciling` | 10 s (`T_RESOLVE`) | `Suspended` |

`Reconciling` expiring goes to `Suspended`, not `Recovering`. The link may be
fine; what is not fine is proceeding. Sending it to `Recovering` would drop a
working connection and retry into the same wall. `Suspended` is not a dead
end: the same 5 s tick, or an explicit re-check, sends `ReconcileRequested`
and re-enters `Reconciling` with a fresh `T_RESOLVE`.

## Ready-state status poll

The 5 s tick is shared across the sessioned states and does three different
things:

| State | Tick |
| --- | --- |
| `Ready` | `GET_STATUS_REQ`, one attempt |
| `Suspended` | `ReconcileRequested` — re-run `QUERY_COMMAND_OUTCOME` for every in-flight command |
| `Reconciling` | no-op; a reconcile is already running |

The first vitals snapshot is taken on entry to `Ready`, which has no timeout,
so a slow status read cannot expire `T_AUTH` or `T_RESOLVE`. The poll is what
keeps reservoir, battery, `deliveryActive`, and `storeInstanceId` from being
that snapshot forever. Authentication dispatches `AuthSucceeded` after its
transaction closes; the status read is not inside it.

A tick that finds an L3 transaction already in flight is dropped, not queued.
That is the "skip during delivery" rule, and it is a property of the
transaction queue rather than a special case: `BOLUS_PROGRESS_IND` already
reports progress while a bolus is running, and a poll piled up behind one
would fire a burst of stale reads. Ticks are also skipped while the adapter
is off. Each poll uses a single send attempt, so a failed tick is bounded by
`T_RESP`.

A failed poll marks vitals stale immediately. Dosing stays allowed: the pump
is still the enforcement point (H-13). Three consecutive failures dispatch
`Disconnected(TransientLink)`, which is the same path the adapter-off
receiver already uses, and leave `Ready` for `Recovering`. A later success
clears the failure count and the stale flag. Worst-case detection is roughly
21 s (three 5 s gaps and three 2 s timeouts).

## Error classes

Retry policy is defined here, once, over abstract classes. Platform error codes
are mapped into these classes at the platform boundary, never handled
individually in the state machine. This is the mechanism that keeps Android and
iOS retry behavior identical despite completely different error surfaces — see
[05-parity-contract.md](05-parity-contract.md).

| Class | Meaning | Policy |
| --- | --- | --- |
| `TransientLink` | Supervision timeout, out of range, failed establishment | Backoff and retry; do not surface until attempts are exhausted |
| `PeerInitiated` | Pump deliberately closed the connection | Longer backoff; expected during a patch change; do not surface as an error |
| `StackFault` | Host stack failure, resource exhaustion, unexplained | Close and release the GATT client before retrying; harder backoff; invalidate cached services after repeated occurrences |
| `CacheStale` | Discovered services disagree with the advertised protocol version, or `ServiceChanged` was indicated | Rediscover; do not count against the retry budget |
| `AuthFailure` | Bond lost, or session MAC verification failed | Do not retry. `Unpaired`, and surface to the user |
| `ProtocolFault` | CRC failure, reassembly failure, sequence outside window | Reset the session; keep the link; do not reconnect |
| `Unrecoverable` | Retry budget exhausted | `Failed`, surface to the user |

Backoff is 1 s, 2 s, 4 s, 8 s, 16 s, capped at 30 s, with ±20 % jitter, reset on
reaching `Ready`. Maximum 8 attempts before `Failed`.

`ProtocolFault` deliberately does **not** reconnect. A CRC or sequence failure
means the two sides disagree about session state, and tearing down a healthy
radio link to fix a software disagreement is both slower and less likely to work
than resetting the session over the link that is already up.

`Recovering`, `Failed`, `Unpaired`, and `StopRequested` emit `ResetSession`.
`Seq` starts at 0 on the next establishment, and the inbound window on both
sides must start there too. A reconnect that leaves `lastAccepted` in place
drops the peer's `Seq` 0 as a replay. A new `AUTH_CHALLENGE_REQ` is what
resets the pump; the controller resets when the radio is released and again
at the start of `authenticateAsController`.

## Transport watchdog

The link machine's timeouts (`CONNECT_MS` 10 s, `T_AUTH_MS` 5 s) are the
right size for a state, and the wrong size for a single GATT call. Android
permits one outstanding GATT operation. If that call is initiated and the
callback never arrives — a missing `onDescriptorWrite` is the case that
found this — a queue that can only be released by the callback is held
forever. Later `connectGatt` calls enqueue and never start. The UI sits
on Connecting against a radio that was never asked.

The platform transport therefore carries its own per-op watchdog, 3 s,
beneath the state timeouts. The gate is released by exactly one of: the
matching callback, a failed initiation (`writeDescriptor` returning
`false`), a thrown start, the watchdog, or a reset on `close`. Never by
nothing. A stall emits `Disconnected`, which every leaf state already
routes into `Recovering`, rather than `DiscoveryFailed`, which only
`Discovering` acts on.

A peripheral that accepts an operation and never calls back must not be
able to stall the queue. The state machine still owns the retry budget;
the transport only guarantees that a dead call cannot prevent the next
one.

The next GATT call is posted off the binder callback. Issuing
`writeDescriptor` synchronously from `onMtuChanged` is a known Samsung
failure: the CCCD write is accepted (`initiated=true`) and
`onDescriptorWrite` never arrives, so Configuring sits on "Negotiating
MTU" until the 5 s state timeout. The watchdog is armed before `start`
returns for the same reason — a callback that beats the return used to
cancel the next op's watchdog.

## What this state machine is not

It is not `UiState`, and the two are not merged.

`UiState` is a projection of this state plus the journal, the pump's last known
status, and whatever the user is currently typing. It has states this machine
does not, such as "user is entering a dose," and it collapses states this
machine distinguishes — for the purpose of deciding whether a dose may be sent,
`Connecting`, `Bonding`, `Discovering`, and `Configuring` are one value.

Conflating them is the standard BLE application mistake. It produces a UI state
enum that grows a case every time the transport learns a new failure mode, and a
transport that cannot be tested without a UI. The projection is a pure function
in `:presentation`, tested by feeding it link states and asserting the rendered
result; see [07-architecture.md](07-architecture.md).

### The collapse is a dosing rule, not a rendering rule

An earlier revision of this document said those four substates were "all one
spinner." That overstated it, and the overstatement cost real debugging time: a
bond waiting on an unanswered system pairing dialog and a stalled MTU
negotiation both presented as an undifferentiated `Linking`, with nothing on
screen to tell them apart.

The rule that matters is narrower. **`LinkStatus`, the value dosing consults,
collapses the substates and must keep collapsing them** — the predicate for
"may I send a delivery command" does not acquire a case when the transport
learns a new failure mode. Nothing about that requires the substates to be
invisible.

A diagnostic surface may therefore render `LinkStep` — the eight substates
above, in order, with the outstanding one named alongside its timeout from the
table in this document. Two constraints keep it honest:

- It is derived by a second, independent mapping (`LinkStatusMapper.progress`)
  from the same `LinkState`. It is not derived from `LinkStatus`, and
  `LinkStatus` is not derived from it.
- It cannot enable dosing. `BolusUiState` still sees one `DosingDisabled(link)`
  case, so no amount of detail on the link screen can widen the set of states a
  bolus is reachable from.

`LinkStep` and `LinkFault` live in `:domain` as protocol-free mirrors, because
`:presentation` may not import `:protocol` and the build enforces it.
