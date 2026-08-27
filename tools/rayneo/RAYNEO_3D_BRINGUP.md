# RayNeo Air 4 Pro — 3D 模式逆向与 Android Bring-up 记录

> Gate 3R-H 工作记录（2026-08-27）。目标：Android 平板上使 Air 4 Pro 的
> 3840×1080 Full-SBS 3D 模式正常枚举并点亮。

## 结论速览

| 项 | 状态 |
|---|---|
| 眼镜 USB HID 控制协议 | **已完全破解，工具实测可用** |
| 软件切 3D（无物理按键） | **已实现**（`rayneo_ctl`，眼镜 ACK 并真实切换） |
| Android 端 3840×1080 点亮 | **被 MTK DP 驱动阻塞**（EDID 缓存无失效手段；官方 App 同样黑屏） |
| 可行路径 | 供电 USB-C Hub + 平板重启（冷枚举 3D EDID），待硬件验证 |

## 1. 官方 App 架构（RayNeoXR V2.1.1 逆向）

```
Flutter/Unity UI
  └─ AirApi.switchTo3DMode()                    com.tcl.xr.api
      └─ FxrApi.SwitchTo3DMode()  [JNI]         com.ffalcon.xr.sdk
          └─ LocalSocketClient::SwitchTo3D()    libFFalconXRClient.so
              └─ (unix socket "FFalconXR")
                  └─ XRService::SwitchTo3D()    libFFalconXRServer.so
                      └─ XRUsbController::SendHidCommand(0x06, 0, {})
                          └─ libusb_bulk_transfer(ep=0x01, 64B, 100ms)
```

USB 权限获取：`MonitorService`（前台服务+静音播放器保活）监听
USB_DEVICE_ATTACHED → 匹配 `VID 0x1BBB / PID 0xAF50`（备用 VID 0x3941）→
`UsbManager.requestPermission` → `openDevice().fileDescriptor` →
`FFalconXRServer.EstablishUsbConnection(fd)`（native libusb）。

## 2. HID 线缆协议

设备：`1bbb:af50`，interface 0 class 03 (HID)。
端点：**OUT 0x01 / IN 0x81**（Interrupt，64 字节）。

```
请求 (64B bulk OUT):
  [0]    = 0x66          magic
  [1]    = command       见下表
  [2]    = sub-command
  [3..57]= payload (≤54B)

响应 (64B interrupt IN):
  [0]    = 0x99          magic
  [8]    = 回显 command
  [9..]  = 数据（部分命令返回设备信息：序列号等）
```

### 已确认命令

| cmd | 语义 | 来源 |
|---|---|---|
| 0x06 | SwitchTo3DMode | XRService::SwitchTo3D 反汇编 `mov w1,#6` |
| 0x07 | SwitchTo2DMode | XRService::SwitchTo2D 反汇编 `mov w1,#7` |

其他已见 API（命令码待挖）：SwitchSideBySide、PanelLunaSet/Save、
PanelPowerOn/Off/Swap、SetAudioVolume、SetBrightness、
DisableWheelKeySideBySide、**GetHeadTrackerPose / EnableSlamHeadTracker**
（6DoF 头追 —— 未来 Gate 4B 的现成路径）。

## 3. 工具

`tools/rayneo/rayneo_ctl.c` —— NDK 交叉编译的 root 工具：

```bash
# 编译
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang \
  -O2 -o rayneo_ctl rayneo_ctl.c
# 用法（bus/dev 从 /sys/bus/usb/devices/*/busnum devnum 读取）
adb shell su -c '/data/local/tmp/rayneo_ctl <bus> <dev> <cmd> [sub]'
# 切 3D
adb shell su -c '/data/local/tmp/rayneo_ctl 1 2 6'
# 切 2D
adb shell su -c '/data/local/tmp/rayneo_ctl 1 2 7'
```

实测：3D 命令后眼镜黑屏（= 已进入 3D、等待 3840×1080 信号）；
2D 命令恢复。与物理按键（音量++亮度+）效果一致。

`tools/rayneo/edid_3840x1080_override.bin` —— 由 2D EDID patch 的
3840×1080@60（297MHz，htotal 4400）备用 EDID。

## 4. Android 端阻塞点（Case 3：EDID 不进 display stack）

眼镜活切 3D 后仅发一次 HPD（`state:80, irq:0, uevent:0`），MTK DPTX
驱动不重读 EDID；framework 全程无感。

### 已验证无效的手段

| 手段 | 结果 |
|---|---|
| `echo detect/off/on > .../card0-DP-1/status` | 走 get_modes 缓存回放，无 AUX 读 |
| `cmd display power-reset / disable+enable` | framework 层，无效 |
| debugfs `edid_override`（自制 3840×1080 EDID） | 内核 EDID 变了，但 MTK get_modes 绕过 override |
| debugfs connector `force digital/on/off` | 不触发驱动 HPD 状态机 |
| typec `data_role` 翻转 | 眼镜（简单 sink）拒绝 DR_Swap |
| `usb_dp_selector` unbind/bind | 平板存活，但不产生 HPD 事件 |
| `mediatek-drm-dp` unbind | **平板内核崩溃**（勿再用） |
| 平板重启（眼镜在线） | 冷枚举完成真实 EDID 读，但 USB 掉电使眼镜先回 2D |
| 官方 RayNeo XR App | **同样黑屏**（对照证明平台层问题） |

### 2D 基线数据（1920×1080 EDID）

- 厂商 EDID：TCL "SmartGlasses"，md5 `70f6f0bc8b48d61b6baccebb03aa2c52`
- DRM：card0-DP-1，modes 仅 1920×1080
- dmesg 正常序列：`HPD_CON → NTSTATE_CHECKEDID → READ EDID done`

## 5. 下一步（按优先级）

1. **供电 USB-C Hub / PD 透传扩展坞**（关键硬件）：
   眼镜在 Hub 供电下保持 3D 不掉电 → `rayneo_ctl ... 6` 切 3D →
   重启平板 → 冷枚举读到 3D EDID → 验证 3840×1080 是否进入 display stack。
   这是唯一未验证的无损路径。
2. 若冷枚举成功：编写 `RayNeoDisplayProbe`（监听 onDisplayAdded/Changed，
   识别 3840×1080 + uniqueId，自动 enable + 通知 AR-glass-plus 切
   AIR4PRO_FSBS 校准 profile）。
3. 若冷枚举也失败（3840×1080 timing 被 ColorOS 拒绝）：进入 vendor/HWC
   层（EDID timing 微调 / DRM mode 注入），或考虑 LSPosed 层
   （Skip Display Confirmation 模块思路）处理投影确认框。
4. Head tracking（Gate 4B）：FFalconXRClient 已暴露
   GetHeadTrackerPose/EnableSlamHeadTracker，同协议栈可复用。

## 6. 崩溃记录（防复发）

- unbind `mediatek-drm-dp/11e10000.dp-tx` → 平板 panic 重启。
- detach usbhid 后立即拔插眼镜 → 平板重启（usbfs 竞态）。
  拔插前先确认无进程持有 /dev/bus/usb 设备节点。
