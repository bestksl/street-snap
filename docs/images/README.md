# 界面截图

这三张图片来自实际 Preview APK，运行于 Android 15（API 35）AOSP ARM64 模拟器，使用内置 `virtualscene` 后置相机。截图用于展示操作界面，不代表真机拍摄效果。

- `viewfinder.png`：正片影调、HDF、构图网格与拍照后的缩略图。
- `color-controls.png`：粉末蓝影调与饱和度调节。
- `white-balance.png`：设备提供的白平衡选项及 A–B / G–M 漂移。

模拟器的相机元数据不足以确定等效焦段，因此界面如实显示「原生视角」。28 / 35 / 50mm 选项按真实设备的相机能力提供。

## 更新方式

构建并安装 `:app:assemblePreview`，在应用中打开对应面板，用 ADB 截图：

```sh
adb -s <设备序列号> exec-out screencap -p > screenshot.png
```

当前截图原始分辨率为 1080 × 2340，等比缩小为 720 × 1560 后存入此目录。保留完整界面，不拼接预览画面或伪造控件状态。更新时同步检查 README 的三列图片与描述。
