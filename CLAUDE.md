# ac_windows — project instructions

(Inherits the global `~/.claude/CLAUDE.md`; this file only adds project-specific rules. It is
published with the repo: personal or machine-specific notes go in the gitignored `CLAUDE.local.md`.)

## Verification

- **Building is not evidence about the widgets.** They are inflated and dispatched in the
  launcher's process by reflection, so an invalid layout class or a non-remotable setter
  compiles, links, launches and crash-scans clean, then fails the whole widget on the home
  screen. Any change under `res/layout/widget_*.xml`, `res/xml/*_widget_info.xml` or a
  `*Provider.kt` must pass
  `~/.claude/skills/android-smoketest/scripts/check_remoteviews.py android/app`
  before it is called done — and on-device, the widget's log lives in the launcher, not in
  `logcat -b crash`. See the **android-smoketest** skill, steps 1 and 5.

## Build

- Toolchain is user-space on this WSL2 box: `source ~/android-tooling/env.sh` then
  `./gradlew assembleDebug` from `android/`. No Android Studio.
