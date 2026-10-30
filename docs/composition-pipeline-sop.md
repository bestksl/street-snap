# 端侧构图建议 SOP

本轮目标：针对当前取景显示一个推荐构图框和一句中文建议，并分别显示快速画面分析与模型选择的耗时。默认在取景页点击“构图”执行一次，可选开启实时建议；功能已开启时进入相机会自动给出首轮建议。建议必须随画面变化并覆盖多种构图语言（三分、中心、对角线、三角形、引导线、框式、前景层次、留白、环境），禁止退化成恒定一句话。框表达建议保留的画面范围，不是主体检测框；拍摄参数与快门由用户控制。

模型择优自 2026-10-20 起仅保留固定 revision 的 onnx-community/d1-omni-600M（marker 选项契约，见 §2 与文末「模型与本地打包」）；SmolVLM 256M/500M 已下架，旧设置中的模型 id 自动迁移到 d1。未下载模型时规则建议独立可用。YOLO 尚未接入。`FastCompositionAdvisor` 是图像统计规则，不是另一个神经网络。实现与验收状态见文末，本文件不把历史测试算成本轮通过。

## 准备阶段：先定义什么是好构图

1. **固定评价标准。** 保留主要视觉信息，避免切断明显结构，适度减少杂乱和空白，兼顾视觉平衡、三分位置与环境交代。三分法是参考，不是每张照片的唯一答案。裁切保持原画面比例，当前规则候选保留至少 68% 边长；低纹理画面优先完整或轻微裁切。
2. **准备可追溯样本与标注。** 使用已有、来源和许可可核验的照片，记录来源、授权范围、场景和原始尺寸。为每张图保存人工可接受的裁切范围、候选偏好顺序或并列，以及简短理由；允许“保持原图”和“无法判断”。不能把规则第一名直接当成人工真值。测试照片和标注保留在本地，不提交开源仓库。
3. **隔离调试与验收。** 按原照片及拍摄场景划分调试、验证和保留测试集；同一照片的裁片、近重复图和连续帧必须在同一组。用调试集改规则或提示，用验证集选择阈值，冻结设置后再看保留测试集，避免把反复调过的照片作为独立证据。
4. **评估任务适配。** 先比较规则首选与模型重排的人工偏好、结构保留、失败率和耗时。若模型不能稳定改善选择，保留规则建议，不用反复换提示词代替评测。后续任务适配或微调也应使用有许可的偏好/裁切标注，并使用隔离测试集评测；目前未做构图训练或微调。
5. **量化后重新验收。** 固定模型 revision、图文件、预处理、候选图样式和提示词，对量化前后或不同量化配置复用同一测试集，比较候选选择、拒答、候选重排偏置、延迟与内存。模型文件更小不等于更快，也不等于审美质量相同。本轮只使用已有本地模型和工具，不新增下载或训练任务。

## 运行阶段：规则生成候选，模型选择，UI 画框

```text
取景页“构图”按钮：主动取一帧
可选“实时建议”：快速候选目标每秒约 4 次（实测约 2.4 次），模型串行且完成后冷却
                 │
                 ▼
CameraX ImageAnalysis → 独立 RGB 帧 + 当前会话/场景标识
                 │
                 ▼
FastCompositionAdvisor → 3 个合法候选框 + 统计分 + 构图风格 + 中文理由
                 ├─→ 立即显示规则首选：“画面分析建议” + 快速分析耗时
                 │
                 ▼
同一帧、统一样式的 A/B/C 候选裁切图
                 │
                 ▼
d1-omni 决策模型：每个排列一次视觉编码 + 一次决策头 prefill（零输出 token）
                 │
                 ▼
A/B/C 支持度、差距、循环换序一致性、场景/会话检查
          ┌──────┴──────┐
          ▼             ▼
有效选择            弱支持/失败/超时
更新为模型所选候选    保留仍有效的规则建议
          └──────┬──────┘
                 ▼
UI：一个推荐构图框 + 对应中文理由 + 来源 + 耗时
```

### 1. 快速生成可见建议

`FastCompositionAdvisor.suggest(width, height, argb)` 在最长边 96px 的图像上分析颜色对比、局部边缘和视觉重心，以主要结构保留、三分位置、边界切割风险、裁切面积和视觉平衡评价候选。输出三个有差异的归一化裁切框；归一化宽高相等，所以保持输入画面的比例。

除裁切评分外，规则层还做一次轻量“摄影眼”场景判读（`SceneFeatures`）：Sobel 梯度方向的 45° 对齐度（对角/引导线）、左右镜像对称与中心能量（中心构图）、3×3 分带能量（多点散布的环境构图）、外带空旷且对侧有内容（方向性留白）、边缘带边缘密度或暗框（框式构图）、能量包络下宽上窄（三角形）、前景横切边（前景层次）。T10 在此基础上把构图语言从 9 类扩到 **17 类**（另加稳妥/通用两档），新增检测器全部只用 96px 低级统计量：左右镜像 + 双侧翼纹理（`SYMMETRY`，单一居中主体因侧翼为空仍归 CENTERED）、≥4 根跨幅竖边普查（`VERTICAL`）、全宽水平长边界且对次强边界占优（`HORIZON`，Sobel 阶跃双行响应通过排除主峰 ±2 行处理，竖分界不报）、列/行投影归一化自相关（lag 处正、半 lag 处负、2×lag 仍正，`REPETITION`）、Otsu 类间方差/总方差与少数派面积门（`CONTRAST`，单一几何分界让位 HORIZON、单斜率斜场让位 DIAGONAL）、左右半区斜边缘双侧内收（`CONVERGENCE`，单斜率场一侧内收占比近零）。单一对角斜率场用斜率符号纯度（`slopePurity`）与汇聚场区分，斜纹的周期/双峰伪信号据此被抑制。每个候选按「场景证据 × 几何契合」得到各风格的契合分，证据为零即为零，**没有任何人为先验基分**——标签由画面证据决定，不预设三分法等风格的概率；对称/竖线/汇聚这类全帧结构的几何中心取裁切框几何中心而非能量矩心，单侧强物体不能决定框本身是否在对称轴上。三个候选贪心分配互不相同的 `CompositionStyle`，文案以风格术语开头（三分、中心、对称、对角线、三角形、引导线、汇聚线、竖线、水平分割、框式、前景层次、节奏、明暗对比、留白、环境），同一风格还有按方位/位置变化的变体；所有风格证据都不足时才回退到通用裁切描述。

T32 第三波把语言从 17 类扩到 **21 类**，新检测器仍只用 96px 低级统计量：① `HORIZON_SPLIT`（水平腰斩）：已检出的水平分界落在帧 0.40–0.60 且落进裁切 0.30–0.70 中段，与近三分线的 `HORIZON` 互斥，文案给抬高/压低机位的明确方向；② `TOO_TIGHT`（取景太满）：外 8% 边框至少三边密度 ≥0.9 倍均值、中心格不空、9 格中 ≥7 格繁忙（`edgeCrowd`），对角斜率场与水平分界按几何铺满四边不属于拥挤，各乘 0.85 抑制；③ `EDGE_CUT`（主体贴边）：沿单一边走查，最外 7% 与 7–14% 两段同时高于 1.1 倍均值的位置占该边 30–90%（铺满是背景场、窄条是结构线，两段外再回落为零），且沿边两端至少留 4% 呼吸（inset 门，把角部起铺的明暗分屏压掉），并要求对侧不是留白带；文案按 L/R/B/T 给出平移方位；该风格只允许 scale≥0.94 的近全幅裁切携带（贴边警告评价的是当前取景）；④ `MINIMAL`（极简留白）：≥72% 像素近静默、3×3 中至多一个活跃格且该格能量占比够高；它成立时把各方向留白读数压 85%，避免同一画面被同时叫成方向性留白。交叉仲裁还有：节奏自相关的最小 lag 提到 n/8（96px→12px、64px→8px，小于 1/8 图宽的周期峰是颗粒纹理不是节奏元素，柱廊/栏杆一类真节奏不受影响），且 `REPETITION` 与 `CONTRAST` 在 `edgeCrowd` 饱和时分别乘 0.9/0.85 让位 TOO_TIGHT；`MINIMAL` 的几何门放到 scale≥0.55，因为追小主体的紧裁切不能抹掉「别把主体拍大」这条建议。

T33 把首选建议动作化（T28 §2.2 标准 1/2）：纯函数 `CompositionActions.plan(crop, style, subjectU, subjectV)` 只吃推荐框与全幅取景的**中心差/尺度差**——水平中心差 ≥0.06 出「向左/向右平移」、垂直中心差 ≥0.06 出「机位抬高/降低」（框偏向哪边就往哪边动）、尺度差 1−s ≥0.07 只可能出「靠近/再靠近」（裁切不可能比全幅大，没有退后档；退后由 TOO_TIGHT/EDGE_CUT 的术语文案承担）；幅度三档「一点/半步/一步」，平移/俯仰阈值 0.06/0.12/0.20，推拉 0.07/0.15/0.25。多轴按平移→俯仰→推拉拼接成一句话；THIRDS 风格按框内能量重心（ScoredCrop 的 u/v，容差 ±0.15）追加锚点短语「让主体落在左/右/上/下三分线」，CENTERED 追加「让主体居中」，重心近画面中央时不编造锚点。EDGE_CUT/TOO_TIGHT/HORIZON_SPLIT 的术语文案本身已是机位动作、CONSERVATIVE 语义为保留全幅，四者一律弃权，卡片不重复指挥。动作句随规则候选生成（`CompositionCandidate.action`），模型重排只是换候选顺序，所以模型选中的候选也带着规则层算出的同一句；`CompositionSuggestion.actionText` 不经过模型 `parse`，不可信模型文本永远写不了这个字段。卡片上动作句是 13sp 主行，构图术语降为 11sp 副行（术语保留不替换）；`carriesSameGuidance` 同时比较 actionText，几何微动导致档位跳变时卡片会更新。

