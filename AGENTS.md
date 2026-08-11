# Repository Guidelines

## Project Overview

Kotlin + Jetpack Compose (Material 3) root utility that turns a Magisk-rooted OPPO tablet into a **secondary-screen host for AR glasses** (RayNeo/HDMI via USB-C DisplayPort Alt Mode). The tablet runs a Compose control panel; the glasses render an independent Compose UI on the system's external display.

Reference app under analysis (NOT copied): `cn.axi.cast` "副屏·阿西西" — see `docs/reference/ARCHITECTURE_RECON.md` and `ROOT_OPTIMIZATION_PLAN.md`.

Current state: P0/P1 done — external display auto-detection, glasses UI launch, root shell + display-aware input injection. Verified acceptance: hotplug detection (display id changes between plugs — never hardcode it), launch, graceful teardown, `input -d <id> tap`.

## Architecture & Data Flow

Dual-display, single-process, no DI/ViewModel:

```
平板 Display 0: MainActivity (控制端 Compose UI)
  ├─ ExternalDisplayController — DisplayManager 监听，发现外部显示（PRESENTATION flag，
  │     displayId 运行时获取，拔插后可能变化：实测 4 → 5），StateFlow<ExternalDisplayState>
  ├─ 启动眼镜界面 → ActivityOptions.setLaunchDisplayId(extId) → ExternalDisplayActivity
  ├─ RootShell (su -c, 超时+双流捕获+脱敏日志) → InputController (input -d <id> tap/swipe/keyevent)
  └─ ExternalDisplayActivity 在眼镜 display 全屏渲染 Compose UI，display 移除时自动 finish

眼镜 Display N (RayNeo/HDMI, 1920x1080@60): ExternalDisplayActivity
```

- Theme entry: `ARglassplusTheme` in `ui/theme/Theme.kt` — `dynamicColor = true`.
- Privilege: Magisk `su -c` per-command (RootShell). No Shizuku. First `su` prompts once, then persistent.
- State: controller exposes `StateFlow<ExternalDisplayState>` (`Connected(displayId, width, height, refreshRate, densityDpi)` / `Disconnected`).

## Key Directories

```
app/src/main/java/com/example/ar_glass_plus/
  MainActivity.kt             # 控制端 Compose UI（状态卡、启动按钮、注入测试、命令日志）
  display/
    ExternalDisplayController.kt  # DisplayManager 枚举/热插拔监听/启动眼镜界面
    ExternalDisplayActivity.kt    # 眼镜端 Compose UI（display 移除自动 finish）
    ExternalDisplayState.kt       # Connected/Disconnected sealed interface
  root/
    RootShell.kt                  # su -c 执行器（isAvailable/exec, RootResult）
    InputController.kt            # display-aware tap/swipe/keyEvent
  ui/theme/                   # Theme.kt, Color.kt, Type.kt (Material 3)
app/src/main/res/             # strings, themes, colors, launcher assets
tools/
  deploy.sh                   # 编译+推送+root 安装+启动（AR_DEVICE 必填）
  glasses-state.sh            # Phase 1.5 眼镜侦察四状态快照
docs/reference/               # 侦察与方案文档（ARCHITECTURE_RECON / ROOT_OPTIMIZATION_PLAN）
example_app/                  # 参考 APK 分析产物（gitignored，见 README）
```

## Development Commands

```bash
# One-shot: build + push + root-install + launch (wireless or USB)
export AR_DEVICE=192.168.0.102:34271      # wireless; or USB serial JN9PYDTGUSGUPFOZ
./tools/deploy.sh

# Build debug APK
./gradlew assembleDebug                   # → app/build/outputs/apk/debug/app-debug.apk

# Root shell on the tablet (wireless works too)
adb -s "$AR_DEVICE" shell su -c <cmd>

# Logs
adb -s "$AR_DEVICE" logcat                # filter: RootShell, ExtDisplayCtrl, ExtDisplayAct

# Tests
./gradlew testDebugUnitTest               # local unit tests (no device)
./gradlew connectedDebugAndroidTest       # instrumented tests (device required)
```

⚠️ Multiple transports may exist (USB + wireless mDNS + other LAN devices) — always pass `-s "$AR_DEVICE"`; `deploy.sh` refuses to run without it.

## Code Conventions & Common Patterns

- **Version catalog is the single source of truth**: `gradle/libs.versions.toml`; all deps/plugins referenced via `libs.*` / `alias(libs.*)` accessors. Add new deps there, never hardcode versions in `build.gradle.kts`.
- **AGP 9 DSL, not legacy forms**:
  - `compileSdk { version = release(37) }` (not `compileSdk = 37`)
  - `buildTypes.release { optimization { enable = false } }` (not `minifyEnabled false`)
