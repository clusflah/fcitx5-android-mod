# AGENTS.md — AI 编程助手须知

本文件写给接手这个仓库的 AI 编程工具（Claude Code、Codex、Gemini CLI、Aider、Cline、Copilot 等）。开始工作前请先读完。

用户说中文，回复用户请用中文。

## 这是什么项目

- 原版小企鹅输入法 Android 版 [fcitx5-android/fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 的 **fork**：`clusflah/fcitx5-android-mod`（公开仓库）。
- 新增的功能（常用语、Azure 语音、大模型助手、AI 联想、clipboard-sync 插件等）见 [MODIFICATIONS.md](MODIFICATIONS.md)。**改了功能，要同步更新 MODIFICATIONS.md。**
- 用户的 Windows 本地仓库：`E:\fcitx5-android-mod`。`E:\fcitx5` 是早期不带 git 的旧副本，只作备份，**不要在那里改代码**。

## 目录结构

| 路径 | 内容 |
|---|---|
| `app/` | 主程序（Kotlin + JNI C++）。修改版的新代码主要在 `app/src/main/java/org/fcitx/fcitx5/android/` 下的 `data/ai/`、`data/phrase/`、`input/phrase/`、`input/voice/`、`input/bar/`、`ui/main/` |
| `plugin/*` | 插件。`plugin/clipboard-sync` 是修改版新增的 |
| `lib/*` | 原生库封装和 `plugin-base`；`lib/*/src/main/cpp/*` 大多是 git 子模块 |
| `build-logic/` | Gradle 约定插件（包名后缀、签名、原生编译参数） |
| `tools/windows/` | Windows 本地编译用的 extra-cmake-modules；`gettext/bin/*.exe` 不在仓库里（被 `.gitignore` 的 `bin/` 排除） |
| `docs/screenshots/` | MODIFICATIONS.md 引用的截图 |
| `.github/workflows/mod-release.yml` | 修改版的自动同步 + 编译 + 发布工作流 |

设置项定义在 `app/.../data/prefs/AppPrefs.kt`；新增界面文字在 `app/src/main/res/values{,-zh-rCN}/strings_ai.xml`、`strings_azure_voice.xml`、`strings_common_phrase.xml`。

## 绝对不能做的事

1. **不要提交任何密钥。** 签名密钥文件、签名密码、API Key、Azure 密钥、服务器账号密码都不能进仓库，也不要打印到输出里。仓库是公开的。
   - API Key 等都由用户在 App 设置中填写，代码里不能有默认密钥。
   - 签名配置只在用户本机的 `~/.gradle/gradle.properties`（`signKeyFile`、`signKeyAlias`、`signKeyPwd`）和 GitHub Secrets 里。**不要读取或显示 `signKeyPwd` 的值。**
2. **不要改动、删除或重新生成签名密钥**（`E:\keys\fcitx.jks`）。换了签名，用户手机上的 App 就无法覆盖更新。
3. **不要提交符号链接被 Windows 改坏的文件**（见下文“Windows 上的坑”）。
4. **不要强推**（`git push --force`）master，也不要改写已推送的历史。
5. 不要修改仓库 Secrets、Actions 设置或仓库设置，除非用户明确要求。

## 网络（用户在中国大陆）

访问 GitHub 需要代理，运行 `git` / `gh` 之前先设置：

```powershell
$env:HTTPS_PROXY="http://127.0.0.1:7890"; $env:HTTP_PROXY="http://127.0.0.1:7890"
```

`gh` 安装在 `C:\Program Files\GitHub CLI\`，旧终端里可能不在 PATH 上，需要用完整路径或执行 `$env:Path += ";C:\Program Files\GitHub CLI"`。

Gradle 的代理和阿里云 Maven 镜像已经配置好：阿里云镜像只在本地编译时使用，有 `CI` 环境变量时会跳过（见 `settings.gradle.kts`、`build-logic/settings.gradle.kts`）。

## 本地编译

环境：Android SDK（路径写在 `local.properties` 里，这个文件不提交）、NDK `28.0.13004108`、CMake `3.31.6`、Build-Tools `36.1.0`。只编译 arm64-v8a（`gradle.properties` 中的 `buildABI`）。

```powershell
.\gradlew.bat :app:assembleRelease :assembleReleasePlugins
```

- 产物：`app/build/outputs/apk/release/`、`plugin/*/build/outputs/apk/release/`
- 只改了 Kotlin 时，可以先快速检查能否编译：`.\gradlew.bat :app:compileReleaseKotlin`
- release 版包名带 `.mod` 后缀（主程序是 `org.fcitx.fcitx5.android.mod`，插件是 `...plugin.xxx.mod`），可以和原版共存。插件通过 `lib/plugin-base` 中的 `.mod` 包名找到主程序。
- 本地编译版本号固定为 `0.1.3-mod`（写在 `gradle.properties` 里）；CI 编译时用环境变量覆盖成 `0.1.3-mod.<运行次数>`。
- **sherpa-onnx AAR**（离线语音识别）：`app/build.gradle.kts` 末尾的 mod 代码块在配置阶段把 AAR 下载到 `app/libs/`（不提交），并校验 SHA-256。升级时同时改 `sherpaOnnxVersion` 和 `sherpaOnnxSha256`，再用 `javap` 确认 `OfflineRecognizer` 等类的接口没有变化。R8 规则 `-keep class com.k2fsa.sherpa.onnx.** { *; }` 不能删（JNI 会按名字读取这些类的字段）。
- 离线语音模型（约 227 MB）**不打包**进 APK，由用户在 设置 → 语音模型 里下载或导入；引擎和 Azure 设置在 `AppPrefs.voice` 分类（`input/voice/OfflineVoice.kt`）。

## Git 约定

- 远程：`origin` = `clusflah/fcitx5-android-mod`，`upstream` = `fcitx5-android/fcitx5-android`。主分支为 `master`。
- 提交身份已在仓库内配置（`clusflah` 和 GitHub noreply 邮箱），不要改成用户的个人邮箱。
- 提交信息用英文祈使句，例如 `Disable push-to-talk voice input in password fields`。
- **只改文档、截图，不影响 APK 时**，在提交信息里加 `[skip ci]`，避免白白编译、发布一个新版本。（`.md` 文件的改动本来就不会触发编译，但截图、工作流等其他文件会。）
- 新建源文件时沿用原版的文件头：`SPDX-License-Identifier: LGPL-2.1-or-later`。

## Windows 上的坑

- 原版仓库里有一些**符号链接**（应用商店图标 `*/play/listings/en-US/graphics/icon/icon.png`、`app/src/main/play/release-notes/en-US/default.txt`、`build-logic/gradle/wrapper/*`）。在 Windows 上（`core.symlinks=false`），git 会把它们检出成只有一行路径的文本文件。
  - 不要把它们替换成真实文件后提交，否则在 Linux 上就会变成坏掉的链接。提交前用 `git diff --cached --raw` 检查有没有模式为 `120000` 的文件被改动。
  - `build-logic/gradle/wrapper/` 下的两个文件，在用户本机被换成了真实文件，并设置了 `git update-index --skip-worktree`。这是有意的，不要撤销，也不要提交。
- `git` 会提示 `LF will be replaced by CRLF`，可以忽略（`core.autocrlf=true`）。

## 自动编译与发布（`.github/workflows/mod-release.yml`）

- **每天 03:00 UTC**（北京时间 11:00）和**手动运行**时：先把 `upstream/master` 合并进来；有新提交才编译。
- **推送到 master** 时：直接编译。
- 编译完成后签名，并发布 GitHub Release `v0.1.3-mod.<run_number>`，附带主程序和全部插件的 APK。
- 签名用的 Secrets：`SIGN_KEY_BASE64`、`SIGN_KEY_PWD`、`SIGN_KEY_ALIAS`（已经设置好，只能覆盖、不能读出）。
- 原版自带的 `Nix`、`Publish` 工作流在这个 fork 中已停用。
- 查看运行情况：`gh run list --repo clusflah/fcitx5-android-mod`，失败日志：`gh run view <id> --log-failed`。

## 和原版合并冲突时

每天自动合并失败时，GitHub 会发邮件通知用户。这时在本地处理：

```powershell
git fetch upstream
git merge upstream/master
# 解决冲突：保留原版的新改动，同时把修改版的功能重新接上
git submodule update --init --recursive   # 原版可能更新了子模块
.\gradlew.bat :app:assembleRelease :assembleReleasePlugins   # 确认能编译
git push origin master
```

- 冲突最常见的地方，是修改版和原版都改过的文件：`KawaiiBarComponent.kt`、`ButtonsBarUi.kt`、`IdleUi.kt`、`ClipboardSuggestionUi.kt`、`TextKeyboard.kt`（只改了 `?123` 那一行）、`AppPrefs.kt`、`FcitxInputMethodService.kt`、`CommonKeyActionListener.kt`、`AndroidManifest.xml`、`settings.gradle.kts`、`build-logic/convention/...`。
- 新功能尽量写在**新文件**里，原版文件里只留最少的调用代码（比如 `NumberSymbolSwitchKey.kt`、`data/otp/`、`data/password/`），这样合并时冲突少。
- 原版改了 `.github/workflows/` 下的文件时，自动推送会被拒绝（GITHUB_TOKEN 没有 workflow 权限）。这种情况让用户在 GitHub 网页上点 **Sync fork**，或者在本地合并后推送。

## 隐私与安全规则（修改功能时要保持）

- AI 联想会把光标前的文字发给模型服务。**密码框和无痕模式（`IME_FLAG_NO_PERSONALIZED_LEARNING`）下必须保持停用**（`KawaiiBarComponent.aiBlocked`）。
- 长按空格的语音输入在**密码框中不能录音**，要回退到原有的空格长按功能（`CommonKeyActionListener`）。
- 明文 HTTP 只允许 `127.0.0.1` / `localhost`（`res/xml/network_security_config.xml`），用于手机上运行的本地模型。
- 导出的用户数据备份里包含 API Key，不要把备份文件放进仓库。
