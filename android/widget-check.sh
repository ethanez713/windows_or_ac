#!/usr/bin/env bash
# Headless-emulator widget-render check.
#
# Boots the ac_widget_avd (API 34, matches compileSdk) if it isn't already up, then runs the
# instrumented WidgetInflationTest — the real RemoteViews.apply() the launcher does, which a
# plain build and `logcat -b crash` can't exercise — and pulls the rendered PNGs for a glance.
#
# The fast render+blank checks (no emulator) are the Robolectric gate: `./gradlew test`.
#
# Usage:  ./widget-check.sh [avd-name]
set -euo pipefail
source ~/android-tooling/env.sh
AVD="${1:-ac_widget_avd}"
SERIAL=emulator-5554
cd "$(dirname "$0")"

if ! adb devices | grep -q "^${SERIAL}[[:space:]]*device$"; then
  echo ">> booting $AVD headless (no window)…"
  nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect >/tmp/ac_widget_emulator.log 2>&1 &
  adb -s "$SERIAL" wait-for-device
  echo ">> waiting for boot to complete…"
  until [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
fi

echo ">> running WidgetInflationTest on $SERIAL…"
ANDROID_SERIAL="$SERIAL" ./gradlew connectedDebugAndroidTest

echo ">> pulling rendered widget PNGs…"
OUT=build/widget-shots
rm -rf "$OUT"; mkdir -p "$OUT"
# The test writes to the app's internal filesDir; run-as + tar streams it out (debug build).
adb -s "$SERIAL" exec-out run-as com.acwindows sh -c 'cd files 2>/dev/null && tar c widget-shots 2>/dev/null' \
  | tar x -C "$OUT" 2>/dev/null || echo ">> (no PNGs pulled — check the app is installed)"
echo ">> done."
echo ">> test report: android/app/build/reports/androidTests/connected/"
echo ">> widget PNGs:  android/$OUT/widget-shots/"
