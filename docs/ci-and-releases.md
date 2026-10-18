# CI APK 与版本发布

普通 CI 用于测试代码；公开下载的 APK 由单独的发布工作流生成。发布签名需要维护者先配置 GitHub Secrets；下面是配置与使用步骤，不代表签名发布流程已经运行通过。

## 下载 CI APK

推送到 `main`、提交 PR，或在 **Actions → Android → Run workflow** 手动触发 [Android 工作流](../.github/workflows/android.yml)。构建、单元测试和 Lint 通过后：

1. 打开这次运行的 **Summary**，点击 **Download Preview APK**；需要调试时选 **Download Debug APK**。
2. 下载并解压 ZIP，里面有 APK 和 `SHA256SUMS`。制品保留 **30 天**，下载需要登录 GitHub。
3. 在手机上打开 APK 安装；文件名包含版本和源码提交号，例如 `street-snap-v0.2.0-dev-preview-ci-a1b2c3d.apk`。

CI 包显示为 **途帧 CI**，包名为 `dev.streetsnap.app.ci`，可与公开版同时安装，参数各自独立。Preview 关闭调试并启用 R8 与资源裁剪；Debug 用于开发排查。

CI 使用构建环境的临时开发签名，跨次运行不保证能覆盖安装。遇到签名不兼容时，卸载旧的「途帧 CI」再安装；这会清除 CI 包的参数和导入 LUT，不影响公开版。需要长期使用时下载 [Releases](https://github.com/light-witness-lab/street-snap/releases) 中的版本。

解压后可校验文件：

```sh
# macOS
shasum -a 256 -c SHA256SUMS
# Linux
sha256sum -c SHA256SUMS
```

本地复现 CI 的包名与构建配置：

```sh
./gradlew -PciBuild=true :app:assemblePreview :app:assembleDebug
python3 .github/scripts/package-apks.py ci
```

第二条命令需要 Python 3.8+，输出到 `dist/preview/` 和 `dist/debug/`；它只整理构建产物，不负责签名。

## 配置固定发布签名

已公开的 **v0.2.0-dev APK 使用开发签名**。若下一版改用新分发密钥，同包名也不能直接覆盖安装，需要先卸载旧版；卸载会丢失应用内参数与导入的 LUT。请先记下参数、保留 LUT 原文件。切换后，后续公开版本持续使用同一把分发密钥。

已有合适的分发密钥时沿用它。仅在首次建立正式签名时，使用 JDK 的 `keytool` 在仓库外生成一次；密码按交互提示输入，不写进命令：

```sh
install -d -m 700 "$HOME/.local/share/street-snap-signing"
keytool -genkeypair \
  -keystore "$HOME/.local/share/street-snap-signing/street-snap-release.jks" \
  -storetype JKS \
  -alias street-snap \
  -keyalg RSA -keysize 3072 -validity 10000 \
  -dname "CN=street-snap, OU=Open Source, O=street-snap"
chmod 600 "$HOME/.local/share/street-snap-signing/street-snap-release.jks"
```

把密钥文件、别名和两个密码保存到可靠的加密备份中。丢失私钥将影响已安装用户的后续升级；不要把密钥、密码或其 Base64 内容提交到仓库、Issue 或构建日志。

仓库 **Settings → Secrets and variables → Actions** 需要以下四个 Repository secrets：

| Secret | 值 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 整个 keystore 文件的 Base64 编码，编码不是加密 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 密钥别名，上面示例为 `street-snap` |
| `ANDROID_KEY_PASSWORD` | 该别名的私钥密码；生成时直接回车则与 keystore 密码相同 |

也可以用已登录的 GitHub CLI 配置，Base64 通过管道上传，不输出到终端；两条密码命令会提示输入：

```sh
base64 < "$HOME/.local/share/street-snap-signing/street-snap-release.jks" | \
gh secret set ANDROID_KEYSTORE_BASE64 --repo light-witness-lab/street-snap
gh secret set ANDROID_KEYSTORE_PASSWORD --repo light-witness-lab/street-snap
gh secret set ANDROID_KEY_ALIAS --repo light-witness-lab/street-snap --body street-snap
gh secret set ANDROID_KEY_PASSWORD --repo light-witness-lab/street-snap
```

工作流将密钥恢复到 runner 临时目录，密码通过步骤级环境变量传入，并在结束时清理密钥文件。缺少任一 Secret 时发布任务会明确失败，不会改用临时开发签名；普通 CI 不依赖这些 Secrets。

本地签名构建使用 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 四个环境变量，其中路径指向已有密钥文件。四项全部设置后，`:app:assembleRelease` 才使用分发签名；不要将这些值写入仓库里的 Gradle 文件。

## 从版本标签生成草稿

1. 在 `app/build.gradle.kts` 更新 `versionName`，并将 `versionCode` 增加到高于所有已发布版本的整数。例如下一版可用 `versionName = "0.3.0-dev"`、`versionCode = 3`。工作流校验版本名，不会自动递增版本号。
2. 提交并推送代码，确认 **Android** 检查通过。标签指向的提交必须包含发布工作流和打包脚本。
3. 创建对应标签并推送，例如：

```sh
git tag -a v0.3.0-dev -m "v0.3.0-dev"
git push origin v0.3.0-dev
```

`v*` 标签会触发 [Android release draft](../.github/workflows/release.yml)。也可在该工作流的 **Run workflow** 中填入已经推送的版本标签。标签必须严格等于 `v` 加上 `versionName`；例如 `v0.3.0-dev` 对应 `0.3.0-dev`，工作流会再次检查实际 APK 元数据。

构建、Lint、测试和 APK 签名验证通过后，工作流创建 **Draft Release**，附带 `street-snap-v<版本>.apk` 与 `SHA256SUMS`。版本名含预发布后缀时同时标记为 Prerelease。维护者从运行摘要进入草稿，检查安装与覆盖升级、补充版本说明，再点击 **Publish release**。

工作流不覆盖任何已有 Release，包括草稿，也不会改动已公开的 `v0.2.0-dev`。需要继续编辑已有草稿时直接打开它；需要新的构建版本时使用新的版本号和标签。
