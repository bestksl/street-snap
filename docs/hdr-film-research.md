# HDR 效果、胶片颗粒与右上角胶片小画 · 调研与落地方案（T23）

日期：2026-10-19。触发：用户现场反馈——①"右上角空间浪费，可以放胶片图片，GR 正片你搜一下整体做一下"；②"胶片的颗粒感，要不要做？要做"；③"HDR 是不是给我丢掉了？要常驻一个按钮，效果可以更夸张——高光位置很柔，外面一层光晕，搜一下 HDR 效果对标"。方法：先 Web 调研行业视觉与工程做法，再做代码现状勘察，产出可直接实现的方案（T24 引擎 / T25 常驻按钮 / T26 颗粒 / T27 胶片小画）。原则不变：不下载不拷贝版权素材，原创 GLSL 与 Compose Canvas；预览与成片共用同一套效果；模拟器结论不外推真机。

> **2026-10-19 正名（T38，优先于下文历史措辞）**
>
> 1. **「HDR 钮」实为理光 HDF**。用户事后明确：取景器常驻圆钮要的语义是理光 **HDF（Highlight Diffusion Filter，高光扩散滤镜）**——柔化高光与过曝边界、带出光晕的胶片感滤镜（理光 GR III HDF 机内内置扩散的数码对应物），**不是多帧合成 HDR**。本文与 T24/T25 记录中所称「HDR 效果 / HDR 钮」应一律读作 HDF；T24 实现的 ACES Narkowicz 肩部压限 + bright-pass 宽外层 halo，与 T08 既有的 bloom 柔光（§一.1）同属这一个 HDF 风格化开关的组成部分，并不存在、也不再规划独立的「HDR 模式」。T38 起全部用户可见文案（取景器圆钮、调整面板快捷钮、DIFFUSION 行、ActiveOverride 药丸、曝光大光比建议、contentDescription）统一为 HDF；**代码标识符 `hdrEnabled/hdrStrength/toggleHdr` 与设置 JSON 的 `"hdrEnabled"/"hdrStrength"` key 保留旧名不动**，避免老用户设置档迁移。
> 2. **胶片小画与影调建议已迁出取景器**。T27 把小画放在取景器内右上角；T38 起小画与影调建议胶囊移到**取景器外顶部铭牌行右侧**（与机身铭牌同一行），取景器内右上只保留用户主动开启的 OSD（直方图/PARAMS），消除了小画与 OSD 参数卡的遮挡；详见 `look-recommendation-research.md` §7。

## 一、代码现状勘察（2026-10-19，main 10c050f）

### 1. bloom 高光扩散已存在，但绑在 HDF 柔光开关上，没有独立 HDR

- `color/.../ColorShaders.kt`：同一套 fragment shader 三个 pass（`uPass` 0 主图 / 1 高光提取 / 2 可分离高斯）。
  - 提取：`brightAt()` 先跑完整 `grade()`（WB→3D LUT→色相→影调），再乘软膝 `smoothstep(0.66, 0.94, luma)`，即只有接近高光的部分进 bloom，中间灰不泛光；4 个亚像素 tap 降采样。
  - 扩散：5-tap 权重高斯（0.227 / 0.316×2 / 0.070×2），`GlRenderer.prepareBloom` 在长边 **384px** 的两张 RGBA8 FBO 上做**两轮** H/V（step 系数 .008 与 .014），光晕半径随短边归一化。
  - 叠加（主 pass）：`color += (1 - clamp(color)) * glow * uHdf * 1.25`——Screen 风格（越暗的邻域吃光越多，亮中心保留），强度只受 `uHdf`（HDF 开关+强度，默认 0.4）控制。
- `GlRenderer.draw`：只有 `state.recipe.hdfEnabled && hdfStrength > 0` 时才绑定 bloom 纹理并 `prepareBloom`；预览每帧重算（OES），成片 128px 条带渲染时只在 `tileTop == 0` 整图算一次，后续条带复用同一 bloom 纹理（`bloomValid`，新 Bitmap upload 时失效）。
- 结论：**用户记忆里的"HDR"在当前 UI 中不存在独立入口**——能做高光扩散的基础设施就是 HDF 柔光（T08 时专业项被收进面板，现在 HDF 开关在"调整→影调"分区与 DIFFUSION 精调滑杆里）。HDR 应当是与 HDF **解耦的独立模式**，复用同一 bloom FBO/管线，但参数（阈值、半径、叠加强度、肩部压缩）独立。

### 2. 颗粒已存在但模型过于简单，预览缩放下几乎不可见

- shader：`n = noise(floor(vUv * uImageSize)) - 0.5; color += n * uGrain * 0.12`，`noise()` 是单频 hash 白噪，每像素独立、无色度噪声、无粗细层次、无密度随曝光变化；强度 0.12 系数很克制。
- 配方默认 grain：晴街 .05、正片 .08、粉末蓝 .14、负片 .16–.30、250D .18、TRI-X .34（`FilmProfile.kt`）；用户可调 0–1（`LookParameter.GRAIN`，每个影调/胶片独立记忆于 `LookSettings`）。
- 预览为 3:4 缩放显示，白噪高频在缩放后被滤掉，因此用户感知"没有颗粒"；成片全分辨率才有颗粒，但仍单薄。
- 时间稳定性：噪声由像素坐标 hash 得出，视频上是**静态斑纹**而非胶片颗粒的每帧微动（这一点反而是"不闪"，但质感像固定灰尘；需要做成随帧变化但密度稳定的颗粒——引入时间种子，避免闪烁结块）。

### 3. 顶行布局与右上角落点

