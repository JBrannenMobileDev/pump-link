# 04 — Hazard Analysis

## What this is, and what it is not

This is a hazard analysis **for one feature**, structured the way ISO 14971
structures one, so that the design decisions in the other documents can be
traced to the harm they exist to prevent. It is not a risk management file. It
has no risk management plan, no production and post-production information, no
benefit-risk determination, and no review or approval.

**No probability estimates appear here.** Probability of occurrence depends on
clinical context, use environment, hardware reliability, and field data, none of
which this project has. Severity is stated because it follows from the
physiology and is knowable. Inventing probabilities to fill a matrix column
would make the document look more complete and be less true, so the column is
absent and its absence is deliberate.

Severity uses a qualitative scale: **Negligible, Minor, Serious, Critical,
Catastrophic.**

## Scope of harm

Two harms dominate, and they pull in opposite directions, which is what makes
the design non-trivial.

- **Over-delivery** causes hypoglycemia. Onset is fast, it can progress to
  seizure, coma, or death, and the patient may be asleep or otherwise unable to
  self-treat. Severity ranges to **Catastrophic**.
- **Under-delivery** causes hyperglycemia and, sustained, diabetic
  ketoacidosis. Onset is slower and there is usually opportunity to intervene,
  but it is not benign. Severity ranges to **Critical**.

The asymmetry matters for design. A control that reduces over-delivery risk by
increasing under-delivery risk is not automatically a good trade, but where the
two cannot both be minimized, this design prefers the slower-onset harm and
makes the situation visible to the user rather than silently choosing for them.
That preference is why the indeterminate state exists and why it blocks dosing
instead of guessing.

## Hazard table

