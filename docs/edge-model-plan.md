# 端侧模型与分发附录

当前产品流程以 [端侧构图建议 SOP](composition-pipeline-sop.md) 为准：用户点「构图」或开启实时，画面统计先生成可见候选框，本地 d1-omni-600M 模型异步对候选做选择题式复核（固定 revision、按需下载），界面显示推荐框、一句中文建议、来源和耗时。主体检测框已从当前需求中移出；YOLO 尚未接入，不是当前依赖。

> **2026-10-20 起 catalog 仅保留 d1-omni-600M 一个 ranker。** SmolVLM 256M/500M 已下架（运行时与选择 UI 均删除，旧设置 id 自动迁移到 d1）；下文「历史记录」一节仅作溯源。权重在独立的 [edge-model-solutions](https://github.com/light-witness-lab/edge-model-solutions) 仓库按 provenance 入库，street-snap 不提交权重、APK 与清单。

## 一个决策模型，三个图部件

模型是**决策模型而非聊天模型**：零输出 token，答案是各选项 `<mask>` marker 隐状态 logits 的 softmax，不做自回归生成。规则候选生成器是 Kotlin 图像统计，不是神经网络。模型只比较已存在的合法裁切，坐标来自规则层；Compose 负责把最终候选画在取景器上。中文建议是候选的规则理由，不是模型生成的解释。

| 部件 | ONNX 输入 / 输出 |
| --- | --- |
| `vision_encoder_fp16.onnx`（opset 21，FP16，XNNPACK 失败回退 CPU） | `pixel_values` F32 `[1,1024,768]`（512 方图、16px patch、RGB/127.5−1，单图 32×32=1024 patch）、`pixel_attention_mask`、`spatial_shapes=[[32,32]]` → 256 个投影前缀嵌入 |
| `embed_tokens_quantized.onnx`（Q4 block gather） | `input_ids` `[1,L]` → `inputs_embeds` `[1,L,1024]` |
| `decision_q4.onnx`（opset 18，4-bit MatMulNBits，双向决策头） | 视觉前缀拼文本嵌入 `[1,256+L,1024]`、`prefix_len=[256]`、文本相对的 `marker_pos`、`marker_mask`、`qtype=[0]`（choice）→ `logits [1,3]` |

> 决策图必须用 `decision_q4.onnx`：官方 8-bit `decision_quantized.onnx` 的 MatMulNBits 节点缺 `accuracy_level=4`，ORT 1.22 在 macOS x86_64 与 Android arm64 都落到只支持 4-bit 的 unpacked 路径并报错（双平台复现）。

提示词逐字移植官方 `prompt.py`（`D1Prompt`，有单测固定）：构图题固定 `Which crop gives the best composition?`，选项 `a/b/c: crop option A/B/C`，state 留空，版本冻结为 `comp-choice-v0`（一次加强题面的 v1 经预注册评测被否决，详见 SOP T07）。用户文本中的分隔符一律 escape，无法伪造 marker。预处理 `D1NaFlex` 固定 512 方图；`D1Decision` 对 3 个 logits softmax，门闸只看 top-1/top-2 margin（实验阈值 0.10，未经审美标注校准）加同帧换序 `[1,2,0]` 一致性；弱支持、不一致、异常或 30 秒超时都弃权，保留规则框。

固定 revision：`onnx-community/d1-omni-600M-ONNX@4ebc1b97bf1477485371c79d1cf8d5e4e8eebfa0`，11 个文件共 `437,985,561` bytes（417.7 MiB），LFM Open License v1.0（含年收入 1000 万美元门槛条款，仓库根 `LICENSE` 是下载计划的必需文件）。

## 下载、校验与打包

- 权重 **不随 APK 分发、不入 git**：用户显式下载，逐文件 SHA-256（LFS）/git-SHA1（小文件）校验、`.part` 校验后原子改名、`install.json` 就绪标记，可随时删除；INTERNET 权限仅用于此显式下载，无遥测。**2026-10-27 起下载源匿名免鉴权、国内优先**：`https://hf-mirror.com/<repo>/resolve/...` 先试，`https://huggingface.co` 兜底，逐源失败自动切换；每个文件支持 HTTP Range 断点续传（中断后 `.part` 从偏移继续，大小/哈希不符自动删残换源重下）。
- **pinned 清单随 APK 内置**（`app/src/main/assets/edge-model-manifests/<id>-<revision>.json`，约 2 KB，可入 git；区别于普通构建会清空、被 gitignore 的 `assets/edge-models/` 权重暂存区）：`EdgeModelStore.prepare()` 优先读内置清单，**零网络**返回下载计划，HF 实时 tree API 仅在 APK 缺清单时回退——HuggingFace 被墙不再阻断「查看安装信息」。已装模型识别纯走本地 filesDir 扫描（`installed()`，不触网），同签名覆盖安装后模型不丢，面板不会再把已装模型误显为需重下（`prepareInstall()` 先本地后网络）。
- 姿势模型（MediaPipe .task，5.5 MB）同样多源 + Range 续传：Google Storage 主源，`PoseModelEntry.mirrorUrls` 已补入魔搭社区匿名镜像（`modelscope.cn/api/v1/models/Composable/pose_landmarker_lite/repo?Revision=<commit>&FilePath=…`，固定到上传该文件的 commit 而非 master，302 跳阿里云国内 CDN）；SHA-256 pin 使任何匿名源只能提供这一个文件。

**2026-10-28 凌晨国内直连实测（合肥联通家庭宽带；Clash TUN 关、系统代理被 `--noproxy` 绕过，同机直连 google.com/huggingface.co 超时确认未走代理）**：
- d1 全 11 文件经 hf-mirror.com 全量拉取 **417.7 MiB / 69.5 s，均速 6.01 MiB/s**，逐文件 pin（5×git-SHA1 + 6×SHA-256）全部通过；小文件走镜像站内 resolve-cache，LFS 302 跳 `cas-bridge.xethub.hf.co`（HF xet CloudFront，该网络直连可达，大文件 9.6–13.3 MiB/s），Range 206 + Content-Range 正确；App 内 `HttpURLConnection` 跟随该跨域 302 下载、安装、ONNX 建图推理全链路通过（清数据后真实下载，非模拟）。
- 断网冷启面板本地识别：d1「已安装 · 417.7 MB」、pose「已安装 · 5.5 MB」，App 无任何网络错误；断网状态下 `adb install -r` 覆盖安装后两模型仍在、面板仍显已安装（更新保留实证）。
- 备选源排查：ModelScope 搜索虽能命中 `onnx-community/d1-omni-600M-ONNX`，但仓库实体未同步（tree/文件直链均 404），不可用；Gitee AI `hf-models` 与 wisemodel 对该模型均 404。hf-mirror + huggingface.co 两源即足够，后续若 xet CDN 在某运营商被封，再补自建国内镜像。
- ONNX Runtime 仅随 ARM64 构建提供：仓库内 `app/libs/onnxruntime-android-1.22.0-arm64.aar` 是从官方 Maven AAR（完整四 ABI 28.5MB）按固定 ETag 与逐成员 SHA-256 提取重打包的 arm64 单 ABI 切片（约 6.7MB），复现脚本 `scripts/prepare_onnxruntime.py`，许可与第三方声明在 `third_party/onnxruntime/`。
- 默认 Preview/CI 包不带权重；普通构建只清空 assets 权重暂存（`assets/edge-models/`，不触碰 `edge-model-manifests/`），只有显式 `-PedgeModelDir=… -PedgeModelManifest=…` 才把外部校验过的模型目录 stage 进 debug 包。
- 模型下载与安装状态、三段分段计时、session 建图耗时、采纳统计与进程内存在「构图助手 → 开发者诊断」面板可见（T80，数值为真机打点预留，模拟器 QEMU 值不外推）。

## 当前验证状态

桌面 ORT 1.22 已用真实 pin 版权重跑通 vision→embed→decision 全链路并输出 finite logits；Android arm64 SDK 35 模拟器实时取景一轮成功（margin 0.214 采纳，vision 11.5s/decision 4.5s）与一轮 30s 超时正确降级。70+ 站位扫测中门闸从未稳定采纳，合成探针显示模型呈位置应答特征。证据等级 **loaded-only**：荣耀 Vs3 真机耗时、温升、采纳率与真实街景审美全部 **not-verified**，唯一下一步证据是独立人工标注照片集。评测原文归档在 edge-model-solutions 的 `evaluations/`（d1-omni-600m-pin、app-ranker-observations、d1-prompt-v1-eval、d1-style-seed-eval、d1-resolution-eval 等）。

## 历史记录（已下架，勿据此构建）

- **SmolVLM 256M/500M**：2026-10-20 前的首个 ranker 试验。256M 固定 revision `7e3e67ed…`，q4/Int8 三图（173.9–251 MiB），AVD 单次 19–21s、输出不可靠，另有一次不可复现的横屏采纳（0.9947/0.9964）。`SmolVlmRuntime.kt`、模型选择器与 debug instrumentation 已删除，权重实物保留在 edge-model-solutions 作为首个 loaded-only 实验证据；`scripts/check_smolvlm_bundle.py` 仅为历史脚本保留，当前构建不使用。
- 更早的通用框任务（让模型直接生成归一化坐标）输出过全零/残缺坐标而被合法性拒绝，证明「输入→推理链路通」不等于定位能力；相关 Int8 4.3–6.4s、Q4 16.4–17.5s 均为历史路径数据。