- 取景屏 overlay（Box）：`TopStart` 一行 Row——左 38dp OSD 循环角钮（T20），`Spacer(weight 1f)`，右侧依次"构图 / 实时 / AF"三个 OverlayPill；水平仪在取景屏顶部 8.5% 居中；PARAMS 只读卡在 TopEnd（top=106dp，宽 118dp）；左下是建议卡。
- 顶行右侧三胶囊只占一条，其下方（右上区域）大部分时间为空——这就是用户说的"浪费"。
- 铭牌（80dp，相机机身）在取景屏**之外**的顶部条；用户现在要的胶片小画是在**取景器内右上角**，二者语义不同（机身=影调族归属摆件；胶片小画=当前胶片/影调的色彩身份），点按都可直达影调面板。

### 4. 状态链路（T24/T25 照此接入）

- `AppSettings.hdfEnabled / hdfStrength`（全局，`SettingsRepository` JSON 持久化）→ `AppSettings.colorRecipe()` → `ColorRecipe.hdfEnabled/hdfStrength` → `GlRenderer` uniform；`CameraViewModel.toggleHdf()/setHdfStrength()`；HDF 开启时进 ActiveOverride 药丸（长按复位）。
- HDR 同构：`AppSettings.hdrEnabled`（+ 强度是否暴露由 T25 决定）→ `ColorRecipe.hdr*` → shader 新增 uniforms；JSON version 升级、缺字段回退默认关。
- 预览 `PreviewColorProcessor` 每帧 `state()` 取最新 recipe，开关生效靠重绑/下帧自然刷新，无需新依赖；成片 `ColorEngine.processPhoto(recipe 快照)` 走同一 shader，效果自动一致。

## 二、Web 调研：HDR 风格化效果对标

来源：LearnOpenGL Bloom/2022 Physically Based Bloom、GPU Gems ch.21、Catlike Bloom、Unity post-processing Bloom、ARM SIGGRAPH 2015（Dual-Kawase）、Narkowicz ACES、glsl-tone-map（Uchimura）、Google Filament ToneMapper.cpp、darktable filmic rgb/soften、Dehancer halation、CloudPano halo 指南等（链接见文末）。

### 1. "HDR 滤镜感"的四个可分离成分

1. **高光肩部压缩（filmic shoulder）**：过曝区不硬切白，沿肩部缓滚，高光里残留色块——"高光很柔"的本体。LDR 管线没有 >1 的 headroom，要先给高光"造余量"再过曲线。Reinhard 省 ALU 但把高光推向白、丢色相；**Narkowicz ACES 拟合（约 8 条 ALU、UE4 默认）保色相**，作默认；Uchimura/GT 分段曲线肩部参数可控，留给夸张档。曲线建议在 luma 域作用、按比例回缩 chroma，避免肤色偏色。
2. **核心 bloom（tight core）**：阈值高、半径小、强度大，裹住灯/窗本体——现有管线的两轮 .008/.014 高斯即此层。
3. **外层 bloom（wide halo）**："外面一层光晕"。行业共识是**宽而淡**——低分辨率大模糊层强度过高是画面发灰发假的头号原因（Unity/LearnOpenGL：无阈值方案权重才 ~0.04）。
4. **Halation / Orton**：halation 是彩负片基反射的窄、暖红、只贴最亮边缘的光晕（指数衰减核比高斯贴边）；Orton = 提亮高斯层以 8–30% Screen/Soft-Light 叠回。二者只作夸张档调味。

### 2. 参数化与默认值（实现时从这里取数，AVD 实拍复核）

| 参数 | 默认档（HDR 开） | 夸张档（强度滑杆高位） |
|---|---|---|
| 总强度（与 uHdf 同量纲） | 0.55–0.70 | 0.90–1.00 |
| 肩部 shoulder / headroom gain | 0.45 / 1.1 | 0.8 / 1.4–1.6 |
| bright threshold / knee | 0.72 / 0.15（收紧，减少帧间跳变） | 0.60 / 0.20 |
| 核心层权重 / 半径 | 1.0 / 现有 | 1.2 / ×1.3 |
| 外层权重 / 级数 | 0.20 / 1 级加宽 | 0.45–0.60 / 2 级 |
| glow 去饱和 | 0.85 | 0.9 + 可选暖橙 halation（1.0,0.55,0.3）混入 0.2–0.4 |

亮度键建议用 `max(r,g,b)` 替代 luma（彩色霓虹不溢色）；软膝可升级为 Catlike 二次膝 `soft²/(4k)`。

### 3. 工程做法与坑

- **外层光晕的最简落地**：现有双 FBO（384 长边 RGBA8）ping-pong 上，HDR 模式再多跑 1–2 轮更宽 step（系数 ~.026/.042）即得宽 halo，零新增纹理；更考究可上 ARM Dual-Kawase（降/升采样各 4 tap，8 pass ≈ 97×97 高斯，Mali 上优于高斯），需要 1/4、1/8 小 FBO 链——T24 先用零新纹理方案，代码注释里留 Dual-Kawase 升级口。
- **合成必须继续用 Screen 式 `(1-color)*glow`，LDR 下禁 Additive**（先后钳白=廉价感，且该因子同时保护暗部不发灰，勿删）。
- **半径按短边归一化**（现有 fraction 写法已是），保证预览 1080p 与成片全尺寸观感一致；静帧条带共享整图 bloom 的机制不动。
- **时间稳定是视频最大坑**：阈值附近像素帧间 pop 造成闪烁——soft knee、阈值高于噪底、HDR 强度按帧 lerp（相机 AE 跳变时不产生强度突变）。时域反馈 `mix(prev,cur,0.8)` 会在运动镜头拖影，本期不做；静帧（成片）无此问题可直接给满。
- **防脏**：外层阈值高于核心层（中间调不发灰）；宽光默认中性白；核心柔化用 midtone mask 排除肤色；暗部不泛光。
- 性能：多级降采样极便宜（1/8 FBO 仅 1.6% 像素量），RGBA8 即可，banding 有颗粒抖动掩盖。
- 未找到：泼辣/Adobe 无工程向 HDR glow 文档；Google Camera 无第三方同类资料。

