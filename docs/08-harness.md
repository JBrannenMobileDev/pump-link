# 08 — Test Harness (not the product protocol)

The product protocol is [02-protocol.md](02-protocol.md). Everything on this
page is a development fixture: a macOS `CBPeripheralManager` that exposes the
GATT service, and a JVM process that runs `PumpCore`. They talk to each other
over a length-prefixed TCP socket on localhost.

A reviewer who treats these frames as part of the wire format has misread the
repository. The socket never leaves the Mac. The phone never sees it. L1
fragmentation, L2 PDUs, L3 opcodes, and L4 session state all still run on the
JVM, against the MTU CoreBluetooth actually negotiated.

## Why a socket exists

`PumpCore` has to stay single-sourced and JVM-testable. Hosting it inside the
Swift process would fork the record store, the outcome query, and the fault
injectors — the exact logic the scenario table pins down. Hosting the GATT
server on Android is off the table: the only Android device in the verification
boundary is a central.

So the radio lives in Swift and the decisions live in Kotlin. The socket is
the seam.

## Framing

TCP is a byte stream. The harness therefore uses a length prefix, which is
exactly the thing [02-protocol.md](02-protocol.md#no-explicit-length-field)
refuses to put in L2, and for the same reason it belongs here rather than
there: the transport does not preserve message boundaries.

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|          Length (2)           |     Type      |    Payload    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

`Length` is the number of octets that follow it (type + payload), unsigned,
big-endian. Maximum payload is 1024 octets. A frame larger than that is a
harness fault and closes the socket.

Default bind: `127.0.0.1:17341`. Not configurable in the demo build, so a
stray listener on that port is a setup error rather than a silent fallback.

## Message types

| Type | Name | Direction | Payload |
| --- | --- | --- | --- |
| `0x01` | `MTU_CHANGED` | Swift → JVM | `mtu` (2) |
| `0x02` | `SUBSCRIBED` | Swift → JVM | `characteristic` (1): `0x01` RSP, `0x02` STATUS |
| `0x03` | `UNSUBSCRIBED` | Swift → JVM | `characteristic` (1) |
| `0x04` | `WRITE_RECEIVED` | Swift → JVM | `characteristic` (1) = `0x00` CMD, then the fragment octets |
| `0x05` | `NOTIFY` | JVM → Swift | `characteristic` (1), then the fragment octets |
| `0x10` | `FAULT` | Swift → JVM | `fault` (1) — see below |
| `0x11` | `SET_RESERVOIR` | Swift → JVM | `reservoirMilliunits` (2) |
| `0x12` | `SET_BATTERY` | Swift → JVM | `batteryPercent` (1) |
| `0x13` | `FORCE_DISCONNECT` | Swift → JVM | — (JVM asks Swift to drop the central; Swift does it) |
| `0x14` | `FORCE_DISCONNECT` | JVM → Swift | — |

`characteristic` values: `0x00` CMD, `0x01` RSP, `0x02` STATUS.

`fault` values match the simulator's injectors: `0x00` none, `0x01` drop next
response, `0x02` drop all responses, `0x03` corrupt next CRC, `0x04` delay
past `T_RESP`, `0x05` duplicate next notify, `0x06` evict all records,
`0x07` reset the record store, `0x08` disconnect after accept.

Reply-shaping faults (`0x01`–`0x05`) skip `GET_STATUS_REQ`. The controller
polls status every few seconds while Ready; applying an injector to that
poll would consume it before a bolus could be tested.

`0x01` (drop next) and `0x03` (corrupt CRC) poison the request `Seq`.
Every retransmission of that command is shaped the same way, so the
controller's three-attempt budget is exhausted and it must query. A later
request with a different `Seq` — the follow-up `QUERY_COMMAND_OUTCOME` —
clears the poison and answers.

`0x04` (delay past `T_RESP`) is one-shot: the first attempt is late, the
identical retry is answered. That contrast is the point. The delay is
applied in both `PumpEndpoint` (JVM loopback) and `PumpHost` (the live
harness).

`0x02` (drop all) also drops retransmissions and the follow-up query, so
the entry stays in flight for `Reconciling`.

`0x06` (evict all) and `0x07` (reset store) apply once, then clear.

Fragmentation is **not** done on the Swift side. The JVM receives the raw
CMD write and emits RSP/STATUS notifications already split against the MTU
from `MTU_CHANGED`. That is what keeps L1 in one place.

## STATUS cache

`STATUS` is a plain GATT read. It does not require a subscription
([02-protocol.md](02-protocol.md#l0--gatt-service)), and
`Configuring` enables notifications on `RSP` only
([03-connection-state-machine.md](03-connection-state-machine.md)). The
central therefore never sends `SUBSCRIBED` for `STATUS`.

The product peripheral would keep the 21-octet value on the characteristic
itself. The harness splits that job across two processes, so the JVM pushes
`NOTIFY` with `characteristic = 0x02` at session establish and after every
`recordEpoch` change (a completed write, a store reset, an eviction). The
Mac stores that payload in a controller-owned cache and serves `didReceiveRead`
from it. `CBMutableCharacteristic.value` is not used: CoreBluetooth reserves
that field for cached read-only characteristics, and `STATUS` is
`.read` + `.notify`, so assigning it is invalid and a later read can still
answer empty.

A read that arrives before the first push is answered with an ATT error, not
an empty success. An empty success would claim the pump's identity is zero
octets; the central then has to guess whether that is a protocol value or a
missing one. An error is the honest answer, and the central retries.

This keeps `STATUS` read semantics faithful to [02-protocol.md](02-protocol.md)
despite the split-process harness. The phone never sees a harness frame.

## What Swift is allowed to know

The Swift process knows the three UUIDs, the characteristic properties, and
how to advertise the service UUID. It does not parse a PDU, does not compute
a CRC, and does not decide a command outcome. Each `CBPeripheralManager`
delegate method is annotated with its Android `BluetoothGattServer`
counterpart so the mapping is readable from the code.

## Why this is an app bundle

CoreBluetooth from an unbundled `swift run` executable fails Bluetooth TCC
silently: there is no `Info.plist` to carry `NSBluetoothAlwaysUsageDescription`
and no bundle identifier for the prompt. The peripheral is therefore a real
macOS app. Build and run instructions are in `mac/PumpPeripheral/README.md`.
