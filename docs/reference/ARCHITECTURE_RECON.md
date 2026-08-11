# ARCHITECTURE_RECON — 副屏·阿西西 (cn.axi.cast v1.1.9) 架构侦察

> 侦察范围：静态（apktool manifest / jadx 2419 类 / 4 个 native .so）+ 动态（root 设备实测）。
> 目的：为独立重实现提供功能/行为规格，**不复制任何源码、资源、字符串**。

## 0. 核心结论（一句话）

**副屏·阿西西 不是网络投屏应用，而是 Viture AR 眼镜的本地"副屏 shell"**：用 MediaProjection / VirtualDisplay 把本机画面镜像进眼镜（USB 连接），配合 Accessibility 手势注入 + Shizuku 特权控制 + 3D SBS 渲染。

**原始假设（PC → 平板投屏协议）被推翻**：全 app 仅 2 个 HTTPS 请求（更新检查 + 赞助配置），**零**流媒体协议、零编解码器、零发现协议、零 protobuf 业务协议。PC 完全不在链路里。

---

## 1. 应用组件

| 组件 | 名称 | 导出 | 作用 |
|---|---|---|---|
| Activity (launcher) | `MainActivity` | **true** | 唯一导出 Activity；眼镜显示主 UI、会话编排 |
| Activity | `CapturePermissionActivity` | false | MediaProjection 授权专用（singleTask，独立 taskAffinity，Android 14+ 模式） |
| Activity | `HostActivity` / `StageHostActivity` | false | 主显示 / 舞台显示宿主 |
| Activity | `AppDrawerActivity` | false | 应用切换器（"切换应用"） |
| Activity | `SettingsActivity` / `AboutActivity` / `DiagnosticsActivity` / `VersionHistoryActivity` / `RuntimeLogFilesActivity` / `CreditsActivity` | false | 设置/关于/诊断 |
| **Service** | `capture.Capture3dSessionService` | false | **FGS type=`mediaProjection`**：MediaProjection 抓屏 + VirtualDisplay（"ARCast 2D to 3D Capture"） |
| **Service** | `ControlAccessibilityService` | false | **输入注入引擎**：dispatchGesture（click/drag/scroll/nudge，`setDisplayId`）、takeScreenshot、TYPE_WINDOW_STATE_CHANGED 包跟踪、悬浮控制 UI |
| Receiver | `androidx.profileinstaller.ProfileInstallReceiver` | true | 库基础设施（忽略） |
| Provider | `rikka.shizuku.ShizukuProvider` | **true** | authority `cn.axi.cast.shizuku`；meta-data `moe.shizuku.client.V3_SUPPORT=true` |
| Provider | `androidx.startup.InitializationProvider` | false | 库基础设施 |

**无任何** VIEW/SEND/cast/DLNA/miracast intent-filter —— 投屏能力全部来自 MediaProjection FGS + Shizuku + Accessibility，不走 intent 面。

## 2. 权限模型（三层特权）

| 层 | 权限/机制 | 用途 |
|---|---|---|
| 普通 | `INTERNET`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`、`POST_NOTIFICATIONS`（运行时，当前未授予） | 网络 + FGS 可见通知 |
| 系统 | `WRITE_SECURE_SETTINGS`（特权权限，运行时授予） | 显示/沉浸设置 |
| **Shizuku** | `moe.shizuku.manager.permission.API_V23` + ShizukuProvider（exported） | 一切特权操作的后端：shell 命令（`Shizuku.newProcess` 反射）、hidden API binder（IWindowManager / IActivityTaskManager / IActivityManager / **IDisplayManager** / **IInputManager**）、Shizuku UserService（屏幕电源） |

配套：`org.lsposed.hiddenapibypass.HiddenApiBypass`（exemptions: `Landroid/hardware/input/`, `Landroid/hardware/display/`, `DisplayInfo`, `DisplayAddress`）。
声明权限：`cn.axi.cast.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (signature)。
`uses-feature: android.hardware.usb.host` (optional)。`uses-library: androidx.window.extensions/sidecar` (optional)。

**重实现含义**：三层特权必须复刻；无 Shizuku 时应优雅降级（app 自带 `rikka.shizuku` + `rikka.sui` 客户端，`sui` 未实际使用 [INFERENCE: root 备选]）。

## 3. 视频链路（本地镜像，无编码/解码）

```
本机屏幕 / app 内容
   │
   ├─ 路径 A：MediaProjection（Capture3dSessionService FGS，mediaProjection 类型）
   │     └─ createVirtualDisplay("ARCast 2D to 3D Capture", w, h, dpi, flags=16, surface)
   │
   └─ 路径 B：VirtualDisplaySessionController（"舞台"模式，MAX_STAGE_SLOTS=3，freeform 多 app 槽）
         └─ VirtualDisplay flags 459 (0x1CB)，surface attach/resize
   │
   ▼
SurfaceTexture-backed Surface
   │  updateTexImage / getTransformMatrix
   ▼
ui/StageProjectionView（GLSurfaceView，external OES 纹理）
   │  draw 委托给 Stereo3dFrameRenderer
   ▼
SBS 3D 合成（side-by-side；可选 AI 深度 provider，本构建 HAS_STEREO3D_AI=false → NoOp）
   ▼
渲染到外部（眼镜）display
```