## 三、Web 调研：GR 正片色彩意象与胶片小画形式

来源：理光映像 GR III 官方功能页/GR 官方博客、GR III 说明书、DPReview 出厂档位核对、smallworks 12 模式作例、富士官方胶片模拟介绍、NOMO/FIMO/Hipstamatic/OldRoll/VSCO 形态资料。

### 1. GR 正片的色彩事实

- 定位**反转片/幻灯片观感（Velvia/Kodachrome 路数）**，不靠加饱和靠曲线：出厂饱和 ±0、色相 ±0，**对比度 +3（最高档之一）、整体低调、高光对比 −1、阴影对比 −2**——高反差但亮部不硬切、暗部留层次，即"浓而不炸"。
- 颜色：正红扎实浓郁（招牌/红伞跳出）；黄暖琥珀；绿偏黄墨绿不发翠；蓝是高密度深蓝（雨夜/水洼出彩）；暗部暖。社区关键词：浓郁、厚重、油润、色密度深（官方博客「色の濃度がグッと深くなる」）、dense/punchy/slide-film look。
- 与富士差异：Velvia 更亮更外放（最高饱和+明亮高调），GR 正片更暗更沉；Classic Chrome 是低饱和冷影纪实味，不是正片对手；GR 负片是浅淡灰蓝日系空气感。
- 官方摄影师拍人像会降饱和 −1、阴影对比 −4——提示：正片不是万能档。

### 2. 小画形式（NOMO/FIMO/Hipstamatic/OldRoll/VSCO）

- 行业范式：常驻、深色半透明底托的**小徽章**，点按进选择器，不占取景面积；
- **形状差异优先于颜色**（片格齿孔/暗盒/色卡/圆点排/纯灰板），文字仅 ≥48dp 作副标签，缩略尺寸才可辨；
- 高饱和面积控制在 1/3 内，避免干扰构图。

### 3. 原创配色表（设计估色，非任何厂方素材取样；T27 直接用）

**正片小画**：35mm 片格斜对角渐变——深夜蓝 `#14223D`→`#2E5C9A`（高密度反转蓝）；片基高光暖白 `#F4E3C0`；小面积正红 `#D23B2A`；琥珀 `#E6A01F`；片边暖黑 `#191411`。

| look / film | 特征色 |
|---|---|
| 原色 | `#ECECE7` `#9AA0A4` `#3E4246` + 极小 RGB 三点 `#D94E3E` `#E3B23C` `#3E7CB1` |
| 正片 | `#2E5C9A` `#14223D` `#F4E3C0` `#D23B2A` `#E6A01F` |
| 负片 | `#EFE4D2` `#C9B49F` `#6E8390` `#A14E38`（奶白高调/灰蓝暗部/褪色砖红） |
| 粉末蓝 | `#D7E8F0` `#AFCBDB` `#8FB2C7` `#F6EBD9` |
| 高反差黑白 | `#0B0B0B` `#333333` `#8A8A86` `#D8D8D2` `#F5F5F0`（阶少边硬） |
| 晴街 | `#5BA3D9` `#BFE0F2` `#FFD45E` `#FFF4D6` `#7E7B70`（晴空蓝+阳光黄） |
| CC 经典正片 | （T27 按低饱和冷影定：`#7E8C8D` `#4A5757` `#C9BFA8` `#8A6F52`） |
| NC 经典负片 | （暖绿阴影：`#E8DCC6` `#B89b7E` `#5E6E5A`） |
| NA 怀旧负片 | （旧化暖黄：`#D9C296` `#A8875A` `#6E5A44` `#EFE3C8`） |
| ACROS | 灰阶但更柔：`#151515` `#5A5A58` `#A8A8A2` `#ECECE6` |
| LUMIX 自然 | 中性写实：`#E6E6E0` `#A7ABA6` `#5B605C` + 一点自然绿 `#6E8F5E` |
| L.单色 | 冷调灰：`#111414` `#4D5655` `#9AA3A0` `#E2E7E5` |
| Vision3 250D | `#F0DFC0` `#D89A52` `#4E7D96` `#2C2620`（识别蓝 `#0E67B4` 极小） |
| Vision3 500T | `#101C2E` `#2E4A66` `#8A8F98` `#E89A3C`（识别红 `#C8102E` 极小） |
| Vision3 50D | `#1F6FB2` `#2F7D4F` `#E7C79B` `#FFF6E6` `#1E2A33` |
| Ektar 100 | `#D7261E` `#0F4C8A` `#2E8B3D` `#F2A91E`（红蓝对撞金橙） |
| Portra 400 | `#F1E4C9` `#D9A87E` `#B97854` `#157A78`（奶油底+青绿） |
| Fujicolor C200 | `#0F8A5F` `#BBD9D2` `#8FA6A0` `#F2F4EF`（富士冷绿，红 `#E60012` 仅极小点缀） |
| TRI-X 400 | `#0F0F0F` `#3A3A3A` `#828282` `#C6C6C0` `#F2F2EE`（纯灰阶） |

注：FilmArtwork 七卷已有片盒视觉语言（148×100），小徽章可复用其 5 段色条语言但换形状（35mm 片格+齿孔）。**遗留修正**：`FilmProfile.kt` TRI-X 的 `swatchStart/End` 元数据是金→绿（靠绘制层特判兜底成灰阶），T26/T27 顺手改为灰阶元数据。

## 四、落地方案

### T24 HDR 效果引擎（color 模块，GLSL）——参数已定