**风格种子候选注入（T10，模型融合侧；T32 升级为 per-style 表达检查）**：能量排序前三的裁切可能与强场景证据的语法不匹配（镜像立面在能量偏置时给不出居中方框）。T32 起 `enrichWithStyleSeeds` 对每个证据风格（贴边/对称/竖线/水平/节奏/明暗/汇聚，EDGE_CUT 列首位）逐风格检查：只有「选中的三个裁切对该风格的 max 契合 <0.18」的未表达风格才可当 seed（同一帧可以 CONTRAST 已表达而 EDGE_CUT 完全未表达），取场景证据 ≥0.60 的最强者，从全部已评分裁切池取该风格契合最高者（要求与已选 IoU<0.80、能量分不低于第三名 −0.10 且 ≥0.45、风格契合 ≥0.35）替换第三名。EDGE_CUT 作 seed 时直接配 ranked 池中的全幅裁切（left=top=0、scale=1），绕过为多样性设计的 IoU<0.80 门——那个门恰好会否决全幅框；贴边警告评价的就是当前取景。这是 T07 评测否决 prompt 改写后的融合路线：**不动一个字的题面，只让送选的 A/B/C 承载更丰富的构图语法**，模型仍只做同帧三选一；证据为零不注入，蓝框首选（能量第一）永不被替换，题面仍为 `comp-choice-v0`。低纹理画面提供保守宽框，且保守文案按中位亮度分暗/亮/普通三档，不再是恒定一句话。规则结果现以 `FastCompositionResult(candidates, subjectLooksDistant)` 返回，后者在内部 6×6 网格中出现单一小而强的能量团（占比阈值 0.24、首选裁切不超过 0.88）时为真。

先显示统计分最高的候选及其中文理由，来源标为“画面分析建议”。画面内容变化应引起候选框与风格文案同时变化；低纹理画面提供保守宽框并说明视觉重点不明显。统计分只用于规则排序，不是主体检测置信度，也不是审美正确率。

广角彩蛋：`subjectLooksDistant` 为真且当前等效焦段 ≤35mm 时，`CompositionEasterEgg` 以约五分之一的低频率、90 秒冷却追加一句委婉的靠近提示（如“28mm 小彩蛋 · 主体有点远，凑近一步会更有存在感”）。它是仅作提示的轻文案，不参与裁切、评分或门闸，模型选择来源不显示，焦段变化不重启相机会话。

### 2. 异步比较候选，不让模型生成坐标

`CandidateContactSheet` 将同一帧的三个实际裁片按统一尺寸、背景和标签样式组成 A/B/C 图。不给模型规则分数、首选高亮或理由，避免直接泄露规则排序；App 实际调用与离线验收必须使用同一个制图实现。标签顺序与原候选身份的映射随请求保存，结果不能误套到下一帧。

`SmolVlmRuntime.rankCandidates` 对整张候选图做一次视觉编码，经文本嵌入后执行一次 decoder prefill，从最后位置的 logits 读取 A/B/C 分数。这里没有逐 token 生成坐标的循环；所谓“一次选择”仍包含视觉、嵌入和解码器三个 ONNX 部件的计算，不是三个独立模型。

`d1-omni-600m`（`D1OmniRuntime`，同样实现 `CandidateRanker`）走 marker 选项契约：视觉图 `vision_encoder_fp16` 吃 512 方图的 1024 个 16px patch（RGB/127.5−1，`spatial_shapes=[[32,32]]`），吐出 256 个投影前缀嵌入；`embed_tokens_quantized` 编码官方 prompt.py 结构的选择序列（`<reserved_8>` 问题 + 每选项 `<reserved_9><mask>选项<reserved_10>` + `<reserved_11>`，state 为空，文本上限 896）；`decision_q4` 是双向决策头（4-bit MatMulNBits；官方 8-bit `decision_quantized` 在 ORT 1.22 因缺 `accuracy_level=4` 走只支持 4-bit 的 unpacked 路径，全平台不可用）、零生成 token，输入把 256 视觉前缀拼在文本嵌入前，另喂 `prefix_len=256`、文本相对的 `marker_pos`、`marker_mask`、`qtype=0`（choice），输出三个 marker 的 logits。`D1Decision` 对三个 logits softmax：marker 评估的支持度恒为 1，门闸只看 top-1/top-2 margin（实验阈值同为 0.10），图像题不做温度校准；不足、非有限值或异常同样弃权保留规则框。两个运行时共用候选图、换序一致性门闸、30 秒预算与忙时返回 null 的契约，UI 不区分模型。

不能只在 A/B/C 中归一化后强行选出赢家。当前实验门槛同时检查完整词表中 A/B/C 的总支持度与三个候选中的领先差距，初始阈值分别为 0.10 / 0.10；这些值未经审美标注校准，不是质量概率。由于本轮实测发现位置偏好，首轮有选择时还必须对同一帧循环换序 `[1,2,0]` 再比较一次：两个结果映射回原候选后须相同，否则标为“AI 比较不稳定”，保留规则框。两次共用一个总超时预算；低纹理候选不调用模型。弱支持、差距不足、异常或超时都保留仍匹配当前画面的规则建议。有效选择只更换为已有候选的框和中文理由，来源标为“模型选择”；中文理由来自候选规则，不伪称模型生成。

### 3. 背压、场景和生命周期

- 默认点击“构图”只处理一次。实时开关才允许持续请求；模型始终单并发，执行期间不积压下一张大图。实时模型比较完成后冷却 3–15 秒，画面不变则复用；快速规则建议的帧时钟为 250 ms（含约 180 ms 取帧/拷贝/调度固定开销，实测约 2.4 次/秒）；冷却不计作模型计算耗时。同场景返回完全相同的框与文案时不重发 overlay（保留原对象、不足 10 秒寿命时续期），把静止场景下的无意义重组降到零。
- 快速建议耗时、模型耗时和完成/保留规则建议/超时状态分别显示。两次比较共用 30 秒的模型保护预算，但验收记录实际耗时，不把超时截断当作性能提升。
- `ImageProxy` 在复制完输入后及时关闭，异步模型只持有独立候选图。模型未安装、加载失败或推理失败仍可使用快速规则建议；后台、关闭辅助和切换模型时停止新请求并释放相应资源。
- 每 250ms 更新最长边 24px 的场景 signature（间隔由 `CompositionFrameSource(sceneIntervalMs)` 配置，与分析帧时钟同值）。画面明显变化时移除旧框，但不因短暂移动就取消整次推理；完成时重新检查当前会话、场景一致性和帧年龄，最长 65 秒，场景采样超过 2 秒未更新也不发布。
- 推荐框坐标属于输入分析画面，经过统一的旋转/裁切几何映射叠到取景器。不能把候选图中的标签位置当成相机坐标，也不能在模型返回后套用另一个场景的框。单个框有效期 30 秒，实时模式可用同场景新帧续期；移动提前清除，手动模式过期后需要再点击。显示有效期、移动清除和横竖持映射需一起验证。
- UI 绘制推荐框与中文建议；规则与模型均不直接画屏幕，也不自动裁切保存照片或改变曝光、对焦、变焦和快门。

## 验收：可见框与模型有效性分别证明

规则层检查坐标合法、比例不变、候选有差异、同一显著区域左右移动时框随内容变化、低纹理时保守裁切，以及多处强结构不被随意丢弃。此外必须用单测固定风格判读：斜向纹理给出对角线、左右对称竖块给出中心构图、偏移特征给出三分、均匀/低纹理给出保守建议，且不同场景的中文文案互不相同；风格契合分只来自场景证据与几何契合，不允许人为基分。T10 起每类新语法都有合成纹理单测固定：镜像水平层叠→对称（且单一居中竖条不报对称、天空地面不报对称）、规则点阵→节奏（斜纹场仍优先对角线）、两根跨幅竖梁→竖线、全宽水平分界→水平分割（竖分界报明暗对比）、双侧内收 V 形线→汇聚线、单侧高对比招牌 + 弱镜像立面→三候选中出现全帧对称种子（尺度 ≥0.95、前两名不被替换），共 22 条 advisor 单测。应用层必须在实际预览上看到推荐框，验证主动一次、开启后首轮自动建议、实时开关、耗时、来源标识、场景变化、失败保留规则建议与快门可用。

模型层至少进行候选重排测试：同一原图和相同三个裁片，改变 A/B/C 排列，记录还原后的候选身份、完整词表支持度、差距和拒绝结果。如果只是固定选择 A 或某个格子，不能视为有内容依据的选择；高支持度本身也不能证明审美。再结合内容变化样本与人工偏好评估规则首选是否被改善。阈值与样本选择不能只针对一次成功输出。

性能分别记录规则耗时、模型视觉编码、prefill、总耗时、首次/预热差异、预览与快门影响。足够样本后才报告 p50/p95；模拟器只验证链路，荣耀 Vs3 的速度、温升与主观构图质量须由真机结果支持。

2026-10-17 Android 35 ARM64 AVD 实测：规则推荐框与中文建议可见，初次规则计算 69 ms，再次 47 ms（不含取帧和屏幕显示调度）。初版单排列模型在预览运行时 5.6 / 4.8 秒返回，曾选择完整画面；这只能证明选择和叠加链路。

随后用同一已保存的相机输入进行独立诊断，原序 `[0,1,2]` 选 B（原候选1，支持度0.798），循环换序 `[1,2,0]` 仍选 B（原候选2，支持度0.814），纯灰也选 B（支持度0.968）。换序不一致，暴露位置偏好，因此不能把单排列结果当成可靠构图判断。后台诊断三次推理为1953/1684/1624 ms，没有相机预览竞争，不能与前台速度直接比较。最终实现新增换序一致性门闸。运行相机预览时，双排列比较样本实测约9.5–11.4秒，出现换序不一致或第二轮未明确选择，界面持续显示“画面分析建议”及合法框，未伪标AI选择。实时模式约每秒更新规则，预热后的规则计算多数1–5 ms；静止场景不反复调用模型。上述样本只来自模拟器内置房间，不是独立审美测试集。

