# AR-glass-plus 项目目标与架构

更新日期：2026-09-12

## 1. 最终目标

在已 root 的 OPPO OPD2407 平板上，以雷鸟 RayNeo Air 4 Pro 为真实物理输出，完成接近 Apple Vision Pro 工作空间体验的 Android 原型：

- Air 4 Pro 以 **3840×1080@60 Full-SBS** 工作，左右眼各获得 1920×1080 图像；
- 同一空间会话最多承载 **四个独立 Android 应用窗口**；
- 每个窗口是具有位置、旋转、尺寸和层级关系的三维平面，而不是最终停留在 2×2 平铺；
- 头部姿态持续驱动双眼相机，实现窗口相对空间稳定；
- 指向射线能够命中窗口及其标题栏，完成聚焦、点击、滚动、拖动和窗口操作；
- 插拔、切回 2D、App 退后台或异常退出时，可靠释放输入状态、VirtualDisplay、SBS 租约和内核模块。

只有以上能力在同一次真实 Air 4 Pro 会话中联合通过，才算最终目标完成。物理 SBS、四窗口、静态立体渲染或射线算法分别通过，都只是阶段能力。

## 2. 产品边界

三种显示方式互补，不能相互替代：

1. **原生镜像**：ColorOS 直接通过 DP Alt Mode 镜像平板。项目不采集或处理镜像帧。
2. **直接外屏界面**：将独立 Activity 启动到 Air 4 Pro，用于普通外屏 UI 和诊断。
3. **渲染工作空间**：第三方 App → 隐藏 VirtualDisplay → OES 纹理 → GLES 三维场景 → Air 4 Pro。SBS、三维窗口和空间交互只走这条路径。

不引入 MediaProjection 来重复系统镜像；不修改或刷写系统分区；内核修复必须按需加载且可逆。

## 3. 总体数据流

```text
平板控制面板
  │  应用选择 / 窗口管理 / 触控板 / 模式切换
  ▼
WorkspaceController ────────────────┐
  │                                 │
  ├─ Window 1 ─ VirtualDisplay ─ OES texture
  ├─ Window 2 ─ VirtualDisplay ─ OES texture
  ├─ Window 3 ─ VirtualDisplay ─ OES texture
  └─ Window 4 ─ VirtualDisplay ─ OES texture
                                    │
HeadPoseSource ─ SpatialCamera ─────┤
SpatialPointerSource ─ Ray/HitTest ─┤
                                    ▼
                         GLES Spatial Compositor
                      model × per-eye view × projection
                                    │
                          3840×1080 Full-SBS surface
                                    ▼
                       RenderDisplayActivity on Air 4 Pro
                                    ▲
App ─ SbsDisplayModeCoordinator ─ root ─ ar-glass-dpctl
                                    │
                         HID 3D command + v6 kprobe module
```

## 4. 三类显示角色

| 角色 | 标识 | 用途 |
|---|---|---|
| 平板控制屏 | `tabletDisplayId = 0` | 控制面板、应用选择和触控板 |
| Air 4 Pro 物理输出 | 动态 `outputDisplayId` | 承载 `RenderDisplayActivity` 和最终 SBS 画面 |
| 隐藏内容屏 | 每窗口一个动态 `contentDisplayId` | 第三方 App 的真实 Android 渲染与输入目标 |

硬约束：

- `outputDisplayId` 与 `contentDisplayId` 永远不能混用，也不能在代码或日志中写成含义不明的 `displayId`。
- 物理输出 id 会在重连或模式重建后变化，禁止写死。
- 内容 VirtualDisplay 使用 `PUBLIC | OWN_CONTENT_ONLY`，禁止 `AUTO_MIRROR` 和 `PRESENTATION`。
- ColorOS 拒绝标准跨应用启动时，以 root 执行 `am start --display <contentDisplayId> -n <package>/<activity>`。

## 5. 子系统职责

### 5.1 外屏与 SBS 模式

`display/` 只负责发现 Air 4 Pro、监听热插拔并把 Activity 启动到当前 `outputDisplayId`。`display/sbs/` 负责 2D/SBS 状态事务；所有特权命令仍由 `root/` 执行。

`sukisu-module/` 是当前唯一维护的最终内核模块仓库。v6 模块不在开机时自动生效：App 请求 SBS 时取得租约、发送眼镜 3D HID 命令、加载运行时修复并触发 EDID 重探测；切回 2D、退后台、拔出或 watchdog 判定租约失效时执行释放。它不覆盖 stock 模块，也不写分区。

软件重探测会短暂移除 Android logical display。判断真实拔出时必须同时检查 Air 4 Pro USB HID 是否消失，不能仅依据 `DisplayManager` 的瞬时断开事件。

`SbsDisplayModeCoordinator` 只允许串行事务；等待锁期间仍可取消，但一旦开始 root acquire/release，就以不可取消区完成对应的 render-mode commit。Activity 或 Compose 页面在模式重建期间离开屏幕，不能把物理 SBS 留在 active 而 App 状态停在 activating/2D。

### 5.2 四窗口会话

`workspace/session/` 是窗口和资源生命周期的单一状态源：

