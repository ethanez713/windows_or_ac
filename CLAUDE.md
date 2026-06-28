# ac_windows — project instructions

(Inherits the global `~/.claude/CLAUDE.md`; this file only adds project-specific rules.)

## Verification / screenshots

- **Do NOT screenshot the app or widget, and do NOT boot the emulator or drive the
  phone to capture screens, unless I explicitly ask in that message.** Screenshot +
  visual-iteration loops burn a lot of tokens. Default to building (`./gradlew
  assembleDebug`) and reasoning about correctness; let me do the visual check.
- When I do ask for a screenshot, the phone connects over wifi adb at an IP:port I
  supply each session (it changes) — see the **Machine environment** section of the
  global `~/.claude/CLAUDE.md` and [[android-local-toolchain]] for the build/install/
  screencap toolchain.

## Build

- Toolchain is user-space on this WSL2 box: `source ~/android-tooling/env.sh` then
  `./gradlew assembleDebug` from `android/`. No Android Studio.
