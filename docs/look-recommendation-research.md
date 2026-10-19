# 智能影调推荐与「建议有用性」行业调研（T28）

日期：2026-10-19
性质：Web 调研结论文档（不新增依赖、不改代码），为 T29–T37 提供交互依据。
证据边界：全部为公开材料中的产品行为描述，产品版本会变化；频次/置信度数字是行业经验区间而非标准；本文不构成任何审美正确性证据。

## 0. 要回答的问题

1. 基于色彩/光照在取景器里**实时**推荐滤镜/影调时，行业如何避免「建议骚扰」（频率、置信度、试用/撤销）？
2. 构图建议怎样才算「可执行（actionable）」？
3. 这些结论分别支撑 T29–T37 的哪些设计参数？

## 1. 产品事实

### 1.1 拍后编辑器派：推荐可以主动，但位置在「拍后」

**VSCO —「For This Photo」**（2019 上线）

- 端侧推理：SqueezeNet 小模型经 TFLite 部署（Android 走 ML Kit），图片不上传；分类体系由内部摄影专家人工标注建立（art、portrait、vibrant、coastal、nature、architecture、light and shadow、monochrome 等）。
- 触发时机：用户进入编辑器（Edit View）加载图片时推理，被动呈现为 preset 行的一个分类「For This Photo」，与 Favorites / Recent / Featured 并列，不是弹窗、不在取景器。
- 交互：点按才预览效果，给 6 个推荐 preset，强度滑杆可调，可直接离开；上线后成为仅次于「All」的第二常用 preset 分类——**被动入口 + 一键试用**本身就有足够采纳率，不需要实时推送。

**Adobe Lightroom — Recommended / Adaptive Presets**

- Recommended presets 由 Sensei 按图像内容与用户使用习惯给出，风格与强度以轮播呈现（Subtle / Strong / B&W / Cool / Warm / Dark / Bright / Cinematic / HDR），长按看 Before，点缩略图才应用并保留 Amount 滑杆。
- Adaptive Presets（2023 起）用 AI 把一张预设分区作用到天空/主体/背景等区域，并生成**可编辑的蒙版**——AI 的产出始终是可修改、可撤销的人工中间态，不是终局。
- （依据：Adobe 公开发布材料与 Lightroom 内置帮助；本文未留存可引用的具体 URL。）

**Google Photos — 编辑建议卡**

- AI 编辑建议（Enhance / Dynamic 等）只在用户主动打开某张照片的 Edit 后出现；建议卡可逐张点「Remove」关闭；设置中有 Editing suggestions / Personalized suggestions 总开关；生成式（Gemini / Ask Photos）功能是**独立 opt-in**，不开就保持经典界面，可随时撤销授权。
- 负面证据同样重要：其官方社区长期存在大量「suggestions 太烦，怎么彻底关掉」的帖子——**即使有关闭入口，频次与时机不当依然显著伤信任**。

### 1.2 拟物胶片派：全部拍前手动选片，不做实时推荐

Dazz Cam、OldRoll、NOMO（含 NOMO RAW）、Hipstamatic 四款代表性胶片相机 App，均**没有**按场景自动推荐胶片的功能：

| App | 选择方式 |
| --- | --- |
| Dazz / OMO Roll | 拍前选虚拟「相机」（FXN R、CPM35、K Funs 等），相机绑定胶片色彩、颗粒、闪灯、日期戳；官方与社区以「白天/夜/海滩/街拍」场景对照表教人手动选 |
| OldRoll | 相机资源（Classic M、Hexar、Argus…）与编辑器滤镜分离；场景匹配由用户完成 |
| NOMO / NOMO RAW | 取景器里选相机即锁定胶片（135 Ti、135 LUNE…）；只有 NOMO RAW 允许**拍后**换色彩 profile |
| Hipstamatic | 镜头+胶片+闪灯 combo 拍前组合，可收藏 combo、可摇一摇随机——随机权仍在用户手里 |

