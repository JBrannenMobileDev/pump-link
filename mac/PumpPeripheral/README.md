# PumpPeripheral

macOS host for the pump radio. `CBPeripheralManager` exposes the GATT service
specified in `docs/02-protocol.md`. Decision logic stays in the JVM
`PumpCore`, reached over the harness socket in `docs/08-harness.md`.

## Run

1. Start the JVM host from the repo root:

   ```bash
   ./gradlew :simulator:run
   ```

2. Build and launch the app bundle (CoreBluetooth needs a real bundle and
   `NSBluetoothAlwaysUsageDescription`; `swift run` fails TCC silently):

   ```bash
   mac/PumpPeripheral/scripts/bundle.sh
   open mac/PumpPeripheral/build/PumpPeripheral.app
   ```

Keep the window in the foreground. A backgrounded Apple peripheral puts its
service UUID in the overflow area, which an Android central cannot see.

3. On the Galaxy A32 5G, install the controller APK and grant Bluetooth
   permissions. It scans for the service UUID and confirms identity via
   `STATUS`.