- ColorRecipe 增加 `hdrEnabled`（默认 false）/ `hdrStrength`（默认 ~0.6，0–1）；新增 uniforms：bright 阈值/knee、肩部 shoulder 量与 headroom gain、核心/外层权重与宽半径系数。**HDR 关时 shader 逐像素与现状一致（回归红线，HDF 行为不变）**。
- 实现四件：①grade 之后对亮部造 headroom（`x*(1+gain*hiMask)`）再过 **Narkowicz ACES 拟合**，按 shoulder 量与原色 mix，luma 域作用保色相；②bright-pass 阈值收到 0.72/knee 0.15、亮度键改 `max(r,g,b)`（HDR 路径，HDF 路径保留 luma 或一并切换后比对）；③核心层=现有两轮高斯；**外层光晕=HDR 时在同一对 384 FBO 上再多 1–2 轮更宽 step（.026/.042）**，宽而淡（权重 0.20→夸张 0.5），零新纹理；合成继续 Screen 式、glow 乘 0.85 去饱和；④夸张档（strength 高位）加大 shoulder/外层权重，可选暖橙 halation 混入。
- HDF 与 HDR 同开：HDF 柔光维持现状叠加，HDR 的 shoulder 在前、两层 glow 各自权重；顺序固定并在测试说明中固化。
- 视频稳定：强度 uniform 随帧 lerp（GlRenderer 持有上一帧强度，成片路径直接给满），无时域反馈。
- 单测：recipe 边界/默认值/identity；AVD 对 virtualscene 亮窗做 HDR 开/关、HDR+HDF 组合的预览截图与成片抽帧对比，检查暗部不泛灰、人脸（virtualscene 海报/静物）不糊。
- 备选升级（不在本期）：Dual-Kawase 1/4–1/8 FBO 链，代码注释留口。

#### 实现与验证记录（2026-10-19，loaded-only：软件渲染 AVD，未外推真机）

- 落地参数：ACES 肩部 mask `smoothstep(.50,.90,luma)`、amount `mix(.45,.85,uHdr)`、headroom `1.0…1.6`；bright-pass 仅 HDR 路径改 `max(r,g,b)` 键 + `smoothstep(.57,.72,…)`（HDF 路径原样）；宽 halo 在同一对 384px FBO 追加 .026/.042 两轮，权重 `mix(.90,1.90,uHdr)`，glow 0.85 去饱和仅在 HDR 分支执行（HDF-only 逐像素不变）；预览强度 0.2/帧 lerp，静片首帧给满；bloom 仍只在 HDF 或 HDR 开启时绑定。
- 单测：ColorRecipeTest（NaN/越界拒绝、disabled/零强度 identity、与 HDF 独立性）；SettingsJsonTest（默认关/.6、往返、legacy 缺字段恢复、越界夹取、坏 JSON 回默认）全绿；`:color:testDebugUnitTest :app:testDebugUnitTest`、`:app:assemblePreview` 通过。
- AVD（emulator-5580，virtualscene 暗木墙场景，EV+2.5）逐像素对比预览：HDR 开/关亮部（luma>170）均值 −15/255（肩部压柔高光，方向正确），暗部（luma<70）+0.45（不泛灰）；成片 960×1280 同场景 mean|Δ|=1.28、max 74，静片路径无异常；HDR+HDF 同时开启拍照成功、logcat 无 GL error/FATAL（软件渲染 80–115 ms/帧）；开关状态重启后保持（药丸即状态判据，点击进面板、长按复位）。
- 亮场补证（同日晚些，横屏切换后 virtualscene 落到带整面亮窗的房间，loaded-only）：亮窗场景 HDR 开/关预览 mean|Δ|≈13、约 30% 像素变化；目视+成片（960×1280 静片）对比一致——关时窗为磨砂白、边缘干净，开时窗成炽白光源、宽外层光晕明显漫上天花板与地面、室内整体柔亮，即"高光柔化+外层光晕更夸张"的目标观感；暗部抬升主要来自窗光扩散与 ACES 中调（地毯/柜面仍有层次，非死灰）。**仍 not-verified：真机 GPU 帧时与真机高光场景观感**；halation 暖橙混入未做（当前四成分中仅取肩部+核心+宽 halo 三件，作为后续调档）。

### T25 HDR 常驻按钮（app 模块）

- 取景器常驻开关（不进面板深处）；具体落点经 T27 统筹（顶行胶片小画也要占位），候选：右上胶囊行左侧/小画下方竖排；保持"构图/实时/AF"与快门零干扰。
- 持久化 + 默认关 + ActiveOverride 药丸（长按复位）+ 无模型依赖；SettingsJsonTest 往返。

#### 实现与验证记录（2026-10-19，loaded-only：软件渲染 AVD）

- 取景器常驻一键开关 `HdrToggleButton`（38dp 圆形，与 OSD 角钮同规格）：落在取景器**左上、OSD 角钮正下方（top=52dp）**，与右侧直方图/PARAMS 卡左右对称、不重叠，且把右上区域留给 T27 胶片徽章；关时安静深底白字、开时主色高亮；contentDescription「HDR 效果开关」+ stateDescription 已开启/关闭。
- 持久化/默认关/JSON 往返/legacy 恢复/越界夹取随 T24 一并落地（SettingsJsonTest 已覆盖，默认值向后兼容无需 version bump）；开启时进 ActiveOverride 药丸（点击进影调面板、长按复位）；面板内 HDR 按钮（柔光参数行）保留为第二入口。
- AVD：默认关、常驻钮始终可见；一键开/关即时生效（以药丸存在与否判状态，uiautomator 的 selected 标志对自定义钮不可靠）；force-stop 重启后开启态保持；竖屏与横屏 fallback 布局均无遮挡；OSD PARAMS 卡与 HDR 钮左右分列不撞。viewfinder.png 已随 T27 胶片徽章重截（2026-10-19，TRI-X 默认态）。

### T26 胶片颗粒强化（color 模块）

