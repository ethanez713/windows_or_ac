# Development

Building, installing and debugging the app from the command line. What the app does is in the
[README](../README.md); how the score is reasoned is in [METHODOLOGY.md](METHODOLOGY.md).

## What you need (all free)

- A **JDK 17**, the **Android SDK** (compileSdk 34), and **Gradle 8.7** — or
  **Android Studio**, which bundles all three:
  <https://developer.android.com/studio>. Building entirely from the command
  line works too; see [Local build & emulator](#local-build--emulator-no-android-studio).
- Your phone, with **USB debugging** on: Settings → About phone → tap *Build
  number* 7× to unlock Developer options → Developer options → enable **USB
  debugging**. (Only needed for the `adb install` path; you can also just copy
  the APK to the phone and tap it.)

## 1. Build the APK

**Android Studio (recommended):**
1. *Open* → select the repository root. Let Gradle sync finish (first sync
   downloads Gradle + dependencies, so it needs internet, and it will create the
   Gradle wrapper for you). If it offers to upgrade the Android Gradle Plugin,
   accept.
2. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
3. The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

**Command line (if you have a JDK + the Android SDK):**
```bash
gradle wrapper            # one-time, only if ./gradlew is missing
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

## 2. Put it on your phone

Either path works — both are free and local:

- **USB (adb):** `adb install -r app/build/outputs/apk/debug/app-debug.apk`
  (Android Studio's **Run ▶** also installs it.)
- **Copy + tap:** transfer `app-debug.apk` to the phone (USB, Drive, etc.), tap
  it in a file manager, and allow "install unknown apps" for that app.

## 3. Set your location

Open the app and tap the gear icon. Either tick **Use my phone's location**, or
type an address and hit **Look up**, or enter a latitude/longitude by hand (find
yours at <https://www.latlong.net>). Comfort thresholds are editable on the same
screen — no rebuild.

## 4. Add the widget

Long-press an empty spot on your home screen → **Widgets** → find **AC /
Windows** → drag it out. It shows "Updating…", then the recommendation. The
background color encodes the call:

| Color | Meaning |
|---|---|
| 🟩 green | open your windows |
| 🟦 blue | run the AC |
| ◾ slate | keep windows closed (too cold, or AQI/rain) |
| 🟦 teal | comfortable — your call |
| 🟥 red | no data (tap to retry) |

**Tap the widget** to refresh immediately; otherwise it updates itself about every
10 minutes.

## Local build & emulator (no Android Studio)

The whole loop — compile, install, run in an emulator — works from the command line. The
**toolchain is user-space** (JDK + SDK + Gradle under `$HOME`, no root); only two emulator
prerequisites on WSL2 need a one-time `sudo` (KVM access + a handful of host graphics libs).
This was used to build the APK, boot a headless emulator, and verify the live fetch+decision
on-device — it's the fastest inner loop.

### One-time toolchain setup (user-space, no root)

```bash
# 1. JDK 17 (Adoptium), Android cmdline-tools, Gradle 8.7 — unpacked under $HOME.
#    (`unzip` isn't needed; extract zips with: python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" <zip> <dir>)
# 2. ~/android-tooling/env.sh exports JAVA_HOME / ANDROID_HOME / GRADLE_HOME / PATH.
source ~/android-tooling/env.sh
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"   # build
sdkmanager "emulator" "system-images;android-34;google_apis;x86_64"       # emulator
gradle -p . wrapper --gradle-version 8.7      # one-time: generates ./gradlew
```

`env.sh` is the single thing to `source` in any new shell. `local.properties` (`sdk.dir=…`)
is generated for Gradle and is gitignored.

### WSL2 emulator prerequisites (one-time, need sudo)

The emulator needs hardware acceleration and a host GL/X11 stack that a headless box lacks:

```bash
sudo chmod 666 /dev/kvm                       # grant your user KVM access (resets on WSL restart)
# …or persistently: sudo usermod -aG kvm $USER  then  wsl --shutdown (from Windows) to relog
sudo apt-get install -y libx11-xcb1 libgl1 libglx-mesa0 libegl1 libgles2 libxcb1 libxkbcommon0
```

One quirk handled automatically by the toolchain layout: the bundled qemu links
`libpulse.so.0` (audio) which isn't present; a tiny **stub** is dropped next to the qemu
binary so it loads (audio is disabled at runtime with `-no-audio`, so the stub is never
called). If you rebuild the toolchain from scratch, recreate it:
`gcc -shared -fPIC -Wl,-soname,libpulse.so.0 -o "$ANDROID_HOME/emulator/qemu/linux-x86_64/libpulse.so.0" <(echo)`.

### The `dev.sh` inner loop

```bash
./dev.sh build      # compile-verify -> app-debug.apk
./dev.sh emu        # boot the emulator HEADLESS (the reliable path on WSL2)
./dev.sh install    # build + adb install onto the running emulator/device
./dev.sh verify     # trigger a refresh, print the on-device fetch+decision from logcat
./dev.sh shot       # screenshot the emulator -> /tmp/acwindows.png
./dev.sh run        # the whole loop: emu (if needed) -> install -> verify
./dev.sh logs       # tail this app's 'AcWindows' logcat
```

`verify` is the key one: it broadcasts `ACTION_REFRESH` (which runs `RefreshWorker` without
needing a placed widget) and prints the result, e.g.

```
AcWindows: fetch ok=[open-meteo, nws] failed=[] confidence=high temp=74.38 dew=48.31 aqi=38 -> OPEN_WINDOWS: Open your windows
```

**Headless vs. windowed:** the **headless** qemu binary boots reliably with just the libs
above. The **windowed** binary (`./dev.sh emu-window`, shown via WSLg) additionally needs the
full Qt stack and is fussier — for verification you don't need the window: `./dev.sh shot`
captures the SwiftShader framebuffer (including any placed widget) as a PNG. To place the
widget itself: long-press the home screen → Widgets → drag **AC / Windows** out.

**RAM:** the box is memory-tight, so `dev.sh` caps the emulator at 1.5 GB; close other heavy
apps while it runs.

## Troubleshooting

- **Footer says `⚠ as of …` / the graph's now-line reads "stale"** — the widget
  hasn't managed a refresh in a while. Aggressive battery optimization is the
  usual cause (Doze also stretches the 10-min tick to ~15 when the phone is
  idle). Tapping the widget forces an immediate refresh; to keep the automatic
  updates on schedule, exempt the app from battery optimization in Settings.
- **"no sources reachable"** — check the phone's internet. NWS is US-only, so
  outside the US only Open-Meteo will respond (still fine — you'll see
  `degraded` confidence). Double-check your lat/lon.
- **Address lookup finds nothing** — `Geocoder` is backed by the device's own
  geocoding service, which some de-Googled ROMs don't ship. Enter a
  latitude/longitude by hand instead; everything else works the same.
- **Gradle/AGP version errors on first sync** — let Android Studio's upgrade
  assistant bump the versions; the code itself doesn't depend on the exact
  versions pinned here.

## Scoring engine

`Decision.kt` and `Score.kt` are the scoring engine. The unit tests next to them
are its specification. `WeatherRepository.kt` fetches the forecast they score.
