# AC / Windows — Android home-screen widget

A self-contained native widget that shows whether to **open your windows** or
**run the AC** — plus a **24-hour Open-Window Score graph** so you can see the
best time to open up once. It fetches three weather sources (Open-Meteo + NWS +
met.no) in parallel and runs an on-device port of the Python backend's scoring
engine. No server, no account, no Play Store.

**Resizable** from 2×2 up to full-width: it shows as many forecast hours as fit
the width, fills the available height, labels the left axis **win** (open) at the
top / **ac** at the bottom, and marks each midnight with a dated divider.

**On-device settings** — adding the widget opens a settings screen (re-openable
later via long-press → reconfigure on Android 12+) where you set, *without
rebuilding*: target max temp, the open-window score threshold, the muggy
dew-point cap, max AQI, and your latitude/longitude. Saving refetches and
redraws. The configured setpoints are shown on the widget (`open ≥ 60 · target ≤
78°F`).

**Behavior:**
- **Caches** the last result and renders it instantly on every load; only hits
  the network when the cache is missing or older than **15 min** (or on tap).
- **Refresh indicator** — a spinner + "updating…" shows while it's pulling data;
  the footer shows the last-updated time.
- **Tap** the widget to force an immediate refresh.
- Background color encodes the call; the graph bars above the dashed threshold
  line are the open-worthy hours, and the best contiguous window is underlined.

> ✅ This project **compiles** (`./gradlew assembleDebug` → `app-debug.apk`, AGP 8.5.2 /
> Gradle 8.7 / JDK 17 / compileSdk 34) and was verified against a user-space toolchain on
> Linux/WSL2. You can build and run it entirely from the command line — Android Studio is
> optional. See [Local build & emulator (no Android Studio)](#local-build--emulator-no-android-studio).

## What you need (all free)

- **Android Studio** (the only practical way to build an APK without a server):
  <https://developer.android.com/studio>
- Your phone, with **USB debugging** on: Settings → About phone → tap *Build
  number* 7× to unlock Developer options → Developer options → enable **USB
  debugging**. (Only needed for the `adb install` path; you can also just copy
  the APK to the phone and tap it.)

## 1. Configure your location

Edit `app/src/main/java/com/acwindows/AppConfig.kt` and set:

```kotlin
const val DEFAULT_LAT = 40.7128            // your latitude
const val DEFAULT_LON = -74.0060           // your longitude
const val DEFAULT_UA  = "ac-widget/0.1 (your-email@example.com)"  // NWS asks for a contact
```

Find your lat/lon at <https://www.latlong.net>. Comfort thresholds (max temp,
dew point, AQI) default to the same values as the Python backend; change them in
`Comfort()` in `Models.kt` if you like. (For the MVP these are baked in at build
time — changing them means a rebuild. A later version can add an in-app settings
screen.)

## 2. Build the APK

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

## 3. Put it on your phone

Either path works — both are free and local:

- **USB (adb):** `adb install -r app/build/outputs/apk/debug/app-debug.apk`
  (Android Studio's **Run ▶** also installs it; if it warns "no default
  activity", choose *Do not launch* / *Nothing* — this app is widget-only and has
  no app screen to open.)
- **Copy + tap:** transfer `app-debug.apk` to the phone (USB, Drive, etc.), tap
  it in a file manager, and allow "install unknown apps" for that app.

## 4. Add the widget

Long-press an empty spot on your home screen → **Widgets** → find **AC /
Windows** → drag it out. It shows "Updating…", then the recommendation. The
background color encodes the call:

| Color | Meaning |
|---|---|
| 🟩 green | open your windows |
| 🟦 blue | run the AC |
| ◾ slate | keep windows closed (not hot, but AQI/rain) |
| 🟦 teal | comfortable — your call |
| 🟥 red | no data (tap to retry) |

**Tap the widget** to refresh immediately; otherwise it updates about every 15
minutes (WorkManager's minimum interval).

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

- **Stuck on "Updating…" / not refreshing** — aggressive battery optimization can
  pause WorkManager. Tapping the widget forces an immediate refresh; to keep the
  periodic updates reliable, exempt the app from battery optimization in Settings.
- **"no sources reachable"** — check the phone's internet. NWS is US-only, so
  outside the US only Open-Meteo will respond (still fine — you'll see
  `degraded` confidence). Double-check your lat/lon.
- **Gradle/AGP version errors on first sync** — let Android Studio's upgrade
  assistant bump the versions; the code itself doesn't depend on the exact
  versions pinned here.

## Relationship to the Python backend

`Decision.kt` / `WeatherRepository.kt` are a faithful port of the repo's Python
`decision.py` / `providers` / `aggregate.py`. The Python project stays the
canonical "brain" and is where smart-home device control will be added; when that
lands, this widget can switch from computing locally to polling that backend's
JSON over your home network.
