# Repository Guidelines

## Project Overview

Kotlin + Jetpack Compose (Material 3) root utility that turns a Magisk-rooted OPPO tablet into a **secondary-screen host for AR glasses** (RayNeo/HDMI via USB-C DisplayPort Alt Mode). The tablet runs a Compose control panel; the glasses render content on the system's external display.

**Three display modes (they complement, never replace each other):**
1. **Native Mirror** — system/ColorOS mirrors the built-in screen to the glasses over DP Alt Mode. AR-glass-plus does NOT participate in image processing; no mirror code exists by design.
2. **AR Workspace** — `ExternalDisplayActivity`: independent Compose UI on the glasses display.
3. **Rendered Display** — `FrameSource → Render Engine → glasses`, only when frame processing (SBS/crop/shader/3D) is actually needed. Ordinary Compose UI must NOT be forced through the render engine.

Reference app under analysis (NOT copied): `cn.axi.cast` — see `docs/reference/ARCHITECTURE_RECON.md` and `ROOT_OPTIMIZATION_PLAN.md`.

Current state: P0/P1/P1.1 done (external display detection, glasses UI launch, root shell + display-aware input injection, per-display density). P2 (render engine) in progress — API abstraction landed, GL backend next.

## Architecture & Data Flow

```
                          AR-glass-plus
                               │
             ┌─────────────────┴─────────────────┐
             │                 │                 │
      Direct Display      Render Engine        Input
      (DisplayManager)   (FrameSource → GL)   (root shell)
             │                 │                 │
             └─────────────────┼─────────────────┘
                               ▼
                    RayNeo External Display
```

- **Direct Display** (`display/`): DisplayManager discovery → dynamic displayId (changes between plugs: 4→5→6→7 observed) → launch Activity onto it via `ActivityOptions.setLaunchDisplayId`. `ExternalDisplayActivity` auto-finishes when its display is removed.
- **Render Engine** (`render/`): `RenderPipeline` drives a `RenderBackend` frame clock. `render/api/*` is backend-agnostic; `render/gl/` is the OpenGL ES implementation; `render/vulkan/` is design-only (see decision below).
- **FrameSource** (`source/`): producers decoupled from the renderer — the engine only knows "content is written to my input surface". Test pattern first; virtual-display app surfaces (non-mirror apps rendered as textures) are the strategic direction; **no MediaProjection/mirror source** (system already mirrors natively).
- **Privilege**: Magisk `su -c` per-command (`root/RootShell.kt`). Render Engine never executes `su` — that is `root/`'s job.
- State: `ExternalDisplayController.state: StateFlow<ExternalDisplayState>`.

## Key Directories

```
app/src/main/java/com/example/ar_glass_plus/
  MainActivity.kt             # 控制端 Compose UI（状态卡、启动按钮、注入测试、density、日志）
  display/
    ExternalDisplayController.kt  # DisplayManager 枚举/热插拔监听/启动眼镜界面
    ExternalDisplayActivity.kt    # 眼镜端 Compose UI（display 移除自动 finish）
    ExternalDisplayState.kt       # Connected/Disconnected sealed interface
  render/
    api/                      # RenderMode, RenderConfig, RenderTarget, RenderBackend, RenderPipeline
    gl/                       # GlRenderBackend + shaders（P2.1 落地中）
    vulkan/                   # README 设计说明 only —— 禁止现在实现
  source/
    FrameSource.kt            # 生产者接口（与 renderer 解耦）
  root/
    RootShell.kt              # su -c 执行器
    InputController.kt        # display-aware tap/swipe/keyEvent
    DisplayDensityController.kt # wm density [-d id]
  ui/theme/                   # Theme.kt, Color.kt, Type.kt (Material 3)
tools/
  deploy.sh                   # 编译+推送+root 安装+启动（AR_DEVICE 必填）
  glasses-state.sh            # 眼镜侦察快照工具
docs/reference/               # 侦察与方案文档
example_app/                  # 参考 APK 分析产物（gitignored）
```

## Rendering backend decision (MUST follow)

> OpenGL ES is the primary rendering backend. All rendering architecture must remain backend-agnostic. Vulkan is reserved as a future backend and must not be implemented until profiling demonstrates a meaningful performance or feature requirement.

- `RenderBackend` (render/api) must never expose backend-specific types.
- No Vulkan for "theoretical performance". Only adopt when: OpenGL profiling shows driver/sync/draw-call bottlenecks (GPU > ~3ms, 16.67ms budget missed), or features need it (depth, reprojection, per-eye complex scenes, compute, multi-pass).
- Current workload (1–few textures, simple shader, 60fps) is light for mobile GPUs — GL is the right tool.

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
| `app/src/main/java/.../render/api/RenderBackend.kt` | 渲染后端抽象（GL 实现，Vulkan 预留） |
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

## Roadmap

```
P0 External Display            ✅   P1 Root/Input         ✅   P1.1 Density  ✅
P2 Render Engine               ← 当前
  P2.0 RenderBackend 抽象      ✅ (api + pipeline + source 接口)
  P2.1 GL external-display 输出 + TestPatternSource
  P2.2 SurfaceTexture / OES 输入
  P2.3 MediaProjectionSource   ✂️ 已砍 —— 系统原生镜像已有，不实现
  P2.4 VirtualDisplaySource（非镜像 App → 纹理）← 战略方向
  P2.5 SBS_DUPLICATE → P2.6 Geometry → P2.7 profiling
P3 RayNeo Hardware（HID/按键/触摸/传感器/display power）
P4 AR Workspace（App surfaces/Cursor/HUD/multi-app）
P5 Advanced Stereo（真 3D/reprojection/depth/distortion → 才评估 Vulkan）
```