主动模式已验证只发一轮；模型运行期间在20:03:41保存 JPEG，运行于20:03:46结束，照片183,180 bytes。旋转后旧结果因SCENE_CHANGED被拒绝，横屏再次点击可显示新框；进入后台停止推理。129项单元测试（app53、camera53、color23）、Lint与离线构建通过。用户得到的是可见、内容相关的规则构图建议；SmolVLM排序的审美有效性仍未通过验证，荣耀Vs3仍待真机测试。

2026-10-17（晚）构图语言升级实测，Android 35 ARM64 AVD + 内置房间场景：规则层新增 `SceneFeatures`/`CompositionStyle` 与 `FastCompositionResult`，风格契合分只取场景证据×几何契合、无先验基分。点击“构图”后取景器立即显示蓝框、三分网格与“三分构图 · 把视觉重点压在三分线交点上，画面更耐看”，实时模式约 1.1 秒更新一次，预热后规则计算 1–6 ms；SmolVLM 双排列在 21.1 秒判 UNSTABLE_SELECTION，界面持续保留规则框与建议，降级路径符合预期。单测新增斜纹→对角线、对称竖块→中心构图、偏移特征→三分、暗/亮保守文案分档、远主体检测、彩蛋门闸（五抽一、90 秒冷却、仅 ≤35mm）等用例；全部 JVM 单测通过，`:app:assemblePreview`（含 R8）通过。模拟器仅有一个固定虚拟场景，风格多样性目前由单测合成场景证明，真实街景下的命中率与荣耀 Vs3 表现仍待真机验证。

2026-10-18 d1-omni-600M APP 侧集成完成（代码与契约层验证）：`EdgeModelKind`/catalog 新增 d1 条目（pin `4ebc1b9`），`EdgeModelStore.prepare` 按 kind 选图并强制 LICENSE；新增 `CandidateRanker` 统一接口（SmolVlmRuntime 改为实现该接口）、`D1Prompt`（prompt.py 逐字移植）、`D1NaFlex`（1024 patch 排布）、`D1Decision`（marker softmax + margin 门闸）、`D1OmniRuntime`（三会话编排，256 视觉前缀拼接，busy/cancel/close 与 SmolVLM 同构），`CompositionAssistant` 按 kind 创建运行时。新增 21 条 JVM 单测（prompt 转义/budget/截断/marker 位置 7 条、patch 排布/归一化 6 条、softmax/弃权/非有限值 8 条），全部模块 `./gradlew test` 与 `:app:assemblePreview`（含 R8、lintVital）通过。

2026-10-18 d1 决策图 q8→q4 切换与设备端实测：官方 8-bit `decision_quantized.onnx` 在 ORT 1.22 无法运行（macOS x86_64 与 Android arm64 双重复现 `MatMulNBits ... Only 4b quantization is supported for unpacked compute`；根因是导出节点缺 `accuracy_level=4`，ORT 落 unpacked 路径只支持 4-bit），改用同仓 `decision_q4.onnx`（4-bit MatMulNBits，模型包由 572 MiB 降至 417.7 MiB）。桌面 ORT 1.22 用真实 pin 版权重对三张合成 sheet 跑通 vision→embed→decision，全部输出 finite logits；Android arm64 模拟器（SDK 35）实测实时取景链路一轮成功：`probabilities=[0.204,0.291,0.505]`、margin 0.214 过 0.10 门闸被采纳（vision 11.5s、decision 4.5s），另一轮因模拟器软件渲染过慢触发 30s 预算超时、按契约弃权保留规则框（`model_outcome=TIMED_OUT`）。模拟器耗时不代表真机；荣耀 Vs3 真机实测、真实街景换序一致性与命中率仍待补，edge-model-solutions 的 evaluations 据实更新。

2026-10-18（夜）规则建议提频与 UI 平滑（T06），Android 35 ARM64 AVD 内置房间 30 秒取样：

- 优化前基线：30 秒 26 次快速分析 ≈ **0.87 次/秒**，帧间隔 1.12–1.26 s（均值约 1.14 s），fast_ms p50≈3 / p95≈12 ms。规则计算不是瓶颈，天花板是帧时钟 1000 ms rest。
- 优化：`CompositionFrameSource` 的分析帧时钟与场景采样间隔均参数化（`minimumIntervalMs`/`sceneIntervalMs`，require 100..60000），实时模式配置由 1000/500 ms 改为 **250/250 ms**；模型低频分支仍走独立 3–15 s 门闸，节奏不受影响。
- 优化后（最终版两次取样）：**2.36–2.40 次/秒（约 2.7 倍）**，间隔 p50 408–424 ms、p95 548–592 ms、最小 270 ms；fast_ms p50 2–3 / p95 19–27 ms（均值约 5–8 ms，偶发 60–80 ms 为模拟器调度抖动）。
- 对照实验：帧时钟再降到 200 ms 实测仅 2.47 次/秒（间隔 p50 410 ms，只快 14 ms），证明 rest 已非瓶颈——约 180 ms 固定开销（384 帧 YUV 拷贝/getPixels、CameraX 在软件渲染模拟器上的帧到达粒度与线程调度）主导；继续压缩只增加耗电与争用，故定稿 250 ms。真机帧率与开销需荣耀 Vs3 复核。
- 静止场景变化抑制：同 origin、同框、同文案且双方均无彩蛋时不重发 overlay（`CompositionOverlay.carriesSameGuidance`，6 条单测覆盖：相同抑制、规则/模型切换、框位移、文案变化、彩蛋来去均必须重发），寿命不足 10 s 时续期 30 s；规则→模型的 handover 永远重发（框色要变）。
- 取景框平滑：`rememberSmoothCrop` 用四边 `Animatable` 做 220 ms FastOutSlowIn 插值，动画值在 Canvas draw scope 读取（只触发重绘不重组）；首帧、origin 切换、缩放/旋转导致 overlay 失效后立即 snap，不跨场景滑动，退化矩形保持上一帧。
- 测试：新增 10 条 JVM 单测（overlay 抑制判据 6 + 帧时钟参数校验 2 及既有生命周期用例），全部模块 `./gradlew test` 与 `:app:assemblePreview` 通过；AVD 截图确认蓝框、三分线、中文文案与“画面分析 1 ms”正常显示。候选质量路径（96px 统计、384 取帧、三个候选、门闸、30 s 预算、单并发背压）均未改动。

2026-10-18（夜）建议质量 prompt 修订实验（T07）——假设被隔离评测否决，保持 v0：

- 官方 SOP 对齐：核对 d1 模型卡与官方 `prompt.py` 后确认现有编码已逐项符合 schema——`state` 可为空（图像题以整张图为 state）、`criteria` 对 choice 题由 `{name: description}` 选项渲染为 `"name: description"`、至少 2 项、512×512 SigLIP2 NaFlex、图像+文本 896 文本位、option token budget = max(96, min(24k+32, maxLen/2))。因此本轮只动候选题的文案，不动编码与管线。
- 假设：把题面从无偏好的 `"Which crop gives the best composition?"` + 对称选项 `"a: crop option A"`（v0，state 空）升级为描述式选项 `"A: crop shown in panel A"` + 点名主体/三分/居中对称/留白/边缘切割 criteria 的题面 + panel-layout state（v1），可让模型从“按物理位置应答”转向“按画面内容应答”。
- 预注册判据（看数前固定）：18 张判别探针上 v1 过闸且正确数 ≥ v0；P1/P2 偏置探针 max-prob 均值增量 ≤ 0.05；差 <4/18 视为无差异。探针为 20 张确定性 Graphics2D 合成 sheet（无 RNG，几何镜像 `CandidateContactSheet`；2 张三连同图偏置探针 + 6 场景变体×好图轮转 A/B/C 共 18 张；GOOD=完整高对比主体压三分点且有留白，CUT=主体一半出画，CORNER=主体只剩边角）；真 pin 版权重、桌面 ORT 1.22、vision 特征每 sheet 只算一次且两版共享；同日复跑逐 sheet 概率完全一致。
- 结果：v0 argmax 正确 **9/18**（好图在 A/B/C 位时 3/1/5）、过 margin 门闸 12/18、过闸且正确 **6/18**、偏置 max-prob 0.4476、物理位分布 A5/B4/C9；v1 为 **6/18**（6/0/0）、过闸 6/18、过闸正确 **3/18**、0.4393、分布 A13/B3/C2。判据 REJECT：两版在合成探针上都是位置应答器（v0 偏 C 位、v1 偏 A 位），criteria 题面没有带来内容跟随力，反而让过闸率减半、偏好槽位旋转。
- 处置：整体回退 v0（state 仍为空），保留 `CANDIDATE_PROMPT_VERSION="comp-choice-v0"` 常量、两条 rank 日志的 `prompt=` 字段与版本锁定单测（`D1PromptTest`：选项对称去字母后全等、版本/题面/state 固定且可编码），未来设备证据可归因到 prompt 修订。证据等级维持 `loaded-only`：合成图形远离照片分布，失败只能否决“文案改进”主张，不能证明审美真值；审美质量的下一步唯一证据来源是独立标注照片集。原始数据与冻结 harness 归档于 edge-model-solutions `evaluations/d1-prompt-v1-eval-2026-10-18.json` 与 `scripts/d1-prompt-eval/`（含 README、预注册判据、复现命令）。

2026-10-18（深夜）构图语言扩类与候选侧融合（T10）——17 类上线；风格探针证实 d1 仍是位置应答器，种子注入仅作多样性改进：

