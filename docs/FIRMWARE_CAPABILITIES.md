# Air 4 Pro 眼镜固件能力文档（HID 控制面）

来源：官方《雷鸟 XR 眼镜 App》V2.1.1（`RayNeoXR_V2.1.1_cn_release_2026.07.02_14.10.51.apk`）静态逆向（jadx DEX + readelf/capstone 反汇编 `lib/arm64-v8a/libFFalconXRServer.so`），以及 2026-09-12 Air 4 Pro 原始 hidraw 真机采样。
更新日期：2026-09-12。除标注「已验证」的条目外，命令字节均为反汇编静态结论，落地前须真机确认。

## 1. 传输层

- USB 身份：`1bbb:af50`（Air 4 Pro）；官方 App 另匹配 `0483:df11`（STM32 DFU bootloader）、`3941:af50/af51`。
- 控制通道：HID interface 0 interrupt OUT `0x01`（64B 报文）；状态回读 `0x81`；IMU 帧 `0x81`（`0x99 0x65`，约 444–500fps，tick=10000/s）。
- **hidraw 帧格式（本仓库已验证）**：65 字节 `frame[0]=0x00`（无编号 report-id）+ 64 字节载荷 `[0]=0x66（厂商标记）, [1]=cmd, [2]=value, [3..]=payload`。与 `RootHeadPoseService` 已验证的 IMU on/off（`00 66 01/02`）一致。
- 官方 App 通过 UsbManager 授权 + libusb 直连。2026-09-12 真机检查确认其启动后会从 interface 0 detach `usbhid`，强停后也不会自动 rebind；须向 `/sys/bus/usb/drivers/usbhid/bind` 写入 USB interface 名恢复 hidraw。本仓库因此继续使用 root hidraw，绝不 claim 或 detach interface 0。
- 官方实现按 `FXRDeviceInfo.DEVICEFUNCSUPPORT` 能力位（24 个布尔，含 isSupportFps120/isSupportPanelHDR/isSupportAudioSpatialMode/isSupportGryoTempBias…）发命令前协商；对旧固件硬发未知命令的风险未知。

## 2. 命令表（cmd / 语义 / 状态）

| 功能 | cmd | value/payload | 语义备注 | 状态 |
|---|---|---|---|---|
| 进入 3D / 2D | `0x06` / `0x07` | 0 | 官方 SwitchTo3D/SwitchTo2D | 由 v6 SBS 租约协调器覆盖，不单独暴露 |
| 亮度设置 | `0x09` | level（0..19） | 20 级 | 已实现、用户确认 |
| 亮度保存 | `0x0D` | 0 | 持久化当前亮度 | 已实现、root hidraw 写入验证 |
| 面板开 / 关 | `0x0E` / `0x0F` | 0 | 摘镜省电可配合佩戴检测 | 已实现、开关可逆写入验证 |
| 面板 L/R 交换 | `0x12` | 0 | 左右眼画面互换 | 已实现、双次写入验证；画面效果待佩戴确认 |
| 屏幕距离 | `0x17` | 0..3；其他输入由 JNI 归为 4 | 原生 `PanelSetDistance(byte)` 确实直发 cmd `0x17`，但官方 App 在已连接 Air 4 Pro 上不显示该能力，真机直发也无效果 | Air 4 Pro 不支持/不暴露；已从控制面移除 |
| 高动态模式 | `0x18` | 1=启用 0=禁用 | Vision 4000（片上 SDR→HDR） | 已实现、开关可逆写入验证 |
| 高亮度模式（HDR） | `0x1A` | 1=启用 0=禁用 | 官方「高亮度模式」 | 已实现、开关可逆写入验证 |
| 饱和度增强 | `0x1B` | 1=启用 0=禁用 | PanelSetColorEnhance | 已实现、开关可逆写入验证 |
| 恢复出厂 | `0x1D` | 0 | 全部眼镜侧设置复位 | 已实现二次确认；危险命令未自动执行 |
| 保存设置 | `0x1F` | 0 | SaveSettings | 已实现、root hidraw 写入验证 |
| 刷新率 60Hz | `0x20` | 0 | **独立命令**，非 value 参数 | 已实现、写入验证 |
| 刷新率 120Hz | `0x21` | 0 | 仅 2D；3D 模式下 UI 禁用刷新率选择 | 已实现；120→60 可逆写入并恢复 60Hz |
| 音频保持 | `0x25` | 推测 1=启用 0=禁用 | 跨显示模式切换保持音频 | 已实现、可逆写入验证；物理语义待确认 |
| SBS 切换 toggle | `0x30` | 0 | 等价镜腿滚轮键 | 不暴露；会绕过 v6 SBS 租约协调器 |
| 音频开关 | `0x34` | payload 1B | EnableAudio(a,b) | 不暴露；payload 语义未确定 |
| 佩戴检测 | `0x38` | 1=启用 0=禁用 | **已验证**；开关开=1 生效 | 已实现、用户确认 |
| 导音鳍 | `0x48` | 1=启用 0=禁用 | 官方 Air 4 Pro UI 直接暴露；开启后锁定音频模式并限制继续增大音量 | 已实现、1→0 可逆写入验证 |
| 音频模式 | `0x49` | 0=标准 1=轻语 2=环绕 | B&O 调音 | 已实现、三档写入验证；听感待佩戴确认 |
| 音量 | `0x50` | level | UI 当前提供 0..15 | 已实现、用户确认 |
| 滚轮键 2D/3D 切换 | `0x58` | 1=可用 0=锁定 | **已验证**；value=0 时镜腿切换被锁 | 已实现、用户确认 |
| 色彩调整 / 图像模式 | `0x73` | value=op，payload=`[0,arg1,arg2]` | 当前 Air 4 Pro 官方 UI 三档为 0=标准、1=电影、2=护眼；选择时依次发送预览 `(12,mode,mode)`、保存 `(255,1,mode)`、二次保存 `(15,1,mode)`。其余 op 1..11 为白点、增益、饱和、对比度、色温和色调 | 三档图像模式已实现并验证完整写入；十二项原始参数仍不暴露 |
| 屏幕尺寸 | `0x76` | size | 仅 Gemini 系列（deviceType 64/65） | 不适用 Air 4 Pro |
| 重启进 bootloader | `0x66` | arg | 之后 `0483:df11` DFU 枚举 | 危险命令，不在普通控制面暴露 |
| IMU 开 / 关 | `0x01` / `0x02` | 0 | root hidraw，P6 门 | 已实现、真机验证 |
| 固件升级 | — | — | 官方 Android App / WebUSB（ota.rayneo.cn）DFU | 未实现 |

