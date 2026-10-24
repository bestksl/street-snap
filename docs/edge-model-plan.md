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

- 权重 **不随 APK 分发、不入 git**：首次在前台启用构图助手时从 HuggingFace 按固定 revision 显式下载，逐文件 SHA-256/SHA-1 校验、`.part` 原子改名、`install.json` 就绪标记，可随时删除；INTERNET 权限仅用于此显式下载，无遥测。
- ONNX Runtime 仅随 ARM64 构建提供：仓库内 `app/libs/onnxruntime-android-1.22.0-arm64.aar` 是从官方 Maven AAR（完整四 ABI 28.5MB）按固定 ETag 与逐成员 SHA-256 提取重打包的 arm64 单 ABI 切片（约 6.7MB），复现脚本 `scripts/prepare_onnxruntime.py`，许可与第三方声明在 `third_party/onnxruntime/`。
- 默认 Preview/CI 包不带权重；构建期普通构建会清空 assets 模型暂存，只有显式 `-PedgeModelDir=… -PedgeModelManifest=…` 才把外部校验过的模型目录 stage 进 debug 包。
- 模型下载与安装状态、三段分段计时、session 建图耗时、采纳统计与进程内存在「构图助手 → 开发者诊断」面板可见（T80，数值为真机打点预留，模拟器 QEMU 值不外推）。

## 当前验证状态

桌面 ORT 1.22 已用真实 pin 版权重跑通 vision→embed→decision 全链路并输出 finite logits；Android arm64 SDK 35 模拟器实时取景一轮成功（margin 0.214 采纳，vision 11.5s/decision 4.5s）与一轮 30s 超时正确降级。70+ 站位扫测中门闸从未稳定采纳，合成探针显示模型呈位置应答特征。证据等级 **loaded-only**：荣耀 Vs3 真机耗时、温升、采纳率与真实街景审美全部 **not-verified**，唯一下一步证据是独立人工标注照片集。评测原文归档在 edge-model-solutions 的 `evaluations/`（d1-omni-600m-pin、app-ranker-observations、d1-prompt-v1-eval、d1-style-seed-eval、d1-resolution-eval 等）。

## 历史记录（已下架，勿据此构建）

- **SmolVLM 256M/500M**：2026-10-20 前的首个 ranker 试验。256M 固定 revision `7e3e67ed…`，q4/Int8 三图（173.9–251 MiB），AVD 单次 19–21s、输出不可靠，另有一次不可复现的横屏采纳（0.9947/0.9964）。`SmolVlmRuntime.kt`、模型选择器与 debug instrumentation 已删除，权重实物保留在 edge-model-solutions 作为首个 loaded-only 实验证据；`scripts/check_smolvlm_bundle.py` 仅为历史脚本保留，当前构建不使用。
- 更早的通用框任务（让模型直接生成归一化坐标）输出过全零/残缺坐标而被合法性拒绝，证明「输入→推理链路通」不等于定位能力；相关 Int8 4.3–6.4s、Q4 16.4–17.5s 均为历史路径数据。
