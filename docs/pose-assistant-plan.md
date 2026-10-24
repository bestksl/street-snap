# 姿势助手 v1 方案（T72，2026-10-21）

> Evidence level: **experimental** at v1. 模型/库选型为可验证事实（许可、制品可达、
> API 形态）；真机帧率、骨架贴合精度、关节引导有效性均 **not-verified**，需真机与
> 独立人工标注/实拍闭环后再升级措辞。模拟器（qemu 软渲）数值不外推真机。

## 1. 需求（用户原话要点）

对准一个人 → 屏幕上出现贴合人体的"小火柴人" → 火柴人演示目标摆拍姿势（手举高、
双手举起、手放头上、叉腰、扭腰、仰头、回头等常见女性摆拍姿势）→ 用户跟随；
关节级引导文案（"把右手抬到头侧""重心压到后腿"）。模型可插拔，无模型时功能
仍可进入（演示/手动节奏模式）。APK 内可见可用是硬交付。

## 2. 选型调研

| 方案 | 模型分发 | 体积 | 契合度 | 结论 |
|---|---|---:|---|---|
| **MediaPipe Tasks Vision `PoseLandmarker`** | `.task` bundle 运行时下载，不入 git/APK | lite **5.8 MB** / full 9.4 MB / heavy 30.7 MB（fp16） | 33 点归一化坐标 + visibility/presence + 3D 世界坐标；LIVE_STREAM 异步 API；CPU/GPU delegate；Apache-2.0；aar 来自 dl.google.com（与 CameraX 同源 maven） | **选用** |
| ML Kit Pose Detection | Play Services 内置模型，无法选档/无法旁路 |  Play 依赖重、行为受 Play 版本影响 | 同为 33 点，但违反"模型可插拔/可选下载"约束，无 Play 设备不可用 | 不选 |
| 服务端姿态 | 上传预览帧 | — | 隐私、延迟、需要联网才能用，与"街拍工具"定位冲突 | 不选 |
| 自研轻量化姿态 | 自训练 | — | 训练/标注成本与周期不匹配"明天 APK 可见" | 不选（后续可演进项） |

可达性已实测（2026-10-21，HEAD 200）：

- `com.google.mediapipe:tasks-vision:1.1.1`（dl.google.com/dl/android/maven2，
  latest=1.1.1，含 arm64-v8a / armeabi-v7a / x86_64）。
- `https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_{lite,full,heavy}/float16/latest/pose_landmarker_{lite,full,heavy}.task`
  5.8 / 9.4 / 30.7 MB。

**分发约束**：aar 是构建期依赖（与 CameraX 同类）；`.task` 模型 **不进 git、不进
APK assets**，首次进入姿势助手时显式提示下载（仅 Wi-Fi 建议、可取消），存入
`filesDir`。下载失败/拒绝 → 自动进入演示模式。后续可在设置里改 full 档。

## 3. 架构（可插拔）

```
ImageAnalysis 帧 ──► PoseSource 接口
                     ├─ MediaPipePoseSource （模型就绪时；CPU delegate 兜底）
                     └─ StubPoseSource       （永远"无人"，演示模式）
                   ──► PoseSample(33 points, presence, tsMs)
                          │
                   PoseCoach（纯 Kotlin，零 Android 依赖，可单测）
                     ├─ PoseCatalog：8 个目标姿势的关节条件 + 引导文案
                     ├─ 当前目标评分（0..1）与最短板条件
                     └─ PoseGuideState（达成脉冲/当前提示/节奏）
                          │
                   Compose overlay：检测骨架（贴合火柴人）+ 目标火柴人 + 提示条
```

- `PoseSource` 与现有模型加载模式同构：资源状态 `Unavailable / Downloading /
  Ready / Failed`，未就绪不阻塞 UI。
- `PoseCoach` + `PoseCatalog` 不依赖 MediaPipe 类型（只吃归一化点数组），桌面
  JVM 单测可完全覆盖：合成骨架 → 期望评分/文案/达成。
- 检测只在姿势助手模式开启时运行（分析器按需订阅），退出即释放，不增加普通取景
  的耗电/时延。
- MediaPipe 异常/时间戳倒退等错误降级为 Stub，不崩溃。

## 4. 姿态库（v1，8 个）

33 点中使用：鼻 0、左右眼/耳（1–10 中取耳 7/8/眼外角）、左右肩 11/12、左右肘
13/14、左右腕 15/16、左右髋 23/24、左右膝 25/26、左右踝 27/28。左右以**被摄者**
自身为准；前置预览镜像时由 UI 层负责翻转，coach 只看人体坐标系。