- **No `kotlin-android` plugin**: AGP 9.3.1 has built-in Kotlin support; only `org.jetbrains.kotlin.plugin.compose` is applied.
- **Compose**: Material 3, `@Composable` + `@Preview` per screen, `Modifier` as trailing default param (`modifier: Modifier = Modifier`), theme-aware colors, dynamic color on.
- **Java 11 bytecode target** (`sourceCompatibility`/`targetCompatibility` in `compileOptions`). No `kotlinOptions`/`jvmTarget` set — don't add unless needed.
- **Configuration cache is enabled** (`org.gradle.configuration-cache=true`) — keep build scripts configuration-cache-safe.
- Package/app id: `com.example.ar_glass_plus`; root project name `AR-glass-plus`.

## Important Files

| File | Role |
|---|---|
| `settings.gradle.kts` | Repos (`google`/`mavenCentral`, `FAIL_ON_PROJECT_REPOS`), includes `:app` |
| `build.gradle.kts` (root) | Declares plugins `apply false` |
| `app/build.gradle.kts` | SDK levels, deps, Compose setup |
| `gradle/libs.versions.toml` | All versions: AGP 9.3.1, Kotlin 2.2.10, Compose BOM 2026.02.01, JUnit 4.13.2, espresso 3.5.1 |
| `app/src/main/AndroidManifest.xml` | MainActivity (LAUNCHER) + ExternalDisplayActivity (non-exported) |
| `app/src/main/java/.../MainActivity.kt` | 控制端 Compose UI |
| `app/src/main/java/.../display/ExternalDisplayController.kt` | 外部显示发现/热插拔/启动 |
| `app/src/main/java/.../root/RootShell.kt` | `su -c` 执行器（所有特权操作入口） |
| `tools/deploy.sh` | 编译+安装+启动闭环 |
| `docs/reference/ARCHITECTURE_RECON.md` | 参考 app 架构侦察（Viture/眼镜/Shizuku） |
| `docs/reference/ROOT_OPTIMIZATION_PLAN.md` | root 优化路线（P0-P3） |
| `gradle.properties` | `-Xmx2048m`, configuration cache |
| `local.properties` | Machine-specific `sdk.dir`; gitignored — never commit |

## Runtime/Tooling Preferences

- **Runtime**: Gradle 9.5.0 wrapper (`./gradlew`), JDK 25 daemon toolchain pinned in `gradle/gradle-daemon-jvm.properties` (foojay resolver). ⚠️ Mismatch: daemon JVM is 25 but bytecode target is Java 11 — deliberate template default, don't "fix" without reason.
- **Android SDK**: via Android Studio; `compileSdk`/`targetSdk` 37 (API 37 preview), `minSdk` 24. ⚠️ **Device runs API 36 — compileSdk-37-only APIs crash at runtime** (e.g. `Display.isInternal()`; `Display.getName/getUniqueId/getDensityDpi` removed in API 37). Verify every Display API against the API-36 framework before use.
- **Device**: OPPO OPD2407 (serial `JN9PYDTGUSGUPFOZ`), **Android 16 (API 36)**, Magisk root. Primary transport is **wireless ADB** (`adb pair` + `adb connect`, port changes each session) so the USB-C port stays free for the glasses. Glasses: RayNeo AR (vendor `1bbb`), enumerated as external display via DP Alt Mode; HID interface present but not an Android input device.
- **IDE**: Android Studio (Gradle sync, Compose preview). CLI (adb/gradle) is the primary workflow.
- **No CI**. No README.

## Testing & QA

- **Frameworks**: JUnit 4 (local), AndroidX Test (`AndroidJUnitRunner`) + Espresso 3.5.1 + Compose `ui-test-junit4` (BOM-managed) for instrumented. Current tests are template placeholders.
- **P0/P1 acceptance (verified on-device 2026-08-11)**: (1) glasses plug → auto-detect external display; (2) tap "启动眼镜界面" → `ExternalDisplayActivity` fullscreen on glasses; (3) unplug → activity auto-finishes, control panel returns, no crash; (4) control-panel tap → `input -d <runtime-id> tap` exit=0. Display id changed 4→5 between plugs — regression tests must never assume a fixed id.
- **QA loop**: edit → `./tools/deploy.sh` → drive UI → `adb -s $AR_DEVICE logcat` (tags: `RootShell`, `ExtDisplayCtrl`, `ExtDisplayAct`). Root-dependent behavior must be verified on-device; the host cannot simulate `su` or the external display.
