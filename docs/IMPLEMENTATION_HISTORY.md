# AR-glass-plus 实现历史、问题与解决方法

更新日期：2026-09-12

## 1. 当前结论

项目的最终目标是：**真实 RayNeo Air 4 Pro 3840×1080 Full-SBS + 四个三维窗口 + 空间射线交互 + 头部跟踪**。

物理 SBS、四路应用内容、多窗口空间渲染、立体标定、Air 4 Pro 头姿和空间交互均已进入真实运行时。2026-09-12 首轮同会话联合门同时覆盖 3840×1080、四个独立内容显示、持续头姿快照、双眼空间光标、跨窗口聚焦/点击和内容拖动，并完成正常停止与 2D 恢复。剩余验收收敛为佩戴状态下的动态头动、空间滚动、窗口 chrome 移动/缩放/旋转，以及拔出和进程异常释放；项目不再处于“空间控制器仅有 host 测试”的状态，但尚不把首轮工程门等同于全部最终体验验收。

| 能力 | 当前状态 | 证据边界 |
|---|---|---|
| 外屏发现、热插拔、动态 id | 完成 | OPD2407 真机验证，重插后 id 会变化 |
| Root 输入、density、App 启动 | 完成 | 真机闭环；ColorOS 跨显示启动使用 root fallback |
| GLES/OES/几何渲染链 | 完成 | host 测试与真机渲染验证 |
| 相对触控板、鼠标注入、光标 | 完成 | 语义状态机和真机 content display 注入闭环 |
| 四窗口会话（N VD → N OES） | 完成联合工程门 | 同一 3840×1080 会话真机验证四个测试 App、content display 34～37 和四个空间 quad |
| 三维 quad 与双眼投影/标定 | 完成工程阶段门 | 真实 3840×1080 校准、佩戴者 profile 保存/复用与 host 数学测试 |
| 空间射线、窗口 chrome、交互状态机 | 运行时已接入并修正空间对齐 | host 回归 + 真机内容点击、双眼光标与内容/chrome 同 pose；滚动和全部 chrome 手势待佩戴体验门 |
| Air 4 Pro 3840×1080@60 SBS | 完成联合工程门 | v6 App + SukiSU 按需加载，并与四窗口、头姿、空间输入同会话运行 |
| 头部跟踪 | 相对磁航向已接入联合工程门 | 原生九轴帧 → root hidraw → 3DoF 融合；磁稳真机激活，绝对罗盘/硬软铁与佩戴多朝向门未完成 |
| 最终四窗口空间工作区 | 首轮联合门通过 | 四窗口、磁稳头姿、内容注入和对齐后的 3D chrome 同会话运行；完整体验与异常矩阵未完成 |

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

### 2026-09-06：独立设置页、双眼校准与投屏确认自动化

- 新增工作台、设置、双眼校准三级控制页面；设置页只展示软件选项、校准入口和开发者 GitHub。
- 指针灵敏度和自动确认开关即时持久化；佩戴者双眼参数采用显式“保存/取消”事务，避免试调值意外覆盖已保存配置。
- 修正双眼投影中心的坐标约定：左、右眼均存为单眼 `1920×1080` 局部坐标，真实 Full-SBS 默认中心分别都是 `(960, 540)`，不再混入右眼的整屏 X 偏移。
- 校准参数扩展为眼序、IPD、左右眼 X/Y 投影中心和垂直 FOV；每项提供滑条、数值输入及上下按钮精调，并在下一帧实时进入渲染器。
- 校准页进入时取得真实 SBS 租约并等待动态 3840×1080 输出；眼镜端绘制分眼箭头、安全框、网格、中心融合目标、圆方比例图和等世界尺寸的近/中/远深度轮廓。
- 根据 OPD2407 实机窗口树捕获实现窄范围自动确认：仅精确匹配 `com.android.systemui` 的“是否开始投屏？”标题、“将本设备屏幕内容投射到外接显示屏”正文及 `android:id/button1` 的“开始”按钮；点击坐标来自实时 bounds，不写死屏幕位置，并以 Air 4 Pro USB VID/PID 和用户开关作为前置门。
- 自动确认请求在进程启动、USB 接入、受控 display 重建和成功切换渲染模式后触发；互斥执行，并合并刚完成点击后的重复扫描。

### 2026-09-10：Air 4 Pro 头部姿态源与运行时相机