- 最大窗口数固定为 4；
- 每个运行窗口拥有独立 `VirtualDisplaySource`、`contentDisplayId` 和 OES 输入；
- 窗口状态保存应用、内容尺寸、三维位姿、焦点和运行状态；
- 切换焦点只改变输入目标和渲染层级，不重建物理输出；
- 关闭窗口必须先释放按键，再停止目标 App、Surface 和 VirtualDisplay。

2×2 tile 仅用于早期多输入闭环和故障诊断；最终渲染模型是四个可独立摆放的三维 quad。

### 5.3 渲染

`render/api/` 定义后端无关接口，`render/gl/` 是当前 GLES 3 实现。每帧流程为：

1. 更新各 OES 纹理；
2. 读取窗口模型矩阵和最新头部姿态；
3. 分别构造左右眼 view/projection；
4. 将同一空间场景绘制到 3840×1080 的左右两个 1920×1080 eye viewport；
5. 绘制窗口边框、焦点、光标或射线反馈。

OES transform 只描述生产者纹理到采样坐标的变换；窗口几何和空间投影是另一层变换，不能混用。

OpenGL ES 是主后端。只有实测 GPU/同步瓶颈或深度重投影、复杂多通道等明确需求出现时才评估 Vulkan；当前不实现 Vulkan。

### 5.4 头部跟踪

`HeadPoseSource` 是可替换的非阻塞姿态契约。当前 Air 4 Pro 实现由独立 libsu `RootHeadPoseService` 保持 `hid-generic` 已绑定的状态下打开 hidraw：发送 IMU-on，解析 `0x99/0x65` 加速度、陀螺仪和磁力计分量，在原生报告频率完成融合，再由 App 进程按显示节奏取得最新完整快照。GL 线程只读内存中的带单调时间戳 `SpatialCamera`，不执行 Binder、文件或传感器 I/O。

姿态链执行静止陀螺零偏标定、重力方向初始化，并把同一静止窗口内的磁场均值投影到水平面，建立本次会话的相对航向参考。跟踪阶段以加速度校正倾斜，以磁场水平分量对 yaw 施加低增益、限幅修正；磁场模长偏离标定值 30%、水平分量退化或连续无效时停止磁校正并继续六轴融合。该参考用于抑制相对 yaw 漂移，不是地磁北向：单位、完整坐标轴响应和硬铁/软铁标定仍未确认，因此 UI 只区分“磁稳”和“六轴”，保留手动归零，不提供罗盘航向。位置始终为零，能力仍是 3DoF，绝不能声称为 6DoF。超过 100 ms 没有新快照时状态转为断流，渲染冻结最后有效相机而不阻塞或跳回错误姿态。

`GlRenderBackend` 仅在 `SBS_STEREO` 每帧应用头姿；校准、2D 和 SBS 复制模式保持静态相机。工作区双眼渲染同时使用已保存 profile 的 IPD、左右眼主点和 FOV。2026-09-12 已在同一次 3840×1080 四窗口会话中观察到磁航向修正活动、持续头姿快照和空间输入；佩戴多朝向动态头动、滚动、全部窗口 chrome 手势和异常退出仍须作为最终体验门继续验证。

### 5.5 空间射线与输入

空间指针产生屏幕采样点或方向，`SpatialHitTest` 结合当前头部相机和投影生成世界射线。交互顺序为：

1. 射线与窗口平面求交并选择最近的可见窗口；距离相同的重叠窗口优先当前焦点，保持与视觉层级一致；
2. 标题栏、缩放手柄和边框分别进入移动、锚定缩放和旋转状态机；
3. 内容区命中转换为窗口局部 UV，再映射为目标 App 的内容像素；拖出窗口后保持捕获并把坐标钳位到内容边缘；
4. 聚焦对应窗口，将鼠标/滚轮/按键事件标注为该窗口的 `contentDisplayId` 后注入；
5. 拖动中窗口或目标消失时取消手势并释放所有按键。

`MouseController` 是触控板语义的唯一消费者：普通模式继续驱动内容坐标光标，`SBS_STEREO` 会话则把相对移动累积为单眼 viewport 采样点并交给 `SpatialInteractionController`。控制器使用渲染线程发布的最新相机/投影执行命中，将窗口局部命中点直接暴露给 `GlRenderBackend`，后者在左右眼分别绘制同一世界点的空间光标。

内容事件进入 `WorkspaceController` 后才以窗口 id 解析当前 `contentDisplayId`。跨窗口操作先取消旧目标手势、释放按键并同步切换输入目标，再注入绝对内容坐标；拖动中的 MOVE/UP 始终使用最新命中点。渲染帧不可用、模式切换、窗口消失和会话停止都会取消空间会话并执行输入复位。

空间 quad 的 CPU 投影、OES 顶点和 solid chrome 顶点统一采用以窗口中心为原点的 `[-0.5, 0.5]` 局部坐标。内容、焦点轮廓、标题栏和缩放手柄必须共享同一个 pose/MVP；禁止一部分使用左上角 `[0,1]` 顶点、另一部分使用中心坐标，否则视觉位置与射线命中会系统性错开。右下缩放以起始窗口平面计算，保持内容左上角的世界位置固定，并在尺寸上下限钳位后同步移动窗口中心。

