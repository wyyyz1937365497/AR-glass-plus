# Third-Party Notices

## AR-Touchpad (Apache License 2.0)

- **Project**: https://github.com/pgratz1/AR-Touchpad
- **Author**: Paul Gratz (Copyright 2026)
- **License**: Apache License 2.0 — https://github.com/pgratz1/AR-Touchpad/blob/main/LICENSE
- **Used for**: uinput virtual-mouse approach (real /dev/uinput REL mouse bound to a
  target Android display), adapted to AR-glass-plus.

### Reused / modified files

| Our file | Origin | Modification |
|---|---|---|
| `app/src/main/cpp/uinput_jni.cpp` | AR-Touchpad `app/src/main/cpp/uinput_jni.cpp` | JNI function names re-packaged to `com.example.ar_glass_plus.input.uinput.UinputNative`; doc comments trimmed; Apache header retained. |
| `app/src/main/java/.../input/uinput/UinputNative.kt` | AR-Touchpad `UinputNative.kt` (concept) | Reimplemented for our package; same 5 native functions + APK-path `System.load` fallback. |
| `app/src/main/java/.../input/uinput/RootMouseService.kt` | AR-Touchpad `MouseService.kt` (concept) | Shizuku UserService replaced by **libsu RootService** (root uid); target display is our hidden **contentDisplayId** (VirtualDisplay), not the physical glasses display; AIDL trimmed to uinput-mouse surface + pressKey; InputManagerGlobal access via reflection (hidden/SystemApi). |
| `app/src/main/aidl/.../IRootMouseService.aidl` | AR-Touchpad `IMouseService.aidl` | Trimmed to: setDisplay / moveMouse / click / scroll / mouseDown / mouseUp / pressKey / destroy. |
| `app/src/main/java/.../input/api/UinputInputBackend.kt` | AR-Touchpad `ShizukuMouseController.kt` (concept) | libsu `RootService.bind` instead of Shizuku `bindUserService`; ~16 ms IPC rate-limit with delta accumulation retained. |
| `app/src/main/java/.../input/api/InputBackend.kt` | — | Our own abstraction (uinput + shell fallback). |

### Derivative-work compliance

Per the Apache-2.0 requirements for distributed derivative works: the original
copyright notice is retained in `uinput_jni.cpp`; this notice documents source,
license, and modifications. No GPL-3.0 code (AVNC, Extend) and no code from
unlicensed projects (adb-touchpad) has been copied.

## libsu (Apache License 2.0)

- **Project**: https://github.com/topjohnwu/libsu
- **Author**: topjohnwu (John Wu)
- **License**: Apache License 2.0
- **Used for**: root shell access (`com.github.topjohnwu.libsu:core`) and root
  service IPC (`com.github.topjohnwu.libsu:service`), including the
  `RootService` base class that runs `RootMouseService` in a root process.

## Android Open Source Project (AOSP) — API reference only

Behavior of `InputManagerGlobal.setInputDeviceDisplayAssociation` and
`injectInputEvent` referenced from AOSP sources; no AOSP code copied.