| 姿势 | 关键条件（归一化） | 最短板引导示例 |
|---|---|---|
| 单手举高 | 一腕高于头顶；该侧肩-肘-腕趋直（角≥160°） | "再把右手伸直一点，手腕高过头顶" |
| 双手举起 | 双腕均高于肩；双肘角≥150° | "左手再抬高一些，肘不要弯" |
| 手放头上 | 一腕距头部中心 < 头长的 0.9；肘外展 | "右手往头侧靠，像撩头发那样" |
| 双手叉腰 | 双腕在髋带区域（距髋中点 < 躯干长 .35）；双肘外凸 | "双肘再打开，手腕贴到腰侧" |
| 扭腰 | 肩连线相对髋连线的转角 ≥25°（2D 投影角） | "肩膀再转过去一点，胯保持不动" |
| 仰头 | 2D 弱代理（experimental）：(耳中 y − 鼻 y)/耳距，正视时约 −0.2，仰头时正向增大，ramp(−0.20→0.45) | "下巴再抬起来一点" |
| 回头 | 鼻相对双耳中点横向偏移 ≥0.6 倍耳距 | "头转向侧后方，下巴带到肩外" |
| 重心后腿 | 髋中点相对踝中点横移；前膝近直、后膝屈（角差≥25°） | "重心压到后腿，前腿再伸直" |

评分：每条件输出 0..1 连续满足度；姿势总分取加权平均（文档化权重，不藏魔数）；
**达成阈值 0.75** 且连续若干帧确认（复用确认帧思路，防抖）；未达成时提示得分
最低的条件。阈值首版保守，真机标定后再调，不用审美真值以外的来源。

## 5. UI / overlay（保守风格）

- 入口：取景器模式切换（不新增杂乱按钮）；姿势助手开启后：
  - 检测到人：半透明白色骨架 + 关节点**贴合人体**；目标姿势以被摄者肩髋锚点
    叠加琥珀色目标火柴人；底部小字提示当前最短板动作；达成时一次轻脉冲。
  - 未检测到人：虚线轮廓 + "把人放进画面"。
  - 演示模式（无模型）：中央静态/轮播目标火柴人卡片 + "下一个姿势"手动节奏，
    全部姿势可浏览；标注"演示模式 · 未启用实时识别"。
- 火柴人绘制只用线/圆/文字，不加装饰素材；镜像、前置摄像头坐标翻转在一层处理。

## 6. 验收与验证

1. `PoseCoachTest`：每个姿势合成"达标/半成/不符合"三套骨架，断言分数、达成与
   文案；左右手对称；缺关键点（presence 低）时给"看不到手/腿"而不是乱引导。
2. 全量 app/camera/color 单测不回归。
3. AVD 冒烟：姿势助手可进入、下载入口与降级可见、演示模式可轮播、火柴人渲染；
   MediaPipe 在模拟器的检测**只验证管线不崩**，性能与贴合不写成结论。
4. 真机遗留（not-verified）：lite/full 帧率与发热、暗光/逆光/全身裁切鲁棒性、
   引导有效率（需被摄者实拍）、女性姿势库覆盖度（需人工标注照片集评审）。

## 7. 不做（v1 边界）

- 不做人脸/美颜/身份相关任何处理；不保存预览帧；姿态检测全在端侧。
- 不做鸟/动物姿态（用户明确另起项目）。
- 不把模型判断接入构图裁切/快门自动决策；v1 仅视觉引导。
- 不新增未经验证的 SDK 传递依赖；tasks-vision 的传递依赖以锁定版本为准并记录。

## 8. 落地状态（2026-10-21，loaded-only）

- 实现：`app/pose/`（Pose/PoseCatalog/PoseCoach/PoseAssistant/MediaPipePoseSource）+
  `camera/PoseFrameSource`（共用单一分析流，借用 proxy 不 close，120ms 节流）+
  `app/edge/PoseModel{Catalog,Store}`（显式下载、字节数+SHA-256 校验、可删除）+
  `PoseOverlayLayer`（白骨架贴合、琥珀目标人、底部关节引导卡、达成变绿保持 2.2s 自动
  换姿势、无人虚线目标、演示卡片轮播、设置内开关与模型管理）+ 顶部"姿势"pill。
- 默认开启且为**演示模式**（不绑分析流、不下载，零隐私/耗电成本），装上 APK 点开即可见；
  模型安装后自动转 live；live 构造失败/删模型/后台/关闭均回退演示或解绑。
- 模型 pin：`pose_landmarker_lite.task` fp16，5,777,746 B（UI 按 MiB 显示 5.5 MB），
  SHA-256 `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a`。
- 测试：新增 `PoseCoachTest` 28 例（8 姿势 met/halfway/neutral、左右镜像、缺躯干/手臂/
  腿、null 静默、4 帧确认+3 帧解除迟滞、8 姿势轮播与回绕）；全量 app 255 / camera 61 /
  color 32 单测全绿。
