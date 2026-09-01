# 00 — Overview, Scope, and Sources

## What this project is

`pump-link` is a reference design for **delivering a non-idempotent command to a
medical device over an unreliable wireless link**, worked end to end: a written
design, a protocol specification, a hazard analysis, a cross-platform behavioral
contract, and a working two-device implementation that demonstrates the failure
modes rather than asserting they are handled.

The concrete feature is a user-initiated insulin bolus, requested from a phone
and executed by a patch pump over Bluetooth Low Energy.

## Why this feature

A bolus is the smallest feature in an insulin delivery system that still
contains the hard problem. It is short, it is user-initiated, and it has no
algorithmic complexity — but it is **not idempotent**, and the transport
underneath it fails in ways that are **ambiguous**. Those two properties
together are what make the design interesting, and they are properties of the
system, not of any particular vendor's implementation.

Everything harder in such a system — closed-loop dosing, history sync, firmware
update — inherits this problem. Nothing is simpler than it. So it is the right
unit to specify completely.

## Scope

**In scope**

- Connection lifecycle: scan, connect, bond, discover, subscribe, authenticate,
  reconcile, ready
- A session layer over GATT: framing, fragmentation against a negotiated MTU,
  sequence numbers, integrity check, acknowledgement, retry
- Session establishment with mutual challenge/response
- One command: request a bolus, observe it to completion or to a known failure
- Recovery from an interrupted command, including the ambiguous case
- Behavioral parity between Android and iOS, specified and enforced
- A pump simulator with deliberate, switchable fault injection

**Out of scope**

