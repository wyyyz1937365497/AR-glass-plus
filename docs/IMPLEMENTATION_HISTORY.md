# AR-glass-plus 实现历史、问题与解决方法

更新日期：2026-09-06

## 1. 当前结论

项目的最终目标是：**真实 RayNeo Air 4 Pro 3840×1080 Full-SBS + 四个三维窗口 + 空间射线交互 + 头部跟踪**。

当前已经分别完成物理 SBS、四路应用内容、多窗口静态空间渲染、立体标定数学和空间命中/交互状态机，但尚未完成它们的运行时整合：头部相机仍为静态值，空间交互控制器仍只在 host 测试中使用，也尚未在一次真实 Air 4 Pro 3840×1080 会话中同时验证四个三维窗口。因此项目处于“关键子能力已具备、最终联合门未通过”的状态。

| 能力 | 当前状态 | 证据边界 |
|---|---|---|
| 外屏发现、热插拔、动态 id | 完成 | OPD2407 真机验证，重插后 id 会变化 |
| Root 输入、density、App 启动 | 完成 | 真机闭环；ColorOS 跨显示启动使用 root fallback |
| GLES/OES/几何渲染链 | 完成 | host 测试与真机渲染验证 |
| 相对触控板、鼠标注入、光标 | 完成 | 语义状态机和真机 content display 注入闭环 |
| 四窗口会话（N VD → N OES） | 完成阶段门 | 真机验证四个测试 App/显示；尚非最终联合 SBS 门 |
| 三维 quad 与双眼投影/标定 | 完成阶段门 | 静态相机渲染与 host 数学测试 |
| 空间射线、窗口 chrome、交互状态机 | 完成算法门 | host 测试；尚未接入运行时 |
| Air 4 Pro 3840×1080@60 SBS | 完成独立真机门 | v6 App + SukiSU 按需加载、释放、拔出闭环 |
| 头部跟踪 | 未完成 | `setCamera()` 无运行时姿态生产者 |
| 最终四窗口空间工作区 | 未完成 | 缺少头部跟踪、空间输入接线和联合真机验收 |

## 2. 实现进度

### 2026-08-11：P0 / P1 / P1.1 外屏与 root 基线

- 通过 `DisplayManager` 动态发现 RayNeo 外屏并将独立 Activity 启动到该屏。
- 验证拔出后 Activity 自动结束，控制面板返回且不崩溃。
- 打通 root `input -d <runtime-id>` 和显示 density 控制。
- 确认 display id 每次连接可能变化，后续实现统一使用运行时 id。

### 2026-08：P2 渲染和输入

- 建立后端无关 `RenderBackend` / `RenderPipeline`，以 GLES 3 为当前实现。
- 建立 OES 输入与 `VirtualDisplaySource`，让第三方应用直接渲染到隐藏内容显示。
- 完成 FIT/FILL/STRETCH、旋转、SBS 区域及正逆向几何映射。
- 从绝对触控板演进为相对触控板 + 内容坐标光标。
- 完成 RD 风格显式手势状态机：移动、点击、双击、左右拖动、右键、水平/垂直滚轮、取消与按键释放。

### 2026-08：P4.1 应用选择

- 使用 `PackageManager` 枚举 launcher App。
- 标准 API 被 ColorOS 拒绝时，通过 root `am start --display` 把明确的 launcher component 启动到 `contentDisplayId`。
- App 切换复用同一 VirtualDisplay，渲染和输入目标无需重建。

### 2026-08-26：P4.2 / P4.7 四窗口基础

提交 `3dd456c` 建立 `WorkspaceSession` / `WorkspaceController`，支持最多四个窗口、每窗口独立 VirtualDisplay 和 OES 输入，并以 2×2 compositor 完成多输入基础闭环。

提交 `adb8da4` 将窗口变为具有 MVP 变换的空间 quad，加入四个颜色/标尺测试 App，用于验证纹理方向、边界和空间布局。

### 2026-08-27：四窗口真机阶段门

提交 `4dedf2f` 修正测试 App package visibility。OPD2407 上实际创建四个测试窗口，内容显示 id 为 36～39；验证窗口位姿调整、输入重定向和 teardown。该次输出采用 overlay 模拟路径，不等同于真实 Air 4 Pro 3840×1080 联合验收。

### 2026-08-27：P5 静态立体与空间交互算法