- 真机确认 Air 4 Pro（USB `1bbb:af50`、board `0x3a`、固件 `Jan 9 2026`）在 HID interface 0 的 interrupt IN `0x81` 连续发布 64 字节 `0x99 0x65` IMU 帧；实测约 444～500 帧/秒，设备 tick 每 2 ms 增加 20，因此换算基准为每秒 10000 tick。
- 新增可替换 `HeadPoseSource` 和独立 libsu `RootHeadPoseService`。root 进程通过 hidraw 发送 `0x66/0x01`、读取 IMU、停止时发送 `0x66/0x02`；不 claim USB interface、不解绑 `hid-generic`，因此不破坏 `ar-glass-dpctl` 的可逆 2D/3D 控制。
- 姿态链在原生报告频率执行静止陀螺零偏标定、重力方向初始化、陀螺四元数积分和加速度重力校正；输出为佩戴零位相对四元数。偏移 28/32/36 的连续磁场向量后来接入会话相对航向：静止标定建立水平场参考，跟踪时用模长比、水平分量和连续无效计数拒绝干扰，再以限幅低增益修正 yaw。它不等于硬铁/软铁校准后的绝对罗盘，手动“头姿归零”继续保留。
- App 进程以 8 ms 周期取得 root 服务的完整姿态快照，GL 线程只读取内存中的最新 `SpatialCamera`，不执行传感器 I/O；快照超过 100 ms 时标记断流并冻结最后有效相机。
- `GlRenderBackend` 在 `SBS_STEREO` 每帧应用最新头姿，并同时把已保存 profile 的 IPD、左右眼主点和 FOV 用于工作区双眼投影；校准、2D 和 SBS 复制模式继续使用静态相机。

### 2026-09-12：官方 App 逆向、固件能力文档、主页与 P1 眼镜硬件控制

- 逆向官方《雷鸟 XR 眼镜 App》V2.1.1（jadx + capstone 反汇编 `libFFalconXRServer.so`），提取 Air 4 Pro 全部 HID 控制命令（亮度 0x09、音量 0x50、刷新率 0x20/0x21、屏幕距离 0x17、色彩 0x73、滚轮键锁定 0x58、佩戴检测 0x38、音频模式 0x49、DFU 重启 0x66 等），连同九轴/磁力计 API、ATW、出厂标定 flatbuffer 与开放问题整理为 `docs/FIRMWARE_CAPABILITIES.md`。
- 新增 `IRootGlassesControlService` + libsu `RootGlassesControlService`（`:rootglasses` 进程）：复用已验证的 65 字节 hidraw 帧格式（`00 66 cmd value payload`），不 claim/不解绑 interface 0，与头姿服务、`ar-glass-dpctl` 并存；App 侧 `GlassesControlClient` 惰性绑定并序列化命令。
- 平板端改为三标签结构：全宽标准 Material 3 `NavigationBar`（工作台 / 主页 / 设置），校准事务页独占无导航；主页为状态卡和眼镜硬件控制，页头与设置页共用 `PageHeader`。
- 第一批主页控制包含亮度 20 级、音量、滚轮键 2D/3D 锁定和佩戴检测。佩戴者确认亮度、音量可用，并纠正布尔语义：`0x58` 与 `0x38` 均为 1=启用、0=禁用。
- 第二批控制增加亮度保存、60/120Hz、高动态、色彩增强、高亮度模式、三档音频模式、音频保持、面板开关、左右眼交换、保存全部设置和恢复出厂。3D 模式禁用刷新率选择；恢复出厂必须经过二次确认。SBS toggle、bootloader 重启、未知 payload 音频开关和未确定范围的十二项色彩参数不进入普通控制面。
- 第三批根据同一副 Air 4 Pro 的官方设置页收敛剩余安全能力：新增标准 / 电影 / 护眼三档图像模式，严格复现官方 `0x73` 预览、保存、二次保存事务；新增导音鳍 `0x48` 开关，并在开启时禁用音频模式选择。官方未显示、真机无效果的屏幕距离继续排除；SBS toggle、未知 payload 音频开关、DFU 和十二项无范围原始色彩参数继续不暴露。

### 2026-09-12：空间射线运行时接线与首轮四窗口联合门

