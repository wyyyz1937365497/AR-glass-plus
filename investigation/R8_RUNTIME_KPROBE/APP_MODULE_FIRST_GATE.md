# App + SukiSU Ultra 首轮真机门

日期：2026-09-06

设备：OPPO Pad OPD2407 / OP615AL1，Android 16 API 36，
`6.1.128-android14-11-o-g415ded6ed906`

眼镜：RayNeo Air 4 Pro，USB HID `1bbb:af50`

## 结论

首轮 App 与 SukiSU Ultra 模块闭环 **PASS**：

1. App 内点击 SBS 会取得 root 租约、加载 v6、发送 RayNeo 3D HID 命令，
   最终建立 3840x1080@60 output display。
2. App 进入后台会发送 2D 命令、reprobe、恢复 1920x1080@60、卸载 v6 并
   删除租约。
3. SBS 活跃时物理拔出眼镜，USB HID 消失后约两秒触发 release；无需 HID 2D
   命令即可卸载 v6 并删除租约。
4. 三条路径均未观察到新 Oops、BUG、kprobe warning 或设备重启。

## 启动与安装门

- SukiSU 模块完成安装后已实际重启平板。
- 重启后 watchdog 由 `service.sh` 启动。
- 开机时 `rayneo_dp_fix_v6` 未加载，租约为空；模块保持 boot inert。
- App 首次调用 root 时由用户在 SukiSU Ultra 中授予权限。

## SBS acquire 证据

干净起点：RayNeo 2D，DRM 1920x1080，模块未加载，无租约。

App 内点击 SBS 后观察到：

```text
state=activating detail=load-kernel-fix
state=activating detail=switch-glasses-3d
glasses-mode=3d
state=activating detail=force-edid-reprobe
state=active detail=3840x1080
```

稳定态：

```text
module=loaded
lease=held
health=ok
dp_mode=3840x1080
whitelist_active=Y
msa_hook_active=Y
```

Android display stack 重建为动态 `outputDisplayId=8`，尺寸
3840x1080@60；本轮开始前的 2D id 是 7，证明实现没有依赖固定 display id。

内核显示链：

```text
mtk_dp_intf_config w 3840, h 1080, clock 297000, fps 60
DP_INTF SBS fixup width=3840 hsw=22 hfp=44 hbp=74
SBS row active for SetMSA
MSA:Htt=4400 Vtt=1125 Hact=3840 Vact=1080, fps=60
```

切换瞬间各有一次 OVL underflow，之后没有持续 underflow 洪水。用户在眼镜中
肉眼确认 SBS 显示正常。

## App 后台释放证据

ADB 向 tablet display 0 注入 Home：

```text
RootShell: ... ar-glass-dpctl release ... app-backgrounded
state=releasing detail=app-backgrounded
glasses-mode=2d
state=inactive detail=app-backgrounded
```

终态：App 进程仍存活，DRM 1920x1080，模块未加载，租约为空，健康状态
inactive；内核记录 stock `dp_plat_limit` 行已恢复。

## 物理拔出释放证据

再次由 App 进入稳定 SBS 后物理拔出：

```text
14:10:18.743  ExternalDisplayController -> Disconnected
14:10:20.759  RootShell -> release ... output-disconnected
14:10:21.067  state=releasing detail=output-disconnected
14:10:21.258  state=inactive detail=output-disconnected
```

终态：RayNeo HID 不存在，DRM disconnected，模块未加载，租约为空，App 进程
仍存活；stock 行已恢复。

## 首轮暴露并修复的问题

### App 找不到 `su`

重启后的 App 进程不会继承 adb shell 的完整 PATH，`ProcessBuilder("su", ...)`
返回 ENOENT。`RootShell.kt` 已改为绝对路径 `/system/bin/su`。修复后 App root
命令实际执行成功。

### 软件 reprobe 被误判为拔出

EDID reprobe 会短暂移除 Android logical output display；只按 DisplayManager
断开计时会让 App 释放自己刚取得的租约。最终判据改为：

- DP logical display 消失但 RayNeo USB HID 仍枚举：受控 reprobe，保留租约并
  主动重新扫描 output display。
- DP logical display 消失且 RayNeo USB HID 也消失：真实物理拔出，两秒后
  release。

该判据在随后 SBS 稳定保持和物理拔出测试中均已实测闭环。

## 尚未扩大解释的范围

- 本门验证的是用户要求的模块加载、SBS 输出、后台释放和拔出释放。
- 未把单次切换 underflow 解释为稳定态故障；稳定态未观察到持续洪水。
- 未在本门新增 Vulkan、MediaProjection 或任何分区写入。
