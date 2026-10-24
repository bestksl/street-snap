# 端侧模型与分发附录

当前产品流程以 [端侧构图建议 SOP](composition-pipeline-sop.md) 为准：用户点“构图”或开启实时，画面统计先生成可见候选框，本地模型异步比较候选（可选 SmolVLM 或 Liquid d1-omni-600M，均为固定 revision 下载），界面显示推荐框、一句中文建议、来源和耗时。主体检测框已从当前需求中移出；YOLO 尚未接入，不是本轮依赖。

## 一个模型，三个图部件

| 部件 | 输入与作用 |
| --- | --- |
| vision encoder | 512×512 的 A/B/C 裁切对照图，提取视觉特征 |
| embed tokens | 将短英文构图比较指令和标签转换成文本嵌入 |
| decoder | 融合视觉/文本，每个排列运行一次 prefill，读取下一 token 的 A/B/C 分数 |

三个 ONNX 图共同组成一个 SmolVLM 256M 模型，规则候选生成器并非另一个神经网络。模型当前只比较已存在的合法裁切，坐标来自图像规则；Compose 负责把最终候选画在取景器上。中文提示是候选的规则理由，不是模型生成的解释。

完整词表支持度和三选项差距不足时保留“画面分析建议”，不能在三个选项内强制归一化后冒充有效理解。当前两个阈值均为 0.10，尚未通过人工构图数据校准。本轮候选循环换序及纯灰对照发现偏爱 B，因此应用新增两次排列的原候选一致性检查，不一致不采纳，保留规则框。低纹理图像直接跳过模型。仍须用独立画面评价是否带来审美收益。本轮没有训练或微调模型。

## 第二个 ranker：Liquid d1-omni-600M（marker 选项契约）

`EdgeModelKind.D1_OMNI_CHOICE` 条目 `d1-omni-600m` 固定 `onnx-community/d1-omni-600M-ONNX` revision `4ebc1b97bf1477485371c79d1cf8d5e4e8eebfa0`。d1 是决策模型而非聊天模型：零输出 token，答案是各选项 `<mask>` marker 隐状态 logits 的 softmax，因此不存在 SmolVLM 那套全词表 support，门闸只看 top-1/top-2 概率差（同样实验阈值 0.10，`D1Decision`）。图像问题按官方 prompt.py 不做 per-question-type 温度校准。

固定图像栈三张图（`EdgeModelStore.prepare` 按 `model.kind` 选择，不猜文件名）：

| 部件 | ONNX 输入 / 输出 |
| --- | --- |
| `vision_encoder_fp16.onnx`（opset 21） | `pixel_values` F32 `[1,1024,768]`（512 方图、16px patch、RGB/127.5−1，单图 32×32=1024 patch）、`pixel_attention_mask` I64 `[1,1024]`、`spatial_shapes` I64 `[1,2]=[[32,32]]` → `image_features` F32 256×1024（2×2 unshuffle 投影后前缀） |
| `embed_tokens_quantized.onnx` | `input_ids` I64 `[1,L]` → `inputs_embeds` F32 `[1,L,1024]`（Q4 block gather） |
| `decision_q4.onnx`（opset 18，MatMulNBits 4-bit） | `inputs_embeds` F32 `[1,256+L,1024]`（视觉前缀在前、文本嵌入在后）、`attention_mask` I64 全 1、`prefix_len` I64 `[1]=[256]`、`marker_pos` I64 `[1,3]`（**文本相对位置**，图内部自行加 prefix）、`marker_mask` I64 全 1、`qtype` I64 `[1]=[0]`（choice）→ `logits` F32 `[1,3]` |

> 决策图必须用 `decision_q4.onnx`，不能用官方 8-bit 的 `decision_quantized.onnx`：后者的 MatMulNBits 节点没有 `accuracy_level=4` 属性，ORT 1.22 在 macOS x86_64 与 Android arm64 上都会落到只支持 4-bit 的 unpacked 路径并报 `nbits_ == 4 was false. Only 4b quantization is supported for unpacked compute.`（已双重复现）。q4 图在两端均实测可跑。

