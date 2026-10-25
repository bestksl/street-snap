# 界面截图

> **2026-10-26 更新（T116 批次）：** 配合取景页菜单梳理（「柔光 / 光晕」定名、扩散专区）与快门位移修复，五张主图全部重拍：`viewfinder.png`、`pose-live-guide.png`、`pose-ready.png`、`pose-demo.png`、`compose-panel.png`。本批次人物画面统一使用 COCO 183391（CC BY-SA 2.0）单一来源，演示模式图改用纯灰场景、不再注入任何照片。2026-10-25 批次的方法记录与署名保留在后文。

## 截图环境（2026-10-26，T116 批次）

本机自建工具链，全程不依赖真机：

- **JDK**：Eclipse Temurin 17.0.21+9（`~/Library/Java/jdk-17.0.21+9`）。
- **Android SDK**（`~/Library/Android/sdk`）：emulator 37.2.12、platform-tools（adb）、platforms `android-35` 与 `android-37.0`、build-tools 36.0.0。
- **AVD**：`lwl_shot_api35`，1080×2340 @420dpi，Android 15（API 35）google_apis arm64；`-no-window -gpu host` 无头运行，手势导航。
- **APK**：`./gradlew :app:assembleDebug` 本机构建、`installDebug` 安装，包名 **`dev.streetsnap.app`**，启动组件 `.MainActivity`。代码基线为分支 **`fix/menu-insets-t116`**（T116：色彩菜单梳理 + edge-to-edge / 快门位移修复，随本 PR 提交）。
- **模型**：姿态模型在 App 内显式下载安装（面板真实显示 5.5 MB）；d1-omni-600M 构图模型本批**未安装**（面板真实显示「AI 推理：安装模型后显示实测耗时」）。模型权重不进 git。

## 出图方法

- **虚拟场景 imagefile 模式**：AVD `environment.ini` 写 `version = 1` + `scene.mode = imagefile:<主机绝对路径>`。运行中经 gRPC 热切：服务 `android.emulation.control.incubating.VirtualSceneService/reloadEnvironment`（明文 gRPC，本机自写 HTTP/2 客户端 `/tmp/lwl-emulator-ctl/vsctl2.py`，不进仓），payload 为 protobuf 字符串帧（field 1 直接放 ini 文本，无空格），成功响应 `0801`；**同路径文件替换内容不生效，每次热切必须换新文件名**。
- **冷启隐私指示器规避**：Android 15 相机会话建立后，systemui 的 `ScreenDecorOverlay` 会在右上角弹约 15 秒的绿色隐私胶囊（已用 `dumpsys window windows` 确认归属，非 App 控件），冷启即拍会截入画面。本批流程：先热切到纯灰 imagefile → 冷启 App（灰场无人，姿势永不达标、不轮换）→ 等 19 秒确认胶囊消失 → 再热切人物图。
- **姿势索引与绿帧**：冷启重置为第 1 个姿势「单手举高」；该测试照在热切后约 1.3–2.6 秒真实达标（PoseCoach：`SCORE_READY=0.75`、连续 4 帧确认），ready 绿窗约 2.1 秒，2200ms 后自动轮换到「双手举起」。用 `screenrecord --time-limit` 录屏后 AVFoundation 按 0.05 秒抽帧（`extractframes.swift`），自写像素扫描按绿（`#54C97E`）/ 琥珀计数定位帧号，再逐张目检定稿——不是手工摆造或后期改色。
- **演示模式**：`adb run-as dev.streetsnap.app` 把 `files/pose-models/pose_landmarker_lite/model.task` 临时改名为 `.hold`，App 回落到演示模式；截后已改回并重新启动确认识别恢复。
- **4:3 画布补偿**：竖版源图等比贴入 1440×1080 黑底 4:3 画布（自写 `canvas.swift`）抵消模拟器二次 cover 裁切，人物完整入镜；照片像素未修改、未与取景画面合成。本批两套参数：①图高系数 0.92、上偏 0.04、左偏 −0.06（成像 981×994，用于姿势两图）；②图高系数 0.72、上偏 0.14、左偏 −0.06（成像 768×778，用于取景/面板两图）。
- **尺寸与纪律**：原始截屏/录屏帧均 1080×2340，`sips -z 1560 720` 等比缩放到 720×1560（PNG）；不拼接取景、不补造控件、不虚构状态。源照片与中间产物只存本机临时目录（`/tmp/pose-fixture`），**不进入 git**。

## 本批图片

- `viewfinder.png`（720×1560）：竖屏相机背式布局，**原色**影调、光晕角钮可见。两卡同屏：左侧画面分析建议卡（动作句 + 三分构图书签行 +「画面分析建议 · 画面分析 172 ms」时序）与底部居中绿色达标姿势卡「姿势到位 · 保持 / 很好，下一个姿势马上来」，上下分离互不遮挡；蓝色推荐取景框为该次点「构图」的真实规则分析结果。场景：imagefile COCO 183391（画布参数②，录屏 rec3 帧 f0080）。
- `pose-live-guide.png`（720×1560）：实时引导态（自动轮换后的第 2 个姿势「双手举起」）。**白色虚线** ghost 骨架贴合真实人体，**琥珀色**目标骨架与关节圆环（未达标关节呼吸脉冲）；卡片含动作提示「左手再抬高一些，举过肩膀」、图例与进度条。场景：imagefile COCO 183391（画布参数①，录屏帧 f0150）。
- `pose-ready.png`（720×1560）：**真实达标保持态**（同一录屏序列，绿帧窗口 f0054–f0094，定版 f0070）。骨架与关节环全部转绿（`#54C97E`），ghost 淡出，卡片为「姿势到位 · 保持 / 很好，下一个姿势马上来」，图例与进度条按设计隐藏。场景：imagefile COCO 183391（画布参数①）。
- `pose-demo.png`（720×1560）：演示模式（临时移走姿态模型）。纯灰 imagefile 场景（不注入任何照片），卡内静态琥珀色小人示范「单手举高」，下方 12 个分页圆点（第 1 个高亮）与「换个姿势」按钮；无模型时仍可浏览全部 12 个姿势。
- `compose-panel.png`（720×1560）：「调整 → 拍摄」面板。「姿势助手 · 实验性」开关开启，副文案「实时识别已就绪 · 白骨架贴合人体，琥珀色小人示范目标姿势」、「实时识别模型已安装 · 5.5 MB」、「删除本地模型」、「模型来自 Google MediaPipe（Apache-2.0），所有计算都在手机本地完成，画面不上传、不保存」；上方可见「AI 推理：安装模型后显示实测耗时」（d1 未安装的真实状态）。背景相机实时引导仍在运行（imagefile COCO 183391，画布参数②）。