- 规则侧：构图语言 11 类扩到 **17 类**，新增对称、竖线、水平分割、节奏、明暗对比、汇聚线六类，全部只用 96px 低级统计量（双侧翼纹理的左右镜像、竖边列普查、全宽水平长边界行、投影归一化自相关、Otsu 类间方差、左右半区双侧内收斜边），仲裁规则（slopePurity、Sobel 阶跃双行 ±2 行排除、lateralTexture、全帧结构几何中心取框中心）见本 SOP 第 1 节。每类新语法有合成纹理单测固定（含对抗用例：居中竖条不报对称、天空地面不报对称、竖分界报明暗而非水平、斜纹场仍归对角线），advisor 单测共 22 条，全模块 103 条 JVM 单测通过，`:app:assemblePreview`（R8/lintVital）通过。
- 融合侧（T07 教训：不动 prompt）：`enrichWithStyleSeeds` 在 top3 无任何证据风格契合 ≥0.18、且存在场景证据 ≥0.60 的风格时，用全池中该风格契合最高的裁切替换第三名（IoU<0.80、能量分 ≥ 第三名−0.10 且 ≥0.45、契合 ≥0.35），蓝框首选永不替换，题面仍为 `comp-choice-v0`。
- AVD 真实帧（emulator-5580，软件渲染）：竖屏虚拟房间首选稳定三分，候选持续含明暗对比（亮窗暗室，新类真实触发）；横屏 WM 配置下首选切为三角形（121/121），候选含居中/明暗/三分——建议类型确实变多且随画面配置变化。d1 在软件渲染模拟器跑满 29.8–30.0 s 预算判"AI 比较不稳定"保留蓝框，为既有已知限制非回归。
- 模型侧评估（edge-model-solutions `scripts/d1-style-seed-eval/`，独立冻结 harness、题面与几何镜像真机管线）：六风格 416×416 合成源帧，每帧 GOOD（保结构，多为全帧）+ BAD-1/BAD-2（角落切片/切走结构）三裁片，GOOD 轮转 A/B/C 共 18 张判别 sheet + 2 张同图偏置。预注册判据：≥12/18 内容跟随；约 9/18 且偏置 max-prob ≥0.40 判位置应答器→仅多样性、门闸不放松。真 pin 版权重（4ebc1b9）、桌面 ORT 1.22、4 线程实测：argmax 正确 **5/18**，过闸正确 5/18，好图在 A/B/C 位时 4/0/1，分风格对称 0、水平 0、竖线 1、节奏 1、汇聚 1、明暗 2；选中时物理槽分布 **A9/B0/C1**，另有 **8/18 弃权**（无 marker 过 0.10 门），偏置 max-prob **0.407**。
- 结论：让候选承载不同构图语法并未带来内容跟随——选中即锁 A 位、不确定即弃权，d1 在该探针上仍是位置应答器，且 5/18 低于 T07 v0 探针的 9/18（不同探针，仅作背景）。因此风格种子**只作为安全的多样性改进随版本发布**（场景证据真实支持、蓝框首选不变、0.10 门闸不放松、框坐标永远来自规则层的契约不变），不得宣称模型排序质量提升；证据等级维持 `loaded-only`，审美真值的唯一下一步证据仍是独立标注照片集。原始数据归档于 edge-model-solutions `evaluations/d1-style-seed-eval-2026-10-18.json`。

2026-10-19（凌晨）构图语言第三波（T32）——17 类扩到 **21 类**，零先验基分与 96px 统计不变：

- 新增四类及量化判据（详见第 1 节）：`HORIZON_SPLIT`（分界 0.40–0.60 中段腰斩，与近三分线的 HORIZON 互斥，文案给抬高/压低机位方向）、`TOO_TIGHT`（外 8% 三边拥挤 + 中心不空 + ≥7/9 格繁忙的 edgeCrowd，方向场/水平分界抑制）、`EDGE_CUT`（单边沿边覆盖率 30–90%、双段（7% rim + 内 7%）同时高密、沿边两端 ≥4% inset、对侧非留白；只允许 scale≥0.94 近全幅裁切携带，方位化文案 L/R/B/T）、`MINIMAL`（≥72% 近静默像素 + 至多一个活跃格 + 该格能量集中；成立时压 85% 方向性留白读数，几何门下放到 scale≥0.55）。
- 交叉仲裁修订：节奏自相关最小 lag = n/8（<1/8 图宽是纹理颗粒不是节奏元素）；`REPETITION` 与 `CONTRAST` 在 edgeCrowd 饱和时分别乘 0.9/0.85 让位 TOO_TIGHT——随机 4px 块纹理实测在投影上形成等高峰链（任何 4 倍数 lag 都满足「半 lag 负相关」三条件），单靠 lag 门无法与柱廊节奏区分，四边无呼吸空间才是判别量。种子注入改 per-style 表达检查（旧逻辑「任一证据风格已表达就全不注入」会让同帧强 CONTRAST 永久挡住贴边警告），EDGE_CUT 列证据风格首位且 seed 命中时配 ranked 池全幅裁切绕过 IoU<0.80 多样性门。
- 测试：`FastCompositionAdvisorTest` 22→**31 测**，新增 9 条（腰斩分界/三分线不误判、满框纹理→太满、居中竖柱既不太满也不贴边、规则点阵既不太满也不极简、左缘出画→贴边且绑定全幅框与向右文案、离边窄条不报贴边、安静场上的小团块→极简、无特征场与垂直明暗分屏上新四类全部沉默），含 4 个确定性合成场景 builder；全模块 JVM 单测通过（app 165 条、color/camera 全绿），`:app:assemblePreview` 通过。
- AVD（emulator-5580，Android 35 软件渲染，VirtualScene 固定房间，loaded-only）：实时建议链路正常，规则耗时 1–23 ms，竖屏首选稳定三分（与 T06/T10 基线一致），横屏与 28/35/50mm 数字变焦切换首选不退化、无异常恒定句，房间场景下新四类零误报，logcat 无 FATAL。固定虚拟场景站位不可移动（主机键鼠独占），无法在真实帧制造居中分界/贴边/满框/极简正例，四类新语言的正确性目前只到合成帧单测级；真机街景命中率与阈值校准仍 not-verified，审美真值唯一证据仍是独立人工标注照片集。

2026-10-19（凌晨）建议动作化（T33，main 提交见 queue 记录，loaded-only 真机 not-verified）：

- 纯函数 `app/composition/CompositionActions.kt`（T28 §2.2「动词+方位+幅度、中心差/尺度差纯函数可单测」落地）：推荐框中心 vs 画面中心差给平移/俯仰（±0.06/0.12/0.20 死区与三档），1−scale 给靠近（0.07/0.15/0.25；crop≤全幅故无退后档，退后由 T32 三类内置动作风格承担）；多轴 pan→tilt→dolly 中文串句，THIRDS 按框内能量重心 ±0.15 锚「让主体落在×三分线」、CENTERED 锚「让主体居中」；EDGE_CUT/TOO_TIGHT/HORIZON_SPLIT/CONSERVATIVE 弃权（前者文案已动作化、后者反动作）；近全幅中性框任何风格都不出动作。
- 接线：`CompositionCandidate.action`（advisor 输出时按最终 style + crop.u/v 算好，conservative 兜底无）；`CompositionSuggestion.actionText` 不经模型 parse，所以 d1/SmolVLM 都无法注入；模型重排复用同一候选对象，AI 选中项自动带规则动作句；overlay 去重比较 actionText（档位跳变会刷新卡片）；左下建议卡动作句 13sp 主行、术语文案 11sp 副行（无动作的风格保持单行）。
- 测试：新增 `CompositionActionsTest` 12 测（各方向/三档/死区含浮点边界 s=.94/.92、多轴顺序、三分锚点四线、重心居中不编锚点、内置动作风格与 CONSERVATIVE 弃权、全幅无动作遍历全部风格），FastCompositionAdvisorTest 加端到端契约测（4 场景三类内置风格/全幅无 action、其余候选 action 必含动作动词且不等于 reason、uniform 保守场零动作）；advisor 32+动作 12，app 模块 177 测全绿，color/camera 全绿，assemblePreview 通过。
- AVD（emulator-5580 固定房间，loaded-only）：竖屏实时建议真实帧首选出「靠近一点，让主体落在左三分线」13sp 主行 + 「三分构图 · …」11sp 副行，2–25ms 无 FATAL；28/50mm 数字变焦与横屏不改变归一化几何故句子恒定（同画面同建议为正确行为，overlay 去重亦如此设计）；站位移动无法经 adb 驱动（主机键鼠独占 VirtualScene，T28/T30/T32 同一限制），「按建议移动后句子变化」的正向证据只到纯函数单测级（几何输入变→句子变，12 测覆盖），真机站位反馈 not-verified，等 T34 对齐正反馈与真机。

### T34：对齐正反馈（到位变绿 + 小勾，可选轻震，无声）

> **2026-10-19 T40 修订**：本节的到位判据（inset ≤ 0.03）、`CONFIRM_FRAMES = 8` 与「绿框 1.3s 自动恢复」均已被下方 T40 小节取代（改为几何带 `isAligned` 0.09/0.88、5 帧确认、持续绿相位）。本节保留为历史；现行实现以 T40 与代码为准。

T28 §2.2 标准 3 的落地。纯逻辑在 `composition/AlignmentFeedback.kt`：`isFramed(crop, style)` 取四边 inset 的最大值 ≤ `FRAME_TOLERANCE = 0.03`（crop 档差 0.08，.92 档至少留 4%，故 3% 实际等于全幅）；`EDGE_CUT / TOO_TIGHT / HORIZON_SPLIT / CONSERVATIVE`（复用 `CompositionActions.ACTION_BUILT_IN`，已提为 internal）永不判定到位——问题风格的目标不是全幅居中。`AlignmentTracker.observe(framed, actionable)` 每分析帧喂一次：必须**先**见到带动作句的非全幅建议（`seenActionableNeed`，防止一开机画面本来就好也被夸），再连续 `CONFIRM_FRAMES = 8` 帧（250ms 节拍 ≈ 2s，防单帧闪烁）判定到位，每次到位只发一个 `AlignmentEvent.ALIGNED`；保持到位不重发，偏离 `REARM_FRAMES = 3` 帧（≈0.75s）后重新武装（偏离本身即新需求，不再要求新动作句）。`reset()` 接场景切换（`clearVisibleAdvice`/`onSceneChanged`）与 `invalidateView`/会话重启。