- 双层噪声（细粒高频 hash + 粗粒低频块），粗粒给胶片"团簇"质感；亮度域密度加权按胶片特性（暗部颗粒略多、高光略少）；极轻色度噪声仅在高颗粒档位带入。
- 引入预览帧时间种子（uniform 帧序号）：密度恒定、纹理每帧微动；防止两种失败——静态斑纹（现状）与闪烁跳变（时间种子只换 hash 相位、幅度统计不变）。成片静帧用固定种子，不依赖时间。
- 强度重标定：shader 系数从 0.12 提到预览缩放可见区间（实现时 AVD 同机位标定）；配方默认复核（正片 .08 克制、负片 .16–.30、TRI-X .34 明显），滑杆 0–1 仍线性可用。
- 顺手修正 FilmProfile TRI-X swatch 金→绿元数据为灰阶（消除绘制层特判）。

#### 实现与验证记录（2026-10-19，loaded-only：软件渲染 AVD，未外推真机）

- 落地（`ColorShaders.kt` / `GlRenderer.kt`）：双尺度颗粒——fine 1px 散粒（权重 .55）+ coarse `/2.6` 像素团簇（权重 .95）；密度按调色后亮度 `mix(1.30, 0.62, smoothstep(0,1,luma))`（暗部密、裁切高光稀）；以 luma 颗粒为主，仅当配方/滑杆 grain 越过 .18–.34 门闸时加极弱 rb 色度噪（权重 .05）；总系数 0.12→0.18（预览缩放下可见、不糊）。
- 时间种子：新增 `uGrainSeed`（splitmix 风格整数 hash → [0,1)）；预览每帧 ++frame 换独立噪声场（幅度统计恒定，微动但不闪、不形成静态水印），**静片固定 frame=1，128px 高的所有渲染分片共享同一无缝颗粒场**（vUv 按全图尺寸计，跨条带连续）。
- TRI-X swatch 元数据改为中性灰 `#C6C6C6→#101010`，`FilmArtwork.toneStrip()` 删除 MONO_01 特判，灰阶完全由数据驱动。七卷 grain 默认：50D .05、EKTAR .08（正片克制）、C200 .14、PORTRA .16、250D .18、500T .30、TRI-X .34（最粗）。
- 单测全绿：新增 `triXSwatchIsANeutralGrayRamp`（两端 R==G==B 且亮→暗）、`grainStocksOrderCoarseAboveFineAndStayInRange`（全部 0..1 且 TRI-X>50D）；`:color:testDebugUnitTest` 通过。
- AVD（emulator-5580，亮窗房间场景）证据：①预览相邻帧 TRI-X mean|Δ|=8.77、58.5% 像素变化（噪声场每帧换、密度稳定）；②TRI-X(.34) vs EKTAR(.08) 灰墙高频能量 8.32 vs 6.38（配方强度序正确）；③960×1280 成片 128px 条带边界垂直差分 ratio 0.79–0.90（无离群接缝；一处 3.59 经放大目视为电视棋盘格斜底边的内容边缘，非条带缝）；④密度梯度：暗柜区高频 |mean|=20.05、中灰墙 5.14、亮窗纯白 5.51，目视暗密亮稀；⑤预览/成片一致：同机位平坦亮窗 5.71 vs 5.51、中墙 5.27 vs 5.14（5px 高通、/255）；⑥零颗粒地板：原色 preset（无胶片）亮窗 1.62、中墙 1.05、帧间 mean|Δ|=0.715（软件渲染+视频底噪水平）；⑦自定义颗粒滑杆同机位 0→100%：亮窗 1.63→3.75、中墙 1.06→5.11，滑杆即时生效；100% 放大目视为均匀双尺度颗粒，无水印/网格/条带。**仍 not-verified：真机传感器噪声与合成颗粒的叠加观感、真机 GPU 上帧种子开销**（当前为每帧两次常量整数 hash，可忽略）。

### T27 右上角胶片/影调小画（app 模块，原创 Compose Canvas）

- 约 48dp 圆角徽章常驻取景器右上角：**35mm 片格（片孔+画格内特征渐变）**，形状优先于颜色；高饱和面积 ≤1/3、深色半透明底托；渐变色严格取第三节估色表，14 个 look/film 全覆盖不留空窗；文字仅在空间允许时放极小副标签。
- 跟随当前 look/film 切换（复用 FilmRegistry / basePreset 族映射）；点按直达影调面板；contentDescription 报当前影调名。
- 与三胶囊、PARAMS 卡（top=106dp）、水平仪重新排布：候选把三胶囊保持顶行、小画落在其下方右上角（与左侧 OSD 角钮纵向呼应），PARAMS 态时小画让位/下移；AVD 逐影调截图核对不重叠；宽横屏/小横屏同步评估。
- 重截 viewfinder.png 与 images README；只用配色意象，不用任何厂方图像/商标素材。

#### 实现与验证记录（2026-10-19，loaded-only：软件渲染 AVD）

