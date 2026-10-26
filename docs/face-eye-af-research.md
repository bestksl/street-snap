# 人脸 / 人眼自动对焦调研（T77）

> 证据标注：`verified` / `loaded-only` / `experimental` / `failed` / `not-verified`。
> 本文的真机行为标注以模拟器编译通过（`loaded-only`）为限，未在真机验证的项目一律标 `not-verified`，不从模拟器外推。

## 1. 需求与范围（2026-10-21 语音原话要点）

> "对焦这一块，可以看一下这个人脸识别这个方案。人眼自动对焦嘛，人脸识别……"
> "啊，这个先不做吧，关于鸟相关的，我到时候再起一个项目来做。"

本期范围：

1. 零新依赖先落 Camera2 HAL 自带的人脸 AF（含 FULL 模式的眼部点位能力读取）；
2. 取景器上用细框显示 HAL 当前跟踪的主脸（只显示 HAL 真值，不自研检测器，T11）；
3. 对眼控 AF（以眼睛坐标为对焦点）给出方案调研与依赖代价；
4. 鸟类 / 动物眼部 AF **明确 deferred**：另起项目，本期不做、不留半成品代码。

## 2. 本期已实现（方案 A：HAL 人脸统计，零新依赖）

### 2.1 原理

- Camera2 中 `CaptureRequest.STATISTICS_FACE_DETECT_MODE`：
  - `OFF = 0`：不输出人脸统计；
  - `SIMPLE = 1`：仅人脸外接矩形 + 置信度 + ID；
  - `FULL = 2`：在 SIMPLE 基础上上报 landmark（双眼、嘴、鼻等点位，由 HAL 决定是否填）。
- 设置该请求键后，**3A 库在 `CONTROL_AF_MODE_CONTINUOUS_PICTURE` 下自动把对焦/测光偏向人脸**——是否进一步偏向眼睛完全由厂商 HAL 决定，公开 API 没有"eye AF 模式"开关（`loaded-only`：AOSP 文档与 CDD 如此规定；各厂实际偏向策略 `not-verified`）。
- 对焦点按对焦（CameraX `startFocusAndMetering`）**不动**：用户点按/长按仍走 CameraX 的 AF/AE/AWB metering action，HAL 人脸跟踪与点按对焦在同一 3A 状态机内共存（CameraX 自动取消周期为 4 秒）。
- 帧结果从 Preview 用例的 session capture callback 读 `CaptureResult.STATISTICS_FACES`（`android.hardware.camera2.params.Face[]`），bounds 为 **active-array 传感器像素坐标**（横屏传感器空间），需按 `SENSOR_ORIENTATION` 四角旋转后取外接矩形，归一化到竖屏 0..1。
- 能力查询：`STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES`、`STATISTICS_INFO_MAX_FACE_COUNT`；选档策略 FULL > SIMPLE > 不支持。

### 2.2 代码落点

| 文件 | 内容 |
| --- | --- |
| `camera/.../FaceDetectPolicy.kt` | 纯 Kotlin 策略：模式选择、中文标签、传感器矩形→竖屏归一化（90/270/180/0 四角旋转、越界夹紧、退化/非常规模态返回 null）、主脸取最大面积。单测覆盖。 |
| `camera/.../CameraModels.kt` | `CameraCapabilities.faceDetectMode/maxFaceCount` + `supportsFaceDetection`；`FaceObservation(count, primary, frameTimestampNs)`；`CameraState.liveFaces`。 |
| `camera/.../CameraCapabilityReader.kt` | 读取 face detect modes / max count 并随能力上报。 |
| `camera/.../CameraCapabilityJson.kt` | 能力 JSON 增加 `faceDetection` 块（supported/mode/modeLabel/maxCount）。 |
| `camera/.../CameraController.kt` | ① 人脸检测请求键并入 `applySettings` 的 interop options（`setCaptureRequestOptions` 每次整体替换，必须随 AF 状态一起写）：自动态写 SIMPLE/FULL，**手动对焦（AF_MODE_OFF）写 OFF**；② Preview session callback 每帧读 `STATISTICS_FACES`，200 ms 节流、去重，按会话 generation 防陈旧；③ 切 MF、重绑、解绑时立即清空 overlay。 |
| `app/.../CameraScreen.kt` | 取景 overlay 在点按对焦框后绘制 HAL 主脸细白框（1 dp、alpha .5）；开发者诊断增加"人脸检测 / 人脸统计"两行真值。 |
| `camera/src/test/.../FaceDetectPolicyTest.kt` | 8 个 JVM 单测：选档、标签、四种旋转手算、越界夹紧、退化矩形/非常规方向/空 active array、主脸选择。 |

测试基线：app **272** / camera **69**（+8）/ color **32** 全绿（`verified`，桌面 JVM 单测）。