提示词逐字移植官方 `prompt.py`（`D1Prompt`，21 条纯逻辑单测固定）：序列 `<bos> <reserved_7 state> state <reserved_8 q> instructions [<reserved_9 opt> <mask> " "+option <reserved_10>]… <reserved_11 decide>`，bos=1、mask=16、reserved_7..11=17..21；图像 `image_text_length=896`，`budget=max(96,min(k·24+32,448))`、`per=max(2,(budget−3k)/k)`，question 截 `max(16,budget)`，state 按剩余 room 右截；调用方文本中的 `<|name|>` 一律 escape 成 `<¦name¦>`，无法伪造分隔符或 marker。构图题固定 `Which crop gives the best composition?`，选项 `a/b/c: crop option A/B/C`，state 留空（画面即上下文）。预处理 `D1NaFlex` 固定方图，跳过 smart-resize 与 tiling；`D1OmniRuntime` 复刻 SmolVlmRuntime 的 worker（`street-snap-d1` 单线程）、忙时返 null、取消 terminate、close 契约，vision 会话先试 XNNPACK 失败回退 CPU。下载必需文件包含 `LICENSE`（LFM Open License v1.0 要求许可文本随权重分发），模型包 11 个文件共 `437,985,561` bytes（417.7 MiB）。模型推理在 Android 侧的端到端验证状态记录于 [端侧模型方案库](https://github.com/light-witness-lab/edge-model-solutions) 的 `evaluations/d1-omni-600m-pin.json`：ORT 1.22 桌面（macOS x86_64）已用真实 pin 版权重跑通 vision→embed→decision 全链路并输出 finite marker logits；Android arm64 模拟器（SDK 35）实测一轮成功（`probabilities=[0.204,0.291,0.505]`、margin 0.214 过门闸采纳，vision 11.5s/decision 4.5s）与一轮 30s 预算超时降级（规则框保留）。模拟器为软件渲染，耗时不代表真机；荣耀 Vs3 实测仍待做。

## 固定版本与打包

本轮使用 `HuggingFaceTB/SmolVLM-256M-Instruct` 固定 revision `7e3e67edbbed1bf9888184d9df282b700a323964` 的已有本地文件，选择 `cpu-int8` 预置包：视觉、嵌入、decoder 三张图均为 Int8，连同配置共 7 个文件、`263,439,781` bytes（251.24 MiB）。较小的 `compact` 配置按原 Q4 优先策略选择图，共 `182,318,056` bytes（173.9 MiB），保留为可选小包。`SmolVLM-500M-Instruct` 固定 revision `a588b6298f96d180aff4c31e259409d106b5571e` 仍为约 347 MiB 的可选对照。默认 Preview APK 不带权重；显式指定本地目录与清单可构建内置模型的 debug APK，首次在前台启用构图辅助时导入应用私有目录。两种 256M 配置共用模型 revision，分别记录选中图的大小与 SHA-256。上一批 CPU Int8 debug APK 为 299,583,942 bytes（约 300 MB / 285.7 MiB）；覆盖安装会优先导入新图配置到独立目录并保留旧 Q4，重复启动复用已有导入。

模型下载后可以放在项目外的专属目录，例如 `/Users/haoxuanli/Downloads/street-snap-models/smolvlm-256m/`。使用仓库内的 `scripts/check_smolvlm_bundle.py` 可在不联网的情况下检查配置、三张 ONNX 图、外部数据文件并生成本地 SHA-256 清单；清单只证明本地文件完整，不替代固定 revision 的来源核验。模型文件和生成的清单都不提交 Git。

如果本机已经有 Hugging Face CLI，可用下面的固定 revision 命令下载用于比较 compact / CPU Int8 的图文件；`HF_HOME` 也放在项目外，便于缓存和复用：

```sh
MODEL_DIR="$HOME/Downloads/street-snap-models/smolvlm-256m"
REV="7e3e67edbbed1bf9888184d9df282b700a323964"
HF_HOME="$HOME/Downloads/street-snap-models/hf-cache" \
hf download HuggingFaceTB/SmolVLM-256M-Instruct \
  --revision "$REV" \
  --include config.json tokenizer.json tokenizer_config.json preprocessor_config.json \
  "onnx/vision_encoder_q4*" "onnx/vision_encoder_quantized*" \
  "onnx/embed_tokens_quantized*" "onnx/embed_tokens_q4*" \
  "onnx/decoder_model_merged_q4*" "onnx/decoder_model_merged_quantized*" \
  --local-dir "$MODEL_DIR" --max-workers 2
```

已有上述本地文件时，可离线生成本轮 `cpu-int8` 清单并构建：

```sh
mkdir -p .tooling
python3 scripts/check_smolvlm_bundle.py "$MODEL_DIR" \
  --profile cpu-int8 --manifest .tooling/first-frame-cpu-int8-manifest.json
./gradlew :app:assembleDebug \
  -PedgeModelDir="$MODEL_DIR" \
  -PedgeModelManifest=.tooling/first-frame-cpu-int8-manifest.json --offline
```

`cpu-int8` 缺少任何一张 Int8 图就会失败，不会静默换成 Q4。如需较小包，把 profile 改为 `compact`，另存一份 manifest 并传给 `edgeModelManifest`。校验脚本不下载文件；权重、清单、APK 均不提交 Git。

ONNX Runtime 仅随 ARM64 构建提供。官方 Maven `onnxruntime-android:1.22.0` 的完整四 ABI AAR 为 28,515,295 字节；当前仓库的复现脚本按固定 ETag 和每个 ZIP 成员 SHA-256 提取 ARM64 Java/native 成员，产物约 6.7 MiB，不携带 NDK。这个裁剪 AAR 的许可和第三方声明位于 `third_party/onnxruntime/`。外置 256M bundle 已通过离线清单校验；模型内置 debug APK 已验证 assets 首次导入到 `files/edge-models/...`、7 个文件的 bytes/SHA-256 校验和三张 ONNX 图会话创建。


## 历史诊断与当前验证

旧版本直接请求归一化坐标，出现全零、残缺坐标或描述。Android 35 ARM64 AVD 曾测得旧 Int8 请求 4.3–6.4 秒、Q4 16.4–17.5 秒，输入 dump 与取景一致。语义探针曾识别右侧电视，也有人物幻觉。这证明部分输入/推理链路工作，不能证明可靠主体定位或构图能力；旧数据不作为新候选排序的实测成绩。

当前快速候选不等待模型导入或初始化；默认手动一次，实时模式才自动更新。模型一个任务执行期间不会堆积候选图，完成后冷却 3–15 秒，未变化场景复用选择。每 500ms 观察小图场景变化，过期、后台或旧会话结果不能覆盖新场景。拍照保留完整画面，不自动应用推荐裁切。

单元测试验证规则坐标/内容关联与支持度门闸。Debug 提供独立 Android Instrumentation 入口 `dev.streetsnap.app.validation.CompositionRankingValidation`，读取应用私有目录已有的 `composition-validation.png`，使用相同制图和真实模型比较原顺序、循环换序与纯色对照；不下载或修改模型、不落盘照片。运行命令：

```sh
adb -s <设备序列号> shell input keyevent KEYCODE_HOME
adb -s <设备序列号> shell am instrument -w   dev.streetsnap.app/dev.streetsnap.app.validation.CompositionRankingValidation
```

输出区分推理是否完成、选择是否有效、映射回原候选后是否一致与纯色选择偏置。三个任务完成不代表质量通过。具体本轮结果与限制见 SOP 验收段。仪器验证入口仅存在 Debug 构建，Preview/Release 不包含。

荣耀 Vs3 尚未连接；模拟器只能验证链路。真机需要记录首框、模型比较、预览/快门响应、温升、内存及折叠屏几何映射。足够独立样本后再报告 p50/p95 和主观构图偏好，不凭参数量或模型包大小推断速度。

## 运行时与许可

Android 最低 API 31，CameraX/Camera2 提供预览、拍照和 ImageAnalysis，Kotlin/Compose 管理手动/实时请求与叠框，ONNX Runtime 1.22.0 使用现有 ARM64 构建。视觉图优先尝试 XNNPACK，失败回退 CPU；不假定设备 NPU 可用。图像模型、相机和渲染仍会竞争 CPU/内存，须联合实测。

SmolVLM 模型使用其独立许可证，来源为 [官方 256M 模型卡](https://huggingface.co/HuggingFaceTB/SmolVLM-256M-Instruct)，代码仓库 MIT 不替代模型授权；d1-omni-600M 权重为 LFM Open License v1.0（含年收入 1000 万美元门槛条款），来源为 [onnx-community/d1-omni-600M-ONNX](https://huggingface.co/onnx-community/d1-omni-600M-ONNX)，下载计划强制把仓库根 `LICENSE` 随权重一起校验保存；ONNX Runtime 许可与声明保存在 `third_party/onnxruntime/`。本轮使用用户已经下载并固定 revision 的文件，不新增模型、SDK、镜像或工具链下载。权重、APK、清单、测试照片与签名材料均不提交 Git（street-snap 仓库）；d1 权重与许可文本在独立的 edge-model-solutions 仓库按其规范入库。
