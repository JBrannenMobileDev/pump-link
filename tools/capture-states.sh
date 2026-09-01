#!/usr/bin/env bash
#
# Screenshots every BolusScenarios row from the debug gallery. Names must
# match BolusScenarios.all exactly — the same table the JVM totality test
# asserts over.
#
#   tools/capture-states.sh
#
# Requires a debug APK installed on a device (or emulator) reachable by adb.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/docs/img/states"
ADB="${ADB:-adb}"
PACKAGE="dev.pumplink"
ACTIVITY="dev.pumplink/.StateGalleryActivity"

if ! command -v "$ADB" >/dev/null; then
  if [[ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]]; then
    ADB="$HOME/Library/Android/sdk/platform-tools/adb"
  else
    echo "adb not found. Set ADB or install platform-tools." >&2
    exit 1
  fi
fi

# Keep in lockstep with BolusScenarios.all.
SCENARIOS=(
  "Entering"
  "Confirming"
  "Delivering"
  "Delivered"
  "Delivered, recovered by query"
  "Partially delivered"
  "Awaiting reissue"
  "Resolving"
  "Blocked"
  "Indeterminate"
  "Dosing disabled"
)

slug() {
  echo "$1" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/-$//'
}

"$ADB" wait-for-device
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true

mkdir -p "$OUT"

for name in "${SCENARIOS[@]}"; do
  file="$OUT/$(slug "$name").png"
  echo "capturing $name -> ${file#"$ROOT/"}"
  "$ADB" shell am force-stop "$PACKAGE"
  # Quote for the remote shell: adb otherwise splits a name that contains spaces.
  "$ADB" shell "am start -n $ACTIVITY --es scenario \"$name\"" >/dev/null
  # Compose needs a beat to settle after the activity starts.
  sleep 1.5
  "$ADB" exec-out screencap -p > "$file"
  if [[ ! -s "$file" ]]; then
    echo "empty screencap for $name" >&2
    exit 1
  fi
done

echo "wrote ${#SCENARIOS[@]} screenshots to ${OUT#"$ROOT/"}"
