#!/usr/bin/env bash
# Local Android dev loop for the AC / Windows widget.
#
# Usage:
#   ./dev.sh build              # compile-verify + produce app-debug.apk
#   ./dev.sh emu                # boot the emulator HEADLESS (reliable on WSL2)
#   ./dev.sh emu-window         # boot with a window via WSLg (needs extra Qt libs, see README)
#   ./dev.sh install            # build + install onto the running emulator/device
#   ./dev.sh verify             # trigger a refresh and print the on-device fetch+decision
#   ./dev.sh shot [file]        # screenshot the emulator (default /tmp/acwindows.png)
#   ./dev.sh widget             # nudge any placed widget to refresh
#   ./dev.sh logs               # tail this app's logcat
#   ./dev.sh run                # emu (if needed) + install + verify  — the full loop
#
# Requires the user-space toolchain at ~/android-tooling (see android/README.md).
# WSL2 prerequisites (one-time, need sudo — see README "Local build & emulator"):
#   sudo chmod 666 /dev/kvm
#   sudo apt-get install -y libx11-xcb1 libgl1 libglx-mesa0 libegl1 libgles2 libxcb1 libxkbcommon0
set -euo pipefail

ENV="$HOME/android-tooling/env.sh"
[ -f "$ENV" ] || { echo "Missing $ENV — run the toolchain setup first (see README)."; exit 1; }
# shellcheck disable=SC1090
source "$ENV"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG="com.acwindows"
AVD="ac_windows_avd"
APK="$HERE/app/build/outputs/apk/debug/app-debug.apk"

ensure_avd() {
  if ! avdmanager list avd 2>/dev/null | grep -q "Name: $AVD"; then
    echo "Creating AVD '$AVD'…"
    echo "no" | avdmanager create avd -n "$AVD" \
      -k "system-images;android-34;google_apis;x86_64" -d pixel_5 >/dev/null
  fi
}

wait_for_boot() {
  echo "Waiting for emulator to boot (cold start can take ~2 min)…"
  adb wait-for-device
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
  echo "Emulator booted."
}

case "${1:-run}" in
  build)
    "$HERE/gradlew" -p "$HERE" assembleDebug
    echo "APK: $APK" ;;
  emu)
    ensure_avd
    # Headless is the reliable path on WSL2 (windowed qemu needs the full Qt UI stack).
    # SwiftShader gives software GPU; 1.5G RAM keeps us within a tight memory budget.
    nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim \
      -gpu swiftshader_indirect -memory 1536 >/tmp/emulator.log 2>&1 &
    wait_for_boot ;;
  emu-window)
    ensure_avd
    nohup emulator -avd "$AVD" -no-audio -gpu swiftshader_indirect -memory 1536 \
      >/tmp/emulator.log 2>&1 &
    wait_for_boot ;;
  install)
    "$HERE/gradlew" -p "$HERE" assembleDebug
    adb install -r "$APK"
    echo "Installed $PKG." ;;
  verify)
    # Trigger RefreshWorker directly (no home-screen widget needed) and read its log line.
    adb logcat -c
    adb shell am broadcast -a com.acwindows.ACTION_REFRESH -n "$PKG/.AcWindowsProvider" >/dev/null
    echo "Fetching on-device (Open-Meteo + NWS)…"
    for _ in $(seq 1 30); do
      line=$(adb logcat -d -s AcWindows 2>/dev/null | grep -E 'fetch ok=|refresh failed' | tail -1)
      [ -n "${line:-}" ] && { echo "$line"; exit 0; }
      sleep 2
    done
    echo "No result in 60s — check 'adb logcat -s AcWindows'." ;;
  shot)
    out="${2:-/tmp/acwindows.png}"
    adb exec-out screencap -p > "$out"
    echo "Saved $out" ;;
  widget)
    adb shell am broadcast -a com.acwindows.ACTION_REFRESH -n "$PKG/.AcWindowsProvider" >/dev/null || true
    echo "Refresh sent. To place the widget: long-press home → Widgets → 'AC / Windows'." ;;
  logs)
    adb logcat -s AcWindows ;;
  run)
    adb get-state >/dev/null 2>&1 || "$HERE/dev.sh" emu
    "$HERE/dev.sh" install
    "$HERE/dev.sh" verify ;;
  *)
    grep '^#' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' | head -22; exit 1 ;;
esac
