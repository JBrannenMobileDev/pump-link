#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
swift build -c release
BIN="$(swift build -c release --show-bin-path)/PumpPeripheral"
APP="$ROOT/build/PumpPeripheral.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/PumpPeripheral"
cp "$ROOT/Resources/Info.plist" "$APP/Contents/Info.plist"

# Ad-hoc sign so macOS treats this as a real bundle rather than a loose binary.
# Without a signature the Bluetooth authorisation is re-prompted on every
# rebuild, and an unanswered prompt looks exactly like a pump that will not
# advertise.
codesign --force --sign - "$APP" >/dev/null 2>&1 || echo "warning: codesign failed"

echo "built $APP"
