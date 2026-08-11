# ROOT_OPTIMIZATION_PLAN — AR-glass-plus（root 增强版副屏工具）

> 目标修正（2026-08-11）：**不重实现 cn.axi.cast，而是利用 root 做功能类似的增强版**。
> 原 app 的能力/限制见 `ARCHITECTURE_RECON.md`；本文件给出 root 优化路线。

## 1. Phase 1.5 实机侦察结论（RayNeo 眼镜 + OPPO OPD2407 / Android 16）

### 硬件拓扑（已实测）

```
RayNeo AR Glasses (vendor 1bbb / product af50)   ← 注意：不是 Viture (13770)
   │ USB-C
   ├─ DisplayPort Alt Mode → 系统枚举为 display 4
   │     "HDMI 屏幕" 1920x1080@60Hz · 213dpi · FLAG_PRESENTATION · state=ON
   └─ HID interface (bInterfaceClass=03, 1 个接口)
         未进入 Android input 系统（EventHub 无此设备）
         无 hidraw 节点（hidraw0/1 = OnePlus Pencil 蓝牙笔）
         → 控制只能 app 侧 UsbManager claimInterface 直接访问
```

### 原 app 在非 Viture 眼镜 + 无 Shizuku 下的行为（实测 logcat）

```
ARCastDiag: Display: connected id=4 MTKDEV 1920x1080 · 60Hz · 213dpi   ← 识别 display 4
ARCastDiag: DisplayDensity: skipped, Shizuku unavailable displayId=4    ← 降级
ARCastDiag: DisplayOverscan: skipped, Shizuku unavailable displayId=4   ← 降级
ARCastDiag: Host: active displayId=4 mode=1920x1080 · 60Hz · 213dpi     ← HostActivity 全屏上眼镜
```

dumpsys window 确认：display 4 唯一可见窗口 = `cn.axi.cast/cn.axi.cast.HostActivity`，frame (0,0)-(1920,1080)。

**关键推论**
1. 眼镜识别不依赖 Viture 私有协议——**标准 DisplayManager 外部显示器即可**，原 app 对任意 DP 眼镜都能显示副屏。
2. Viture USB 增强（native DOF / IMU / 标定 / 云 SLAM）对 RayNeo 完全无效，可以忽略。
3. 原 app 的增强全靠 Shizuku；**root 可直接取代且更强**（Magisk su 无需授权弹窗、可做 Shizuku 做不到的事）。
4. 眼镜输入 HID 是空白面——原 app（Viture 路径）也不支持 RayNeo HID。

## 2. 产品形态（功能类似）

| 能力（对齐原 app） | AR-glass-plus 实现路径 | 原 app 方式 |
|---|---|---|
| 副屏显示 | 检测外部 display（`DisplayManager.getDisplays(FLAG_PRESENTATION)`）→ Activity `Presentation`/指定 display 全屏 | HostActivity 指定 display |
| 主屏内容镜像 | MediaProjection → VirtualDisplay → GLSurfaceView → 眼镜 | Capture3dSessionService |
| 显示控制（density/overscan/mode） | **root shell**：`wm density` / `wm overscan` / `wm size` / `cmd display` | Shizuku shell（降级跳过） |
| 屏幕电源 | **root**：`input keyevent KEYCODE_POWER` / `cmd power` | Shizuku UserService + 反射 DisplayControl |
| 眼镜按键/触摸板 | **UsbManager claimInterface**（RayNeo HID，需探测 report） | Viture USB msgId（对本眼镜无效） |
| 触摸注入 | root：`input tap/swipe` 或 Accessibility dispatchGesture | Accessibility |
| 应用切换器 | 可选：PackageManager 枚举 + display 上启动 | AppDrawerActivity + Shizuku |

## 3. 技术路径（实现顺序建议）

### P0 — 眼镜显示（必须先通）
1. `DisplayManager` 监听 `ACTION_WIFI_DISPLAY_STATUS_CHANGED` / `DisplayManager.DisplayListener`，识别外部 display（`FLAG_PRESENTATION`）。
2. `ActivityOptions.setLaunchDisplayId(extDisplayId)` 或 `Presentation(context, extDisplay)` 全屏上眼镜。
3. 验证：眼镜看到 UI = 链路通。

### P1 — root 显示控制（核心差异化）
4. `adb shell su -c`（应用内 RootShell 执行器）：
   - `wm density <dpi>` / `wm overscan 0,0,0,0` / `wm size`（per-display 需 `wm --display 4 ...`）
   - `cmd display set-mode 4 10`（1080p60）
   - `input keyevent KEYCODE_POWER`（屏幕电源）
5. 注意：`wm` 命令的 display 参数支持度需实测（`wm help`）；OPPO/Android 16 可能有厂商差异 → 用 `dumpsys display` 验证效果。

### P2 — 内容源
6. 镜像主屏：MediaProjection + VirtualDisplay（复用原 app 思路，但实现自研）。
7. 或副屏独立 UI：直接在眼镜上跑自家 Compose UI（推荐先做这个，最简单且验证 P0/P1）。

### P3 — 眼镜输入（未解项）
8. RayNeo HID：`UsbManager.getDeviceList()` 找 vendor 1bbb/af50 → `claimInterface` → 读 report。
   - 需先做 HID 探测（见 §5 风险）。
   - 备选：先支持平板触摸注入（`input tap`），眼镜按键后置。

## 4. 与既有工程的关系

- 项目仍是 Compose 单模块 app（AGENTS.md 已记录）。
- `tools/deploy.sh` 已支持无线 `AR_DEVICE` 部署。
- root 执行器是新增核心模块：`RootShell.kt`（`Runtime.exec("su -c ...")` + 超时 + 输出解析），后续所有特权功能走它。

## 5. 风险与未解项

| 项 | 状态 | 影响 |
|---|---|---|
| RayNeo HID report 格式未知 | 需实机探测（libusb dump / UsbManager 读取） | P3 输入映射 |
| `wm --display` 厂商支持 | 需实测 OPPO Android 16 | P1 部分命令可能需替代方案 |
| screencap -d 4 不可用（surfaceflinger 未暴露？） | 影响副屏截图/自动化验证 | 用 `dumpsys window` 验证 |
| 原 app 与我们的 app 并存 | 两 app 各自上眼镜，无冲突（各自独立 display 窗口） | 无 |
| MediaProjection 需要 FGS + 用户授权 | 标准流程 | P2 复杂度 |

## 6. 下一步

1. [ ] P0：写 `ExternalDisplay.kt`（检测 display 4 + Presentation 上眼镜）→ 部署验证
2. [ ] P1：写 `RootShell.kt` + 显示控制命令面板 → 验证 `wm --display 4 density`
3. [ ] P3 探测：`UsbManager` dump RayNeo 接口/端点（`dumpsys usb` 无 host 视图时用 libusb 小程序）
4. [ ] 与用户确认：副屏形态（镜像 vs 独立 UI）、输入优先级
