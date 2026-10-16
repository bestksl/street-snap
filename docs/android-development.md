# Android 开发与试拍

当前为 v0.2 开发版，支持 Android 12（API 31）及以上、后置相机和 OpenGL ES 3.0。首测设备为荣耀 Vs3，具体 Android / MagicOS 版本、镜头能力和性能数据待连接真机记录。本版功能见 [影调与操作说明](android-v0.2-notes.md)。

## 构建

- JDK 17。
- Android SDK Platform 37.0（`platforms;android-37.0`）与 Build Tools 36.0.0。
- Gradle 9.6.0、AGP 9.4.1；使用 AGP 内置 Kotlin 2.2.10，Compose 插件版本与其一致。
- Android SDK 工具安装后，配置 `local.properties` 中的 `sdk.dir=/absolute/path/to/sdk`，或设置 `ANDROID_HOME`。本地配置不入库。

```sh
./gradlew :app:assemblePreview
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :camera:testDebugUnitTest :color:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleRelease
```

试拍 APK：`app/build/outputs/apk/preview/app-preview.apk`，启用与 Release 一致的 R8 和资源裁剪，使用开发测试签名且关闭调试。调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。正式 Release 默认生成未签名 APK，正式分发时使用维护者自己的签名，密钥和密码不能提交仓库。Preview / Debug 与正式包的签名不同，后续切换正式签名可能需要卸载开发包。

GitHub Actions 工作流在推送或 PR 时执行构建、Lint 和核心单元测试，产出 `street-snap-preview` 与 `street-snap-debug` 制品。未推送、未运行的 CI 不视为已通过。

## 本地验证 · 2026-10-16

| 检查 | 结果 |
| --- | --- |
| Debug / Preview / Release | 三种 APK 均构建成功；Release 为未签名包 |
| 核心单元测试 | 32 项通过：设置迁移与持久化 6 项、视角与白平衡能力 9 项、LUT 与影调参数 17 项 |
| Android Lint | 0 错误、13 警告：12 项工具链/依赖版本提示，1 项图标目录冗余 `v26` 限定 |
| Preview APK | 3,102,477 字节（3.10 MB / 2.96 MiB），已启用 R8 和资源裁剪 |
| 签名与清单 | APK v2 签名校验通过；`dev.streetsnap.app`，`0.2.0-dev`（versionCode 2），最低 API 31、目标 API 37，关闭调试 |
| 桌面 GPU | 2D 着色器编译/链接通过；6 项像素检查通过，覆盖中性输出、去饱和、白平衡方向、空间处理与柔光的分条一致性 |
| 硬件验证 | 用户已反馈 v0.1 可运行；v0.2 新效果、实时取景、折叠屏与温升尚待荣耀 Vs3 实拍记录 |

本次 Preview APK 的 SHA-256：

```text
48fe4b41823a008c0ca99e3cafc417e43d2292ec24178929fd643501118339f5
```

以上为本地构建、单元测试和桌面 GPU 验证结果。桌面验证使用 ANGLE / OpenGL，覆盖照片用的 2D 纹理路径；Android OES 相机预览、Camera2 控制与设备性能仍需真机验收，下方检查项保持待测。

## 模块

| 模块 | 职责 |
| --- | --- |
| `app` | Compose 界面、权限、音量快门、JSON 参数记忆、LUT 导入与系统相册保存 |
| `camera` | CameraX 生命周期、相机能力、视角与曝光/对焦/白平衡设置、私有 JPEG 缓存 |
| `color` | `.cube` 解析、内置 LUT、影调微调、软件柔光与白平衡漂移、GPU 预览和照片处理 |

相机输出约 12 MP 的 JPEG，经过 EXIF 转正、同套影调算法处理后写入 `Pictures/street-snap`。处理串行执行，最多两张进行中的照片；相册文件以待完成状态创建，仅在写入成功后公开，失败时清理。

## 当前范围与边界

- 三档视角基于相机元数据与数字裁切。元数据未知时只显示原生视角；设备原生视角不够广时，不提供更广的虚假焦段。
- 能唯一匹配物理主摄时尝试固定镜头；无法固定时在设备能力中记录，需要真机检查是否发生厂商自动换镜。
- 手动 ISO / 快门、手动对焦与曝光补偿按能力启用。不把屈光度滑杆标成精确米制距离，不承诺泛焦。
- 行色支持 SDR sRGB、17³ / 33³ 的 `.cube` 3D LUT，输入文件最多 4 MiB。暂不支持 Log、HDR、1D 或混合 LUT。
- GPU 照片处理上限为 20 MP；设备无法提供该范围内的拍照尺寸或内存不足时明确报错，不静默输出无影调照片。
- 原色、正片、负片、粉末蓝、黑白和晴街均为独立编写的预设，各自保存调节值，效果仍需实拍评估。柔光与白平衡为全局设置。
- 启动直接取景；拍摄页不联网、不读取整个图库，首次仅申请相机权限。LUT 导入通过系统文件选择器。
- 当前配方为本地 JSON 参数记忆，暂不提供配方分享、RAW、视频和前摄。

## 荣耀 Vs3 验证记录

接入手机并开启 USB 调试后可安装开发包：

```sh
adb devices -l
adb install -r app/build/outputs/apk/preview/app-preview.apk
```

打开应用中的设备能力信息，记录并复制相机能力 JSON。正式记录应包含设备具体型号、Android 版本、MagicOS 版本、APK 版本与测试日期。

- [ ] 首次授权、拒绝后重试、系统撤销授权后恢复。
- [ ] 相机占用、切后台、熄屏与恢复，不持续黑屏。
- [ ] 28 / 35 / 50mm 预览与成片取景范围一致，裁切尺寸如实保存。
- [ ] ISO、快门、曝光补偿与对焦设置真实生效；重启后恢复设置。
- [ ] 旋转与内外屏切换后不拉伸；点击对焦位置正确；180° 旋转照片方向正确。
- [ ] 拍照与保存期间折叠、旋转，不丢照片；处理队列满时有明确反馈。
- [ ] 六套内置影调和外部 LUT 的取景/照片对照，无明显错误偏色、翻转或通道错位。
- [ ] 切换滤镜后恢复各自的饱和度、锐度等设置；HDF、白平衡及 A–B / G–M 漂移方向正确。
- [ ] 连续拍摄 50 张，成功保存后缩略图更新且相册张数一致；失败或空间不足有提示，无残留空文件。
- [ ] Release 构建授权后做 30 次冷启动，测到有效预览且预设与快门均就绪的耗时。
- [ ] 正常光照持续取景、调参并拍照，检查帧率、温升、内存与后台保存耗时。

模拟器和 JVM 测试可以检查部分界面与算法；它们不能代替上述硬件验证。启动 P95 ≤ 1.5 秒和正常光照 30fps 仍为待测预算。