- 新增 `app/.../FilmBadge.kt`：`FilmFrameBadge`（48dp 圆角 12dp、黑 32% 底托）内绘 40×27dp 原创 35mm 片格——暖黑/近黑片边、上下各三片孔（perf，随片基冷暖取色）、画格为 look 特征色对角线性渐变，彩色款右下一枚 ≤画格 1/9 面积的饱和小方块，原色款改画格中部 RGB 三点，黑白款（MONO/ACROS/L.单色/TRI-X）纯灰阶无彩点。`LookBadgePalette.of(filmId, preset)` 覆盖全部 7 胶片 + 13 preset（含 CUSTOM 中性板、未知 filmId 回退 preset），20 种身份无空窗；配色全部取自第三节原创估色表。
- 挂载点在**取景器内右上**（`Viewfinder` 预览 Box 的 TopEnd），而非取景器外的铭牌条：与左列 HDR 圆钮（top=52dp）顶对齐；`animateDpAsState` 按 osdMode 自动避让——OFF top=52dp（与 HDR 同行）、HISTOGRAM top=106dp（让到直方图卡下）、PARAMS top=180dp（让到参数卡下）；三种布局路径（竖屏 framed、宽横屏 side rail、小横屏 floating）均挂载，点按一律打开影调面板（AdjustmentTab.LOOK）；contentDescription「胶片小画：<名>，点按调整影调」。
- 单测 `FilmBadgePaletteTest`：七卷胶片与全部 preset（含 CUSTOM）均解析、gate 非空板；未知 filmId 回退等价；四个黑白身份 mono 且无 accent、gate 通道差 <0.05；原色 original 且无 accent。`:app:testDebugUnitTest`、`:app:assemblePreview` 通过。
- AVD（emulator-5580）逐影调截图核对：TRI-X 灰阶片格、正片高密度反转蓝+小红块、负片奶白→灰蓝+砖红、原色灰底 RGB 三点、晴街晴空蓝+阳光黄、500T 深钨蓝+小红、C200 冷绿+极小红点，48dp 下片孔/渐变/彩点均可辨；点击 (938,473) 直达影调面板且落在影调 tab；OSD 三档徽章平滑下移、与直方图/PARAMS 卡零重叠；宽横屏 side rail 落在取景器右上 AF 胶囊下方、不压侧栏控件。
- 重截 `docs/images/viewfinder.png`（720×1560，TRI-X 默认态、OSD 关、HDR 关），同步更新 images/README 与根 README alt（补胶片小画与 HDR 钮、影调改为 TRI-X）。**仍 not-verified：真机肉眼可辨度（高分屏 48dp 片孔 2.6dp）、真机 GPU 渐变观感**。

## 五、T39 二次回调（2026-10-19）：HDF 克制化、胶片包装徽章、建议卡与铭牌比例

用户实机反馈四条：HDF「太过了」、胶片小画「只是比较像」且不显示具体型号、AI 建议卡挡住半个屏幕、左上角机身矢量压扁。全部落在取景器视觉层，无链路改动。

### 1. HDF 对标真品后的参数回调（loaded-only：软件渲染 AVD，未外推真机）

对标基线（理光 GR III HDF / GR IIIx HDF，2024-09 起售的厂内 Highlight Diffusion Filter 版本）：硬件滤镜**只柔和/扩散接近溢出的高光边缘、带出克制的光晕**；中暗部保持清晰锐利、整图不雾化；固定强度、非多帧 HDR 包围。T24 实现把它做成了「强 HDR 风格」（中灰以上即进入 ACES 肩部、宽 halo、低阈值亮部提取），三处参数全部回调：

| 位置 | T24 旧值（默认 uHdr=.6 实效） | T39 新值（同强度实效） | 理由 |
| --- | --- | --- | --- |
| 亮部提取 `brightAt` uHdr 分支 | `smoothstep(.57,.72,max-channel)` | `smoothstep(.74,.92,max-channel)` | 只有接近溢出的光源才进 halo；普通天空/中调不再扩散，仍保留彩灯颜色（max-channel 键控） |
| 肩部 mask / amount / headroom | `.50→.90`；`mix(.45,.85)=.69`；`mix(1.10,1.60)=1.40` | `.78→.97`；`mix(.10,.28)=.208`；`mix(1.04,1.14)=1.10` | HDF 是扩散不是色调映射：中灰以上不再被整片 ACES 重映射，只在溢出边缘保留一点柔化挽色 |
| halo 权重 / 染色 | `mix(.90,1.90)=1.50`，85% 彩色 | `mix(.30,.70)=.54`，70% 彩色 | 光晕收敛到真品的克制量级，近中性不污染中调 |
| 宽模糊轮次（GlRenderer `prepareBloom`） | `.008/.014/.026/.042` 四轮 | `.008/.014/.020/.030` 四轮 | 外 halo 半径收窄约 30%，「软核+短外晕」而非大范围雾状泛光 |

`uHdr=0` 输出逐路径不变（三个分支都在 `uHdr>0` 内）；T08 原有 uHdf 柔光路径未动；uniform 名与 JSON key 继续保留 `hdr*`。同 shader 同时驱动预览与成片，二者自动一致。代码注释从「HDR look」改写为理光 HDF 语义（`ColorShaders.kt`、`GlRenderer.kt`、`ColorRecipe.kt` 字段注与非法值提示）。

### 2. 胶片小画重做为「胶片包装」并印出具体型号（原创绘制，无厂方素材）

`FilmBadge.kt` 的 48dp 抽象片格重做为 **84×50dp 微型胶片纸盒**：左侧 16dp 签名色带，右侧三行印刷字（6.5sp 厂/类标签 + 10sp 粗体型号首行 + 9sp 半粗次行），外 1dp 发丝边保证浅盒在亮画面上可读。新增 `FilmPackCopy.of(filmId, preset)`：七卷胶片印真实型号（VISION3 250D/500T/50D、EKTAR 100、PORTRA 400、FUJICOLOR C200、TRI-X 400，标签行带 KODAK·CINE/COLOR/B&W、FUJIFILM·COLOR），13 个机内 preset 明确印「IN-CAMERA SIM」与缩写（NC/CC/NA/ACROS…），不伪装实体胶片。

个性按包装家族分化（`LookBadgePalette.art: PackArt`，旧色表与 `FilmBadgePaletteTest` 全部保留）：CINE 深盒+渐变窄带；COLOR 奶白纸盒+渐变齿孔带+厂色三角块（Ektar 金蓝红角、Portra 奶青）；FUJI 绿盒+白带+红角；MONO 白盒黑带强反差；BUILTIN 深卡细渐变带；ORIGINAL 中性卡+竖向 RGB 三点。contentDescription 改「胶片包装：<名>，点按调整影调」。

