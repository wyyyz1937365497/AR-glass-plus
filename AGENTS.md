# Repository Guidelines

## Project Overview

Kotlin + Jetpack Compose (Material 3) root utility that turns a Magisk-rooted OPPO tablet into a spatial-workspace host for RayNeo Air 4 Pro over USB-C DisplayPort Alt Mode. The final target is **real Air 4 Pro 3840×1080 Full-SBS + four 3D application windows + spatial ray interaction + head tracking**.

**Three display modes (they complement, never replace each other):**
1. **Native Mirror** — system/ColorOS mirrors the built-in screen to the glasses over DP Alt Mode. AR-glass-plus does NOT participate in image processing; no mirror code exists by design.
2. **AR Workspace** — `ExternalDisplayActivity`: independent Compose UI on the glasses display.
3. **Rendered Display** — `FrameSource → Render Engine → glasses`, only when frame processing (SBS/crop/shader/3D) is actually needed. Ordinary Compose UI must NOT be forced through the render engine.

The two authoritative project documents are `docs/ARCHITECTURE.md` and `docs/IMPLEMENTATION_HISTORY.md`.

Current state: the render/input foundation, four-window session (`N VirtualDisplay → N OES`), static spatial quads, stereo calibration math, hit testing, and the on-demand v6 Air 4 Pro 3840×1080 SBS fix are implemented. Physical SBS and four-window rendering have passed separate on-device gates. **The final combined gate is not complete**: `GlRenderBackend` still starts from `SpatialCamera.STATIC_HEAD`, no runtime head-pose source calls `setCamera()`, and `SpatialInteractionController` is host-tested but not wired into the runtime pointer path.

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
- **FrameSource** (`source/`): producers decoupled from the renderer — the engine only knows "content is written to my input surface". `VirtualDisplaySource` (P2.3) is the strategic content producer: third-party apps run on a hidden VirtualDisplay whose frames feed the OES texture. `SyntheticSurfaceSource` remains as a test producer. **No MediaProjection/mirror source** (system already mirrors natively).

### Display role model (MUST follow)

Three distinct display roles — never conflate them:

```
Display 0          = tabletDisplayId   (tablet control display)
Display N (RayNeo) = outputDisplayId   (physical external display, FLAG_PRESENTATION)
Display M (hidden) = contentDisplayId  (VirtualDisplay, PUBLIC | OWN_CONTENT_ONLY)
```

- RayNeo = **output**; VirtualDisplay = **content**. Logs/variables must name them explicitly (`outputDisplayId` / `contentDisplayId`), never a bare `displayId`.
- `ExternalDisplayController` only ever resolves `outputDisplayId` (RayNeo).
- Content VirtualDisplay flags: `PUBLIC | OWN_CONTENT_ONLY`. **Never** `AUTO_MIRROR` (duplicates system mirror) or `PRESENTATION` (would pollute output discovery).
- `am start --display <contentDisplayId>` via root is the fallback when ColorOS denies `ActivityOptions.setLaunchDisplayId` (observed for `com.android.settings`).
- **Privilege**: Magisk `su -c` per-command (`root/RootShell.kt`). Render Engine never executes `su` — that is `root/`'s job.
- State: `ExternalDisplayController.state: StateFlow<ExternalDisplayState>`.

## Key Directories

