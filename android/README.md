# AC / Windows — Android home-screen widget

A self-contained home-screen widget plus a companion app, showing whether to
**open your windows** or **run the AC** — with an **Open-Window Score graph**
(36 h by default, configurable 6–96 h) so you can see the best time to open up
once. It fetches three keyless weather sources (Open-Meteo + NWS + met.no) in
parallel and runs an on-device port of the Python backend's scoring engine. No
server, no account, no telemetry.

**Resizable** from 2×2 up to full-width: it shows as many forecast hours as fit
the width, fills the available height, labels the left axis **win** (open) at the
top / **ac** at the bottom, and marks each midnight with a dated divider.

**Settings, no rebuild needed.** The app's gear icon opens the global settings —
comfort thresholds (each factor's "comfortable up to" and "cutoff"), the
open-window score threshold, location, per-source trust weights, and optional API
keys for hyperlocal sources. Long-press the gear (or add/reconfigure the widget)
for the widget-only settings: forecast length and background opacity. There is no
Save button — edits commit when you leave the screen, and a **Discard changes**
button appears in the header once something is staged.

**Three ways to set your location:**
- **Use my phone's location** — a checkbox. While it's on, the current fix is
  re-read before every refresh, so the forecast follows you; the coordinate fields
  are locked and show what was last resolved. (Android only hands out a fix to an
  app that's actually on screen, so the position updates when you open the app and
  the widget picks it up on its next tick — this app deliberately doesn't request
  the background-location permission, which Play gates behind a separate review.)
- **Address lookup** — type a street, city, or ZIP and hit *Look up*; Android's
  built-in geocoder converts it to a latitude/longitude.
- **By hand** — type the coordinates directly, and grant no location permission
  at all.

Whichever you use, the resolved place name is shown on the widget's footer next
to a pin, and the coordinates go nowhere but the weather providers.

**Behavior:**
- **Auto-refreshes every 10 min** on its own (an inexact AlarmManager tick that
  re-arms itself, with a 15-min WorkManager job as a backstop — WorkManager alone
  can't go under 15 min). Refreshes are single-flight, so overlapping triggers
  coalesce instead of racing each other into the cache.
- **Caches** the last result and renders it instantly on every load; only hits
  the network when the cache is missing or older than **10 min** (or on tap).
- **Says what time it's showing.** The footer reads `as of 3:45 PM`, and a bright
  vertical **now** line on the graph marks the present hour. Because the bars are
  fixed clock hours, that line drifts right between refreshes — so if the widget
  ever stops updating you can see it: past hours fade, the footer turns amber with
  `⚠ as of 9:05 AM (5h old)`, and once the present runs off the end of the series
  the marker pins to the edge and reads **stale**.
- **Tap** the widget to force an immediate refresh; the refresh icon does the same
  without opening the app.
- Background color encodes the call; the graph bars above the dashed threshold
  line are the open-worthy hours, and the best contiguous window is underlined.
- **Rain line** — when rain is in the forecast, a blue line traces the chance of it
  on the same 0–100 axis as the bars, thickening with the hourly rate (1dp under
  0.05″/h, 2dp to 0.33″, 3dp to 1″, 4dp above) and labelled with the total inches.

> ✅ This project **compiles** (`./gradlew assembleDebug` → `app-debug.apk`, AGP 8.5.2 /
> Gradle 8.7 / JDK 17 / compileSdk 34) and was verified against a user-space toolchain on
> Linux/WSL2. You can build and run it entirely from the command line — Android Studio is
> optional. See [Local build & emulator (no Android Studio)](#local-build--emulator-no-android-studio).

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
1. *Open* → select this `android/` folder. Let Gradle sync finish (first sync
   downloads Gradle + dependencies, so it needs internet, and it will create the
   Gradle wrapper for you). If it offers to upgrade the Android Gradle Plugin,
   accept.
2. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
3. The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

**Command line (if you have a JDK + the Android SDK):**
```bash
cd android
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
yours at <https://www.latlong.net>). Comfort thresholds default to the same
values as the Python backend and are editable on the same screen — no rebuild.

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

## Relationship to the Python backend

`Decision.kt` / `WeatherRepository.kt` are a faithful port of the repo's Python
`decision.py` / `providers` / `aggregate.py`. The Python project stays the
canonical "brain" and is where smart-home device control will be added; when that
lands, this widget can switch from computing locally to polling that backend's
JSON over your home network.