### 2.3 已知边界（约束与 not-verified）

- **FILL_CENTER 裁切未补偿**：归一化框直接乘全屏尺寸，未按 ViewPort 裁切比做映射；居中主体误差小，贴边脸偏差大。真机贴合度 `not-verified`，代码注释已标 loaded-only。
- **后置限定**：坐标旋转按后置非镜像推导；本应用只绑 `DEFAULT_BACK_CAMERA`，前摄镜像不在范围内。
- **手动对焦下停用**：AF_MODE_OFF 时 HAL 不保证输出人脸统计，切 MF 立即停显；这是接口语义，不是缺陷。
- **眼点未使用**：FULL 模式下 `Face.getLeftEyePosition()` 等只在部分设备、`score > 0` 时有效，本期不读不画；HAL 是否据此驱动对焦环 `not-verified`。
- 待真机清单：多人/侧脸/弱光/逆光下 HAL 上报率与稳定性、FULL 是否真给眼点、框延迟与跳动、厂商对人脸 AF 的实际合焦偏向、点按对焦后回人脸跟踪的恢复节奏。

## 3. 眼控 AF 调研（以眼睛坐标为对焦点；方案 B：未做，附代价评估）

Android **没有公开的眼部对焦 API**。厂商系统相机里的"人眼/动物眼 AF"是私有 3A 扩展（三星、OPPO/vivo 等的 vendor tag 不公开、不跨设备），AOSP 公开面拿不到。要在 app 层实现眼控 AF，只能"自己检测眼睛 → 把眼睛坐标喂给对焦系统"：

1. **检测器**
   - ML Kit Face Detection（`com.google.mlkit:face-detection`）：可输出双眼/耳/嘴/鼻 contour，模型经 Play Services 动态下发；
   - CameraX 有 `androidx.camera:camera-mlkit-vision` 的 `MLKitAnalyzer` 便利封装；
   - 自管方案：ImageAnalysis（YUV，需转 Bitmap 或用实验性 YUV 输入）→ 检测器 → 眼部坐标。
2. **对焦点**：眼睛归一化坐标经 `SurfaceOrientedMeteringPointFactory` 造 `MeteringPoint`，调现有的 `startFocusAndMetering(FLAG_AF)`（或 Camera2 `CONTROL_AF_REGIONS`），按节流节奏（如 300–500 ms）轮询；必须处理与 HAL 人脸 AF、用户点按对焦的优先级互斥。
3. **依赖与产品代价（本期不接受的原因）**
   - 依赖 Google Play Services：无 GMS 设备（部分国行机、出海场景）模型不可用或行为不一致；
   - 模型首次使用可能需下载，带来体积/隐私弹窗/首次延迟；与本项目"零未验证外部依赖、模型 pin URL+SHA-256 显式下载到 filesDir"的纪律不符，需单独立项设计下载与降级；
   - ImageAnalysis 与现有构图分析、pose（T72）共用分析线程，多检测器并发有帧率/发热代价，需预算节流；
   - 自喂对焦点本质是持续 AF trigger，厂商 HAL 对频繁 AF region 更新的响应差异大，可能出现拉风箱；
   - ML Kit 人脸只做人脸（含双眼轮廓），**不做动物/鸟眼**——对街拍里"人"的场景，方案 A 的 HAL FULL 已覆盖一大半收益，方案 B 的增量主要在"明确把瞳孔作为对焦点"，收益与代价不成比例，故本期只调研不落地。
4. 若将来要做，建议形态：默认关闭的实验开关 + HAL 不支持 FULL 时的补充路径 + 无 GMS 时安静降级为方案 A，且复用 T72 pose 的模型下载/校验/节流基础设施。

## 4. 鸟类 / 动物眼部 AF（用户明确 deferred）

- Camera2 公开 API 无人脸以外的主体统计；`STATISTICS_FACES` 不含动物，厂商动物 AF 均为私有扩展。
- 技术路线只能是自携检测器（TFLite 鸟类/动物检测器或大型视觉模型的检测能力）→ 主体/眼部坐标 → AF region 轮询，与构图主体定位（T73 SubjectLocator）是不同的信号链，且需要独立的标注集做真机评测。
- **结论：按用户要求，另起项目时再做，本仓不留相关代码与依赖。**

## 5. 复现与验证入口

- 单测：`./gradlew :camera:testDebugUnitTest`（`FaceDetectPolicyTest` 8 例）。
- 真机验证路径：开发者诊断 → "人脸检测"应显示 `HAL 模式 2 · 含眼部点位`（或 1）与最大张数；对准人脸出现细白框，"人脸统计"显示上报张数；切手动对焦后框消失、诊断显示已停用。
- 能力 JSON：`CameraCapabilities.toJson()` 的 `faceDetection` 块可用于真机众测回收各厂档位。