### 5.6 平板控制面板

控制端分为工作台、设置和双眼校准三个页面。工作台页面整体不滚动：左侧 `260dp` 的配置栏可滚动，右侧操作区和 `TrackpadSurface` 固定；触控板不允许进入任何滚动容器，并消费自己的全部 pointer 事件。光标逻辑坐标始终属于当前内容空间。

设置页集中维护软件选项、校准入口和开发者 GitHub。佩戴者配置通过 `UserPreferences` 本地持久化；滑条编辑实时更新渲染状态，但双眼参数只有在用户明确保存后才覆盖持久配置，取消会恢复进入页面前的已保存值。

双眼校准是独立的真实输出事务：进入页面后请求 SBS 租约，等待动态 `3840×1080` 输出重新出现，再在眼镜上绘制左右眼标识、安全框、网格、固定屏幕中心、世界空间融合目标、比例图形和近/中/远深度轮廓。固定屏幕中心与有限深度世界目标之间存在正常水平视差，校准流程不得要求把它们在每只眼中强行重合。投影中心以单眼 `1920×1080` 局部坐标保存，禁止把右眼中心记为全屏坐标；IPD、双眼 X/Y 主点和垂直 FOV 均支持滑条、直接输入及细调按钮。该流程校正渲染几何和佩戴偏心，不能修复近视、散光或镜片光学畸变。

ColorOS 在眼镜接入或 2D/SBS 切换后弹出的投屏确认窗可由用户在设置页启用自动确认。自动化不是通用点击器：只有 Air 4 Pro 的 USB VID/PID 仍存在，且 SystemUI 包名、标题、正文、正按钮资源 id、按钮文字、可点击状态全部与实机捕获一致时，才按当前 accessibility bounds 计算按钮中心并执行一次 Display 0 root tap；任一条件不符即不操作。短时间内的重复请求会合并，避免连续执行昂贵的 UI dump。

## 6. 生命周期与故障收敛

正常启动顺序：发现 Air 4 Pro → 用户确认或精确自动确认 ColorOS 投屏请求 → 请求 SBS → 等待新的 3840×1080 `outputDisplayId` → 启动渲染 Activity → 创建窗口 VirtualDisplay → 启动应用 → 启动 IMU/静止标定 → 接入姿态和输入。

正常停止顺序相反：取消交互并释放按键 → 停止 IMU 并发送 IMU-off → 停止应用和四个内容显示 → 停止渲染 → 释放 SBS 租约并恢复 2D。

任何中途失败都要回滚已经取得的资源。眼镜拔出后迁移到平板默认屏的渲染 Activity 必须自结束，不能把外屏工作空间留在 Display 0。

## 7. 仓库结构

```text
app/                         Android App、GLES、输入和工作空间实现
sukisu-module/               最终 Air 4 Pro SBS SukiSU 模块仓库
build_ko_assest/             本地内核、vendor、prebuilt 与历史构建资产（Git 忽略）
investigation/
  reverse-engineering/
    disassembly/             MTK DRM 与 USB-DP selector 反汇编
    binaries/                逆向所需 stock 模块、BTF、RayNeo 库
    tools/                   反汇编与协议分析工具
  evidence/
    linux-golden/            Linux 下 2D/3D EDID 与模式参考
    oppo-display-transition/ OPPO 2D/SBS 转换的保留原始证据
tools/                       当前仍使用的 App/模块构建部署工具
docs/ARCHITECTURE.md         本文件：目标、边界和目标架构
docs/IMPLEMENTATION_HISTORY.md 进度、问题、解决方法和资产迁移记录
```

阶段命名的调查目录、旧构建实验和调试文档不再作为仓库结构继续维护。

## 8. 最终验收门

最终验收必须在同一次真实设备运行中同时满足：

- Air 4 Pro 被识别为动态物理输出并稳定运行在 3840×1080@60；
- 左右眼视图内容正确、无持续显示 underflow 或黑屏；
- 四个不同应用同时通过四个独立 `contentDisplayId` 更新；
- 四个窗口在三维空间有独立位姿，双眼视差和遮挡/层级行为正确；
- 实时头部运动改变左右眼 view，并验证可接受的稳定性和延迟；
- 空间射线可选择任一窗口，内容点击/滚动和标题栏拖动均落到正确目标；
- 拔出、切 2D、退后台和进程异常后资源及按键全部释放，输出恢复正常 2D；
- 完整流程可重复执行，不依赖固定 display id。

2026-09-12 首轮联合真机门在动态 output 33 上同时运行 3840×1080@60、四个独立 VirtualDisplay、四个空间 quad、持续头姿快照和空间射线输入；跨窗口点击从 content display 34 切到 35，内容拖动完整产生 DOWN → MOVE（按键保持）→ UP，停止后四个内容进程退出、IMU 关闭，切回动态 output 38 的 1920×1080 后模块 unloaded、lease none。该记录证明运行时接线和同会话工程闭环，不替代上列尚未执行的佩戴动态、滚动、chrome 操作、拔出和异常进程验收。