接线：状态机在 `CompositionAssistant.showAdvice` 内、同景去重提前 return **之前**喂入——静态近终景每帧复用同一 overlay，这些帧正是要累计的确认 streak；事件写一次性 `alignmentPulse: StateFlow<Long>`（elapsedRealtime 时间戳）。UI（`CameraScreen` Viewfinder）收脉冲后推荐框描边由蓝/黄改绿 `0xFF54C97E`（2dp→3dp）、三分线随之转绿、框内**右下角**小勾一现（黑底圆 + 绿勾，避开左下建议卡与右上可选直方图片），1.3s 自动恢复。震动为 opt-in：`AppSettings.alignmentHapticEnabled` 默认 false，SettingsJson 加布尔字段不 bump version（缺字段旧装默认 false）；开启时仅 `VibrationEffect.createOneShot(20ms, amplitude 60)` 一次极轻震，**无任何声音路径**；Manifest 加普通权限 `VIBRATE`。拍摄设置「构图辅助 · 实验性」分区实时构图片下新增「到位轻震反馈」ToggleRow + 说明文案。不拍照链路、不改规则层、不改候选坐标。

- 测试：新增 `AlignmentFeedbackTest` 10 测（阈值 3% 边界含单边、问题风格四不触发、开机即好帧不夸、8 帧连续才发且之后不重发、单帧闪烁打断后 streak 归零重数、latch 后 1–2 帧偏离不误 rearm、持续偏离 3 帧后可再确认一次、无动作需求不武装、reset 重来）；CompositionActions 12 + advisor 32 等全量 app/color/camera 测试绿，assemblePreview 通过。
- AVD（emulator-5580，loaded-only）：VirtualScene 站位仍不可经 adb 驱动，走 T31 路线一次性注入合成轨迹（前 4 帧非到位且带真实动作句、第 5 帧起到位）：logcat 实测事件在连续第 8 帧单发一次（draw=11 ALIGNED、其后全部 null），截图确认整框转绿 + 小勾渲染；随后删除全部临时代码（`grep T34-TEMP/T34TEMP` 源码零命中），全量重测重包，逐 dex `strings | grep` 零命中。设置面板实测开关可切、force-stop 重启后 checked=true 持久化（已关回默认 false）。正式包冒烟 8s 实时建议正常无 FATAL。真实「人按句子走到位」的端到端反馈仍受站位限制，not-verified，留真机；审美质量不由此特性声称。

### T35：长按建议卡「试取景」（纯显示变换，照片永远全幅）

T28 §2.2 标准 4 的落地。纯逻辑在 `composition/CropTrial.kt`：`CropTrial.from(crop)` 把建议框换算成预览变换——等比 scale 取 `max(1/width, 1/height)`（cover，短边撑满、长边溢出裁切，绝不拉伸），变换原点取框中心 `(centerU, centerV)`；四边最大 inset ≤ `AlignmentFeedback.FRAME_TOLERANCE`（即全幅）返回 null，全幅建议没有可试的景。与 T34 共用同一「等于全幅」阈值。

接线（`CameraScreen` Viewfinder）：预览 Box 加 `clipToBounds()` 防止放大后画出 3:4 画面；`AndroidView` 用 `graphicsLayer` 在长按期间设 scaleX/scaleY + TransformOrigin(框中心) + translation 把框中心移到屏中心。建议卡 Column 挂自定义 `pointerInput`（`awaitEachGesture` + `awaitFirstDown` 消费 down + `awaitLongPressOrCancellation`），长按成立才进入试取景并循环等到手指全部抬起还原；消费 down 也挡住了下层 Canvas 的点按对焦/AF-L。试取景期间：隐藏蓝色推荐框/遮罩/三分线（框已铺满整个取景器，再画是遮黑）、隐藏顶部 pill 行（避免与提示条重叠）、顶部居中显示黑底胶囊「试取景 · 松开还原」。场景切换发布不同 suggestion 时，持有的 trialCrop 与当前框不等即自动结束（`activeTrial = trialCrop?.takeIf { it == suggestedBox }`）。**铁律**：这是 PreviewView 单一 use case 的显示层变换，ImageCapture 是独立 use case，不碰拍照链路、不改规则/模型框坐标；松手即还原。

- 测试：`CropTrialTest` 7 测（全幅与 3% 容差 null、居中 .92 框等比 1/.92 且中心不变、偏置框中心随动、cover 取短边、最紧 .68 档缩放上界、窄条沿薄边缩放、合法框约束下的退化情形——NormalizedBoundingBox 契约禁止零/逆序尺寸）；全量 app/color/camera 测试绿，assemblePreview 通过。
- AVD（emulator-5580，loaded-only）：用 `input motionevent DOWN/UP` 保持长按态截图——长按中预览按 1/.92 放大、框线遮罩隐去、提示条出现（初版与「构图」pill 重叠，改为长按期隐藏顶部行后复测干净），抬手后蓝框/遮罩/三分线完整还原；**试取景长按保持中用音量键拍照**，MediaStore 实测产物 `STREET_...jpg` 为 960×1280 全幅 3:4（测试后删除），证明显示放大不影响成像；无 FATAL。站位移动限制同前，但本特性不依赖站位。审美质量不由此特性声称。

### T37：建议效果回归度量骨架（edge 仓探针，可复跑基线）

「够不够多、有没有用」的回归基线落在 edge-model-solutions：`scripts/advice-regression-eval`（Gradle JVM，零 Android/零模型依赖）+ `evaluations/advice-regression-baseline-2026-10-19.json`。探针逐字快照在产纯 Kotlin（`FastCompositionAdvisor`/`CompositionActions`/`AlignmentFeedback`/`CompositionStyle` 整文件 + 从 `CompositionPipelineContracts.kt` 逐字抽取的 `NormalizedBoundingBox`，源为 street-snap `e891092`，包名不变、internal 同模块可见，README 记录再同步步骤），跑 204 个确定性 96×96 几何场景（1 平坦基线 + 3 尺寸×7×7 位置块扫描 + 贴边/拥挤/水平分界扫描/竖条/对角/汇聚/对称/三角/半明半暗/画框/前景层次/小主体留白），看数前固定四个指标：M1 首选风格覆盖率（排除 CONSERVATIVE/GENERIC，分母 19）；M2 频次分布（ln(21) 归一化熵、最大类占比、带动作句比例）；M3 变化率（同帧重复必须零变化；扫描相邻边变化率；全序列平均 run 长度）；M4 对齐闭环——对每个带动作句的建议，按 `plan` 的方向/推拉直接改场景参数（每步走几何差 60%、12 步预算、主体不出画），重渲染后喂真实 `AlignmentTracker`，以发出 ALIGNED 为成功；另带状态机门闸健全性（无需求不夸、12 帧需求后第 8 帧单发）。

首版基线（2026-10-19，loaded-only，复跑除延迟外逐字节一致）：覆盖 10/19；首选分布 THIRDS 115 / MINIMAL 51 占 81%（熵 .44、最大类 .56），83.3% 带动作句；同帧重复 0/204 变化，扫描变化率 .247，平均 run 3.64 帧；M4 对齐 49/147（33%），98 个失败全部卡在 inset 0.03–0.10——结构性原因：crop 尺度档 0.68/.76/.84/.92/1.0 中 .92 档四边 inset=.04，而 T34 的 `FRAME_TOLERANCE`=.03，听话走位停在 .92 首选就永远不亮绿；126/147 闭环终态被读作 SYMMETRY。多个场景族首选与几何意图错位（拥挤边框→SYMMETRY 而非 TOO_TIGHT、汇聚线→CENTERED、三角块→THIRDS），作为「合成几何 vs 规则触发条件」的基线事实记录，首版看数后不调场景（改进套件几何是另立预注册的 v2）。本骨架是回归绊线而非质量声称：不含模型门闸/排序/调度，walker 假设操作者精确听话，桌面耗时（中位 1.88ms）非真机；审美仍 not-verified，唯一下一步是独立人工标注照片集。

### T40：构图建议整体闭环打磨（对标 + 治闪烁 + 可感知的「到位」）

用户反馈（2026-10-19，逐字要点）：「那个框框感觉一直在闪，很像是早期的工程版本」「什么时候算是构图可以了我好像没感觉到，一直在说各种建议，按照建议调整后也不会变成 ok」。对应三个结构性缺陷，逐一闭环：

**① 到位判据结构性不可达 → 几何化的 `isAligned`。** T34 的 `isFramed` 要求四边 inset ≤ 0.03，但规则裁切档是 0.68/.76/.84/.92/1.0，听话走位收敛到的稳态首选正是 .92 档（四边 inset=.04），**永远差 1% 不亮**——T37 M4 实测 49/147=33%，98 个失败终态全部卡在 inset 0.03–0.10。T40 改为 `AlignmentFeedback.isAligned(crop)`：中心偏移 `|cu−.5|、|cv−.5| ≤ ALIGN_CENTRE = 0.09` 且尺度 `s ≥ ALIGN_MIN_SCALE = 0.88`（几何上 s≥.88 的居中框中心偏移最大 .06，居中性自动满足；0.09 略宽于平移触发死区 0.06，出入带不对称迟滞防边界抖动）。`isFramed`（.03）保留给 T35 长按试取景。`FrameGuidance` 四态：NEEDS_MOVE（正向风格但还有移动）、ALIGNED（正向风格且进带）、PROBLEM（EDGE_CUT/TOO_TIGHT/HORIZON_SPLIT——成功定义是问题消失并进到正向位，不是变安静）、NEUTRAL（CONSERVATIVE，不武装不前进）。**对照实验**：曾把尺度带放宽到 .84，M4 仍 98/147 无变化——逐步 trace 证明真正卡点是探针走位预算（见③），.84 零作用，遂回退 .88（最小放宽、数据决策，不自欺）。