- AVD 实测（emulator-5580，仅证管线不代表真机）：演示卡片渲染与"换个姿势"轮播 verified；
  下载进度 UI verified；该 AVD 无外网 DNS，下载失败中文文案+降级演示 verified（真机网络
  路径未测）；手工置入 pin 模型后 live 启动，`libmediapipe_tasks_jni.so` 加载 ok、
  detectAsync 持续处理帧 0 崩溃 0 推理异常、无人时虚线目标人与引导卡正确；虚拟场景无真人，
  **白骨架贴合人体未验证（not-verified）**。
- not-verified 真机遗留不变：帧率发热（lite/full）、暗光逆光、骨架贴合与投影对齐、
  引导有效率、姿势库覆盖度。

### 2026-10-21 补（T78）：混淆包 live 模式被 R8 打回演示的真坑

首版 preview APK 在模拟器与真机上都表现为"模型已安装却永远演示模式"，debug 构建从不复现，
根因是 **R8 fullMode 对 MediaPipe 传递依赖优化过度**，且两道坎串行暴露：

1. `com.google.common.flogger.FluentLogger`（MediaPipe 内部日志库）的静态初始化靠遍历调用栈
   推断 caller 类名；R8 内联/outline 后栈帧丢失，`com.google.mediapipe.framework.Graph.<clinit>`
   抛 `IllegalStateException: no caller found on the stack for: hj0`（经 mapping.txt 反查
   `hj0 = FluentLogger`）。
2. 补 keep 后再炸 protobuf lite：`com.google.protobuf.Any.typeUrl_` 字段被改名，
   `Graph.loadBinaryGraph` 反射序列化 calculator options 时抛
   `Field typeUrl_ for la not found`（`la = com.google.protobuf.Any`）。

修复在 `app/proguard-rules.pro`：除已有的 `-keep com.google.mediapipe.**` 外，再 keep
`com.google.common.flogger.**` 与 `com.google.protobuf.**`（MediaPipe 的 consumer rules
不覆盖这两类失败；APK 代价约 +0.5 MB）。验证方式：混淆包+已安装 .task 模型启动后
logcat 无 `StreetSnapPose` 告警、UI 演示横幅消失进入 `LivePoseLayer`。新 APK
56,673,752 B / SHA-256 `ab5ee2bd921e41e5ea768d102e2eb3ba15ad18ad97c46cfc4ebf75a36c89a32b`
（2026-10-21，提交 20665ae）。全量基线 app 272 / camera 69 / color 32。
排查方法可复用：`app/build/outputs/mapping/preview/mapping.txt` 反查短名→原类名。

### 2026-10-21 补（T79）：实时帧被代际门全部丢弃 + 无人时不渲染火柴人

T78 修完后混淆包不再退回演示，但 live 态下帧**从未到达检测器**：`detectAsync` 与 result
回调零调用，底部卡永远显示姿势 invite（说明 `PoseGuide` 一直为 null）。临时在
`PoseFrameSource.analyze` 入口打日志锁定根因——每帧都是 `active=true generation=3
session=1`，全部被陈旧会话门丢弃：

- `CameraController` 在相机状态 OPEN 抖动时对 source 连调 `pause()` + `resume()`；
- 旧实现两者都 `generation++`（1→3），而 analyzer lambda 捕获的 session 是 `activate()`
  返回的 1，且 analyzer 并未重绑；`analyze` 的 `generation != session` 门此后永久失败。
- 对照 `CompositionFrameSource`：pause/resume 只切 `active`，generation 仅在
  activate/deactivate（分析器真正重绑）时前进。PoseFrameSource 按此修正，注释写明
  pause/resume 不得 bump generation。

同时修正一处产品逻辑（用户指出"怎么可能先有小火柴人，人是活动的"）：live 层原先在无人
时于画面中央画一个虚线目标火柴人，等于假装人已经站在那里。现改为**只在真正检测到人
（`sample != null && guide.personVisible`）后**才画贴合真人的白骨架与投影到同一身体上的
琥珀目标人；无人时取景器零渲染，仅由底部卡提示"把人放进画面，头到脚都入镜"。

- 测试：新增 `PoseFrameSourceLifecycleTest` 3 例（间隔下界、pause/resume 后 session
  仍有效、deactivate 才推进 generation）；全量 app 272 / camera 72 / color 32。
- AVD 验证（debug 与混淆包各一次，模型已在位）：启动即显示"把人放进画面，头到脚都
  入镜"（证明帧穿过 analyze→MediaPipe→PoseCoach），无演示横幅、无错误日志、画面无
  火柴人；Home 切后台再回来后管线不中断（pause/resume 回归路径）。虚拟场景无真人，
  **白骨架贴合与目标人投影到真人身体仍 not-verified，待真机**。
- 新 APK 56,673,752 B / SHA-256
  `fe640cbab043ae70ee7fea3a3aa125af2351d11f118c6496f1b7b00577b7e158`
  （2026-10-21，覆盖桌面同名交付包）。