结论：胶片气质 App 的主流范式是「**人选身份，相机守身份**」。在取景器里实时推胶片不是行业惯例，我们要做就必须比编辑器派更克制。

### 1.3 相机厂：自动可以自动，但识别不确定就闭嘴

**富士 AUTO 胶片模拟**

- 胶片模拟菜单里的 `[AUTO]` 项**仅在模式拨盘处于 AUTO 时可选**：官方手册原文「The camera will automatically select the best film simulation mode according to scene. This option is available when the mode dial is set to [AUTO].」
- 它与 SR AUTO 场景识别（肖像/风景/夜景/微距/夜景人像/逆光人像等）是同一套识别引擎，联动曝光、白平衡、AF；识别结果以场景图标可见，**识别不出就不显示图标**——弃权是常态输出，官方从不公布场景→胶片映射表。
- AUTO MODE SETTING 允许用户强制场景偏好；一旦用户把拨盘拧到具体模式或手选胶片模拟，AUTO 即刻失效。

映射到途帧：影调建议必须是**手动影调之外的旁路**（用户已选 TRI-X，就不该再推正片）；「没有足够证据就不出卡」是默认行为而非缺陷。

### 1.4 取景器专业反馈派：空间锚定、按需出现、对齐全绿

**Halide（Mark II/III）**

- Zebra 条纹直接叠在画面中 clipping 的**具体位置**上（Color Zebras 还分 R/G/B 通道，亮部暗部通道裁切都报）；直方图/波形/focus peaking 全部锚定画面内容，不弹文字窗。
- XDR Analysis 用 14-bit RAW 传感器数据而非 8-bit 预览算 clipping，避免误报逼用户欠曝。
- 工具按需出现、用完淡出（「Tactile Touch」），全部收纳在可自定义 quickbar、默认不堆在取景器上；Halide 明确把竞品堆控件的做法称为「飞行模拟器 UI」并与之切割。

**ProCamera — 3D Tiltmeter**

- 控制面板里可开的取景器虚拟水平仪，水平与竖直双轴，**对齐瞬间指示归正变绿**——这是「用户按反馈调整→到位即时确认」最直接的行业先例。

**手机厂商的 AI 构图教练（趋势旁证，来源较弱）**

- 2026 旗舰宣传材料中出现实时构图评分、箭头提示手机移动方向、半透明推荐裁切框「对齐到位变绿」（Auto-Crop Preview）等形态。该说法来自 SEO 聚合站（见来源 mobileos.it），仅作趋势旁证；「对齐变绿」的可靠先例以 ProCamera / Halide 一手材料为准。

### 1.5 主动建议（nudge）防骚扰的量化共识

综合 Digia Studio 的 nudge 系列文章、NN/g 移动端引导层研究、GNOME/Material 的 snackbar 规范与 mixed-initiative HCI（打断期望效用模型）：

- **会话预算**：每会话最多 1 个「可自由裁量的」建议（非交易性反馈），保守起点 1–2 个/会话，按对照组会话质量数据再放宽；按**用户**而非按「建议类型」计数（四个功能各「每会话 1 个」= 用户被打扰 4 次）。
- **行为门闸优于固定时间**：一条建议被点或被忽略后，至少等用户**完成一次主任务（拍一张照）**再考虑下一条；行为触发比定时触发有效得多。
- **重复与压制**：同一条建议无互动最多出现 2 次；dismiss 后 48–72 小时（保守可取 7 天）不再出现；同一用户连续 dismiss 3 次，本会话剩余时间关闭所有 discretionary 建议；目标动作已完成则该建议永不再出。
- **dismiss 必须持久化**：按 用户 × 建议 ID 存储，× / 滑动 / Esc 都算 dismiss；「Don't show again」要在设置里给可恢复入口（JetBrains 的做法），不能把恢复路径藏起来。
- **位置的语气**：锚定在相关控件上的 tooltip 读起来像「帮助」，顶/中 banner 读起来像「广告」；手机上底部锚定打断成本最低；同屏最多一个建议，多个排队不堆叠。
- **建议 → 预览 → 确认，绝不自动执行**；凡代用户执行的可逆动作，配 snackbar「撤销」（8–10 秒，悬停暂停计时；GNOME HIG / Material 共识）。
- **置信度门闸**：能找到的经验值是「出建议约 90%、自动执行约 95%」（也有低 stakes 内容 ~70% 即可的说法），来源是厂商经验而非标准化研究——只作数量级参考。规则系统没有概率，正确替代物是「多项独立读数一致 + 证据强度门闸」，**禁止在 UI 上伪造百分比置信度**。

