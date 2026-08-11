# Repository Guidelines

## Project Overview

Fresh Android Studio **Empty Activity** scaffold (Kotlin + Jetpack Compose, Material 3) for a root-tablet system tool targeting a USB-connected, Magisk-rooted OPPO tablet. The app is a single-module Compose UI with no business logic yet — the goal is to grow it into a Root Android utility (shell executor, package/window/settings control) with `adb shell su -c` as the privileged backdoor.

Current state: stock template, compiles to a "Hello" scaffold. No git repo initialized yet — the first commit is the natural next step.

## Architecture & Data Flow

Single-activity, pure Compose, no navigation/DI/ViewModel yet:

```
MainActivity (ComponentActivity)
  └─ enableEdgeToEdge()
      └─ setContent { ARglassplusTheme { Scaffold { Greeting(name) } } }
```

- Theme entry: `ARglassplusTheme` in `ui/theme/Theme.kt` — `dynamicColor = true`, falls back to custom purple schemes on Android < 12 (SDK < S).
- Data flow: none. UI is static (`Text("Hello $name!")`). When real features land, expect a `Root Service / Shell Executor` layer invoked from composables via coroutines.
- Device integration happens OUTSIDE the app via adb (see Development Commands); app-side root access is not yet implemented.

## Key Directories

```
app/src/main/java/com/example/ar_glass_plus/
  MainActivity.kt          # entry point + Greeting composable + @Preview
  ui/theme/                # Theme.kt, Color.kt, Type.kt (Material 3)
app/src/main/res/          # strings, themes, colors, launcher assets, backup rules
app/src/test/              # host-JVM unit tests (JUnit 4)
app/src/androidTest/       # instrumented tests (device)
gradle/                    # libs.versions.toml (version catalog), wrapper/
.idea/                     # IDE state (gitignored caches/workspace)
```

## Development Commands

```bash
# Build debug APK
./gradlew assembleDebug                      # → app/build/outputs/apk/debug/app-debug.apk

# Install + launch on the connected tablet
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.example.ar_glass_plus
adb shell monkey -p com.example.ar_glass_plus -c android.intent.category.LAUNCHER 1

# Logs
adb logcat                                   # or: adb logcat --pid=$(adb shell pidof -s com.example.ar_glass_plus)

# Tests
./gradlew testDebugUnitTest                  # local unit tests (no device)
./gradlew connectedDebugAndroidTest          # instrumented tests (device required)

# Root shell on the tablet
adb shell su -c <cmd>                        # Magisk su, shell already authorized
```

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
| `app/src/main/AndroidManifest.xml` | Single exported MainActivity (MAIN/LAUNCHER); no permissions declared |
| `app/src/main/java/.../MainActivity.kt` | App entry point |
| `app/src/main/java/.../ui/theme/Theme.kt` | `ARglassplusTheme` composable |
| `gradle.properties` | `-Xmx2048m`, configuration cache |
| `local.properties` | Machine-specific `sdk.dir`; gitignored — never commit |

## Runtime/Tooling Preferences

- **Runtime**: Gradle 9.5.0 wrapper (`./gradlew`), JDK 25 daemon toolchain pinned in `gradle/gradle-daemon-jvm.properties` (foojay resolver). ⚠️ Mismatch: daemon JVM is 25 but bytecode target is Java 11 — deliberate template default, don't "fix" without reason.
- **Android SDK**: via Android Studio; `compileSdk`/`targetSdk` 37 (API 37 preview), `minSdk` 24.
- **Device**: OPPO OPD2407 (serial `JN9PYDTGUSGUPFOZ`), USB, **Android 16 (API 36)**, Magisk root — `adb shell su -c` works passwordlessly. Always verify `adb devices` shows `device` (not `no permissions`) before instrumented runs; udev rule `/etc/udev/rules.d/51-android.rules` (vendor `22d9`) is in place.
- **IDE**: Android Studio (Gradle sync, Compose preview). CLI (adb/gradle) is the primary workflow.
- **No shell scripts or CI yet** — ad-hoc commands above serve that role. No README.

## Testing & QA

- **Frameworks**: JUnit 4 (local), AndroidX Test (`AndroidJUnitRunner`) + Espresso 3.5.1 + Compose `ui-test-junit4` (BOM-managed) for instrumented.
- **Current tests are template placeholders**: `ExampleUnitTest.addition_isCorrect` (asserts `4 == 2 + 2`), `ExampleInstrumentedTest.useAppContext` (asserts package name). Delete/replace as real code lands.
- **No `testOptions` block**; no coverage gates. Run both suites before shipping behavior: unit via `testDebugUnitTest` (fast, no device), instrumented via `connectedDebugAndroidTest` (needs the tablet).
- Compose UI tests can use `createAndroidComposeRule<MainActivity>()`; the test-manifest artifact is already wired in `debugImplementation`.
- **QA loop**: edit → `./gradlew assembleDebug` → `adb install -r` → relaunch → `adb logcat` for crashes. Root-dependent behavior must be verified on-device via `adb shell su -c` since the host cannot simulate it.
