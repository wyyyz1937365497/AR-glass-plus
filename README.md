# AR-glass-plus

将已 root 的 OPPO 平板与 RayNeo Air 4 Pro 组合成 Android 空间工作区实验平台：真实 `3840×1080@60` Full-SBS 输出、最多四个独立应用窗口、双眼校准，并继续向空间射线交互和头部跟踪推进。

[下载 v1.1.0](https://github.com/wyyyz1937365497/AR-glass-plus/releases/tag/v1.1.0) · [目标架构](docs/ARCHITECTURE.md) · [实现历史与问题记录](docs/IMPLEMENTATION_HISTORY.md) · [第三方声明](THIRD_PARTY_NOTICES.md)

> [!IMPORTANT]
> 这是设备与固件绑定的研究原型，不是 RayNeo、OPPO 或 Apple 官方项目。物理 SBS、四窗口和静态立体等能力已分别通过阶段验证，但“真实 SBS + 四个三维窗口 + 空间射线 + 头部跟踪”的最终联合验收仍未完成。

## 最终目标

- Air 4 Pro 稳定工作于 `3840×1080@60` Full-SBS，左右眼各 `1920×1080`；
- 同一会话承载最多四个独立 Android 应用，每个应用拥有独立 `VirtualDisplay` 和 OES 纹理；
- 四个窗口以具有位置、旋转、尺寸和层级关系的三维平面存在；
- 头部姿态实时驱动双眼相机，使窗口相对空间稳定；
- 空间射线完成窗口聚焦、内容点击/滚动、拖动和窗口操作；
- 插拔、切回 2D、App 退后台或异常退出时，可靠释放输入状态、VirtualDisplay、SBS 租约和内核模块。

## 三种显示模式

| 模式 | 画面路径 | 适用场景 |
|---|---|---|
| 原生镜像 | ColorOS → DP Alt Mode → 眼镜 | 普通系统镜像；本项目不采集、不处理镜像帧 |
| 直接外屏界面 | `DisplayManager` → 外屏 Activity | 独立 Compose 外屏界面和诊断 |
| 渲染工作区 | App → VirtualDisplay → OES → GLES → 眼镜 | SBS、三维窗口、校准和空间交互 |

三种模式互补，普通 Compose 界面不会被强制绕行渲染引擎，也不会引入 MediaProjection 重复系统镜像。

## 当前进度

| 阶段 | 状态 | 证据边界 |
|---|---:|---|
| 外屏发现、动态 output id、热插拔 | ✅ | OPD2407 真机闭环 |
| Root 输入、相对触控板、显示感知注入 | ✅ | host 测试与真机 content display 注入 |
| GLES/OES 渲染链与几何映射 | ✅ | host 测试与真机渲染 |
| Air 4 Pro `3840×1080@60` SBS 修复 | ✅ | v6 模块独立真机门；按需加载且可逆 |
| 设置页、真实 SBS 双眼校准、配置持久化 | ✅ | 真实 3840×1080 校准输出与保存/取消事务 |
| 四窗口会话：`N VirtualDisplay → N OES` | ✅ 阶段门 | 四窗口真机验证；尚未与全部最终能力联合验收 |
| 静态空间 quad 与双眼投影 | ✅ 阶段门 | 静态相机与标定数学验证 |
| 空间射线、窗口 chrome、交互状态机 | 🟡 | host 测试通过，尚未接入运行时 pointer 路径 |
| Air 4 Pro 头部姿态源 | ❌ | `GlRenderBackend` 仍从 `SpatialCamera.STATIC_HEAD` 启动 |
| 最终联合门 | ❌ | 尚缺头部跟踪、空间输入接线和同会话综合验收 |

这里的“✅ 阶段门”不等于最终产品能力。完整证据边界见 [实现历史](docs/IMPLEMENTATION_HISTORY.md)。

## 系统结构

```text
平板控制端
  │  应用选择 / 窗口管理 / 触控板 / 模式切换 / 双眼校准
  ▼
WorkspaceController
  ├─ Window 1 ─ VirtualDisplay ─ OES texture
  ├─ Window 2 ─ VirtualDisplay ─ OES texture
  ├─ Window 3 ─ VirtualDisplay ─ OES texture
  └─ Window 4 ─ VirtualDisplay ─ OES texture
                                    │
HeadPoseSource ─ SpatialCamera ─────┤  当前尚待接入
SpatialPointer ─ Ray/HitTest ───────┤  当前尚待运行时接线
                                    ▼
                         GLES Spatial Compositor
                                    │
                         3840×1080 Full-SBS
                                    ▼
                            RayNeo Air 4 Pro
                                    ▲
App ─ SbsDisplayModeCoordinator ─ root ─ ar-glass-dpctl
                                    │
                         HID 3D 命令 + v6 kprobe 模块
```

显示角色必须严格区分：

- `tabletDisplayId = 0`：平板控制屏；
- 动态 `outputDisplayId`：Air 4 Pro 物理外屏；
- 每窗口一个动态 `contentDisplayId`：隐藏 VirtualDisplay 和输入目标。

## 兼容范围与警告

当前 SukiSU 模块仅固定到以下组合：

- OPPO Pad OPD2407（`OP615AL1`）；
- 内核 `6.1.128-android14-11-o-g415ded6ed906`；
- ColorOS build `V.2158010-2`；
- RayNeo Air 4 Pro USB `1bbb:af50`；
- SukiSU Ultra / KernelSU 模块环境。

> [!CAUTION]
> 不要把 `sukisu-module/` 安装到其他设备、固件或内核。该模块使用设备绑定的运行时 kprobe 修复；版本不匹配可能导致黑屏、显示异常或内核崩溃。它不会刷写分区或替换 stock 模块，并在开机时保持 inert，但 root 与内核级操作仍有风险。

## 快速安装

1. 从 [v1.1.0 Release](https://github.com/wyyyz1937365497/AR-glass-plus/releases/tag/v1.1.0) 下载：
   - `AR-glass-plus-v1.1.0-release.apk`
   - `ar_glass_plus_dpfix-1.0.0-v6.zip`
2. 在 SukiSU Ultra Manager 中安装模块 ZIP，并重启一次以启动 watchdog。显示修复不会在开机时自动加载。
3. 安装 APK，首次运行时授予 root 权限。
4. 连接 Air 4 Pro。设置页可启用 ColorOS 投屏窗口的精确自动确认。
5. 先进入“设置 → 双眼 SBS 校准”，以真实佩戴姿势完成眼序、IPD、左右眼投影中心和 FOV 调节并保存。
6. 返回工作台，选择 SBS/立体模式并启动渲染工作区。

Release APK 使用当前开发设备证书签名，只用于本项目开发设备，不代表生产发布签名。

## 从源码构建

环境基线：Gradle Wrapper `9.5.0`、AGP `9.3.1`、Android SDK/target `37`、minSdk `24`、Java 11 字节码目标。依赖版本统一维护在 `gradle/libs.versions.toml`。

```bash
# Host 单元测试与 Debug APK
./gradlew testDebugUnitTest assembleDebug

# 生成 SukiSU Ultra 模块 ZIP
./tools/build-sukisu-module.sh

# 指定唯一设备后，构建、上传、root 安装并启动
export AR_DEVICE=192.168.0.102:34271
./tools/deploy.sh
```

多条 ADB transport 可能同时存在，所有手动命令也必须显式使用 `adb -s "$AR_DEVICE" ...`，禁止依赖自动选择设备。

## 仓库结构

```text
app/                         Android App、Compose 控制端、渲染与输入
sukisu-module/               Air 4 Pro SBS v6 SukiSU 模块
investigation/
  reverse-engineering/       驱动、模块与参考库逆向材料
  evidence/                  Linux golden 与 OPPO 显示转换证据
tools/                       App/模块构建部署与四窗口测试应用
docs/
  ARCHITECTURE.md             最终目标、系统边界和目标架构
  IMPLEMENTATION_HISTORY.md  实现进度、问题、解决方法与验证记录
build_ko_assest/             本地内核/vendor/历史构建资产；Git 忽略
```

## 下一阶段

1. 验证已保存双眼 profile 在重新佩戴后的重复一致性；
2. 接入可验证的 Air 4 Pro `HeadPoseSource`，驱动每帧 `SpatialCamera`；
3. 将现有 `SpatialInteractionController` 接入真实 pointer → ray → hit → `contentDisplayId` 注入路径；
4. 在同一次真实 3840×1080 会话中完成四窗口、头动、射线操作和异常释放联合验收。

OpenGL ES 是当前主渲染后端。除非真实 profiling 证明存在明确瓶颈或功能需求，否则不会为了理论性能提前实现 Vulkan。

## 文档与许可

- [项目目标与架构](docs/ARCHITECTURE.md)
- [实现历史、问题与解决方法](docs/IMPLEMENTATION_HISTORY.md)
- [SukiSU 模块说明](sukisu-module/README.md)
- [第三方声明](THIRD_PARTY_NOTICES.md)

仓库当前未声明整体开源许可证；不要据此假定代码可以自由再分发。第三方代码和设计来源按各自许可证处理。

开发者：[wyyyz1937365497](https://github.com/wyyyz1937365497)