## 2. 两个核心问题的回答

### 2.1 途帧实时建议防骚扰规约（T30/T31 直接照此实现）

1. **只建议，永不自动改**：影调、EV、对焦点、裁切都不自动应用；照片永远保存完整画面（现有铁律）。
2. **一键试用 → 预览即结果 → 保留需确认 → 撤销一键**：影调预览本就是 WYSIWYG（LUT 实时），「试用」成本极低；试用态给明确「还原」出口，不试用则什么都不变。
3. **稳定才提、换景即废**：场景签名连续稳定约 2 秒（与构图分析共用 250ms 时钟，约 8 帧）才允许出影调建议；画面明显变化时旧建议立即失效（复用现有 scene signature 失效契约，不新造防抖机制）。
4. **会话预算共享**：每次 App 回前台算一个取景会话；影调建议每会话 ≤2 条，影调+曝光提示合计 ≤3 条；**用户手动选过影调/卷后，本会话不再出影调建议**（富士原则：拨盘离开 AUTO）。
5. **同景同建议不重提；dismiss 有记忆**：按（场景类别 × 建议内容）去重；点忽略后该组合 7 天内不再出现，持久化进 AppSettings；连续 dismiss 3 次自动关闭建议总开关，恢复入口放在「拍摄 → 构图辅助」同级设置里。
6. **证据不足不出卡**：读数需要多项一致（如昼夜 + 色温 + 蓝天占比同向）才映射到建议；任一项处于灰度区间就弃权。文案不出现概率/置信度数字。
7. **必须给理由、锚定控件、不弹对话框**：卡片一句话「现在适合：正片——蓝天占比高、光比柔和」；影调卡锚定右上胶片小画，曝光卡锚定左下建议区；点卡=试用，× =忽略。
8. **曝光提示更高优先但同走预算**：仅当 clipping 正在发生（过曝/欠曝比例超阈、持续 N 帧）才提示，给 EV 方向不给绝对值的强迫动作。
9. **独立开关、静默呈现**：「影调建议」「曝光提示」各有设置开关，默认开；不发声；震动仅 T34 对齐反馈可用且默认关。

### 2.2 构图建议「可执行」的四条标准（T32–T35 照此实现）

1. **动词 + 方位 + 幅度**：右移 / 左移 / 抬高 / 降低 / 靠近 / 退后 / 转正，幅度分「一点 / 半步 / 一步」三档；一句话能在 2 秒内做完才算数。
2. **几何可算**：移动指令必须由推荐框与当前取景框的**中心差、尺度差、角度差**直接算出（纯函数、可单测），不靠文案模板随机拼。
3. **到位有确认**：当前取景持续 N 帧落入对齐阈值 → 推荐框描边瞬时变绿 + 小勾一现（ProCamera 水平仪 / Halide 反馈范式），离开阈值恢复；不改变照片、不改变规则层。
4. **可预判**：长按建议卡进入「试取景」——遮罩推荐裁切框外区域，松开发还；照片始终全幅（对标 Lightroom 长按 Before 的反向用法：按住看 After）。
5. 构图术语（「引导线」「明暗对比」）保留为副文案/理由，不替代动作句。

## 3. 调研结论 → T29–T37 映射表