- `MouseController` 在活动的 `SBS_STEREO` 会话中把相对触控板移动映射到单眼 viewport；`GlRenderBackend` 每帧发布中心头姿相机/投影，并按左右眼投影绘制命中窗口局部点的空间光标。
- `SpatialInteractionController` 现在输出窗口 focus、绝对内容 DOWN/MOVE/UP/CLICK/DOUBLE_CLICK、滚动和位姿更新意图。内容拖动修正为 MOVE 与最终 UP 都使用最新射线命中点，不再复用初始按下坐标。
- `WorkspaceController` 在实际注入前以窗口 id 解析最新 `contentDisplayId`；跨窗口点击先同步释放旧目标、切换输入显示，再执行新目标点击。模式切换、帧不可用、窗口消失和 teardown 均取消空间状态并复位 LEFT/RIGHT/MIDDLE。
- host 回归覆盖绝对移动顺序、空间点击路由、内容拖动最新坐标和跨窗口目标切换。OPD2407 真机首轮联合门同时运行四个测试 App、四个 VirtualDisplay、3840×1080 Full-SBS、持续头姿和空间射线；窗口 1 的 content 34 接收绝对点击，随后跨窗口切到 content 35 并完成 DOWN → 连续 MOVE（`buttons=1`）→ UP。
- 真机复现了模式切换启动后立即离开工作台导致调用方 Compose scope 取消的窗口：旧实现可能已取得物理租约，却来不及提交 App render mode。`SbsDisplayModeCoordinator` 现在线程互斥后以不可取消区包住 root 事务和对应状态提交；等待互斥锁时的取消仍不会启动操作。


### 2026-09-12：磁力计航向与三维交互对齐

- `RayNeoImuFrameParser` 开始解析主 `0x99 0x65` 帧偏移 28/32/36。磁场单项非有限时只禁用该帧磁修正，不丢弃仍有效的加速度计/陀螺仪数据。
- `RayNeoOrientationFusion` 在静止标定中要求至少四分之三帧具有可用磁场，保存水平场方向和场强基线；运行时仅在场强为基线的 0.7～1.3 倍且水平投影有效时校正 yaw，修正速度限幅为 0.35 rad/s。连续 50 帧拒绝后 UI 从“磁稳”降级为“六轴”，姿态流不中断。
- 空间命中对共面重叠窗口优先当前焦点；内容拖动离开窗口时继续捕获并钳位到内容边缘；右下缩放改为移动相反角并保持内容左上角世界位置不变。
- 真机截图暴露了一个旧渲染错误：CPU 射线/轮廓采用中心坐标 `[-0.5,0.5]`，OES 与 solid shader 顶点却是 `[0,1]`，导致内容、chrome 与命中区域错开。两类 GL 顶点已统一为中心坐标；修复后的 Air 4 Pro 双眼截图中 OES 内容、标题栏、焦点边框和空间光标重新对齐。

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

### 当前最终体验验收缺口

问题：工程联合门已证明四窗口、Full-SBS、磁稳头姿、对齐后的空间内容/chrome 和内容绝对注入能够同会话运行，但尚未在佩戴状态下完成多朝向动态头动、双指空间滚动、标题栏移动、边框旋转、缩放手柄和拔出/进程异常释放的完整矩阵。当前磁力计只建立会话相对场参考，没有完成设备级硬铁/软铁标定，不能作为绝对罗盘。

下一步：按最终验收门逐项执行空间滚动与 chrome 操作，佩戴确认多朝向头动方向、稳定性和延迟，再覆盖拔出、退后台和异常进程释放。同步采集覆盖多个朝向的磁场数据，评估硬铁偏置、软铁尺度和固件 accuracy 语义；在此之前只宣称相对磁稳 3DoF。

## 4. 2026-09-06 仓库整理

### 主仓库保留内容

- `sukisu-module/`：唯一维护的最终 v6 模块及安装控制脚本；
- `investigation/reverse-engineering/`：按二进制、反汇编和分析工具分类；
- `investigation/evidence/`：只保留 Linux golden reference 与 OPPO 2D/SBS 转换的代表性原始证据；
- `tools/`：只保留当前 App 部署、模块打包、远程部署和四窗口测试 App；
- `docs/`：只保留本文件和 `ARCHITECTURE.md`。

旧阶段目录、vendor_boot 镜像、失败构建产物、完整 ramdisk、重复模块、临时交接文档和旧 `tools/rayneo/` 已移出主仓库，集中到：

```text
/home/wyyyz/WS/AR-glass-plus/build_ko_assest/repository_cleanup_20260906/
```

其中保留了原始的阶段式 `investigation_by_stage/`、旧文档、旧 RayNeo 工具、设计草稿、参考 APK/反编译目录、OTA 和项目压缩备份，必要时可以人工取回。

### 上级目录资产迁移