**② 确认太慢且成功只闪一下 → 5 帧确认 + 持续绿相位。** `AlignmentTracker`：`CONFIRM_FRAMES 8 → 5`（250ms 节拍 ≈1.25s；手持街拍很难稳满 2s）；`REARM_FRAMES = 3`（≈.75s）不变。仍是「先见需求（NEEDS_MOVE/PROBLEM 武装，开机即好不夸）→ 连续 5 帧 ALIGNED 发一次脉冲并 latch」，但新增**持续相位** `alignmentConfirmed: StateFlow<Boolean>`：latch 期间恒真，偏离 3 帧解 latch 并重新武装，与一次性边沿 `alignmentPulse`（只触发可选轻震）区分。UI 绑定持续相位：绿框 `0xFF54C97E`、描边 2→3dp、遮罩 .26→.10、三分线 .40→.26、对勾常显，不再 1.3s 消失；建议卡换成单行绿字「构图到位 · 可以拍了」，隐藏动作句/计时/easterEgg 行。**克制度（对标 iPhone 水平仪/FocalAi）**：成功句不是永久横幅——确认相位保持 2.2s 后建议卡 320ms 淡出，稳态只剩绿框+对勾；一旦失锁，卡片立即随下一条移动句淡回。

**③ 移动中框每 250ms 熄灭 → 600ms 清除宽限 + 交叉淡化。** 闪烁根因是状态竞态而非渲染：用户跟建议移动时，每分析帧的场景签名失配 → `onSceneChanged`/`visibleScene==false` → `clearVisibleAdvice` 同步把 overlay 置 null，而 Default 线程上的 advisor 同帧跑完才重发，null 中间态上屏约 4 次/秒；tracker 也每帧 reset，导致移动中永远攒不满确认帧。修法分两层：tracker/`alignmentConfirmed` **仍即时重置**（跨场景绝不误夸），视觉 overlay 改为 `requestGracefulClear()` 延迟 `OVERLAY_CLEAR_GRACE_MS = 600ms` 再清（容得下两帧 250ms），同帧 `showAdvice` 立即取消宽限；只有真换景且 600ms 内无新建议才清。显隐从硬切改交叉淡化：`SmoothCropState` 增 `alpha: Animatable`，overlay 消失时保留末帧几何 180ms fade-out，新框 220ms fade-in，所有描边/遮罩/三分线/对勾颜色乘 `guideAlpha`。

**对标调研结论（2026-10-19，公开资料 + 产品实测）**：iPhone 相机水平仪——近水平才出现、双十字重合变黄且同帧一次轻震、偏离即恢复、达成不庆祝；Muse「等虚线变绿再拍」；FocalAi 双震动+整屏变绿+「Now.」只留给决定性瞬间（高频功能庆祝必须克制）；Mola 金框锁定；Framed 快门在到位时点亮。行业惯例区间：淡入淡出 150–250ms、清除宽限 300–700ms、建议最小驻留 1.5–3s、入置信 .65–.75 / 出 .45–.55（迟滞）、同屏 1 条；ML Kit 连续帧同值才采信+忙时丢帧，One Euro Filter（mincutoff≈1.0Hz、beta≈.007 起调）；HIG 要求触觉与视觉同帧。**各产品的毫秒级迟滞参数均未公开**，本条如实标注，不取来路不明的精确值当权威。本轮落地交叉淡化、清除宽限、成功态克制度；One Euro 几何平滑、建议最小驻留、拍后评分复盘等留作后续（知识教育侧的行业惯例：高可靠信号——水平/陀螺仪、人脸人体→三分/留白/距离/切边——才自动建议，黄金螺旋等专业法则一律做手动叠加库；拍后评分与实时流分开）。

**M4 探针闭环（edge-model-solutions，loaded-only）**：除判据外，walker 的听话走位预算 `LOOP_BUDGET 12 → 16`（小主体第 9–10 步才进带，原 12 步容不下「几何收敛 + 5 帧确认」共需窗口；进带后持机不再施加 move，避免质量越过带重置 streak）。结果 M4 **49/147=33% → 147/147**（仅改判据为 98，判据+预算才满格），成功步分布 `{8:49, 10:31, 11:18, 14:49}`；门闸健全性 noPraise=0、armedPulseAt=17（12 帧需求后第 5 帧）、armedPulses=1。M1/M2/M3 未动 advisor，维持覆盖 10/19、熵 .436、topShare .564、actionable .833、scanChange .247。快照源标注 `street-snap@ee8d53c + T40 alignment rework`；冻结基线 `advice-regression-baseline-2026-10-19.json` 未触碰，新结果写 `advice-regression-t40-2026-10-19.json`。

**AVD 端到端验证（emulator-5580，loaded-only）**：VirtualScene 站位仍不可经 adb 驱动，沿用一次性合成轨迹注入（move=THIRDS 偏置小框带动作句 → aligned=CENTERED .92 框 → hold → 3 帧 drift → realign；另有 grace 模式验宽限）。排查中发现一个方法论要点：注入开头把配置停成 enabled=false 后，**CameraViewModel 的持久化 settings 收集器约 0.5s 后用真实开关重新 `configure` 回 enabled=true，重启会话并每帧场景失配 reset tracker**，导致合成 streak 永远被真实帧打断（现象是 align/hold 全 false、realign5 偶然转 true）；加临时日志拿到 `configure RESTART` 的完整栈与时间戳后，注入期加总闸抑制 ViewModel 重配置/真实帧/会话 reset，才隔离出纯合成轨迹。最终 logcat 逐帧符合设计：move1–6 武装 false → align5 转 true 并贯穿 align6/7、hold；drift1–2（3 帧窗内）保持 true、drift3 解 latch 转 false → realign5 重新 true；grace 模式移动中 overlay 全程存活、真 cut 后 +300ms 仍在、+700ms 已清。截图核对蓝框建议 / 绿框对勾+成功卡 / 容忍窗 / 失锁 / 再绿 / **卡片淡出后只剩绿框** 六个相位。验证后删除全部临时代码，源码 `grep t40Verify|T40_VERIFY|T40Verify|TEMP-T40|synthetic` 与重包 dex strings **双零命中**；294 个单测全绿（AlignmentFeedbackTest 17 测含 .92 居中即 aligned、.84 仍太紧不确认、偏置紧框不确认、PROBLEM 闭环、rearm 等）；正式 preview 包装机冒烟无 FATAL。

- 证据等级：状态机时序与 UI 相位为 AVD 合成轨迹 **loaded-only**；桌面探针为合成几何，不是照片。真实「人拿手机按句子走到位」的端到端手感、真机（荣耀 Vs3）与真实街拍审美仍 **not-verified**，唯一下一步是独立人工标注照片集 + 真机站位；本特性不声称审美质量。

### 2026-10-20 晚批次（T41–T58）：实时 AE 肩屏、OSD 对标重排、HDF 双层辉光、SmolVLM 下架、预热与开发者诊断

用户通宵指令的逐字要点：实图功能「还是比较慢，按照这个方向继续优化」；「HDF 的特效还是不明显……高光的时候那个灯，它会有个光晕在那里面」；「ISO、快门虽然是 Auto，要显示它当前实时的是多少，不能只显示个 Auto」；「滤镜的建议也可以给个位置」；「EV 曝光补偿那个亮暗位置挡着了」；光圈/直方图/EV 的位置「整体结合最佳实践，主流相机 APP 都放哪里」；「模型前两个是不是都不用了？淘汰掉可以去掉」；建议模型「就显示实际的名字，不要有决策这样的东西」；「放一个开发者选项，比如当前的推理性能」；「拍摄的档位 P/A/S/M 可以放到界面上，跟正常相机一样」（手机光圈固定，无 A 档）；铁律是「别优化某个点之后牺牲了其他的体验」。

**① 实时 AE 读数：真实 Camera2 CaptureResult，绝不只显示 AUTO（AE-1/AE-2）。** camera 模块新增 `LiveExposureReading(iso, exposureTimeNs, aperture, focalLengthMm, frameTimestampNs)`；`CameraController` 用 `Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback`（本 CameraX 版本只有单参重载，回调在 CameraX 自己的相机线程，只读数与线程安全 `StateFlow.update`，不需额外 executor）挂在**始终绑定的预览 use case** 上，`onCaptureCompleted(TotalCaptureResult)` 读 `SENSOR_SENSITIVITY` / `SENSOR_EXPOSURE_TIME` / `LENS_APERTURE`（>0 才取）/ `LENS_FOCAL_LENGTH` / `SENSOR_TIMESTAMP`。200 ms 自节流，AE 收敛后去重不重组 UI；回调带 session generation，解绑/重建后旧会话不能发陈旧值，解绑时置 null。读数经 `CameraState.liveExposure` 透传到 UI。P/S 档显示设备 AE 的实时真实值，M 档显示拨盘请求值，无光圈能力就不出现光圈字段（T11：不虚构）。

**② DSLR 肩屏范式取代画面内 PARAMS 卡（OSD-1…OSD-5，对标主流相机 App）。** 快门上方黑底控制区新增常驻等宽字体条 `ExposureShoulderStrip`（大横屏侧栏同组件复用）：**拍摄档位 chip（P/S/M，无 A）→ 固定光圈「f/x.x」（仅 `fixedAperture` 非空）→ 实时快门（1/125 s 格式）→ 实时 ISO → 新鲜读数绿点 → EV chip**。档位 chip 用锚定在黑底区的 `DropdownMenu`（绝不浮在画面上），仅当 `CameraCapabilities.exposureModes` 多于一档时可点并显示 ▾，每档带中文说明；该 AVD 只暴露 P，故 S/M 下拉与 M 档拨盘值仅代码走查。EV 从原来挡画面的亮暗指示改为点按 chip 弹出锚定菜单：−3…+3 滑条 + 数值 + 归零钮（0 位禁用）。画面内旧 PARAMS 只读卡整组删除；`ViewfinderOsd` 收敛为 **OFF/HISTOGRAM 两态循环**，旧持久化值 PARAMS 仍可 decode、按 HISTOGRAM 渲染（`showsHistogram()`，单测覆盖迁移）；直方图缩为右上角 100×42 dp 半透明小窗。影调/滤镜建议与胶片徽章在竖屏 framed 布局下固定在取景屏外的铭牌行（只在横屏路径或铭牌隐藏时浮在屏内），曝光亮暗提示卡固定在 HDF 钮下方，位置不再随其他控件漂移。所有下拉都锚定在黑底控制区，取景画面内零悬浮菜单。