| 任务 | 行业依据 | 落地要点与初始参数 |
| --- | --- | --- |
| **T29 SceneReadings 规则读数** | VSCO 专家标注类别（portrait/coastal/nature/architecture/light-shadow/monochrome）；富士 SR AUTO 场景类型；Halide 复用现有帧不新增负载 | 输出：R/B 色温倾向、通道离散度饱和度、对比度、亮/暗 clipping 比例、上半幅蓝天/绿植粗分、昼/黄昏/夜粗判。复用 640×480 YUV 与 96px 采样，不开新相机流；纯 Kotlin 确定性函数 |
| **T30 实时影调建议** | VSCO 被动分类 + 点按试用 + 强度滑杆；富士 AUTO 手动旁路 + 无图标弃权；§1.5 频率/dismiss 共识 | 映射：晴日暖光→正片 / C200 类；冷绿街景→CLASSIC_CHROME 类；夜+高反差→MONO/TRI-X；黄昏暖→CLASSIC_NEGATIVE/NOSTALGIC；阴低饱和→POWDER_BLUE/原色。门闸 §2.1 第 3/6 条；预算第 4 条；卡锚胶片小画、点按试用、× 忽略、7 天 dismiss 记忆；默认只建议 |
| **T31 曝光提示** | Halide zebra 空间锚定 + 通道级 clipping；Halide 「勿误报逼用户欠曝」 | v1 文字卡（过曝/欠曝/大光比 + EV 方向，点按直达 EV 控件）；阈值用 LumaMeter 合成直方图单测定标，clipping 需持续 N 帧；v2 再考虑 clipping 区域描边（zebra 式）；只建议不改曝光；与 T30 共享会话预算 |
| **T32 构图语言第三波** | 三分网格是所有相机零成本基础教学；地平线压 1/3、负空间、走动代替变焦 | 新增：地平线过高/过低/居中、大面积极简留白、距离不足（退后半步）、贴边主体方位细化；仍只用 96px 统计、零先验基分、证据为零不出文案、中文含方位 |
| **T33 动作化文案** | §2.2 可执行标准 1/2；ProCamera 双轴仪把偏差变成方向 | 框中心/尺度/角度差分三档阈值→动作句纯函数；方位/幅度档位单测；移动后建议随画面变化（AVD 站位验证）；术语留副文案 |
| **T34 对齐正反馈** | ProCamera Tiltmeter 对齐变绿；Halide 辅助按需淡出；AI 构图框变绿趋势 | 当前框与推荐框持续 N 帧入阈：描边瞬时变绿 + 小勾一现，可选轻震默认关、无声；离阈即恢复；不改照片/规则层；阈值与防抖纯几何单测 |
| **T35 长按试取景** | Lightroom 长按看 Before（反向：按住看推荐裁切 After）；VSCO 试用零成本 | 长按建议卡：遮罩框外 + 「试取景，松开还原」；松开还原；坐标只做显示变换；拍照仍全幅（铁律）；test + AVD 三态 |
| **T36 d1 输入分辨率探针（edge 仓）** | 实时建议的能耗/时延预算必须先量出来；Halide 级实时反馈依赖低成本分析 | 512/384/320 三档 vision 耗时 + 20 sheet 位置偏好/正确率对照，看数前预注册耗时收益与劣化上限；不改 App 契约；直接提交 edge 仓 main 不推送 |
| **T37 建议效果回归度量** | VSCO 用「分类采纳率/使用率」验证（For This Photo 成第二大分类） | eval JSON：建议类型覆盖数/频次分布、随画面变化率、动作建议方位分布、对齐成功率（配 T34）、试用/采纳/dismiss 比；脚本可复跑；不含审美真值，审美仍 not-verified |

## 4. 证据边界

- 全部结论来自公开网页的产品行为描述（2019–2026），产品改版会使行为过期；富士与 VSCO 的关键行为以引用的官方手册 / TensorFlow 工程博客为准。
- 频次数字（1 条/会话、2 次无互动压制、48–72 小时、约 90% 置信）是 nudge 行业的经验区间，Digia 系列为厂商观点而非一手研究；T30 先取保守值，T37 有数据后再调。
- mobileos.it 为 SEO 聚合源，仅用于「实时评分/框变绿」趋势旁证；「对齐变绿」的一手先例是 ProCamera 3D Tiltmeter 与 Halide。
- 本文回答的是「怎么建议才不讨厌、建议怎么才做得出来」，不回答「推荐的影调对不对」——后者唯一的下一步证据仍是独立人工标注照片集。