## 第三方照片署名

### COCO image id 183391（本批唯一人物画面）

用于 `viewfinder.png`、`pose-live-guide.png`、`pose-ready.png`、`compose-panel.png` 背景：2011 年日本网球公开赛中的发球照片。

- 作者：**globalite**（Flickr）
- 标题：*fun shots of giants, dwarfs, and conjoined twins at Japan Open tennis 2011*
- COCO 元数据许可：**Creative Commons Attribution-ShareAlike 2.0 Generic（CC BY-SA 2.0，license id 5）**——本仓库按该许可使用与署名，衍生截图同样以 CC BY-SA 2.0 释出。
- COCO 源址：<http://images.cocodataset.org/val2017/000000183391.jpg>
- Flickr 照片页：<https://www.flickr.com/photos/globalite/6233885701/>
- **许可变更记录（如实标注）**：2026-10-26 核实发现作者已将该页许可改为 All Rights Reserved（oEmbed 与照片页均如此），晚于 COCO 抓取时间（`date_captured` 2013-11-21）。按 CC 授权不可撤销原则，经 COCO 分发的副本仍可依其记录的 BY-SA 2.0 条款使用；若权利人提出异议，将立即更换全部相关截图。

### 历史批次署名（图片仍随仓保留）

- **COCO image id 185950**（2026-10-25 批次的 `pose-*`/`compose-panel` 旧版，现已不在当前图片中；历史文件曾用）：滑板场滑手，CC BY 2.0。COCO 源址 <http://images.cocodataset.org/val2017/000000185950.jpg>，Flickr id 2890964329；2026-10-25 核实时 Flickr 页 404、作者不可考，不臆造署名。
- **COCO image id 22705**：Joe Shlabotnik 摄影，*Refrigerator Model*，CC BY 2.0。<https://www.flickr.com/photos/joeshlabotnik/1940631815/> ｜ <http://images.cocodataset.org/val2017/000000022705.jpg>。2026-10-25 批次曾用于 mesh3d 房间海报，本批已不再注入海报。

## 证据边界

- 全部截图来自 qemu 模拟器（无头、`-gpu host`），**不代表荣耀 Vs3 真机**的成像、骨架贴合度、帧率、温升或功耗；姿态识别在平面照片上运行，只验证识别与引导链路本身。
- `pose-ready.png` 的转绿是该测试照片上 PoseCoach 的真实判定（录屏全量抽帧所得），不是真机命中率证据。
- `compose-panel.png` 中 d1 未安装、姿态模型 5.5 MB 已安装均为截图时设备真实状态。
- 模拟器 HAL 不报告数字变焦，焦段条呈灰态；真机行为可能不同。

## 历史批次图片（2026-10-19）

- `camera-picker.png`（720×1560）：控制面板「拍摄」分区→「相机设置」里的取景器相机弹窗，六张卡片：首张「跟随影调」（默认，顶部机身随影调或胶片变化）+ 五款可固定机身，底部「不显示」可彻底隐藏铭牌。五款机身按真机轮廓特征原创矢量绘制：理光 GR 扁宽平顶+左侧握把+居中定焦镜头，富士 X100VI 银色顶盖+左右双转盘+右上混合取景方窗+银环镜头，适马 fp 直角方块+无握把无窗+四角螺丝+居中卡口，松下 LX100 II 非对称台阶顶+突出大镜头+小握把，尼康 F3 高耸五棱镜+过片扳手+握把红竖线。
- `edge-models.png`（720×1560，**历史 UI，已从 README 导览撤下，文件暂留**）：旧版三模型选择器；模型选择器 2026-10-20 已移除，SmolVLM 下架，当前 catalog 仅 d1-omni-600M。
- `model-adoption.png`（1170×540，横屏原尺寸未缩放）：端侧模型通过门闸、推荐框升级为黄色「AI 选择」的真实截图（SmolVLM 256M，低分辨率横屏 AVD，2026-10-19 稀有一次、不可复现）。

蓝色推荐框来自规则图像统计（画面分析建议），绿色骨架/对勾表示姿势或构图到位，黄色框只在端侧模型对同一帧 A/B/C 候选两轮选择一致且领先差距超门限时出现。中文提示来自规则理由，不伪装成模型输出；模型弃权、不稳定、超时或未安装时一律保留画面分析建议并如实标注状态。

## 更新截图时

同步检查 README 与本文件描述；人物照片只用可再分发许可并在本文件署名（image id、作者/标题、许可、链接，许可状态变化也要记录），源照片不进仓。默认 debug/Preview APK 不带权重，模型在 App 内显式下载，不能仅看包体判断模型是否存在。