**③ 推理加速方向继续：session 预热 + 丢帧审计（PERF-1/PERF-2）。** PERF-1：模型安装查找、bundled asset 导入与三个 `ALL_OPT` OrtSession 的构建移到 `Dispatchers.IO` 上的 session-scoped 预热 job，在 `ImageAnalysis` 绑定**之前**启动，图优化与 CameraX 绑定重叠；runtime 经 ready-deferred 发布，会话在此期间被拆掉则自关闭，不发布陈旧 runtime。PERF-2：审计确认四层丢帧/合并，无需改码——`STRATEGY_KEEP_ONLY_LATEST`、CONFLATED 单消费者帧闸、串行帧时钟里的 in-flight gate + rest、模型侧 single-flight（busy flag + 推理 mutex + model job），旧帧被替换回收、永不排队，慢设备只产生更少的完整排序而不是积压。推理工程库 case 文档 roadmap 两项已标 applied。

**④ HDF 双层光学辉光（HDF-1/HDF-2，详见 hdr-film-research.md 本轮小节）。** 关键命名事实：UI 上标「HDF」的按钮实际驱动 `hdrEnabled → uHdr`（legacy uniform 名），「柔光」chip 驱动 `hdfEnabled → uHdf`。bloom 从单一被拉宽的模糊对改为**两对独立 FBO**：tight core（长边 ≤384 px）+ 独立宽光晕（长边 ≤192 px，下采样自带 box 预模糊）；`scatterGap=(1−color)` 保证暗部零抬升；亮部键控为 max-channel 软膝（彩灯也能出辉光）。排查到一个实质 bug：宽光晕 FBO 之前只在柔光 uHdf 路径准备/绑定，真正的 HDF 按钮（uHdr）路径采样不到宽层——已修为 `wideWanted = diffusionOn || hdrOn`，两种 look 都绑 unit3；uHdr 分支改为 core*0.70 + wide*0.45 的双层叠加（amount=mix(.22,.68,uHdr)）。AVD 静态房间无点光源，以电视棋盘格暗格做代理量化：两种开态暗格 luma 抬升 +24.03/+30.35（0–255），关态同区噪声级 +3.84，差分 p90 +127（n=83,929 暗像素），无 GL 错误、无 FATAL、拍照正常。**真实路灯/灯泡夜景辉光仍 not-verified，需真机夜景人工核对。**

**⑤ SmolVLM 下架与真实模型名（MODEL-1/MODEL-2）。** `EdgeModelKind` enum 删除，catalog 仅 `d1-omni-600m`（`EdgeModelCatalog.DEFAULT_MODEL_ID` 单一事实源，显示名「d1-omni 600M」，无「决策/decision」字样）；`EdgeModelStore.prepare` 固定三张图（vision_encoder_fp16 / embed_tokens_quantized / decision_q4）+ 五个配置/LICENSE 文件；`SmolVlmRuntime.kt`（478 行）与 debug `CompositionRankingValidation.kt` 删除（ByteBpeTokenizer 保留，D1 runtime 在用）。旧设置中的 smolvlm-256m/500m/未知 id 经 SettingsJson decode 的 `EdgeModelCatalog.find(...) ?: DEFAULT_MODEL_ID` 自动迁移，显式模型选择 Row 从构图面板移除，只显示实际模型名，单测覆盖 256m/500m/removed/缺字段全部迁移、d1 保留、PARAMS OSD 遗留值。

**⑥ 开发者诊断折叠区（DEV-1）。** 构图助手面板新增默认折叠的「开发者诊断」：实际模型 id + revision 前 7 位、运行态、**AI 推理 wall-clock 历史（最近 10 次，成功/超时/失败都记，显示最近/中位/最小/最大与样本数，`CompositionUiState.inferenceHistoryMs`）**、画面分析 fastAdviceMs、调度预算与冷却、上次结果六态中文映射、实时测光（ISO/快门/f//实际焦距，无读数显「尚无相机读数」）、设备曝光档与光圈情况、ISO/快门/EV 能力范围。数据全部来自真实 state/capabilities/CaptureResult，不造性能数值。

- 测试：新增 `ExposureReadoutFormatTest`（快门格式化 8 例：1/250–1/30、1/10–2.5 s），SettingsJsonTest 改模型迁移与 OSD 两态/PARAMS 兼容；全模块 `./gradlew testDebugUnitTest` **296 条全绿（app 209、camera 61、color 26，0 failures/0 errors）**，`:app:compilePreviewKotlin` 与 `:app:assemblePreview`（含 R8/lintVital）通过。
- AVD 回归（emulator-5580，1080×2340，loaded-only；shot-01…17 相位）：肩屏条实时读数（实测 1/100 s、ISO 200、绿点；诊断页另见 f/4.0、实际焦距 1.0mm）、档位 chip（该 AVD 仅 P 档无 ▾）、EV 弹层（−3…+3、归零钮 0 位禁用）、模型名「d1-omni 600M」无决策字样、开发者诊断展开、HDF 差分、拍照后缩略图正常、无 FATAL。
- 证据边界：全部数值与视觉结论为 qemu 软件渲染 AVD 上的 **loaded-only**，禁止外推真机。**not-verified 遗留（荣耀 Vs3）**：实时 AE 数值与绿点、S/M 档下拉与 M 拨盘、夜景真实点光源辉光目视、大横屏侧栏、真机推理耗时中位数（诊断 UI 已具备采集能力）。

### 2026-10-20 深夜追加（T59–T63）：移动方向箭头、面板常驻性能、推理链路 v1 入库

用户追加三点：「起码要让人知道怎么挪动取景框，右下角或合适位置来个箭头辅助，往哪里挪动态一点」「模型性能可以在面板里显示」「推理链路项目里把当前推理链路放进去当第一个版本，考虑后续优化方向」。

**① 移动方向箭头（T59/T60）。** 新增纯几何 `composition/MoveGuide.kt`：`MoveGuides.fromCrop(crop)` 以建议框中心相对全幅中心的偏移给单箭头方向（八方向 + FORWARD），死区**复用 `CompositionActions` 的 PAN_MIN/TILT_MIN/DOLLY_MIN**，保证箭头与建议卡动作句永远一致（任何平移/俯仰偏移存在时单箭头取平移轴合成，居中但偏紧才指 FORWARD；贴边/太满等内置问题风格绑定全幅框，天然无箭头）。`GuideDirection.arrowAngleDeg`（chevron 0° 朝右、八方向每 45°）与 `arrowLabel`（中文无障碍标签）同为可单测纯逻辑，12 条 JVM 单测覆盖八方向/死区/优先级/角度/标签。UI 为预览屏右下 38dp 半透明圆内 chevron：760ms 往复沿自身方向漂移 + 透明度呼吸，FORWARD 用上下对向双 chevron；仅蓝框建议存在、未进绿色对齐相位、非长按试取景期显示，180ms 淡入淡出；点击仍穿透给点按对焦（指示器不可点）。AVD verified：靠近相位右下双 chevron 出现且 content-desc 为「移动方向提示：靠近被摄主体」，对齐绿相位箭头隐藏；VirtualScene 站位不可 adb 驱动，八方向的屏上目视由单测保证，真机走位手感 not-verified。

**② 面板常驻推理性能（T61）。** 构图助手面板在模型名行下新增常驻「推理性能」区：AI 推理最近/中位/最小/最大（最近 10 次 wall-clock，成功/超时/失败都记）与画面分析 ms，未安装/未跑过有明确占位文案；折叠的开发者诊断保留模型 id/rev、运行态、调度、上次结果、实时测光与能力范围（耗时两行上移避免重复）。

**③ 推理链路 v1 冻结入库（T62）。** inference-engineering 新增 `docs/on-device-composition-pipeline-v1.md`：v1 十阶段表（帧源/四层丢帧/96px 规则/三候选/场景去重/512 contact sheet/三 ORT session/双门闸+一致性/自适应节奏/UI 闭环，含源文件名）、数据流图与 loaded-only 数值、六条 v1 不变量（丢帧不排队、弃权一等、照片不可变、只显真实值、模型文本不可信、先测量）、已知瓶颈排序与 V2-1…V2-8 优化方向（384 降档桌面探针 0.496× 的证据与契约风险、INT8 vision、NNAPI 审计、线程亲和、取帧开销、持续性能/功耗、级联价值测量），README 索引同步；v1 冻结基线为 street-snap f809fc2，后续以版本修订追加。

- 测试：MoveGuideTest 12 测，全模块 **308 条全绿（app 221、camera 61、color 26）**，compilePreviewKotlin/assemblePreview 通过。

### 2026-10-21（T69/T73）：复杂背景主体定位收紧 + 前中后景三层识别

用户诉求：「复杂场景下，它的主体其实还是存在的。你要识别到我的主体在哪里。然后告诉我怎么构图，然后前景、中景、后景，这些你都要识别到」；并指出「只有在白色背景下框才会缩小」是 bug——复杂背景下建议框也必须能收紧到主体。T69 的繁忙背景仲裁（质心锚定/切边赋权/内部 tie-break，edge 仓 eval README 有完整 0/6→6/6 记录）解决了强峰主体跨背景不变性；T73 补齐两个剩余缺口。

**① 弱峰主体路径（SubjectLocator，纯 Kotlin 无 Android 依赖）。** 繁忙背景抬高粗格均值密度后，中等主体的峰值常落在 0.45–0.60，被 0.60 强峰门直接推入保守弃权，框不收紧。新路径从最强粗格（seed ≥ 0.45）做 4-连通 BFS（join ≥ 0.30，对角不连通），外接矩形即主体图像统计位置（归一化 `SubjectLocus`，**不是人/物检测**）。接受弱峰必须**同时**过五维门：peak ≥ 0.45、有效能量比 ≥ 0.12、连通块占全部点火权重 ≥ 0.25、块占格数 4–40%、块内**色差能量占比** ≥ 0.40（`blockColorShare`：相干色块高、靠内部边撑着的 hash 纹理尖刺极低——这是区分「偶然尖刺」与主体的关键一维）。接受后弱峰帧整体走主体路径（组织场让位，避免栏杆节奏/立面柱列抢帧），候选尺度档增加 0.52/0.60 让框能收到主体周围；`FastCompositionResult.subjectLocus` 暴露归一化位置供后续链路使用。