## 5. 落地注记（T30 实现后）

- 实现件：`composition/LookRecommendations.kt`（SceneReading→LookTarget 纯映射）、`composition/LookAdviser.kt`（门闸状态机）、`CompositionAssistant` look-only 分析会话（构图开关关闭时，影调建议仍以同一 250ms/384px 帧源驱动 SceneReadings，不跑 FastCompositionAdvisor、不绑模型）、`CameraViewModel`（试用/保留/还原/忽略/开关）、取景器胶片徽章下建议卡、拍摄设置「影调建议」开关。
- 最终保守参数：稳定 8 帧（≈2s，分析帧 250ms 时钟）；每前台会话最多 2 条；同 key 会话内不重提；dismiss 压制 7 天；连续 dismiss 累计 3 次自动关总开关（试用/保留/手动换影调清零计数，恢复入口在拍摄设置）；手动选影调或胶片后本会话噤声；试用是可一键还原的预览，后台化自动还原。
- 映射五条：夜+反差≥0.16→TRI-X 400（MONO_01）；黄昏→CLASSIC_NEGATIVE；昼+上半幅天空≥0.35→正片（暖/冷两种理由文案）；非夜+绿植≥0.30、天空<0.12、非暖→CLASSIC_CHROME；昼+饱和度≤0.10 且反差<0.13→粉末蓝；其余弃权不出卡。
- 证据：映射与门闸为合成帧 JVM 单测（含昼夜/暖冷/黑白/预算/7 天窗口/弃权）；AVD 为 loaded-only——真实暖暗室内帧端到端触发「经典负片」卡、试用真实换色、还原/dismiss/跨会话 7 天压制/设置开关均按设计工作；模拟器虚拟场景站位移动由主机键鼠消费、adb 无法转向，「随不同站位色彩变化」的多场景证据只到单测级，审美正确性仍 not-verified，等独立人工标注照片集。

## 6. 落地注记（T31 实现后）

- 实现件：`camera/.../ExposureStats.kt`（48 bin 亮度直方图 → 顶 bin 251..255 / 底 bin 0..5 占比与 bin 中心加权均值的纯函数，`HistogramSample` 随帧携带）、`LumaMeter` 回调升级为 `HistogramSample`（同一份 200ms 节流、64px 点采样同时供给直方图 OSD 与曝光门闸，不开新相机流）、`composition/ExposureAdviser.kt`（三态门闸）、`composition/AdviceSessionBudget.kt`（影调建议与曝光提示共享：每前台会话影调≤2、合计≤3）、`CameraViewModel`（文案与 EV/EV 范围/手动模式门控、开关、会话重置）、HDR 钮下文字卡、拍摄设置「曝光提示」开关（默认开，随 v4 设置 JSON 持久化，旧档缺字段按开处理）。
- 最终保守参数：高光占比 ≥0.04 判过曝、暗部 ≥0.15 判欠曝（暗部容忍度高于高光，呼应 Halide「勿因高光误报逼用户欠曝」）；同一问题连续 6 个 meter 帧（≈1.2s）才发卡，连续 3 个健康帧撤卡；同问题会话内只出一次；双端同时 clipping 判大光比，文案指向 HDR/避开强光（EV 救不了两端）；手动 ISO/快门模式不出单极 EV 建议（控件惰性），大光比卡仍出；EV 已在补偿范围端点时不出该方向建议；只建议、不自动改曝光；曝光卡无跨会话 dismiss 记忆、不参与「3 次 dismiss 关开关」。
- 证据：统计纯函数 6 个 JVM 测（空直方图、单 bin 极限、4%/16%/80% 精确混合、尺寸无关、bin 24 均值≈0.51）、门闸 12 个测（6 帧确认/3 帧撤收/阈值边界/手动弃权/端点压制/会话单次/dismiss/切候选重置连计/共享预算 2+1 封顶与 reset）；`SettingsJsonTest` 补开关默认值与往返。
- AVD 为 loaded-only：真实 VirtualScene 室内帧统计链路工作（n=3072、均值 0.40、暗部约 3%、亮窗被 AE 压在顶 bin 以下 hi=0），门闸据此正确保持沉默；但模拟器相机的 EV 补偿（−2…+2.5 全范围实测）与点按测光均不改变分析帧像素、且不支持手动曝光，无法用 adb 人工制造 clipping——阈值的端到端正例只到合成直方图单测级。卡渲染与交互经一次性强制注入验证（验证代码已移除）：卡位于 HDR 钮下、× 可点撤收且会话内不重现；开关关闭后 meter 回调静默而 OSD 直方图（独立开关）照常。**真机亮窗/夜景的阈值校准仍是 not-verified**，审美与曝光正确性的下一步证据不变：真机 + 独立人工标注照片集。