### 3. AI 构图建议卡收紧（CameraScreen 浮层卡）

- 宽度：wrap content（实测约占取景器 92%）→ `widthIn(max = 208.dp)`；内边距 8dp → 横 8/竖 6。
- 字号：动作句 13sp/2 行 → 11sp/行高 14；术语副行 11sp → 9.5sp；彩蛋 10sp → 9sp；「画面分析建议 · Nms」10sp/2 行 → 9sp/1 行省略。
- 长按试取景手势、黑 68% 底、圆角 8dp 不变。

### 4. 机身铭牌比例修正

矢量本体（`CameraArtwork`）一直按 180:112 画布等比缩放，压扁感来自卡片 92×52（1.77）+ 矢量框 82×50 使整机顶到上下边、拨盘/棱镜局促。铭牌行固定 80dp 有富余：卡片改 **98×62dp（圆角 12）**、矢量框 **90×56dp**——恰为 180:112 的 0.5 倍等比（scale=.500，旧 .446），五机身放大约 12%、四周留 4/3dp 边；无机身时 NeutralLookArtwork 32→38dp。行高仍 80dp，3:4 取景器尺寸不变。

### 5. 证据与遗留

- 单测：新增 `FilmPackCopy` 覆盖测试（7 胶片 + 13 preset 两行型号均非空、未知 filmId 回退 preset）；`:app :color :camera` 全量单测通过；`:app:assemblePreview` 通过。
- AVD（emulator-5580，软件 GL，VirtualScene 固定房间）截图复核四项：
  - **HDF 开/关同帧像素差分（0–255 通道均值绝对差）**：近溢出亮窗区 R6.6/G7.4/B8.2，书架电视中调区与右下中暗区均仅 0.5–0.6（噪声量级），差异热图沿亮窗框、电视白格等高光边缘分布——量化证实「只扩散接近溢出高光、中暗部不动」，与旧 uHdr 路径中灰以上整片重映射形成对比；
  - 包装徽章逐家族实拍：ORIGINAL 浅灰卡竖向 RGB 三点、TRI-X 400 白盒黑带白齿孔、PORTRA 400 奶盒+奶→teal 渐变带+铜色三角、FUJICOLOR C200 绿盒+白带+红角、VISION3 250D 深棕盒+米→蓝窄带，三行型号在 84×50dp 内均可读（`includeFontPadding=false` 是三行不溢出的关键）；
  - 建议卡宽实测约 557px≈206dp（旧版接近取景器全宽），三行 11/9.5/9sp 可读；
  - 铭牌 GR III 矢量 90×56dp=180:112 等比 0.5×，拨盘/握柄/镜头不再局促。
  软件渲染只证路径与量级，**真机光晕观感仍 not-verified**；审美质量唯一证据仍是独立人工标注照片集。

## 六、2026-10-20 三次回调（T45/T46，HDF-1/HDF-2）：点光源辉光强化——tight core + 独立宽光晕双层模型

用户反馈（逐字要点）：「HDF 的特效还是不明显。主要 HDF 一般你可以搜一下样章……它一般都是高光的时候，那个灯啊，它会有个光晕在那里面。」本轮研究环境无法访问在线样章 URL，样章/真机夜景照片留待人工核对；以下方向据光学扩散原理与 §五 已核实的理光 HDF 事实推导，不把没看到的样章当证据。

### 1. 两个先查清的事实

- **按钮与 uniform 的命名错位**：UI 上标着「HDF」的按钮实际驱动 `hdrEnabled → uHdr`（legacy uniform 名），UI 上的「柔光」chip 才驱动 `hdfEnabled → uHdf`。T39 克制化调的是 uHdr（真 HDF 按钮），T41 排查「还是不明显」时首先核对了这一层，避免改错路径。
- **单模糊层加宽必然发灰**：T24/T39 共用一对 bloom FBO，要更大的光晕只能在同一对上增加模糊轮次，结果灯核被抹成灰雾——这是单层架构的结构性问题，不是再调阈值能解决的。

### 2. 双层光学辉光模型（loaded-only：软件渲染 AVD 验证管线与量级）

bloom 改为**两对独立 FBO**（`GlRenderer.prepareBloom(width, height, wideWanted)`）：

| 层 | 长边 | 职责 |
| --- | --- | --- |
| tight core（uBloom, unit2） | ≤384 px | 灯核紧光晕，保留灯的染色；不能再被加宽 |
| wide halo（uBloomWide, unit3） | ≤192 px | 独立宽软光晕；下采样自带 box-filter 预模糊，再走高斯轮次 |

合成着色器（`ColorShaders.kt`）关键点：

- `scatterGap = 1 − clamp(color)`：光晕只在比光源暗的区域显出，**暗部零抬升**（屏幕式光散射语义），此因子是载荷项；
- 亮部提取 `brightAt` 用 **max-channel 软膝**而非 luma：红灯/蓝灯 luma 低但单通道接近 clip，也必须被提取（T39 已修，T41 保持：uHdr 膝 .74/.92，uHdf 膝 .72/.90）；
- 真 HDF 按钮（uHdr）新双层叠加：`amount = mix(0.22, 0.68, uHdr)`，`coreHalo = mix(luma(glow), glow, .60) * 0.70`，`wideHalo = mix(luma(wideTex), wideTex, .75) * 0.45`，合项 `color += scatterGap * (coreHalo + wideHalo) * amount`——轻度去饱和的宽层模拟空气中的散射光球，紧层保留灯色；
- 柔光（uHdf）路径：`core * 1.50 + wide * 0.55`，语义不变；
- `uHdr = 0 / uHdf = 0` 时输出路径不变，无效果开关零差异。

### 3. 排查并修掉的实质 bug：HDF 按钮路径拿不到宽层