## 3. 传感与跟踪面（FxrApi 只读接口）

- `GetHeadTrackerPose`（3DoF 四元数）、`GetHeadTrackerPose9X`、`GetNineAxisAzimuth`、`GetMagFieldStrength`、`GetMagSensorAccuracy`：官方固件存在九轴和磁力计接口。真机连续采集 1000 个主 `0x99 0x65` 报告后，确认偏移 28/32/36 本身就是三个 little-endian float 磁场分量；单姿态均值约 `(40.147, 96.336, -56.454)`，模长均值约 `118.657`，1000 帧含 218 个不同向量。当前运行时用静止均值建立会话相对水平场参考，并以场强/水平分量门控低增益 yaw 修正；真机已进入“磁稳”状态。单位、多朝向轴向响应、硬铁/软铁标定和固件 accuracy 语义仍未确认，因此这不是绝对罗盘航向。
- `SetGyroBiasTemperTable` + `RegisterGYROBiasWriteBackCallback`（能力位 isSupportGryoTempBias）：陀螺零偏温度补偿表，优于当前静止标定。
- `RegisterVsyncEventCallback` + `AtwUtils::predQuatForATW`：官方做 ATW 姿态预测；GL 后端可借 late-latch 降 M2P 延迟。
- `EnableSlamHeadTracker` / `EnablePlaneDetection` / `GetMobileTrackerPose`：SLAM/6DoF 为 X 系列接口，Air 4 Pro 不适用（产品页确认 3DoF）。

## 4. 开放问题

1. `0x50` 的固件 `maxVolume` 回读值；当前 0..15 控制已由佩戴者确认可用。
2. `0x18`、`0x1A`、`0x1B`、`0x25`、`0x48`、`0x49` 的佩戴者主观效果与持久化行为。
3. `0x73` 十二项原始色彩参数的合法范围和状态回读；当前仅暴露官方 Air 4 Pro UI 明确提供的三档图像模式。
4. `0x81` 状态报告格式，以及 QueryRuntimeState / AcquireDeviceInfo 中的能力位和当前值回读。
5. 主 `0x99 0x65` 报告偏移 28/32/36 磁场向量的单位、完整坐标系、多朝向响应、硬铁/软铁标定和 accuracy 映射；当前只把它用于带干扰拒绝的会话相对 yaw 稳定，完成这些结论前不提供绝对航向。

