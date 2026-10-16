# 途帧 · 项目简介草案

## GitHub About

途帧 · 轻量开源街拍相机。28 / 35 / 50mm 三档等效视角、Snap 快拍、自定义影调。Android 一期开发中，iOS 后续支持。

## 中文简介

喜欢口袋相机，也喜欢它们各自的脾气：熟悉的视角、顺手的快门，还有一眼认得出的颜色。途帧于手机之上：收束纷扰，快门轻触，影调自调。途遇，帧藏。

途帧（street-snap）面向 Android / iOS，以理光 GR 这类随身相机快速就绪的体验为参照，追求启动快、操作少、体积轻。只保留 28 / 35 / 50mm 三档等效视角，借鉴 Snap 快拍的思路，把准备工作前置：预设参数，快速启动，取景确认，按下快门；也方便熟悉视角后的盲拍。等效视角通过设备镜头切换或画面裁切实现，预设对焦按镜头与系统 API 支持情况提供。

「行色 / Xingse」是途帧的调色模块，提供正片、负片、粉末蓝等独立创作的影调，支持自定义 LUT、饱和度与锐度等参数调节，并单独记住每套影调的设置。另有软件柔光、白平衡与漂移控制。代码和内置预设一同开源。Android 一期开发中，最低支持 Android 12，首测设备为荣耀 Vs3；效果和性能待真机验证。iOS 与配方分享后续支持。项目免费，无广告、无内购。

同时关注取景流畅度与快门响应，计划结合 Shader Fusion、相机缓冲共享与预览背压控制，减少渲染开销和画面积压。

## English description

street-snap (途帧) is an open-source street photography camera project, starting with Android 12 and later; iOS support will follow. Inspired by the ready-to-shoot experience of pocket cameras such as the Ricoh GR, it targets a fast launch, a small app size, and fewer steps to capture the decisive moment. The first Android version uses the rear camera and cropping for 28mm, 35mm, and 50mm equivalent fields of view, subject to device capabilities. Snap presets keep supported exposure and focus settings ready for the next shot. Xingse (行色) provides independently authored looks, custom SDR LUT import, per-look adjustments, and local JSON settings. Software highlight diffusion, white balance presets, and A–B / G–M shift round out the controls. The code and built-in presets are open source. Development is underway, with camera, color, foldable behavior, and performance awaiting validation on the Honor Vs3.

Planned optimizations include shader fusion, shared camera buffers, and preview backpressure, targeting smooth previews and responsive capture.

## English tagline

street-snap: Open-source cross-platform documentary camera (iOS/Android), crafted for the serendipity of street capture.

## Slogan

途帧：途遇，帧藏。

行色：沿途所见，自有其色。
