# street-snap · 途帧

喜欢口袋相机，也喜欢它们各自的脾气：熟悉的视角、顺手的快门，还有一眼认得出的颜色。途帧于手机之上：收束纷扰，快门轻触，影调自调。途遇，帧藏。

**street-snap: Open-source cross-platform documentary camera (iOS/Android), crafted for the serendipity of street capture.**

[![Status: Development](https://img.shields.io/badge/status-development-f59e0b?style=flat-square)](#roadmap)
[![Android: v0.3 dev](https://img.shields.io/badge/Android-v0.3%20dev-3DDC84?style=flat-square&logo=android&logoColor=white)](#安装与发布)
[![iOS: Later](https://img.shields.io/badge/iOS-later-64748b?style=flat-square&logo=apple&logoColor=white)](#安装与发布)
[![License: MIT](https://img.shields.io/badge/license-MIT-3b82f6?style=flat-square)](LICENSE)

[![Kotlin: 2.2.10](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](#技术栈)
[![Swift: Later](https://img.shields.io/badge/Swift-later-F05138?style=flat-square&logo=swift&logoColor=white)](#技术栈)

[![Focal lengths: 28 / 35 / 50mm](https://img.shields.io/badge/focal%20lengths-28%20%2F%2035%20%2F%2050mm-64748b?style=flat-square)](#快捷街拍)
[![Shooting: Snap](https://img.shields.io/badge/shooting-Snap-f59e0b?style=flat-square)](#快捷街拍)
[![Custom LUT: .cube](https://img.shields.io/badge/custom%20LUT-.cube-a855f7?style=flat-square)](#行色--xingse)
[![Recipes: JSON](https://img.shields.io/badge/recipes-JSON-22c55e?style=flat-square&logo=json&logoColor=white)](#行色--xingse)

## Why

手机相机的功能越来越多，但街头抓拍没有多少时间留给菜单和模式切换。

途帧只保留 **28 / 35 / 50mm** 三档等效视角，搭配 Snap 快拍和可自定义的影调，让取景、设定和按下快门成为熟悉的拍摄习惯。

街头抓拍留给你的时间很短。以理光 GR 这类随身相机快速就绪的抓拍体验为参照，途帧希望做到**启动快、操作少、体积轻**，抓住街头的决定性瞬间。

**预设参数 → 快速启动 → 取景确认 → 按下快门**

借鉴 **Snap 快拍**的思路，把准备工作前置：提前设好曝光参数，并在设备支持时预设对焦，减少拍摄时的临场调整。举机取景时，通过参考框、网格、水平仪和影调预览确认画面；熟悉视角后，也可以尝试不看屏幕的盲拍。

围绕这条拍摄流程，计划做到：

- **Fast launch · 极致启动**：精简初始化，快速进入可拍状态。
- **28 / 35 / 50mm**：三档经典等效视角，用熟悉的取景范围形成构图直觉。
- **Snap first**：提前设好参数，服务于举机快拍与熟悉视角后的盲拍。
- **Live view**：在取景时看到构图辅助、参数变化和影调效果。
- **Custom LUTs**：加载自己的 LUT，调整影调参数，保存为配方。
- **Small footprint**：精简依赖，结合代码裁剪与资源压缩，尽可能缩小安装包体积。
- **Fork & tweak**：拍摄、调色代码和内置预设一同开源，欢迎修改代码、制作预设、提交 PR。

项目坚持免费、无广告、无内购。

> **Android 一期开发中，最低支持 Android 12。** 仓库已包含应用源码，首测设备为荣耀 Vs3；相机、GPU 与折叠屏行为仍需真机验证。iOS 后续启动。详见 [一期计划](docs/android-v0.1-plan.md) 与 [开发说明](docs/android-development.md)。

[功能规划](#功能规划) · [技术栈](#技术栈) · [Roadmap](#roadmap) · [安装与发布](#安装与发布) · [Contributing](#contributing) · [开源协议与名称使用](#开源协议与名称使用)

## 功能规划

以下为完整产品范围。一期优先完成 Android 拍照与行色流程，具体支持范围以版本说明和真机验证结果为准。

### 快捷街拍

- **Snap 快拍**：保存并快速调用曝光、影调等常用设置；在镜头与系统 API 支持时预设或锁定对焦，减少拍摄时等待自动对焦的过程。
- **预设对焦与景深**：规划支持区域对焦的使用方式，让预先选定的距离范围保持可接受的清晰度；可用的对焦设置与实际清晰范围需按镜头验证。
- **三档视角**：28mm / 35mm / 50mm 等效焦段，一键切换。
- **盲拍与单手操作**：音量键快门、ADJ 式参数快调，减少对屏幕操作的依赖。
- **构图辅助**：规划与三档视角对应的参考框、三分线、黄金分割网格与水平仪。
- **对焦辅助**：规划实时黑白取景与峰值对焦，帮助观察光影和判断手动对焦范围。

手机同样有景深与超焦距的概念，部分广角镜头可以利用较大景深获得近似“泛焦”的效果。Snap 指预设后快速拍摄的方式；泛焦描述较大范围内可接受清晰的效果，实际范围取决于镜头、对焦设置与清晰度要求。

### 行色 / Xingse

> 沿途所见，自有其色。

「行色」是途帧的调色模块，使用 **3D LUT** 调整颜色，并提供颗粒、暗角等效果。Android 首版支持导入 LUT、调整参数和本地参数记忆；配方分享后续支持。你也可以直接修改调色代码。

- **内置影调**：原色、正片、负片、粉末蓝、黑白、晴街，均为独立创作的预设。
- **参考风格**：参考理光 GR、富士、适马、松下等相机作品中的纪实影调，自主调校色彩，以独立预设名称呈现。
- **LUT I/O**：支持 SDR `.cube` LUT 导入；导出后续支持。
- **JSON 配方**：本地保存拍摄参数与各套影调的调节值，配方分享后续支持。
- **质感调节**：饱和度、锐度、清晰度、颗粒、暗角等参数可调，并提供软件柔光。
- **实时预览**：取景与成片共用调色算法，通过真机验证逐步改善流畅度及效果一致性。
- **源码与预设**：调色代码与内置影调预设随项目一同开源，欢迎修改和分享。

Android 开发版提供原色、正片、负片、粉末蓝、黑白、晴街六套影调，支持自定义 LUT。正片、负片参考 GR 同类影调方向，粉末蓝参考适马官方描述，均由项目独立调校；效果需结合手机实拍评估。

每套影调可单独调整饱和度、色相、对比、亮度、高光、阴影、锐度、清晰度、颗粒与暗角。HDF 按钮切换软件高光柔光，WB 提供设备支持的白平衡预设及 A–B / G–M 漂移。常用控制集中在快门附近，调参时保留取景。详见 [v0.2 试拍说明](docs/android-v0.2-notes.md)。

以下为影调命名与后续调校方向：

| 影调 | 设计方向 |
| --- | --- |
| 晴街 | 明亮、通透，适合日光下的街头 |
| 暮巷 | 低饱和、暖高光，适合傍晚与巷陌 |
| 夜行 | 冷色暗部，保留夜色与霓虹的层次 |
| 街影 | 高对比黑白，强调人物轮廓与光影 |

影调效果用于预览和处理后的照片。若同时保存 RAW，RAW 文件保留原始图像数据，不将影调效果直接写入原始数据。

### 图像与拍摄参数

- 保存处理后的照片，并在设备及系统 API 支持时提供 RAW / DNG 输出。
- 写入系统可提供的 EXIF 拍摄元数据；位置等敏感信息的写入方式将在权限设计中明确。
- 规划使用 JSON 保存和分享拍摄参数方案；导入时根据设备能力检查适配情况。

### Native camera APIs

Android 已通过 CameraX / Camera2 接入原生相机，提供取景、拍摄和设备支持的手动控制能力。iOS 后续接入 AVFoundation。

手动对焦、曝光控制、RAW 输出、按键映射与快门声音控制均受系统、设备及地区限制；快门静音仅在系统允许时提供。

### 流畅取景与响应

为取景、调参和拍摄提供流畅体验，计划重点优化：

- **Shader Fusion**：合并 LUT、颗粒与暗角计算，减少 GPU 渲染 Pass。
- **Buffer Sharing**：共享相机图像缓冲、复用 GPU 纹理，减少数据拷贝与内存分配。
- **Backpressure**：预览优先处理最新帧，避免画面积压带来的延迟。

## 技术栈

Android 使用原生实现；iOS 暂留后续方案。首版不引入共享原生运行时或跨平台界面框架。

| 部分 | 建议方案 | 用途 |
| --- | --- | --- |
| Android | Kotlin + Jetpack Compose + CameraX / Camera2 interop | 界面、取景、拍照及设备支持的手动控制 |
| 行色 | OpenGL ES 3.0 / GLSL | LUT、颗粒、暗角；预览与照片共用效果算法 |
| iOS（后续） | Swift + SwiftUI + AVFoundation；Core Image / Metal | 原生拍摄与实时调色 |
| 预设文件 | `.cube` + JSON | LUT、影调参数与拍摄参数配置 |

Android 按 `app / camera / color` 分模块，分别处理交互与保存、相机会话、LUT 与 GPU 效果。照片在后台处理后写入系统相册，预览不逐帧转换为 Bitmap。

Rust 可作为后续共享模块的候选，用于 LUT 解析、配方校验或确有复用价值的算法；实时效果仍主要由 GPU 执行。

## Roadmap

Android v0.3 开发预览已提供可安装 APK。当前优先完善拍摄体验与设备验证，再推进 iOS。

- [x] **Design**：确认 Android 一期范围、最低系统版本与首测设备。
- [x] **Android 基础拍摄**：取景、拍照、三档视角、Snap 参数记忆、实时影调、LUT 导入和 JPEG 保存；源码与本地构建已完成。
- [x] **影调与操作**：正片、负片、粉末蓝、每套影调独立微调、软件柔光、白平衡与漂移；已实现，效果继续实拍调校。
- [x] **Android Preview**：v0.3.0-dev，支持 Android 12 及以上版本；包含 AF/MF 双向切换、P/S/M 曝光档、白平衡漂移、HDF 柔光、胶片分类和可选端侧构图辅助。
- [ ] **Device validation**：荣耀 Vs3 真机测试、折叠屏适配、预览与成片对照及性能基线。
- [ ] **Advanced controls**：RAW / DNG、峰值辅助、配方分享、完整 ADJ 式快捷操作。
- [ ] **iOS**：复用产品交互与配方规范，接入 iOS 原生相机和调色实现。

当前源码已增加 AF/MF 明确切换、点按对焦反馈、P/S/M 曝光档、7 套胶片配方与可选端侧构图辅助。边框和日期打印已移除，照片直接保存为带 EXIF 的 JPEG；实现记录见 [胶片与对焦说明](docs/film-and-focus.md)，模型边界见 [端侧模型说明](docs/edge-model-plan.md)。

## Interface

| 取景与快拍 | 行色 · 影调微调 | WB · 白平衡漂移 |
| :---: | :---: | :---: |
| <img src="docs/images/viewfinder.png" width="250" alt="途帧取景界面，包含 28 / 35 / 50mm 视角按钮、构图网格与快门"> | <img src="docs/images/color-controls.png" width="250" alt="行色调整面板，粉末蓝预设与饱和度调节"> | <img src="docs/images/white-balance.png" width="250" alt="白平衡预设与 A–B、G–M 漂移控制"> |

Android 15 模拟器中的实际 APK 截图，取景为内置测试场景。28 / 35 / 50mm 按钮固定显示，不支持的视角置灰；图中模拟器使用原生视角。实拍效果以设备为准。

## 安装与发布

**Android 版已可安装，最低支持 Android 12。**

[获取 APK](#ci--apk) · [Android 构建说明](docs/android-development.md)

下载后在手机上打开 APK，按系统提示允许本次安装。已安装同签名开发版时，直接覆盖安装可保留拍摄参数与影调设置，无需卸载。

这是用于试拍与反馈的 **Preview 开发预览**：采用开发测试签名、非调试构建，启用 R8 与资源裁剪。v0.3 构建先通过 CI 或本地 Build 产出，真机验证后再创建 Release；机型兼容性、预览与成片一致性、折叠屏行为及性能仍需验证，详见 [Android 试拍说明](docs/android-v0.2-notes.md)。

**iOS** 后续开发，TestFlight 入口将在可用时公布。

## Build

环境：JDK 17、Android SDK Platform 37.0、Build Tools 36.0.0。Gradle Wrapper 与依赖版本已固定。

在 `local.properties` 配置 `sdk.dir`，或设置 `ANDROID_HOME`，然后运行：

```sh
./gradlew :app:assemblePreview
```

输出：`app/build/outputs/apk/preview/app-preview.apk`。调试可使用 `:app:assembleDebug`；构建、测试与设备验收步骤见 [Android 开发说明](docs/android-development.md)。

### CI → APK

[![Android CI](https://github.com/bestksl/street-snap/actions/workflows/android.yml/badge.svg)](https://github.com/bestksl/street-snap/actions/workflows/android.yml)

| 触发 | 产出 |
| --- | --- |
| 推送到 `main` / PR / 手动运行 | 构建、测试、Lint；提供 Preview / Debug APK 与 SHA-256 |
| `v*` 版本标签 / 手动指定标签 | 固定签名 APK、SHA-256 与 Release 草稿 |

体验最新代码：打开 [Actions → Android](https://github.com/bestksl/street-snap/actions/workflows/android.yml)，选择成功的运行，在摘要下载 **Preview APK**，解压后安装。CI 制品保留 30 天，下载需要登录 GitHub。

CI 包使用独立包名，显示为「途帧 CI」，可与公开版并存；临时签名变化时需卸载旧 CI 包。长期使用请下载 [Releases](https://github.com/bestksl/street-snap/releases)。标签发布需要先配置签名 Secrets，步骤见 [CI 与版本发布](docs/ci-and-releases.md)。

## Contributing

欢迎通过 [Issue](https://github.com/bestksl/street-snap/issues) 讨论方案，通过 PR 贡献实现。当前优先完善 Android 一期拍摄流程、设备适配与行色效果。

- **Camera**：原生相机接入、参数控制、设备适配。
- **Color**：独立创作的 LUT、影调配方与调色代码。
- **UX & Docs**：单手操作、取景界面、开发与预设制作文档。
- **Device testing**：安装开发预览试拍，反馈机型、系统版本、相机能力与复现步骤。

提交影调预设时，请附上创作说明、来源与许可信息，并确保你有权以项目采用的许可证发布该内容。请勿提交从相机原厂固件中直接提取的色彩文件，或无权再分发的第三方 LUT。

## 独立项目声明

途帧是摄影爱好者发起的独立开源项目，与理光（Ricoh）、富士（Fujifilm）、适马（Sigma）、松下（Panasonic）等厂商无关联、隶属或授权关系。

项目内置影调预设均为独立创作，不使用从原厂固件直接提取的色彩文件，也不代表原厂相机的成像效果。文中涉及的商标归各自权利人所有。

## 开源协议与名称使用

项目代码与内置影调预设采用 [MIT License](LICENSE)，允许在保留版权与许可声明的前提下使用、修改、分发及商业使用。第三方素材须单独标明许可。

MIT License 不授予项目名称与标识的商标权。请勿将「途帧」「street-snap」及项目标识用于商标抢注或冒用本项目官方身份；名称与标识的品牌保护安排将另行说明。
