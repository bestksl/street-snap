# 端侧构图建议 SOP

本轮目标：针对当前取景显示一个推荐构图框和一句中文建议，并分别显示快速画面分析与模型选择的耗时。默认在取景页点击“构图”执行一次，可选开启实时建议；功能已开启时进入相机会自动给出首轮建议。建议必须随画面变化并覆盖多种构图语言（三分、中心、对角线、三角形、引导线、框式、前景层次、留白、环境），禁止退化成恒定一句话。框表达建议保留的画面范围，不是主体检测框；拍摄参数与快门由用户控制。

模型择优支持固定 revision 下载的 SmolVLM 256M/500M 与 Liquid d1-omni-600M（marker 选项契约，见 §2），由 `EdgeModelKind` 选择图文件与运行时；未下载模型时规则建议独立可用。YOLO 尚未接入。`FastCompositionAdvisor` 是图像统计规则，不是另一个神经网络。实现与验收状态见文末，本文件不把历史测试算成本轮通过。

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
SmolVLM rankCandidates：每个排列一次视觉编码 + 一次 decoder prefill
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

广角彩蛋：`subjectLooksDistant` 为真且当前等效焦段 ≤35mm 时，`CompositionEasterEgg` 以约五分之一的低频率、90 秒冷却追加一句委婉的靠近提示（如“28mm 小彩蛋 · 主体有点远，凑近一步会更有存在感”）。它是情绪价值文案，不参与裁切、评分或门闸，模型选择来源不显示，焦段变化不重启相机会话。

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

## 历史诊断：为什么先前没有框

此前直接让通用 SmolVLM 生成裁切坐标，实际输出包含 `[0,0,0,0]`、残缺坐标或普通描述，被合法性检查拒绝。相机输入与 ONNX 推理已打通；未经过本项目构图/定位训练的模型不能仅凭提示词保证坐标和审美能力。此前 Q4 语义探针曾连续两次正确识别画面右侧电视，描述任务也出现人物幻觉，不能据此证明可靠定位。

旧通用框任务在 Android 35 AVD 上，Int8 App 请求约 4.3–6.4 秒，视觉编码约 3.2–5.1 秒；Q4 约 16.4–17.5 秒。更早的 4.75 秒取消策略还会提前打断慢请求。这些都是历史路径数据，不能作为当前候选排序速度或推荐框质量的验收结果。

## 模型与本地打包

一个 SmolVLM 256M 模型由视觉编码器、文本嵌入、decoder 三张 ONNX 图及四个配置/tokenizer 文件组成。固定 revision 为 `7e3e67edbbed1bf9888184d9df282b700a323964`。本轮选择已有本地 `cpu-int8` 配置，7 个运行文件共 263,439,781 bytes（251.24 MiB）；`compact` 为 173.9 MiB 的可选小包。来源、版本、大小和 SHA-256 随清单保留；许可参见官方模型卡，模型许可独立于项目代码许可。

d1-omni-600M 为第二个可选 ranker，`EdgeModelCatalog` 固定 `onnx-community/d1-omni-600M-ONNX` revision `4ebc1b97bf1477485371c79d1cf8d5e4e8eebfa0`；图像栈三张图均带 `.onnx_data` 外部权重（vision_encoder_fp16 约 180 MiB、embed_tokens_quantized 约 41 MiB、decision_q4 约 192 MiB，11 个文件合计 `437,985,561` bytes / 417.7 MiB），另含 config/tokenizer×2/preprocessor 与仓库根 `LICENSE`（LFM Open License v1.0，含年收入门槛条款，`EdgeModelStore` 将 LICENSE 列为必需文件）。决策图固定 `decision_q4.onnx`：官方 8-bit `decision_quantized.onnx` 的 MatMulNBits 节点缺 `accuracy_level=4`，ORT 1.22 在 macOS x86_64 与 Android arm64 均报 `Only 4b quantization is supported for unpacked compute`；q4 已在两端实测跑通。`EdgeModelStore.prepare` 按 `EdgeModelKind` 选图，SmolVLM 保持 Q4/quantized 回退策略，d1 不做图名猜测。权重在独立的 edge-model-solutions 仓库入库；street-snap 仍不提交权重、APK 与清单。

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