| ID | Hazardous situation and harm | Severity | Cause | Control | Verification |
| --- | --- | --- | --- | --- | --- |
| H-01 | A delivered bolus is delivered a second time. Hypoglycemia. | Catastrophic | Response lost after the pump accepted the command; controller retries as if it had never been sent | Client-generated `CommandId`; pump commits the delivery record before actuating; a repeat `CommandId` is answered from the record and never actuates (REQ-S-01, 03, 04, 08; invariants I-1, I-2) | `DropResponseAfterAcceptTest` asserts total actuated equals requested across an arbitrary number of retries |
| H-02 | An ambiguous outcome is reported to the user as failure. The user doses again by another route. Hypoglycemia. | Catastrophic | Retry budget exhaustion treated as a negative result | Exhausted retries produce an unknown state, never a failure state; dosing is blocked until reconciled (REQ-S-07) | `AmbiguousOutcomeIsNotFailureTest` asserts the terminal state after retry exhaustion is `Resolving`, never `NotDelivered` |
| H-03 | A bolus that never reached the pump is shown as delivered. The user omits a needed dose. Hyperglycemia, DKA. | Critical | Rendering delivery from the GATT write callback | Delivery is rendered only from a pump-confirmed record (REQ-S-05, invariant I-4) | `WriteCallbackIsNotDeliveryTest` asserts no UI state reaches `Delivered` on a write acknowledgement alone |
| H-04 | An old command whose record has been evicted is treated as never delivered and reissued. Hypoglycemia. | Catastrophic | Finite record store wraps; absence of a record stops being evidence | `oldestRetainedCommandId` is returned with every outcome; absence resolves to `NEVER_SEEN` only inside the retention window, otherwise `EVICTED` and indeterminate. Automatic re-reconciliation does not clear that row. | `EvictedIsNotNeverSeenTest` asserts an out-of-window `CommandId` never produces a reissue; SC-24 keeps `Suspended` until acknowledgement |
| H-05 | The app addresses a pump whose service layout has changed. A command is misinterpreted. | Critical | Android caches discovered services aggressively and can serve a stale layout after a firmware change | Protocol version in every PDU and in `STATUS`; version mismatch is rejected with `NAK(UNSUPPORTED_VERSION)`; `CacheStale` error class forces rediscovery on `ServiceChanged` | `VersionMismatchRejectedTest`; `StaleServiceCacheTest` in the scenario table |
| H-06 | A corrupted dose value is actuated. | Catastrophic | Bit errors on the link, or tampering | CRC-16 over the whole PDU; session MAC over header and payload; independent pump-side range and limit validation that does not trust the requested value | `CorruptCrcTest`, `TamperedMacTest`, and pump limit tests |
| H-07 | Insulin is delivered automatically after the user has disengaged. Hypoglycemia. | Serious | Automatic reissue of a `NEVER_SEEN` command long after the user confirmed | Reissue requires all of: under 60 s elapsed, screen in foreground, no intervening user action, first reissue for this `CommandId` | `ReissueGuardTest` covers each of the four conditions failing independently |
| H-08 | A captured command is replayed by an attacker. Unintended delivery. | Catastrophic | No replay protection on the air interface | Session key derived from fresh nonces on both sides; MAC covers the sequence number; forward-only sequence window | `ReplayAcrossSessionsRejectedTest`, `ReplayWithinSessionRejectedTest` |
| H-09 | Two partial messages are spliced into one valid-looking PDU. An unintended command executes. | Catastrophic | Reassembly buffer surviving a disconnection or a lost fragment | Buffer discarded on disconnect, on `FIRST` arriving mid-message, on index discontinuity, and above 512 octets; CRC and MAC over the reassembled PDU | `FragmentSplicingTest`, `ReassemblyDiscardOnDisconnectTest` |
| H-10 | A duplicate is accepted, or a valid command is rejected, around sequence wrap. | Serious | Naive sequence comparison at the 8-bit boundary | Forward acceptance window of `1..127` modulo 256 | `SequenceWrapTest` sweeps every value across the wrap |
| H-11 | The response to the first command of a session is missed. Unnecessary reconciliation, and divergent behavior between platforms. | Minor | On Android, writing before the CCCD write completes; on iOS the timing differs | `Configuring` requires `CccdConfirmed` before `Subscribed`; no L3 traffic is permitted before `Subscribed` | `NoTrafficBeforeSubscribedTest`; parity scenario row |
| H-12 | The phone loses the `CommandId` for a command that may be in progress. Permanently unresolvable. | Critical | Process death after transmitting but before journaling | Journal is committed and flushed before transmission; a foreground service of type `connectedDevice` runs for the duration of an in-flight command (REQ-S-02) | `JournalPrecedesTransmitTest`; process-death instrumented case |
| H-13 | The app permits a dose the pump would refuse, or refuses one the pump would permit. | Serious | Safety limits duplicated in two places and drifting apart | The pump is the sole enforcement point. App-side limits are advisory, exist only for early feedback, and never gate what the pump will accept | `AppLimitsAreAdvisoryTest` asserts the pump independently rejects an over-limit request that bypasses the app check |
| H-14 | The user acts on pump state that changed while the app was absent. | Serious | The device can be serviced with no phone present ([00-overview.md](00-overview.md#a-requirement-worth-deriving-explicitly)) | `recordEpoch` in `STATUS` detects out-of-band change cheaply; reconciliation and history sync complete before `Ready` | `EpochChangeForcesHistorySyncTest` |
| H-15 | A command outstanding across a record-store reset is treated as never delivered and reissued. Hypoglycemia. | Catastrophic | Factory reset, storage migration, or corruption recovery empties the store and restarts `oldestRetainedCommandId` low, so the retention-window test reports every outstanding command as in-window | `storeInstanceId` is recorded on the journal entry at send time and re-checked at query time; a mismatch yields `STORE_REPLACED` and is indeterminate, never a reissue. Automatic re-reconciliation does not clear that row. | `StoreResetIsNotNeverSeenTest` asserts a command outstanding across a store reset never produces a reissue; SC-24 keeps `Suspended` until acknowledgement |
| H-16 | A late or duplicate response is attributed to a different command. A delivery that did not happen is journaled, or the real outcome is discarded. | Catastrophic | Controller matches inbound PDUs on opcode alone | A response binds to its request by `AckSeq` and, where non-zero, `CommandId`. Unmatched PDUs are discarded. The inbound sequence window drops duplicates in both directions. | `ResponseBindingTest`; `ReplayWithinSessionRejectedTest`; SC-11 asserts a duplicated `BOLUS_RSP` cannot answer the next command |
| H-17 | Vitals from link establishment are presented as current. The operator doses against a reservoir or battery that has since changed. | Serious | `GET_STATUS` runs once after authentication and never again. Distinct from H-14, which is change *while the app was absent*. | A 5 s `GET_STATUS` poll while `Ready`; vitals go stale on the first failed poll; three consecutive failures are `Disconnected(TransientLink)` | `ResponseBindingTest` covers the request/response pairing the poll uses; the poll policy is specified in [03-connection-state-machine.md](03-connection-state-machine.md#ready-state-status-poll) |

## The three worth expanding

### H-01, H-04, and H-15 are the same hazard with different causes

All three end in a repeated dose. H-01 is defended by the delivery record. H-04
and H-15 are the observation that the record store has properties of its own —
it is finite, and it can be destroyed — and that each of those is a failure mode
of the defense rather than a detail beneath it.

This is the failure mode most likely to survive design review, because the
control for H-01 is obviously correct and the reviewer stops there. "We key on a
command ID and dedupe" is true and sufficient right up to the moment the store
wraps, at which point the system confidently reissues a dose that already
happened — and it does so using the exact mechanism that was supposed to prevent
that.

The control is not a bigger buffer. A bigger buffer moves the boundary without
removing it. The control is for the pump to **report where the boundary is**, so
that the controller can tell the difference between "I know this did not happen"
and "I no longer know." Those are different answers and a protocol that returns
one value for both has thrown away the distinction that the safety argument
depends on.

H-15 is the same argument applied to the store's existence rather than its size,
and it is the easier one to miss. Eviction is visible in the data structure and
invites the question. A reset is visible only as an absence, and the watermark
that defends against eviction moves *backwards* when it happens — so the
retention test does not merely fail to help, it actively returns the dangerous
answer. Reporting where the boundary is only works if the controller can also
tell that it is still looking at the same boundary, which is what
`storeInstanceId` is for.

Automatic re-reconciliation from `Suspended` re-queries commands that are
still `Pending` or `InFlight`. It is explicitly barred from clearing an
`Indeterminate` row. `EVICTED` and `STORE_REPLACED` mean the pump has
destroyed the evidence; treating a later empty query as `NEVER_SEEN` would
be H-04 or H-15 at catastrophic severity. Only a human acknowledgement
journals `Acknowledged`. This falls out of the reconcile count: `BeginReconcile`
queries `inFlight()` and recounts `hasIndeterminate()` afterward, so an
`Indeterminate` entry keeps `unresolvedCount > 0` and the link stays
`Suspended` until that acknowledgement.

### H-02 is the hazard that naive designs create while fixing H-01

Once a team understands H-01, the reflex is to make retries conservative: on
doubt, report failure and do not resend. That converts a catastrophic
over-delivery into a catastrophic under-delivery, because the user reads
"failed," doses again by pen or by a second attempt, and now two doses are in the
body — one of which the app never knew about.

There is no safe way to answer an ambiguous outcome with a definite one. The only
correct response is to widen the state space: three outcomes, not two, with the
third blocking further action until it is resolved against the pump. This is why
[01-feature-flow.md](01-feature-flow.md#terminal-states) has five terminal states
and two of them block dosing.

### H-13 is about authority, not validation

It is tempting to put the safety limits in the app, where the error message can
be friendly and immediate. Doing so creates two authorities for one limit, and
they will drift — through a phased app rollout, a firmware update, a
configuration change that reaches one and not the other.

The pump is the enforcement point because the pump is the thing that actuates.
App-side limits exist purely so the user learns about a problem before
committing, and they are deliberately allowed to be more conservative than the
pump's and never less. The test asserts the pump refuses an over-limit request
that bypassed the app entirely, which is the only way to know the app's check is
not load-bearing.

## Traceability

| Hazard | Requirement | Invariant | Enforced in |
| --- | --- | --- | --- |
| H-01 | REQ-S-01, 03, 04, 08 | I-1, I-2 | `:simulator` record store; `:protocol` retry |
| H-02 | REQ-S-07 | — | `:domain` outcome mapping; `:protocol` state machine |
| H-03 | REQ-S-05 | I-4 | `:domain` projection to `UiState` |
| H-04 | REQ-S-06 | I-3 | `:protocol` outcome codec; `:domain` reconciler |
| H-05 | — | — | `:protocol` version check; `:data` error classification |
| H-06 | — | — | `:protocol` CRC and MAC; `:simulator` limit validation |
| H-07 | REQ-S-08 | — | `:domain` reissue guard |
| H-08 | — | — | `:protocol` session layer |
| H-09 | REQ-S-09 | — | `:protocol` reassembly |
| H-10 | — | — | `:protocol` sequence window |
| H-11 | REQ-S-10 | — | `:protocol` state machine; `:data` platform adapter |
| H-12 | REQ-S-02 | — | `:data` journal; foreground service |
| H-13 | — | — | `:simulator` limit validation |
| H-14 | REQ-S-06 | I-5 | `:domain` reconciler; `:protocol` state machine |
| H-15 | REQ-S-06 | I-3 | `:protocol` outcome codec; `:domain` reconciler |
| H-16 | — | — | `:protocol` session layer |
| H-17 | — | — | `:data` Ready-state poll; `:presentation` vitals projection |

Every control in the right-hand column lands in `:protocol`, `:domain`, or
`:simulator` — all three pure Kotlin. That is not a coincidence and it is the
practical argument for the module boundaries in
[07-architecture.md](07-architecture.md): the layers that carry the safety
argument are the layers that can be exhaustively tested without a device.

## Residual risks accepted

Stated rather than omitted, because an analysis with no residual risk is an
analysis that was not performed.

- **H-06.** CRC-16 has finite error-detection capability and will pass some
  multi-bit error patterns. The MAC is the stronger control; the CRC exists to
  fail cheaply and early. Accepted.
- **H-07.** The bounded reissue window can still deliver to a user who set the
  phone down inside 60 seconds with the screen foregrounded. Accepted in
  preference to prompt fatigue, with the reasoning in
  [01-feature-flow.md](01-feature-flow.md#the-reissue-guard).
- **H-08.** The MAC is truncated to 8 octets. Accepted for this reference design
  only, and flagged in [02-protocol.md](02-protocol.md#l4--session) as requiring
  quantitative justification or rejection in a real one.
- **H-12.** A journal write that is acknowledged by the filesystem but lost to a
  power failure before reaching stable storage would defeat the control. Not
  mitigated here.
- **Indeterminate state.** By design, resolution requires the user to read the
  pump's own history. This depends on the user doing so correctly, which is a
  use-related risk that belongs in an IEC 62366-1 usability analysis and is out
  of scope.

## What a real risk file would add

Named so the boundary of this document is unambiguous: a risk management plan
and policy; probability estimation with a documented basis; a risk acceptability
matrix; risk control option analysis under 14971 §7.1; verification of
effectiveness for each control as distinct from verification of implementation;
evaluation of overall residual risk; a production and post-production
information plan; software safety classification and the corresponding IEC 62304
process rigor; a security risk assessment under AAMI TIR57 cross-referenced to
this file; and use-related risk analysis under IEC 62366-1.
