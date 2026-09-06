# AR-glass-plus 项目目标与架构

更新日期：2026-09-06

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

目标架构以可替换的 `HeadPoseSource` 向渲染循环发布带单调时间戳的头部位置和四元数姿态。优先接入 Air 4 Pro 可用的传感器数据；若只能使用平板 IMU，它只能作为调试后备，不能作为最终真机门的等价证明。

姿态链必须包含：传感器坐标系转换、佩戴零位校准、方向归一化、时间戳对齐、必要的滤波/预测以及断流降级。渲染线程读取最近的完整姿态快照，不在 GL 帧循环中阻塞传感器 I/O。

当前 `GlRenderBackend` 已有 `setCamera()` 接口，但运行时仍使用 `SpatialCamera.STATIC_HEAD`，尚未接入姿态源。

### 5.5 空间射线与输入

空间指针产生屏幕采样点或方向，`SpatialHitTest` 结合当前头部相机和投影生成世界射线。交互顺序为：

1. 射线与窗口平面求交并选择最近的可见窗口；
2. 标题栏/边框命中交给窗口操作状态机；
3. 内容区命中转换为窗口局部 UV，再映射为目标 App 的内容像素；
4. 聚焦对应窗口，将鼠标/滚轮/按键事件标注为该窗口的 `contentDisplayId` 后注入；
5. 拖动中窗口或目标消失时取消手势并释放所有按键。

`SpatialInteractionController`、窗口 chrome 和命中测试已有 host 侧实现与测试，但尚未连接真实运行时输入和渲染循环，因此不能标记为空间交互完成。

### 5.6 平板控制面板

页面整体不滚动。左侧 `260dp` 的配置栏可滚动；右侧操作区和 `TrackpadSurface` 固定，触控板不允许进入任何滚动容器，并消费自己的全部 pointer 事件。光标逻辑坐标始终属于当前内容空间。

## 6. 生命周期与故障收敛

正常启动顺序：发现 Air 4 Pro → 请求 SBS → 等待新的 3840×1080 `outputDisplayId` → 启动渲染 Activity → 创建窗口 VirtualDisplay → 启动应用 → 接入姿态和输入。

正常停止顺序相反：取消交互并释放按键 → 停止应用和四个内容显示 → 停止渲染 → 释放 SBS 租约并恢复 2D。

任何中途失败都要回滚已经取得的资源。眼镜拔出后迁移到平板默认屏的渲染 Activity 必须自结束，不能把外屏工作空间留在 Display 0。

## 7. 仓库结构

```text
app/                         Android App、GLES、输入和工作空间实现
sukisu-module/               最终 Air 4 Pro SBS SukiSU 模块仓库
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