```
app/src/main/java/com/example/ar_glass_plus/
  display/                    # physical output discovery, activities, SBS transaction
    sbs/                      # app ↔ root module acquire/release coordinator
  source/                     # FrameSource and per-window VirtualDisplay producers
  workspace/session/          # four-window state, lifetime, launch and focus authority
  render/
    api/                      # backend-agnostic renderer contract
    geometry/                 # pure Kotlin 2D geometry and mapping
    spatial/                  # vectors, matrices, camera, projection, calibration
    gl/                       # GLES 3 compositor and per-eye rendering
    vulkan/                   # design-only placeholder; do not implement yet
  interaction/spatial/        # ray hit test, window chrome, interaction state machine
  input/                      # touchpad semantics, cursor and display-aware injection
  app/                        # launcher app discovery and explicit component launch
  root/                       # the only app-side privilege boundary
sukisu-module/                # final on-demand v6 Air 4 Pro SBS module repository
build_ko_assest/              # local 21GB kernel/vendor/build archive; gitignored
investigation/
  reverse-engineering/        # binaries, disassembly and analysis tools by purpose
  evidence/                   # retained golden and OPPO display-transition evidence
tools/                        # current app/module build, deploy and spatial test apps
docs/                         # ARCHITECTURE.md + IMPLEMENTATION_HISTORY.md only
example_app/                  # README only; local reference payloads are external assets
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
| `app/src/main/AndroidManifest.xml` | Activities, RootService and package visibility |
| `app/src/main/java/.../MainActivity.kt` | 控制端 Compose UI |
| `app/src/main/java/.../display/ExternalDisplayController.kt` | 外部显示发现/热插拔/启动 |
| `app/src/main/java/.../root/RootShell.kt` | `su -c` 执行器（所有特权操作入口） |
| `app/src/main/java/.../render/api/RenderBackend.kt` | 渲染后端抽象（GL 实现，Vulkan 预留） |
| `app/src/main/java/.../workspace/session/WorkspaceController.kt` | 四窗口会话状态与资源生命周期 |
| `app/src/main/java/.../interaction/spatial/SpatialInteractionController.kt` | 空间射线和窗口交互状态机（尚待 runtime 接线） |
| `sukisu-module/` | Air 4 Pro 3840×1080 SBS 最终 v6 模块仓库 |
| `tools/deploy.sh` | 编译+安装+启动闭环 |
| `docs/ARCHITECTURE.md` | 最终目标、系统边界和目标架构 |
| `docs/IMPLEMENTATION_HISTORY.md` | 已完成进度、问题、解决方法和资产迁移记录 |
| `gradle.properties` | `-Xmx2048m`, configuration cache |
| `local.properties` | Machine-specific `sdk.dir`; gitignored — never commit |

## Runtime/Tooling Preferences

- **Runtime**: Gradle 9.5.0 wrapper (`./gradlew`), JDK 25 daemon toolchain pinned in `gradle/gradle-daemon-jvm.properties` (foojay resolver). ⚠️ Mismatch: daemon JVM is 25 but bytecode target is Java 11 — deliberate template default, don't "fix" without reason.
- **Android SDK**: via Android Studio; `compileSdk`/`targetSdk` 37 (API 37 preview), `minSdk` 24. ⚠️ **Device runs API 36 — compileSdk-37-only APIs crash at runtime** (e.g. `Display.isInternal()`; `Display.getName/getUniqueId/getDensityDpi` removed in API 37). Verify every Display API against the API-36 framework before use.
- **Device**: OPPO OPD2407 (serial `JN9PYDTGUSGUPFOZ`), **Android 16 (API 36)**, Magisk root. Primary transport is **wireless ADB** (`adb pair` + `adb connect`, port changes each session) so the USB-C port stays free for the glasses. Glasses: RayNeo Air 4 Pro (`1bbb:af50`), enumerated as external display via DP Alt Mode; HID is used for the 2D/3D vendor command but is not an Android pointer device.
- **IDE**: Android Studio (Gradle sync, Compose preview). CLI (adb/gradle) is the primary workflow.
- **No CI**. No README.

## Testing & QA

- **Frameworks**: JUnit 4 (local), AndroidX Test (`AndroidJUnitRunner`) + Espresso 3.5.1 + Compose `ui-test-junit4` (BOM-managed) for instrumented. Host tests cover geometry, gestures, workspace lifecycle, SBS coordination, stereo calibration and spatial interaction.
- **P0/P1 acceptance (verified on-device 2026-08-11)**: (1) glasses plug → auto-detect external display; (2) tap "启动眼镜界面" → `ExternalDisplayActivity` fullscreen on glasses; (3) unplug → activity auto-finishes, control panel returns, no crash; (4) control-panel tap → `input -d <runtime-id> tap` exit=0. Display id changed 4→5 between plugs — regression tests must never assume a fixed id.
- **QA loop**: edit → `./tools/deploy.sh` → drive UI → `adb -s $AR_DEVICE logcat` (tags: `RootShell`, `ExtDisplayCtrl`, `ExtDisplayAct`). Root-dependent behavior must be verified on-device; the host cannot simulate `su` or the external display.

## Roadmap

```
P0 External Display / P1 Root Input                         ✅
P2 GLES + OES + geometry + relative mouse                   ✅
P3 Air 4 Pro HID mode switch + reversible 3840×1080 v6 fix ✅ independent device gate
P4 App picker + workspace session + four VirtualDisplays    ✅ stage gate
P5 Static 3D quads + stereo calibration                     ✅ stage gate
P5 Spatial hit test + window interaction state machine      ✅ host gate, runtime wiring pending
P6 Air 4 Pro head-pose source                               ❌
P7 Combined physical SBS + 4 3D windows + ray + tracking    ❌ final gate
```

Input routing conventions (MUST follow):
- Input layer never touches GL/OES/VirtualDisplay implementation classes. Since P2.5.2 the input path is RELATIVE: touchpad deltas → cursor in content coordinates → displayId-stamped injected mouse events. No GeometryMapper inverse needed (cursor is already in content space; click injects at the cursor point).
- `RenderLayoutStore.snapshot` is consumed by the renderer only, not the input layer (left over from the P2.5 absolute pad).
- All injected events carry `contentDisplayId` (setEventDisplayId); one inject call per semantic event (MOVE streams per frame; a completed gesture = its final UP).
- Gesture is cancelled if contentDisplayId changes mid-gesture (engine.cancel → DragEnd on the old display); letterbox rejection is N/A for relative mouse (cursor is clamped to content bounds by CursorController).
- Structured log: `MouseCtrl: click button=... at=(x,y)`, `RootMouseSvc: injected action=... -> display N`, `scroll -> REL steps X=... Y=...`.

UI invariants (P2.5.1, HARD constraints — never regress):
- Main layout: `Row { ControlSidebar(260dp, scrollable) + MainPane(fixed touchpad) }`.
- **Left sidebar MAY scroll** (it is a LazyColumn).
- **The touchpad MUST NOT scroll and MUST NOT be inside any scroll container** — it owns its pointer stream entirely (consume() every change). Two-finger scroll in the future injects SCROLL into the content app; it NEVER scrolls the control page.
- Cursor state lives in CONTENT coordinates (`CursorState(x, y, visible, pressed)`), published via `CursorOverlayState`; GL draws it per ResolvedGeometry (SBS → one cursor per eye); input injects directly at the content point (no inverse needed for click).
- Cursor is clamped to content bounds; FILL-cropped regions simply hide the cursor (mapContentToOutput null) without moving its logical position.

Gesture/input conventions (P2.5.2, MUST follow):
- `TrackpadGestureEngine` is an explicit state machine; it ONLY emits semantic `TrackpadGesture`s and knows nothing about displays/backends/cursors. `MouseController` is the single gesture→backend consumer.
- RD mapping: one-finger move=Move, tap=LeftClick, double-tap=LeftDoubleClick, double-tap+hold=LeftDrag, two-finger tap=RightClick, two-finger move=Scroll (V+H wheel), two-finger dbl+hold=RightDrag. Pinch/3-finger NOT implemented.
- **Scroll scaling has exactly ONE conversion point**: the RootService converts finger pixels to wheel detents (`SCROLL_DETENT_PX`). Gesture/Mouse layers pass raw deltas — never re-scale (the client/service double-division bug is an architecture violation).
- **Scroll targets the list zone** (`displayHeight * SCROLL_ZONE_RATIO`), never the content center — top buttons swallow scrolls.
- Move-lock: a gesture that reaches MOVING/SCROLLING can never emit a click afterwards (verified: staggered two-finger lift must stay TWO_PENDING until the last finger lifts, else the right-click is lost).
- **Any teardown MUST release held buttons**: `MouseController.releaseAllButtons()` → `backend.resetInputState()` (LEFT/RIGHT/MIDDLE up); RootService also calls resetInputState() in onDestroy. contentDisplayId change → `engine.cancel()` first (emits DragEnd on the OLD display), then re-target.
- All injected events are displayId-stamped to contentDisplayId via `setEventDisplayId` (OPPO lacks `setInputDeviceDisplayAssociation` — uinput device exists but association is unavailable; injection is the functional path).
- System cursor does NOT enter the hidden VD — the GL cursor overlay is the cursor (not a stopgap).

Geometry conventions (MUST follow):
- Geometry layer: top-left origin, x right, y down, pixels; pure Kotlin — no GL, no VirtualDisplay/DisplayManager. Host-JVM unit-testable (`GeometryTest`, 7 cases).
- GL adapter converts to bottom-left NDC; the GL↔geometry difference lives only in render/gl.
- OES transform (SurfaceTexture.getTransformMatrix) is producer→sampling; Geometry transform is content→output placement. Never conflate; input inverse mapping uses ONLY the geometry result, never the OES matrix.
- `GeometryMapper.mapOutputToContent` returns null outside content (letterbox/crop) — never inject there.

Known behaviors (system): unplugging migrates the RenderDisplay task to the
built-in display and recreates the activity — the activity self-finishes when
`displayId == DEFAULT_DISPLAY` and force-stops the content app so the control
panel returns. VirtualDisplay teardown migrates the content app's task (killed
by the cleanup).

App launching (P4.1, verified on-device):
- Launch to content VD: standard `ActivityOptions.setLaunchDisplayId` is DENIED
  for third-party apps on ColorOS (Permission Denial with launchDisplayId) —
  our own app is allowed. Root fallback: `am start --display <id> -n pkg/cls`.
- **`am start -n <package>` without a class is invalid** ("Bad component
  name"); a positional package fails for apps whose launcher activity lacks
  MAIN/LAUNCHER resolution (e.g. documentsui). Always pass `-n pkg/cls` from
  AppEntry.launcherClassName.
- `am start --display` works for most apps (Play Store, Gallery verified →
  window lands on the content display); **ColorOS's own files manager
  (com.android.documentsui) is FORCED to display 0** — known platform quirk,
  don't chase it.
- App switching reuses the SAME VirtualDisplay: renderer/input never rebuild
  (RootMouseService keeps its target display); the old app's task stays
  backgrounded on the VD.