- Any closed-loop or automated dosing algorithm
- CGM integration
- Firmware update over the air
- The full pairing and onboarding user experience
- Multiple concurrent pumps
- Production-grade cryptography (see [Security posture](#security-posture))

## This is not a medical device

Nothing here is clinically validated, regulatory-cleared, or fit for use with a
real person or a real pump. No insulin moves. The simulator's dose values are
numbers in a data structure. Standards are referenced to show where the
corresponding real work would live, not to claim it has been done.

## Sources

Every design decision here derives from public material:

| Source | Used for |
| --- | --- |
| Bluetooth Core Specification | GATT/ATT semantics, MTU exchange, bonding, connection parameters, Service Changed |
| [Android BLE documentation](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview) | `BluetoothGatt`, `BluetoothGattServer`, permission model, foreground service types |
| [Apple Core Bluetooth documentation](https://developer.apple.com/documentation/corebluetooth) | `CBCentralManager`, `CBPeripheralManager`, state preservation and restoration |
| [Android app architecture guidance](https://developer.android.com/topic/architecture) | UI layer architecture, unidirectional data flow, events-as-state |
| ISO 14971 | Structure and vocabulary of the hazard analysis |
| IEC 62304 | Software lifecycle framing, software item decomposition |
| AAMI TIR57 | Where security risk management attaches to safety risk management |
| FDA guidance on premarket submissions for device software functions, and on cybersecurity in medical devices | What a design history file is expected to contain |
| Public company announcements and press releases | Problem context only — see below |

**No proprietary material from any prior employer appears in this repository.**
No commercial pump protocol is reproduced, paraphrased, or generalized here. The
frame layout, opcode set, session establishment, and state machine in these
documents were designed for this project from the Bluetooth specification and
first principles, and they are deliberately shaped by what a *simulator* needs
in order to be interesting to test against.

## A requirement worth deriving explicitly

Beta Bionics' [May 2026 announcement](https://www.globenewswire.com/news-release/2026/05/21/3299712/0/en/Beta-Bionics-Updates-Commercialization-Timeline-Expectations-for-Mint-its-Patch-Pump-in-Development.html)
for its Mint patch pump lists, among the expected features, that the device is
"iOS and Android smartphone controlled" and that there is "**no phone required
during the Mint disposable patch change process**."

That second item is a design constraint, not a convenience feature. If the
device can be serviced without the phone present, then the pump's state
legitimately advances while the app is not observing it. The app is therefore
structurally incapable of being the source of truth for delivery state, and any
design that treats the app's cache as authoritative is wrong on the first patch
change rather than on some rare error path.

This is the same conclusion the ambiguous-response problem reaches from a
different direction, which is a good sign that the conclusion is load-bearing:

> The pump owns the delivery record. The app is a cache that must reconcile
> before it is allowed to act.

## System-level requirements

Referenced by ID from [04-hazard-analysis.md](04-hazard-analysis.md) and from
the test suite.

| ID | Requirement |
| --- | --- |
| REQ-S-01 | Every delivery command shall carry a client-generated command identifier that is unique for the lifetime of the pairing. |
| REQ-S-02 | The command identifier shall be committed to non-volatile storage on the phone before the command is transmitted. |
| REQ-S-03 | The pump shall commit a delivery record for a command before beginning delivery for that command. |
| REQ-S-04 | A command identifier already present in the pump's delivery record shall be answered from that record and shall not initiate a second delivery. |
| REQ-S-05 | The app shall not present a delivery as having occurred on the basis of a transport-level write acknowledgement. Only a pump-confirmed delivery record shall justify that presentation. |
| REQ-S-06 | On establishing a session, the app shall resolve the outcome of every locally journaled in-flight command before issuing any new delivery command. |
| REQ-S-07 | Where the outcome of a delivery command cannot be determined, the app shall present an explicit indeterminate state and shall block further dosing until it is resolved. |
| REQ-S-08 | Retry of a delivery command shall reuse the original command identifier. A new identifier constitutes a new dose. |
| REQ-S-09 | The framing layer shall operate correctly at any MTU permitted by the Bluetooth specification, without prior knowledge of the negotiated value. |
| REQ-S-10 | Android and iOS shall exhibit identical observable behavior for every scenario in the shared scenario table, notwithstanding differing platform mechanisms. |

## Security posture

The session layer specified in [02-protocol.md](02-protocol.md) demonstrates
*where* authentication, replay resistance, and message integrity attach to a
device protocol, and the simulator exercises those paths. It has not been
cryptographically reviewed and is not proposed as a production scheme. In a real
program this is where a security risk assessment under AAMI TIR57, threat
modeling, and independent review would attach, traced to the safety risk file
rather than kept beside it.

## Verification boundary

Stated as a boundary rather than left implicit, because an unqualified claim of
"works" is the less honest option.

- **BLE, on hardware.** Controller verified against a real radio on a
  Samsung Galaxy A32 5G running **Android 13 (API 33)** — that device's last OS
  version. The pump peripheral is hosted on macOS via CoreBluetooth; the
  Android device acts only as BLE central. See
  [05-parity-contract.md](05-parity-contract.md#harness-deviations).
- **Foreground-service typing is declared, not exercised.** The app declares
  `foregroundServiceType="connectedDevice"` and
  `FOREGROUND_SERVICE_CONNECTED_DEVICE`, which Android 14 made mandatory. The
  hardware under test is API 33, so that enforcement path is not observed. The
  same "spec, not pass" honesty already applied to the iOS column applies
  here.
- **Not verified on API 34+ as a runtime.** `targetSdk` is 37 because AndroidX
  1.19 requires `compileSdk` 37; lowering `targetSdk` to the tested OS would
  change nothing on API 33 (behavior changes gate on the lower of `targetSdk`
  and the running OS) and would cost currency. The claim that was wrong was
  "verified on Android 14," not the number in the catalog.
- **Not verified on iOS as a controller.** The iOS *central* column of
  [05-parity-contract.md](05-parity-contract.md) is derived from Apple's
  published documentation and is a specification of required behavior, not a
  record of observed behavior. The macOS peripheral *does* exercise several
  CoreBluetooth paths that the table previously marked `spec` — those rows
  are labeled as such.
- Emulators are not used for BLE. The Android emulator has no usable radio.
- BLE peripheral capability is a property of the chipset and OEM firmware, not
  of the OS version. It is irrelevant to this build: nothing on Android hosts
  a GATT server.

## Reading order

| Document | Question it answers |
| --- | --- |
| [01-feature-flow.md](01-feature-flow.md) | What does the feature do, including every abort path? |
| [02-protocol.md](02-protocol.md) | What exactly goes over the air? |
| [03-connection-state-machine.md](03-connection-state-machine.md) | What states can the link be in, and how does it leave each one? |
| [06-sequence-diagrams.md](06-sequence-diagrams.md) | What is the call flow between app and pump, normally and during recovery? |
| [04-hazard-analysis.md](04-hazard-analysis.md) | What can hurt someone, and what specifically prevents it? |
| [05-parity-contract.md](05-parity-contract.md) | How do Android and iOS stay behaviorally identical? |
| [07-architecture.md](07-architecture.md) | How is the app structured, and why that way? |
| [08-harness.md](08-harness.md) | What is the Mac-to-JVM socket, and why is it not the product protocol? |
| [09-device-verification.md](09-device-verification.md) | What remains when a radio is attached? |
| [10-demo-storyboard.md](10-demo-storyboard.md) | What does the 90-second recording show? |