提交 `889e7f5` 增加双眼标定模型、空间投影、射线命中、窗口标题栏/边框区域和交互状态机。该门使用静态头部相机并通过 host 测试，未连接实时传感器，也未把 `SpatialInteractionController` 接入 Activity 的真实 pointer 路径。

### 2026-08-27～2026-08-28：Air 4 Pro / MTK DP 根因调查

- Linux golden reference 证明 Air 4 Pro 能公布并进入 3840×1080 SBS，而 OPPO/MTK 路径在模式切换后仍受驱动约束。
- 确认 `mediatek-drm.ko` 位于 vendor_boot ramdisk，并完成 stock 模块 ABI/构建环境复现。
- vendor_boot 替换路线最终失败，未作为可用解决方案继续维护；旧 B0/B1/B2 构建工具和阶段文档已从主仓库移出。
- 反汇编定位到 MTK DP 模式白名单、时钟类别、DP_INTF 宽度/时序和 MSA 表之间的不一致：仅让 3840 模式通过白名单会造成 DP_INTF 仍按 1920 编程并触发持续 underflow。

### 2026-09-06：运行时 v5/v6 SBS 修复

最终运行时链路同时完成：

1. 临时允许 3840×1080@60 / 297 MHz 模式；
2. 借用 1080p120 类别选择 297 MHz 时钟；
3. 在公共汇合点恢复 3840 宽度及四像素时钟域的水平参数；
4. 在 SetMSA 前写入真实 4400×1125 / 3840×1080@60 时序；
5. 卸载时恢复被修改的白名单内容并注销 probe。

v5 首先在真机获得稳定可视 SBS；v6 保留该链路并补全可逆恢复。App 与 SukiSU Ultra 的首轮真机闭环验证了：

- App 请求 SBS 后加载 v6、发送 Air 4 Pro 3D HID 命令并得到 3840×1080@60；
- 切入稳定态后未出现持续 underflow；
- App 退后台恢复 1920×1080、卸载模块并删除租约；
- SBS 状态下物理拔出可自动释放；
- 开机时模块保持 inert，不自动加载；
- 不刷分区、不替换设备上的 stock 内核模块。

提交 `7f22b10` 将 App 协调器、root 控制和最终 `sukisu-module/` 仓库纳入 Git。

## 3. 关键问题与解决方法

### MTK 接受 EDID，却无法正确输出 SBS

问题：模式枚举、时钟选择、DP_INTF 编程和 MSA 时序不是同一处逻辑。单点放宽模式或只改 MSA 会得到黑屏或持续 OVL underflow。

解决：v6 运行时模块在限定设备/固件与限定调用条件下完成完整链路修正，同时保留卸载恢复路径。最终模块集中维护在 `sukisu-module/`，相关 stock 输入和反汇编按用途保存在 `investigation/reverse-engineering/`。

### 重复注册 kprobe 导致警告/崩溃风险

问题：失败路径遗漏注销，反复装卸可能触发重新注册警告。

解决：记录每个 probe 的实际注册结果，对所有成功注册项实施对称清理，并把非关键 MSA probe 的失败限制为可诊断错误路径。

### App 重启后找不到 `su`

问题：Android App 进程不继承 adb shell 的完整 PATH，`ProcessBuilder("su", ...)` 返回 ENOENT。

解决：`RootShell` 使用 `/system/bin/su` 的绝对路径。

### Release Lint 误判 libsu RootService

问题：`RootMouseService` 继承 libsu 的 `RootService`，但 Android Lint 的 `Instantiatable` 检查只识别常规 Service 继承路径，导致 `assembleRelease` 被误报阻止。

解决：仅在该 manifest service 节点抑制 `Instantiatable`，不关闭其他 release lint；随后 release 变体、lintVital、APK 签名验证均通过。

### 软件 EDID reprobe 被误判为物理拔出

问题：重探测会短暂删除 logical output display，若只监听 `DisplayManager` 就会释放刚取得的 SBS 租约。

解决：把 logical display 与 Air 4 Pro USB HID 存在性联合判定；HID 仍在时视为受控 reprobe 并重新扫描输出，HID 同时消失才进入拔出延时释放。

### ColorOS 拒绝第三方 App 启动到隐藏显示