**② 三层景深检测。** 96px 降采样按 y 分下/中/上三带（0.70/0.30 分界）：前景带看相对全幅的边缘能量增益（栏杆/近物）；中景带看「五个 1/3 宽滑动窗」的集中度 × 窗内绝对密度闸（中心主体与**三分线主体**都能被滑动窗包住——固定三竖列会把三分线上的主体劈成两半；绝对密度闸防止空带上的小远点靠「均值接近零」骗高集中度）；远景带走三路 max（对全幅均值、对中景相对增益、信号密度，防强前景压平远景）。三层取 `min` 再乘 `(1 − 0.6×horizontalSplit)`，任一单层缺失即为 0——单一繁忙带不能凭空发明纵深。新风格 `DEPTH_LAYERS`（「三层纵深」）亲和度还要求尺度 ≥ 0.80 且垂直居中（三层都要留在画面里），并对 VERTICAL/FOREGROUND_DEPTH 按三层强度让权；建议文案区分下方前景（栏杆）与上方前景两种站位。

**证据（loaded-only）。** APP 侧新增 `SubjectLocatorTest` 6 测（种子/4-连通/对角排除/权重份额/最强格平票）与 advisor 5 测（等密度抖动繁忙弱峰场必须 CONSERVATIVE、同场上左右主体收紧到 ≤0.76 且跨侧跟随、三层帧首选 DEPTH_LAYERS 且文案含前景/中景/远景且全幅、两平面对照不出三层、五维门逐维边界）。edge 仓逐字快照新增 `SubjectLocator.kt`（现五个整文件），38 帧复杂套件复跑（`advice-regression-complex-2026-10-21.json`）：冻结 204 帧首选分布**零差异**、M3/M4 不变，24 配对帧 M5 保持 6/6、mean/worst 漂移 0.0079/0.0157 逐位不变；新增 depth3_0/depth3_1（三分线主体）意图命中 2/2，depth2_0 平顶部两平面对照为 THIRDS。合成 96px 统计上弱峰端到端窗口很窄（可见色差团块几乎必让粗格饱和走强峰路径），弱峰路径的主要保证来自纯函数门测试与真机前的弃权回归；**真实杂乱街景的收紧效果、夜景点光源、真实雾霾/空气透视下的三层判定均 not-verified**，审美真值仍只来自独立人工标注照片集。全模块 **app 272 / camera 61 / color 32 单测全绿**。

### 2026-10-21（T80）：开发者诊断面板专业化——推理链路、真实分段计时、token/TTFT、显存、会话统计

用户诉求（逐字）：「开发者模式里需要说明 推理链路 当前推理性能 token 这些 ttft 比较专业一些 模型情况 显存这些 当前已完成会话次数 平均会话时长?」折叠区重做为六组：**推理链路**（640×480 KEEP_ONLY_LATEST → 96px 规则候选 → 512² sheet → FP16 视觉塔 1024 patches → Q4 嵌入 → Q4 决策头 3 logits → 换序双轮一致性 → margin ≥ 0.10 采纳，单飞串行慢则降频）；**推理性能**（规则层 fastAdviceMs、视觉/嵌入/决策三段耗时、含换序轮总耗时、最近 10 次中位/最小/最大、首结果延迟、调度预算/冷却、六态结果）；**模型情况**（id·rev、FP16/Q4/Q4 量化构成、清单字节、ORT 后端标签含 XNNPACK 是否真建图成功、三个 session 建图耗时、运行态、输入/输出 tokens、TTFT）；**本次启动统计**（排名作业四桶、采纳率、平均墙钟、前台会话次数/平均/本次时长）；**进程内存**；**相机/HAL** 原行不动。

三条诚实性约束：①所有耗时来自真实计时点——`D1OmniRuntime.rank()` 内三段 `elapsedRealtime` 与建图计时，`CropRanking` 新四字段（visionMs/embeddingsMs/decisionMs/promptTokens）以 -1 表未测，retired/其他 ranker 路径不伪造；超时/失败轮保留上轮真实值不清空。②token 数是固定 prompt `comp-choice-v0` 经 ByteBPE 真实 encode 的 `ids.size`（约 217），输出恒为 0；**TTFT 显式标注「不适用」**——d1 是单次判别式前向（3 logits softmax），无自回归解码、无生成 token、无 KV cache（见 inference-engineering `case-d1-omni-on-device.md` §8），最接近的真实指标是首结果延迟。③CPU 推理无独立设备显存，不虚构显存数：用 `Debug.MemoryInfo` 的总/native/Java PSS 加 native 堆分配（ORT arena、MediaPipe 计入 native），仅展开时 1s 采样，标题即注明「CPU 推理，无独立显存」。计数器放进程级 `InferenceStats` object（CompositionAssistant 频繁 restart 会清空内部计数），in-memory only 随进程清零；前台会话由 CameraScreen ON_RESUME/ON_PAUSE 驱动（旋转按设计计一次）。新增 10 测（app → 282）；AVD 六组渲染与实时采样 verified，PSS/耗时等 QEMU 数值 loaded-only 不外推真机，装上 d1 后的真机分段与采纳率 not-verified。

## 历史诊断：为什么先前没有框

此前直接让通用 SmolVLM 生成裁切坐标，实际输出包含 `[0,0,0,0]`、残缺坐标或普通描述，被合法性检查拒绝。相机输入与 ONNX 推理已打通；未经过本项目构图/定位训练的模型不能仅凭提示词保证坐标和审美能力。此前 Q4 语义探针曾连续两次正确识别画面右侧电视，描述任务也出现人物幻觉，不能据此证明可靠定位。

旧通用框任务在 Android 35 AVD 上，Int8 App 请求约 4.3–6.4 秒，视觉编码约 3.2–5.1 秒；Q4 约 16.4–17.5 秒。更早的 4.75 秒取消策略还会提前打断慢请求。这些都是历史路径数据，不能作为当前候选排序速度或推荐框质量的验收结果。

## 模型与本地打包

> **2026-10-20 起仅保留 d1-omni-600M 一个 ranker。** SmolVLM 256M/500M 已从 catalog 下架：`EdgeModelKind` enum、`SmolVlmRuntime.kt` 与 debug 验证脚手架删除，`EdgeModelStore.prepare` 固定取 d1 三张图，不再按 kind 分支或做图名回退猜测；UI 只显示「d1-omni 600M」实际名字。旧安装的设置 JSON 里若是 smolvlm id，decode 时自动迁移到 `EdgeModelCatalog.DEFAULT_MODEL_ID`（单测覆盖）。以下 SmolVLM 段落与打包命令仅作历史记录保留，当前构建不再使用该脚本。

一个 SmolVLM 256M 模型由视觉编码器、文本嵌入、decoder 三张 ONNX 图及四个配置/tokenizer 文件组成。固定 revision 为 `7e3e67edbbed1bf9888184d9df282b700a323964`。本轮选择已有本地 `cpu-int8` 配置，7 个运行文件共 263,439,781 bytes（251.24 MiB）；`compact` 为 173.9 MiB 的可选小包。来源、版本、大小和 SHA-256 随清单保留；许可参见官方模型卡，模型许可独立于项目代码许可。

d1-omni-600M 是当前唯一在线 ranker，`D1ModelCatalog`（`app/model/`）固定 `onnx-community/d1-omni-600M-ONNX` revision `4ebc1b97bf1477485371c79d1cf8d5e4e8eebfa0`；图像栈三张图均带 `.onnx_data` 外部权重（vision_encoder_fp16 约 180 MiB、embed_tokens_quantized 约 41 MiB、decision_q4 约 192 MiB，11 个文件合计 `437,985,561` bytes / 417.7 MiB），另含 config/tokenizer×2/preprocessor 与仓库根 `LICENSE`（LFM Open License v1.0，含年收入门槛条款，`ModelStore` 将 LICENSE 列为必需文件）。决策图固定 `decision_q4.onnx`：官方 8-bit `decision_quantized.onnx` 的 MatMulNBits 节点缺 `accuracy_level=4`，ORT 1.22 在 macOS x86_64 与 Android arm64 均报 `Only 4b quantization is supported for unpacked compute`；q4 已在两端实测跑通。2026-10-20 SmolVLM 下架后，计划解析固定取 d1 三张图，不做图名猜测或回退。权重在独立的 edge-model-solutions 仓库入库；street-snap 不提交权重、不把权重打进默认 APK。pinned 文件清单（约 2 KB）随 APK 内置在 `assets/edge-model-manifests/`，prepare 零网络；2026-10-30（T120）起 d1 与 pose 共用唯一权重出口 `app/model/ModelStore`，下载匿名免鉴权、魔搭 ModelScope 国内 CDN 首选、hf-mirror/HF（pose 为 Google）兜底、逐源切换 + Range 断点续传。

默认 Preview/CI 不内置权重。显式内置调试包使用项目外模型目录，首次在前台启用构图辅助时导入应用私有目录；普通构建清理残留 staging，避免意外带入模型。复现命令：

```sh
mkdir -p .tooling
python3 scripts/check_smolvlm_bundle.py \
  "$HOME/Downloads/street-snap-models/smolvlm-256m" \
  --profile cpu-int8 --manifest .tooling/first-frame-cpu-int8-manifest.json
./gradlew :app:assembleDebug \
  -PedgeModelDir="$HOME/Downloads/street-snap-models/smolvlm-256m" \
  -PedgeModelManifest=.tooling/first-frame-cpu-int8-manifest.json --offline
```

校验脚本不下载文件；缺少所选 Int8 图即失败，不静默换成 Q4。只使用已有本地模型与工具，不下载新模型、SDK、镜像或工具链。Task.md、APK、权重、构建工具、测试照片、签名材料和本地模型清单不提交开源仓库；本地源码提交由用户自行推送个人 fork，再通过 PR 合并组织仓库，不强推、不自行发布 Release。

所有操作限于途帧项目，不操作通讯软件或无关网站，不进行高危不可逆操作，不更改系统安全或账号权限，也不删除用户数据。
