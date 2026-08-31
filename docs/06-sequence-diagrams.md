# 06 — Sequence Diagrams

Call flow between the app and the pump. Participants are grouped by process
boundary: everything in the blue box runs on the phone, everything in the red box
runs on the pump, and every arrow that crosses between them is a BLE operation
that can fail.

Steps are numbered so the [hazard analysis](04-hazard-analysis.md) and the test
suite can cite them.

## 1. Cold start to `Ready`

```mermaid
sequenceDiagram
    autonumber
    box rgb(219,234,254) Phone
        participant LM as Link Manager
        participant SES as Session L4
        participant RC as Reconciler
    end
    box rgb(254,226,226) Pump
        participant GS as GATT Server
        participant FW as Pump Core
    end

    Note over LM: state = Scanning
    GS-)LM: advertisement + scan response
    Note right of LM: matched on logical device id from<br/>manufacturer data, not on MAC or<br/>CBPeripheral handle

    LM->>GS: connect
    GS-->>LM: connected
    LM->>GS: bond, or confirm existing bond
    GS-->>LM: bonded
    LM->>GS: discover services
    GS-->>LM: PumpLink service, 3 characteristics
    LM->>GS: negotiate MTU
    GS-->>LM: ATT_MTU = 185
    LM->>GS: enable notifications on RSP
    GS-->>LM: CCCD confirmed
    Note over LM: Subscribed. Transport usable,<br/>no session yet.

    SES->>+GS: AUTH_CHALLENGE_REQ, controllerId, nonceC
    GS-->>-SES: AUTH_CHALLENGE_RSP, pumpId, nonceP
    Note over SES,FW: both derive K_s = HKDF over K_p<br/>salted with nonceC and nonceP
    SES->>+GS: AUTH_VERIFY_REQ, mac
    GS->>+FW: verify controller
    FW-->>-GS: verified
    GS-->>-SES: AUTH_VERIFY_RSP, mac
    Note over LM: Authenticating to Reconciling

    RC->>RC: read journal, no in-flight entries
    RC->>LM: ReconcileDone, unresolved = 0
    Note over LM: Ready. Dosing enabled.
```