## 参考来源

- VSCO 工程实践：[Suggesting Presets for Images: Building “For This Photo” at VSCO — TensorFlow Blog](https://blog.tensorflow.org/2019/06/vsco-suggesting-presets-for-images.html)；[Let VSCO find the perfect filter — App Store 故事](https://apps.apple.com/ge/story/id1483965840)
- 富士 AUTO 胶片模拟：[X-M5 官方手册 IMAGE QUALITY SETTING](https://fujifilm-dsc.com/en-int/manual/x-m5/menu_shooting/image_quality_setting/)；[SR AUTO 场景识别（FinePix S2950 官方功能页）](https://fujifilm.com.mx/productos/camaras_digitales/serie_s/s2950/features/page_02.html)
- Google Photos 建议与开关：[Edit your photos — Google Photos Help](https://support.google.com/photos/answer/6128850)
- 拟物胶片派：[NOMO 完全指南（爱范儿）](https://www.ifanr.com/1482823)、[OldRoll 官网](https://www.oldroll.io/)、[Dazz Cam 官网](https://dazzcam.app/)、[Hipstamatic 操作（Hitchin Camera Club）](https://www.hitchincameraclub.org/wp-content/uploads/2018/09/Viveka-Koh-presentation.pdf)、[Hipstamatic App Store](https://apps.apple.com/cn/app/hipstamatic-analog-film-camera/id1450672436)
- Halide：[官网](https://www.halide.cam/)、[The Histogram — Halide Support](https://luxoptics.zendesk.com/hc/en-us/articles/360051435832-The-Histogram)、[Halide Mark II 40+ 新功能 — DPReview](https://www.dpreview.com/news/8854455407/halide-mark-ii-redesigned-raw-camera-app-iphones-ios-40-new-and-improved-features/)
- ProCamera：[专业功能页（含 3D Tiltmeter）](http://www.procamera-app.com/features/)
- AI 构图趋势（弱源，仅旁证）：[Smartphone Photo Trends 2026 — MobileOS.it](https://mobileos.it/en/2026/07/smartphone-photo-trends-2026-master-ai-composition/)
- nudge 防骚扰：[How Over-Nudging Erodes User Trust — Digia](https://www.digia.tech/post/how-over-nudging-erodes-user-trust/)、[Designing Non-Annoying Nudges — Digia](https://www.digia.tech/post/designing-non-annoying-nudges-frequency-placement-context/)、[When NOT to Show a Nudge: Suppression Logic — Digia](https://www.digia.tech/post/when-not-to-show-a-nudge-building-suppression-logic/)、[Instructional Overlays and Coach Marks — NN/g](https://www.nngroup.com/articles/mobile-instructional-overlay/)、[Toasts — GNOME HIG](https://developer.gnome.org/hig/patterns/feedback/toasts.html)
