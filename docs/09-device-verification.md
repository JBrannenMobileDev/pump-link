# 09 — Device verification

The JVM scenario table is the daily gate. This page is what remains when a
radio is attached.

## Hardware

| Role | Device | OS | Radio role |
| --- | --- | --- | --- |
| Controller | Samsung Galaxy A32 5G | Android 13 (API 33) | BLE central |
| Pump | Apple Silicon Mac | macOS 26 | BLE peripheral via CoreBluetooth |

API 33 is the A32's last OS version. The Android 14+ mandatory
foreground-service typing path is declared (`connectedDevice`) and is **not
exercised** on this handset. See
[00-overview.md](00-overview.md#verification-boundary).

## Probe (do this first)

On the A32, with the debug APK installed and Bluetooth on:

```bash
adb devices
adb logcat -s BleController BleTransport
```

Confirm `isMultipleAdvertisementSupported` if logged. Nothing in this build
depends on it — the phone is central-only — but the value belongs in the
record.

## Procedure

1. `./gradlew :simulator:run`
2. `mac/PumpPeripheral/scripts/bundle.sh && open mac/PumpPeripheral/build/PumpPeripheral.app`
3. Install and launch the controller. Grant `BLUETOOTH_SCAN`,
   `BLUETOOTH_CONNECT`, notifications.
4. Wait for `Ready` (Entering). Note the negotiated MTU in the Mac window.
5. Deliver 1.00 U. Expect `Delivered 1000 mU`.
6. Inject **Drop next RSP**, deliver 0.50 U. Expect Resolving, then a query
   that surfaces Completed or AwaitingReissue — never a silent second dose.
7. Inject **Reset store** with a command outstanding. Expect Indeterminate.
8. Inject **Disconnect mid-command**. Expect query-then-decide after
   reconnect.

## Status

Not yet executed on hardware in this repository. The Android column of the
scenario table for SC-16 and SC-17 remains a specification of what those
instrumented tests would assert. JVM rows (SC-01–SC-15, SC-18–SC-20) pass in
CI.

When this page is run, replace this paragraph with the observed MTU, the
`isMultipleAdvertisementSupported` value, and pass/fail per row.