Steps 15 through 21 cannot be skipped, and steps 22 through 24 cannot be
skipped, because `Ready` is only reachable through them. That is the structural
enforcement described in
[03-connection-state-machine.md](03-connection-state-machine.md#reconciling-is-the-point-of-the-diagram).

## 2. Bolus, happy path

```mermaid
sequenceDiagram
    autonumber
    box rgb(219,234,254) Phone
        participant UI as Bolus Screen
        participant UC as DeliverBolusUseCase
        participant JN as Command Journal
        participant TX as Transport L1-L3
    end
    box rgb(254,226,226) Pump
        participant GS as GATT Server
        participant FW as Pump Core
    end

    UI->>+UC: ConfirmBolus 4.5 U
    UC->>+JN: allocate CommandId
    JN-->>-UC: 0x1A7F
    UC->>+JN: write PENDING, flush
    JN-->>-UC: committed
    Note right of JN: durable before any radio traffic

    UC->>JN: mark IN_FLIGHT
    UC->>+TX: BOLUS_REQ cmd=0x1A7F, 4500 mU
    TX->>+GS: write CMD, 1 fragment
    GS-->>TX: ATT write response
    Note over TX: transport acknowledgement only.<br/>Not evidence of delivery.

    GS->>+FW: BOLUS_REQ cmd=0x1A7F
    FW->>FW: no existing record for 0x1A7F
    FW->>FW: validate against pump limits
    FW->>FW: commit record ACCEPTED
    FW->>FW: begin actuation, record IN_PROGRESS
    FW-->>GS: record snapshot
    GS--)TX: notify RSP, BOLUS_RSP IN_PROGRESS
    TX-->>-UC: IN_PROGRESS, 0 of 4500 mU

    UC-->>UI: state = Delivering

    loop until complete
        FW--)TX: BOLUS_PROGRESS_IND
        TX-->>UC: delivered so far
        UC-->>UI: state = Delivering, n of 4500 mU
    end

    FW->>FW: record COMPLETED, 4500 mU
    deactivate FW
    FW--)TX: BOLUS_RSP COMPLETED
    deactivate GS
    TX-->>UC: COMPLETED, 4500 mU
    UC->>JN: mark CONFIRMED_COMPLETED
    UC-->>-UI: state = Delivered, 4.5 U
```

The two annotations carry the argument. Step 12 is the ATT write response, and it
is the thing a naive implementation treats as success. Step 21 is a pump-confirmed
record, and it is the only thing the app is permitted to render as delivery.
(REQ-S-05, invariant I-4)

## 3. Ambiguous outcome and recovery

The diagram this whole repository exists for.

```mermaid
sequenceDiagram
    autonumber
    box rgb(219,234,254) Phone
        participant UI as Bolus Screen
        participant UC as DeliverBolusUseCase
        participant JN as Command Journal
        participant TX as Transport L1-L3
    end
    box rgb(254,226,226) Pump
        participant GS as GATT Server
        participant FW as Pump Core
    end

    UI->>+UC: ConfirmBolus 4.5 U
    UC->>JN: write PENDING then IN_FLIGHT, cmd=0x1A7F
    UC->>+TX: BOLUS_REQ cmd=0x1A7F
    TX->>+GS: write CMD
    GS->>+FW: BOLUS_REQ
    FW->>FW: commit record ACCEPTED
    FW->>FW: begin actuation

    FW--xTX: BOLUS_RSP lost, link dropped
    deactivate FW
    deactivate GS
    TX-->>-UC: Disconnected, TransientLink

    Note over UI,FW: The app cannot distinguish<br/>"never arrived" from "delivering now".<br/>Both look identical from here.

    rect rgb(254,243,199)
        Note over UC: Retry budget is exhausted.<br/>This is NOT a failure. It is an unknown.
        UC->>JN: entry stays IN_FLIGHT
        UC-->>UI: state = Resolving, dosing blocked
    end

    Note over TX,GS: reconnect, re-authenticate, fresh K_s

    critical resolve every in-flight entry before Ready
        UC->>+TX: QUERY_COMMAND_OUTCOME cmd=0x1A7F
        TX->>+GS: write CMD
        GS->>+FW: look up delivery record
        FW-->>-GS: IN_PROGRESS, 1200 of 4500 mU,<br/>oldestRetained = 0x1900
        GS-->>-TX: QUERY_COMMAND_OUTCOME_RSP
        TX-->>-UC: IN_PROGRESS, 1200 mU
        UC-->>UI: state = Delivering, 1.2 of 4.5 U
        Note right of UC: resume monitoring.<br/>No command reissued.
    option outcome = NEVER_SEEN
        Note right of UC: cmd 0x1A7F is newer than oldestRetained,<br/>so absence proves zero actuation
        UC->>TX: reissue BOLUS_REQ, same cmd=0x1A7F
    option outcome = EVICTED
        Note right of UC: cmd is older than oldestRetained.<br/>Absence proves nothing.
        UC->>JN: mark INDETERMINATE
        UC-->>UI: state = Indeterminate, verify at pump
    option T_RESOLVE expires
        UC->>JN: mark UNREACHABLE
        UC-->>UI: state = Blocked, retry next session
    end
```

Three details worth stopping on.

**Step 11 is the correct interpretation of an exhausted retry budget.** Retries
ran out. The command did not fail — its outcome is unknown, and unknown is a
third state that most implementations do not have. Collapsing it into failure is
what causes under-dosing; collapsing it into success causes over-dosing.

**The `critical` block is not decoration.** It is a UML critical region because
reconciliation must complete before any other operation on this session, and the
`option` branches are its named failure handlers. The state machine makes this
structural: none of the four branches can be preceded by a bolus, because `Ready`
is downstream of all of them.

**The `NEVER_SEEN` branch reissues the same `CommandId`.** Allocating a fresh one
would be a new dose by definition and would defeat the pump's duplicate
suppression entirely. (REQ-S-08)

## 4. Duplicate suppression

What happens when the pump *did* receive the original and the controller retries
anyway. This is the mirror of diagram 3 and the reason reissue is safe.

```mermaid
sequenceDiagram
    autonumber
    box rgb(219,234,254) Phone
        participant TX as Transport L1-L3
    end
    box rgb(254,226,226) Pump
        participant GS as GATT Server
        participant FW as Pump Core
    end

    TX->>+GS: BOLUS_REQ cmd=0x1A7F, seq=12
    GS->>+FW: dispatch
    FW->>FW: no record, commit ACCEPTED, actuate
    FW-->>-GS: record
    GS--xTX: BOLUS_RSP lost
    deactivate GS

    Note over TX: T_RESP expires

    TX->>+GS: retransmit byte-identical PDU<br/>cmd=0x1A7F, seq=12, same MAC
    Note right of GS: seq equals lastAccepted and<br/>content is identical, so L2 replays<br/>the cached response and does not<br/>dispatch to L3
    GS-->>-TX: BOLUS_RSP from cache

    Note over TX,FW: Suppressed twice, independently:<br/>L2 by sequence number,<br/>L3 by delivery record on CommandId

    TX->>+GS: later, after reconnect and new session<br/>BOLUS_REQ cmd=0x1A7F, seq=0
    Note right of GS: new session, so seq window<br/>gives no protection here
    GS->>+FW: dispatch
    FW->>FW: record for 0x1A7F exists
    FW-->>-GS: existing record, no actuation
    GS-->>-TX: BOLUS_RSP COMPLETED 4500 mU
```

The two suppression mechanisms cover different failures and neither is
redundant. Sequence-number suppression handles retransmission inside one
session and is content-addressed. Delivery-record suppression handles
reissue across sessions, where the sequence counter has been reset and offers
nothing. A design with only the first double-doses on every reconnect.

## 5. Fragmentation at the minimum MTU

Included because "we handle fragmentation" is the kind of claim that is usually
untested at the boundary.

```mermaid
sequenceDiagram
    autonumber
    participant TX as Transport L1
    participant GS as GATT Server L1

    Note over TX,GS: ATT_MTU = 23, so 19 octets of<br/>fragment payload per write

    Note over TX: BOLUS_REQ PDU is 22 octets:<br/>8 header + 4 payload + 8 MAC + 2 CRC

    TX->>GS: write CMD, hdr 0x80 FIRST idx 0, 19 octets
    Note right of GS: FIRST resets the reassembly buffer
    TX->>GS: write CMD, hdr 0x41 LAST idx 1, 3 octets
    Note right of GS: LAST completes, 22 octets to L2
    GS-->>TX: BOLUS_RSP, fragmented the same way
```

The reassembly buffer is discarded on disconnection, on a `FIRST` arriving
mid-message, on an index discontinuity, and on exceeding 512 octets. Each of
those is a scenario-table row with a JVM test, because a reassembly buffer that
survives a disconnection is how two unrelated messages get spliced into one
valid-looking PDU.