- **零** MediaCodec / AudioTrack / MediaFormat / MediaMuxer（Java 与 smali 全树搜索）。
- 音频：应用 privateFlag `ALLOW_AUDIO_PLAYBACK_CAPTURE`，允许被捕获，但本版本无音频管道 [INFERENCE: 计划中/眼镜端处理]。
- Stereo3D 可插拔：`com.limelight.stereo3d.Stereo3dBackend` 接口 + 反射查找外部 APK 的 `com.axi.stereo3d.Stereo3dProvider`（本版本缺失 → NoOp 回退）。

## 4. 输入链路

- **手势注入**：`ControlAccessibilityService.dispatchGesture`（`GestureDescription.Builder.setDisplayId`）→ click / drag / scroll / nudge，目标 display 由会话状态决定。
- **物理输入路由**：`ExternalInputRoutingController` — public `InputManager` 枚举 + hidden `IDisplayManager$Stub`（ShizukuBinderWrapper）`bindInputDeviceToDisplay` / `clearInputDeviceBinding`；`dumpsys input` descriptor/port 解析。
- **屏幕读取**：`takeScreenshot(displayId)`（accessibility 权限）。
- **空中鼠标**：`AirMouseController`（传感器倾角，max tilt 0.34 rad）+ `AirMouseBeamOverlayView` / `CursorOverlayView`。
- **触摸板**：`TouchpadProcessor` / `TouchpadScrollProcessor` / `LegacyScrollController` / `TouchpadTuning`（速度预置）。
- 设置键（`screen_control_settings`，实测设备 dump）：`air_mouse_speed_preset`、`scroll_speed_preset`、`move_speed_preset`、`drag_speed_preset`、`cursor_size_preset`、`cursor_style_preset`、`game_menu_enabled`、`keep_screen_on`、`back_auto_focus_enabled`、`scroll_natural_direction`、`external_orientation`、`app_orientation`（共 30+ 键，含 stage layout/background/aspect、stereo3d source/depth、screen-off、density/overscan/rotation）。

## 5. 眼镜连接（"发现机制"与"连接协议"）

**没有 PC，没有网络发现**。连接对象是 Viture 眼镜，传输层是 **USB**：

- `VitureUsbMonitor`：`UsbManager` + `ACTION_USB_DEVICE_DETACHED` / `ACTION_USB_PERMISSION`；**VITURE_VENDOR_ID=13770**，product-ID allowlist 4113..4625。
- `VitureDeviceSession`：会话生命周期，`VitureBridge.nativeInitialize(cacheDir)`。
- `VitureBridge`（JNI，`System.loadLibrary("arcast_viture")`）—— 13 个 native 方法，全部经 libarcast_viture.so **1:1 透传**到 libglasses.so 的 `xr_device_provider_*` API（14 个符号）：
  `nativeCreate/nativeDestroy/nativeInitialize(String)/nativeStart/nativeStop/nativeShutdown/nativeGetDeviceType/nativeIsProductSupportNativeDof/nativeGetDisplayMode/nativeSetDisplayMode/nativeGetNativeDisplayMode/nativeSetNativeDisplayMode/nativeGetNativeMode`

### Native 栈（4 个 .so，仅 arm64-v8a，全 stripped，动态链接）

```
libarcast_viture.so (8KB, JNI 胶水, 13 Java_* 导出)
   └─> libglasses.so (2MB, 硬件 provider, 63 个 xr_* 导出)
         ├─ libusb：UVC MJPEG 相机 1920x1080@30fps + vendor (Nordic nRF) 控制设备
         │    └─ msgId 命令/响应包协议（0x%04X），LongPacketTransfer；IMU 包 0x0307-09；标定 7 类（flatbuffer/yaml）
         ├─ 显示/亮度/音量/DOF 控制
         └─ cpp-httplib + TLS（Cybertrust Japan CA）→ https://cloud.viture.dev/api/v1/glassesbind
         └─> libcarina_vio.so (14MB, Carina VIO/SLAM 引擎 + 云 SLAM 客户端, SDK V2.5.14-viture)
               ├─ OpenCV 4.2.0 imgcodecs；libmediandk (MediaCodec，仅 H264/NV12 字符串)；可选 Hexagon DSP (libdsp_helper.so)
               ├─ nordic_server/client；http_server_* 自有服务
               └─> libcloud_protocol.so (13MB, Envoy 静态链接 + gRPC C-core)
                     └─ cloud_slam_proto.MapService — 24 个 RPC（InitClient, Communicate, GetMap, Relocalization,
                        FeedStereoCamera, FeedImu(Stream), FeedRgbdFrame(Stream), EndFeedRgbdFrame, FeedFrame(Stream),
                        GetFramePose, SaveMap, RecieveMapData, RelocalizationStream, Stream, SyncTimestamp(Stream),
                        TickToc(Stream), GetRegistrationResultStream, Upload, DownloadSubMapMeshFile, EndFeedData）
```

