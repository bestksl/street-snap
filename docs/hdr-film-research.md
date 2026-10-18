# HDR 效果、胶片颗粒与右上角胶片小画 · 调研与落地方案（T23）

日期：2026-10-19。触发：用户现场反馈——①"右上角空间浪费，可以放胶片图片，GR 正片你搜一下整体做一下"；②"胶片的颗粒感，要不要做？要做"；③"HDR 是不是给我丢掉了？要常驻一个按钮，效果可以更夸张——高光位置很柔，外面一层光晕，搜一下 HDR 效果对标"。方法：先 Web 调研行业视觉与工程做法，再做代码现状勘察，产出可直接实现的方案（T24 引擎 / T25 常驻按钮 / T26 颗粒 / T27 胶片小画）。原则不变：不下载不拷贝版权素材，原创 GLSL 与 Compose Canvas；预览与成片共用同一套效果；模拟器结论不外推真机。

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
- **时间稳定是视频最大坑**：阈值附近像素帧间 pop 造成闪烁——soft knee、阈值高于噪底、HDR 强度按帧 lerp（相机 AE 跳变时不抽风）。时域反馈 `mix(prev,cur,0.8)` 会在运动镜头拖影，本期不做；静帧（成片）无此问题可直接给满。
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
- 遗留 not-verified：本虚拟场景无灯窗/点光源且基础曝光拉不亮，**宽外层光晕的夸张视觉无法在该场景目视标定**；亮场（灯窗/霓虹/逆光）观感与真机 GPU 帧时留待真机或高动态场景复核；halation 暖橙混入未做（当前四成分中仅取肩部+核心+宽 halo 三件，作为后续调档）。

### T25 HDR 常驻按钮（app 模块）

- 取景器常驻开关（不进面板深处）；具体落点经 T27 统筹（顶行胶片小画也要占位），候选：右上胶囊行左侧/小画下方竖排；保持"构图/实时/AF"与快门零干扰。
- 持久化 + 默认关 + ActiveOverride 药丸（长按复位）+ 无模型依赖；SettingsJsonTest 往返。

### T26 胶片颗粒强化（color 模块）

- 双层噪声（细粒高频 hash + 粗粒低频块），粗粒给胶片"团簇"质感；亮度域密度加权按胶片特性（暗部颗粒略多、高光略少）；极轻色度噪声仅在高颗粒档位带入。
- 引入预览帧时间种子（uniform 帧序号）：密度恒定、纹理每帧微动；防止两种失败——静态斑纹（现状）与闪烁跳变（时间种子只换 hash 相位、幅度统计不变）。成片静帧用固定种子，不依赖时间。
- 强度重标定：shader 系数从 0.12 提到预览缩放可见区间（实现时 AVD 同机位标定）；配方默认复核（正片 .08 克制、负片 .16–.30、TRI-X .34 明显），滑杆 0–1 仍线性可用。
- 顺手修正 FilmProfile TRI-X swatch 金→绿元数据为灰阶（消除绘制层特判）。

### T27 右上角胶片/影调小画（app 模块，原创 Compose Canvas）

- 约 48dp 圆角徽章常驻取景器右上角：**35mm 片格（片孔+画格内特征渐变）**，形状优先于颜色；高饱和面积 ≤1/3、深色半透明底托；渐变色严格取第三节估色表，14 个 look/film 全覆盖不留空窗；文字仅在空间允许时放极小副标签。
- 跟随当前 look/film 切换（复用 FilmRegistry / basePreset 族映射）；点按直达影调面板；contentDescription 报当前影调名。
- 与三胶囊、PARAMS 卡（top=106dp）、水平仪重新排布：候选把三胶囊保持顶行、小画落在其下方右上角（与左侧 OSD 角钮纵向呼应），PARAMS 态时小画让位/下移；AVD 逐影调截图核对不重叠；宽横屏/小横屏同步评估。
- 重截 viewfinder.png 与 images README；只用配色意象，不用任何厂方图像/商标素材。

## 五、主要参考

- [LearnOpenGL Bloom](https://learnopengl.com/Advanced-Lighting/Bloom) / [Physically Based Bloom 2022](https://learnopengl.com/Guest-Articles/2022/Phys.-Based-Bloom) / [GPU Gems 21 Real-Time Glow](https://developer.nvidia.com/gpugems/gpugems/part-iv-image-processing/chapter-21-real-time-glow)
- [Catlike Coding Bloom（soft knee）](https://catlikecoding.com/unity/tutorials/advanced-rendering/bloom/) / [Unity post-processing Bloom](https://docs.unity3d.com/Packages/com.unity.postprocessing@2.2/manual/Bloom.html)
- [ARM SIGGRAPH 2015 Bandwidth-Efficient Rendering（Dual-Kawase）](https://community.arm.com/cfs-file/__key/communityserver-blogs-components-weblogfiles/00-00-00-20-66/siggraph2015_2D00_mmg_2D00_marius_2D00_slides.pdf)
- [Narkowicz ACES Filmic Curve](https://knarkowicz.wordpress.com/2016/01/06/aces-filmic-tone-mapping-curve/) / [glsl-tone-map（Uchimura）](https://github.com/dmnsgn/glsl-tone-map) / [Google Filament ToneMapper.cpp](https://github.com/google/filament/blob/main/filament/src/ToneMapper.cpp) / [darktable filmic rgb](https://docs.darktable.org/usermanual/4.2/en/module-reference/processing-modules/filmic-rgb/)
- [Dehancer Halation](https://www.dehancer.com/learn/articles/halation-in-dehancer) / [darktable soften（Orton）](https://docs.darktable.org/usermanual/4.6/en/module-reference/processing-modules/soften/) / [避免 HDR halo](https://www.cloudpano.com/blog/how-to-avoid-halos-in-hdr-photography)
- [理光 GR III 影像控制官方介绍](https://www.ricoh-imaging.com.cn/china/products/gr-3/feature/03.html) / [GR 官方博客](https://www.grblog.jp/en/article/2192/) / [DPReview 出厂档位](https://www.dpreview.com/forums/thread/4520401) / [富士胶片模拟入门](https://fujifilm-x.com/en-gb/learning-centre/get-to-grips-with-film-simulation-modes/)
- 胶片 App 形态：NOMO / FIMO（拟物暗盒+齿孔）、Hipstamatic（机背片窗）、OldRoll（机身即胶片）、VSCO（色块徽章）