下列项目相关资产已从 `/home/wyyyz/WS/` 移到当前仓库根目录的 `build_ko_assest/`，名称保持不变。该目录约 21 GB，仅作本地资产仓库并由 Git 忽略：

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

按要求没有修复内部依赖。已知 `build_ko_assest/android_kernel_modules_and_devicetree_oneplus_mt6897` 内仍有绝对符号链接指向旧位置，包括 `/home/wyyyz/WS/android_kernel_oneplus_mt6897`、`/home/wyyyz/WS/opd2407-1601-out/...`、`/home/wyyyz/WS/prebuilts-build-tools` 和 `/home/wyyyz/WS/prebuilts-kernel-build-tools`。因此这些历史构建树当前仅作为资产归档；若再次使用，需要统一改到仓库内 `build_ko_assest/` 的新路径并重新检查 overlay/bazel 输出。

## 5. 验证记录

- 2026-09-06，在提交 SBS App/模块集成前执行 `./gradlew testDebugUnitTest`：构建成功。
- 2026-09-06，整理完成后再次执行 `git diff --check`、`./gradlew testDebugUnitTest` 和 `./tools/build-sukisu-module.sh`：全部成功，生成 v6 安装 ZIP。
- 2026-09-06，发布准备阶段执行 `./gradlew testDebugUnitTest assembleRelease`：host 测试与 release lint/build 成功；release APK 使用本机 Android debug 证书签名以供当前开发设备直接安装，不作为生产签名。
- 2026-09-06，设置/校准改造后执行 `./gradlew testDebugUnitTest assembleDebug`：新增坐标缩放、参数边界、FOV 转发和精确对话框匹配测试全部通过。
- 2026-09-06，最终回归中 OPD2407 + Air 4 Pro 从 `1920×1080` 的动态 output 21 切换到 `3840×1080@60` 的动态 output 22；自动化从实时候选按钮 bounds 得到 `(1625,1120)` 并确认投屏，紧接着的重复请求被合并，`GlRenderBackend` 以 `CALIBRATION` 模式初始化且绘制 60 个校准 quad。退出后动态 output 23 恢复 `1920×1080`，模块卸载且租约为空。该记录证明真实 SBS 校准链、自动确认和恢复闭环，不代表佩戴者主观视觉校准已经通过。
- 2026-09-06，v1.1.0 发布构建继续附带相同的已验证 v6 SukiSU 模块；本次版本未改动内核模块，只新增 App 侧设置、校准和投屏确认功能。
- 2026-09-10，OPD2407 + Air 4 Pro 从动态 output 4 的 1920×1080 切到动态 output 5 的 3840×1080@60，启动一个真实应用窗口后，root 头姿服务完成静止标定并持续向 `GlRenderBackend` 提供四元数。佩戴者确认水平头动方向正确且基本回位；手动归零后的 6 秒静止记录保持在约 0.3° 内。停止会话并切回 2D 后，动态 output 9 恢复 1920×1080，设备信息 `sensor_on=0`，v6 模块 unloaded、lease none。
- 2026-09-12，执行 `./gradlew assembleDebug testDebugUnitTest` 成功并部署到 `10.126.126.3:32835`。UI Automator 确认新增显示、音频、高级控制和全宽底栏均出现在真实平板界面；恢复出厂对话框显示风险说明，取消后日志中没有 `0x1D`。
- 2026-09-12，OPD2407 + Air 4 Pro 以 root uid=0 向 `/dev/hidraw1` 完成可逆命令闭环：120→60Hz（`0x21/0x20`）、高动态/色彩增强/高亮度 `1→0`、轻语/环绕→标准、音频保持 `1→0`、面板关→开，并发送两次 L/R 交换；另验证亮度保存 `0x0D` 和保存全部设置 `0x1F`。最终部署后动态 output 29 为 `1920×1080@60`。该记录证明 UI→Binder→root hidraw 写入和可逆恢复，不替代佩戴者对 HDR/色彩、L/R 交换和音频模式效果的主观确认。
- 2026-09-12，佩戴者确认“屏幕距离”直发没有效果。复查官方 V2.1.1：JNI 只把输入规范为 0..3（其他值归为 4），服务端仍是 `SendHidCommand(0x17, value)`，说明发送帧没有第二种格式；更关键的是，官方 App 在同一副已连接 Air 4 Pro 的完整设置页中不显示“屏幕距离”，SDK 也按 `isSupportPanelDistanceAdjust` 能力位决定是否暴露。结论是当前 Air 4 Pro 不支持该控制，而非参数映射错误；已删除主页入口和 `SCREEN_DISTANCE` 常量，UI Automator 确认修正版不再显示无效项。
- 检查官方 App 时观察到其 libusb 会 detach USB interface 0 的 `usbhid`，强停后 hidraw 没有自动恢复；本次已将 `1-1:1.0` 写回 `/sys/bus/usb/drivers/usbhid/bind`，确认 `/sys/class/hidraw/hidraw1/device/uevent` 恢复为 `DRIVER=hid-generic`、`HID_ID=0003:00001BBB:0000AF50`，并以 `0x1B 1→0` 再次完成 root hidraw 写入。这进一步验证本仓库“永不 claim/detach interface 0”的约束。
- 2026-09-12，在同一副 Air 4 Pro 的官方设置页确认图像模式仅显示“标准 / 电影 / 护眼”，官方运行日志分别映射为 mode `0/1/2`；原生 `XRService::PanelColorAdjust` 反汇编确认 HID 格式为 cmd `0x73`、value=op、payload=`[0,arg1,arg2]`。部署本 App 后，点击电影再恢复标准均实际写入 `op=12/255/15` 三命令事务；导音鳍实际写入 `0x48 1→0` 并恢复关闭。UI Automator 同时确认导音鳍开启后音频模式三档变为 disabled，最终设备保持标准图像模式和导音鳍关闭。
- 2026-09-12，停止 App 头姿服务后向 `/dev/hidraw1` 发送 IMU-on，连续采集 1000 个 64 字节 `0x99 0x65` 报告并发送 IMU-off。偏移 28/32/36 的三个 little-endian float 全部为有限非零量化值，均值约 `(40.147, 96.336, -56.454)`，向量模均值约 `118.657`、范围 `118.054～119.539`；1000 帧含 218 个不同向量。设备 tick 差值以 20 为主（另有 18/22 抖动），与 10000 tick/s、约 500 Hz 一致。结论：Air 4 Pro 的主 IMU 报告内确实携带磁场向量；静止单姿态采样不能证明可用绝对航向，当前不接入融合。
- 2026-09-12，`./tools/deploy.sh` 后在 OPD2407 + Air 4 Pro 的动态 output 33 上完成首轮四窗口联合门：物理输出 `3840×1080@60`，四个测试 App 分别运行在 content display 34～37，控制面显示窗口 4/4、DP fix active、头姿跟踪。眼镜截图同时显示左右眼四个空间 quad；空间光标在两眼按同一世界命中点绘制。射线先向 content 34 注入绝对点击 `(562.7,367.6)`，再跨窗口切到 content 35 注入点击 `(573.8,365.3)`；双击保持拖动产生 DOWN、连续带 `buttons=1` 的 MOVE 和使用最新坐标的 UP。停止后窗口 0/4、头姿 Inactive、root 服务发送输入复位；四个测试进程均退出。切回 2D 后动态 output 38 为 `1920×1080`，模块 unloaded、lease none。
- 2026-09-12，针对模式切换调用方取消新增回归：host 测试在 kernel acquire 已进入后取消选择协程，仍要求提交匹配的 `SBS_STEREO`。修复版部署后真机点击“立体”并立即离开工作台，root 日志在页面销毁后仍完成 lease acquire，返回工作台显示 `DP fix: active`、立体已选、动态 output 39 为 `3840×1080`，不再停在 activating/2D。随后正常切回 2D，模块 unloaded、lease none、`dp_mode=1920x1080`。
- 2026-09-12，磁力计与空间交互修正版执行 `./gradlew testDebugUnitTest assembleDebug` 成功。host 用例覆盖磁场帧解析、静止 yaw 漂移约束、真实旋转不被磁参考抵消、强干扰降级、共面焦点优先、窗口外拖动钳位和左上角锚定缩放。部署到 OPD2407 + Air 4 Pro 后，动态 output 51 为 `3840×1080`，四个测试 App 运行在 content display 52～55，控制面同时显示窗口 4/4、DP fix active、头姿“磁稳”。双眼物理截图确认中心坐标修复后内容、标题栏和焦点轮廓对齐；中心空间射线向 content 52 的 `(668.4,322.7)` 注入 CLICK，root 记录 DOWN/UP 且测试 App 在双眼显示触点反馈。佩戴多朝向和完整 chrome 手势仍未在本次无人移动眼镜的自动门中宣称通过。