- **传输**：USB（libusb：UVC MJPEG + Nordic msgId 命令）与 TLS 网络（gRPC/HTTP2+HTTP3/QUIC → 云 SLAM；HTTPS → cloud.viture.dev）。无 BT/GATT/AOA/UART/SPI。
- **编解码**：仅 MJPEG（眼镜相机）；H264/NV12 字符串只出现在 VIO 侧（MediaCodec 硬件加速）；无 H265/VP9/AV1/opus/aac/rtsp/webrtc。
- **重实现判断**：4 个 .so **都不需要**。Java 面 = 13 个 VitureBridge 方法；`xr_*` C API 完全导出可见；USB msgId 协议可从零实现（需实机探测包布局/msgId 值/标定 schema）；云 SLAM 是标准 gRPC（Envoy 只是附带依赖，可用任意 gRPC 客户端替代）。若重实现只做"本地副屏"（无云 SLAM/无 6DoF），native 层只需 USB 显示 + MJPEG 相机读取。

## 6. Android 显示路径（系统 API 面）

| 能力 | 实现方式 |
|---|---|
| 外部显示器拓扑 | Shizuku shell：`cmd display` / `wm` / `settings`（DisplayModeManager / DisplayDensityManager / DisplayOverscanManager / DisplayRotationManager / StageImmersiveController） |
| 虚拟舞台 | `VirtualDisplay`（3 槽 freeform；immersive 策略经 settings 删除实现） |
| 3D 抓屏 | `MediaProjection.createVirtualDisplay`（flags=16）+ FGS mediaProjection |
| 物理屏幕电源 | `DisplayPowerUserService`（Shizuku UserService，IDisplayPowerControl AIDL）：`setPowerMode`/`setBrightness` via 反射 `SurfaceControlProxy` + `DisplayControlProxy`（从 `/system/framework/services.jar` 加载 `com.android.server.display.DisplayControl` + `loadLibrary0("android_servers")`） |
| app 启动到副屏 | `AppLauncher`：检查 `activities_on_secondary_displays` feature，经 `ShizukuActivityLauncher`（IActivityManager startActivity） |

**依赖栈**：AndroidX（appcompat/core/fragment/activity/lifecycle/constraintlayout/recyclerview/viewpager2/preference/window-embedding…）+ Material Components。**无 Compose**（经典 View + 自定义 OverlayView）。第三方仅 okhttp3/okio、gson、glide、kotlinx.coroutines（传递）、rikka.shizuku/moe.shizuku、lsposed hiddenapibypass。

## 7. 动态观察证据（2026-08-11，OPPO OPD2407 / Android 16）

- 启动后仅 1 条外部连接：`[240e:…]:48760 → [2606:4700:3031::6815:5467]:443`（Cloudflare 边缘）——即更新检查 HTTPS，无任何 LAN/组播流量。
- `ARCastDiag: Display: disconnected`（未插眼镜时）；`ARCastDiag: Update: local=20 remote=20 interactive=false`。
- 首启生成 `shared_prefs/screen_control_settings.xml`（键值见 §4）、`files/logs/`、`profileInstalled`。`/data/user/0/cn.axi.cast` 无数据库、无凭据文件。
- 版本核对：APK sha256 `02e424b0…cdf225` = 安装版本（versionCode 20 / versionName 1.1.9 / minSdk 31 / targetSdk 35 / arm64），安装器 com.rosan.installer.x.revived。

## 8. 重实现要点（转入 FEATURE_SPEC 的输入）

1. **形态**：本机 MediaProjection/VirtualDisplay → 眼镜（USB 或将来独立传输）。重实现可先做"眼镜显示 shell"，USB 协议从 `xr_*` C API 语义 + msgId 探测实现。
2. **三层特权**：普通 / Accessibility（手势+截屏）/ Shizuku（shell + hidden binder + UserService 屏幕电源）。Shizuku 缺失时降级（仅本机显示）。
3. **显示管道**：VirtualDisplay（舞台多槽）+ MediaProjection（3D 源）双路径，SurfaceTexture → GLSurfaceView external OES → SBS 渲染器 → 外部 display。
4. **Stereo3D SPI**：`Stereo3dBackend` 接口 + NoOp 回退，外部 provider 可选加载。
5. **设置模型**：单 SharedPreferences 文件 `screen_control_settings`，30+ 键全枚举可 1:1 复刻；状态用进程内 SessionStore。
6. **无网络协议负担**：更新检查 + 赞助配置是唯一网络面，重实现可去掉或自建。

## 9. 未解问题（需实机探测）

- USB msgId 包布局、端点号、命令集枚举（`xr_device_provider_*` 符号 + 抓包可还原）。
- 眼镜标定 flatbuffer schema（7 类）。
- 云 SLAM 认证握手（gRPC cloud_slam_proto）——若不做云功能可忽略。
- 显示模式语义（native mode vs display mode 的映射表）。