改完双层后 AVD 上 HDF 按钮仍无宽光晕。定位：`draw()` 之前只在柔光路径准备/绑定宽 FBO（`wideWanted` 原等价于 `diffusionOn`），真 HDF 按钮走 uHdr 时 unit3 绑到 texture 0，宽层贡献为零。修法：`val wideWanted = diffusionOn || hdrOn`，两种 look 都 `extractHighlights` 宽表并绑 `wideBloomTextures[0]`。同 shader 同时驱动预览与成片，二者自动一致。

### 4. AVD 代理量化与证据边界

AVD VirtualScene 是静态房间、无路灯/灯泡等点光源，无法直接验证「灯有光晕」的目标观感；以电视棋盘格的近白格做**代理光源**，同帧开/关做像素差分（PIL + statistics，暗格 n=83,929）：

- 两种开态暗格 luma 平均抬升 **+24.03 / +30.35**（0–255），关态同区域仅 +3.84（噪声量级），差分 p90 +127；
- 抬升沿近白格边缘空间衰减，符合光晕分布而非整片抬色；
- 全程无 GL error、无 FATAL，拍照链路正常。

这只证明管线连通、量级可见、暗部不被抬；棋盘格不是灯，qemu 软件 GL 的模糊/带宽与真机 GPU 不同。**真实路灯/点光源夜景的辉光目视效果仍 not-verified**，唯一下一步是荣耀 Vs3 夜景实拍人工核对（含与理光 HDF 样章的目视对标）；审美/氛围判断只认独立人工评估。

### 5. 2026-10-27 retune：HDF 只在点光源周围成一圈光晕

用户反馈（夜间实拍口径）：HDF 的精髓是灯泡、霓虹灯这类**点状高光边缘的一圈光晕**；没有光的位置与正常光照位置应几乎无变化；不能整个屏幕泛白。对照旧参数（uHdr 膝 .74/.92、core .70、wide .45、point .30）定位到两个泛白来源：① 膝阈值偏低，白墙/亮皮肤等正常亮部（max 通道约 0.8）也被提取；② wide 层从全屏亮部提取并大半径扩散，亮窗/天空把周边整片抬白。点光蒙版（uBloomPoint，中心对盒形邻域的局部对比）天然只在点光源边缘响应——亮面内部 centre≈around、对比为零、蒙版塌缩——正是该效果的正确载体。

现行参数（`BloomStyle.kt`，仅 uHdr 路径；柔光 uHdf 不动）：

| 参数 | 旧 | 新 | 作用 |
| --- | --- | --- | --- |
| KNEE_HDR_LO/HI | .74 / .92 | **.80 / .97** | 只有近 clip 的光入 bloom，正常亮部被挡 |
| HDR_CORE_GAIN | .70 | **.50** | 紧核只作灯边最内一圈 |
| HDR_WIDE_GAIN | .45 | **.12** | 宽层近乎静音，大面积亮面不再铺白 |
| HDR_POINT_GAIN | .30 | **.55** | 点光层成为光晕主载体 |
| POINT_CONTRAST_HI | .55 | **.45** | 对比更早饱和，细霓虹管周围成完整环而非断弧 |

`scatterGap = 1−color` 与高膝共同保证暗部/中间调近零变化；`BloomStyleTest` 新增关系断言（point gain > core gain、point gain > 3×wide gain）。证据边界不变：qemu 点光代理只验量级，**真实霓虹/灯泡夜景仍待 Vs3 人工核对**。

## 七、主要参考

- [LearnOpenGL Bloom](https://learnopengl.com/Advanced-Lighting/Bloom) / [Physically Based Bloom 2022](https://learnopengl.com/Guest-Articles/2022/Phys.-Based-Bloom) / [GPU Gems 21 Real-Time Glow](https://developer.nvidia.com/gpugems/gpugems/part-iv-image-processing/chapter-21-real-time-glow)
- [Catlike Coding Bloom（soft knee）](https://catlikecoding.com/unity/tutorials/advanced-rendering/bloom/) / [Unity post-processing Bloom](https://docs.unity3d.com/Packages/com.unity.postprocessing@2.2/manual/Bloom.html)
- [ARM SIGGRAPH 2015 Bandwidth-Efficient Rendering（Dual-Kawase）](https://community.arm.com/cfs-file/__key/communityserver-blogs-components-weblogfiles/00-00-00-20-66/siggraph2015_2D00_mmg_2D00_marius_2D00_slides.pdf)
- [Narkowicz ACES Filmic Curve](https://knarkowicz.wordpress.com/2016/01/06/aces-filmic-tone-mapping-curve/) / [glsl-tone-map（Uchimura）](https://github.com/dmnsgn/glsl-tone-map) / [Google Filament ToneMapper.cpp](https://github.com/google/filament/blob/main/filament/src/ToneMapper.cpp) / [darktable filmic rgb](https://docs.darktable.org/usermanual/4.2/en/module-reference/processing-modules/filmic-rgb/)
- [Dehancer Halation](https://www.dehancer.com/learn/articles/halation-in-dehancer) / [darktable soften（Orton）](https://docs.darktable.org/usermanual/4.6/en/module-reference/processing-modules/soften/) / [避免 HDR halo](https://www.cloudpano.com/blog/how-to-avoid-halos-in-hdr-photography)
- [理光 GR III 影像控制官方介绍](https://www.ricoh-imaging.com.cn/china/products/gr-3/feature/03.html) / [GR 官方博客](https://www.grblog.jp/en/article/2192/) / [DPReview 出厂档位](https://www.dpreview.com/forums/thread/4520401) / [富士胶片模拟入门](https://fujifilm-x.com/en-gb/learning-centre/get-to-grips-with-film-simulation-modes/)
- 胶片 App 形态：NOMO / FIMO（拟物暗盒+齿孔）、Hipstamatic（机背片窗）、OldRoll（机身即胶片）、VSCO（色块徽章）