问题：`ActivityOptions.setLaunchDisplayId` 对本 App 可用，但第三方 App 常出现 Permission Denial；仅给 package 的 `am start -n` 也不是合法 component。

解决：保存 launcher activity 全名，root 执行 `am start --display <id> -n <package>/<activity>`。ColorOS 自带文件管理器仍可能被系统强制到 Display 0，作为平台限制记录。

### 输入落到错误窗口或 teardown 后保持按下

问题：多窗口中物理输出 id、内容显示 id 和内容坐标容易混淆；目标切换或窗口消失时可能遗留按键状态。

解决：每个注入事件显式标注聚焦窗口的 `contentDisplayId`；目标变化时先取消旧手势，再调用 `resetInputState()` 释放 LEFT/RIGHT/MIDDLE。空间射线接入后仍必须沿用这一约束。

### 当前最终目标缺口

问题：`GlRenderBackend` 仍以 `SpatialCamera.STATIC_HEAD` 启动，`setCamera()` 没有运行时调用者；`SpatialInteractionController` 只出现在测试中。物理 SBS 与四窗口真机门也是分开完成的。

下一步：先建立可验证的 Air 4 Pro 头部姿态源并驱动每帧相机，再把 pointer → ray → 最近窗口 → 局部内容坐标 → display-aware 注入接入运行时，最后执行一次 3840×1080、四窗口、头动、射线操作和异常释放的联合真机验收。

## 4. 2026-09-06 仓库整理

### 主仓库保留内容

- `sukisu-module/`：唯一维护的最终 v6 模块及安装控制脚本；
- `investigation/reverse-engineering/`：按二进制、反汇编和分析工具分类；
- `investigation/evidence/`：只保留 Linux golden reference 与 OPPO 2D/SBS 转换的代表性原始证据；
- `tools/`：只保留当前 App 部署、模块打包、远程部署和四窗口测试 App；
- `docs/`：只保留本文件和 `ARCHITECTURE.md`。

旧阶段目录、vendor_boot 镜像、失败构建产物、完整 ramdisk、重复模块、临时交接文档和旧 `tools/rayneo/` 已移出主仓库，集中到：

```text
/home/wyyyz/WS/build_ko_assest/repository_cleanup_20260906/
```

其中保留了原始的阶段式 `investigation_by_stage/`、旧文档、旧 RayNeo 工具、设计草稿、参考 APK/反编译目录、OTA 和项目压缩备份，必要时可以人工取回。

### 上级目录资产迁移

下列项目相关资产已从 `/home/wyyyz/WS/` 移到 `/home/wyyyz/WS/build_ko_assest/`，名称保持不变：

```text
android_kernel_oneplus_mt6897
android_kernel_modules_and_devicetree_oneplus_mt6897
opd2407-1601-work
opd2407-1601-out
kernel
kernel-6.1
bootimg-tools
prebuilts-build-tools
prebuilts-kernel-build-tools
prebuilts-bazel-linux-x86_64
vendor
vendor-mirror-mt6877
vendor-mirror-mt6895
vendor-mirror-mt6993
vendor-mirror-mtk-vendor
vendor-mirror-sm7675
setup_overlay.sh
llvm.sh
```

按要求没有修复内部依赖。已知 `android_kernel_modules_and_devicetree_oneplus_mt6897` 内仍有绝对符号链接指向旧位置，包括 `/home/wyyyz/WS/android_kernel_oneplus_mt6897`、`/home/wyyyz/WS/opd2407-1601-out/...`、`/home/wyyyz/WS/prebuilts-build-tools` 和 `/home/wyyyz/WS/prebuilts-kernel-build-tools`。因此这些历史构建树当前仅作为资产归档；若再次使用，需要统一改到 `build_ko_assest` 下的新路径并重新检查 overlay/bazel 输出。

## 5. 验证记录

- 2026-09-06，在提交 SBS App/模块集成前执行 `./gradlew testDebugUnitTest`：构建成功。
- 2026-09-06，整理完成后再次执行 `git diff --check`、`./gradlew testDebugUnitTest` 和 `./tools/build-sukisu-module.sh`：全部成功，生成 v6 安装 ZIP。
- 2026-09-06，发布准备阶段执行 `./gradlew testDebugUnitTest assembleRelease`：host 测试与 release lint/build 成功；release APK 使用本机 Android debug 证书签名以供当前开发设备直接安装，不作为生产签名。
