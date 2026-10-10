# 小企鹅输入法（修改版）Windows 编译说明

本压缩包已包含：全部子模块源码、arm64-v8a 预编译库、extra-cmake-modules、msgfmt.exe（位于 `tools/windows`）。
**不需要安装 MSYS2、不需要 git**，只需 Android Studio。

新增功能：常用语（分组 + 工具栏快捷键）、Azure 语音输入（长按空格）、大模型助手（Gemini / DeepSeek / Kimi）。

## 1. 解压
解压到**短路径**，例如 `C:\f5`（路径不要有中文和空格）。
建议开启 Windows 长路径支持（管理员 PowerShell 执行一次后重启）：
```
New-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem" -Name LongPathsEnabled -Value 1 -PropertyType DWORD -Force
```

## 2. 安装 SDK 组件
Android Studio → Settings → Languages & Frameworks → Android SDK：
- **SDK Platforms**：Android 16（API 36）
- **SDK Tools**（勾选右下角 *Show Package Details*）：
  - Android SDK Build-Tools **36.1.0**
  - NDK (Side by side) **28.0.13004108**
  - CMake **3.31.6**

## 3. 打开并编译
1. File → Open → 选择 `C:\f5`，等待 Gradle Sync 完成（首次需下载依赖，国内网络可能需要代理）。
2. 若提示 JDK 版本，使用 Android Studio 自带的 JBR 17/21（Settings → Build Tools → Gradle → Gradle JDK）。
3. 菜单 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**。
   首次编译 C++ 部分约 10–30 分钟。
4. 生成的 APK：`app\build\outputs\apk\debug\app-arm64-v8a-debug.apk`

Debug 版包名为 `org.fcitx.fcitx5.android.debug`，**可以和官方版共存**，无需卸载官方版。
也可以直接连接手机点 ▶ Run 安装。

## 4. 其他
- 只编译 arm64-v8a（`gradle.properties` 里的 `buildABI`）。其他架构的预编译库已删除以减小体积。
- 想要 Release 版：Build → Generate Signed App Bundle / APK，用自己的签名。
- 编译报错：把 Build 窗口里第一处红色错误发给我。

## 常见问题
- **找不到 ECM / msgfmt**：确认 `tools\windows\ecm` 与 `tools\windows\gettext\bin\msgfmt.exe` 存在。
- **NDK / CMake 版本不对**：按第 2 步安装精确版本，或在 `gradle.properties` 中加 `ndkVersion=你的版本`、`cmakeVersion=你的版本`。
- **路径过长 / 文件找不到**：换到更短的目录并开启长路径支持，然后 Build → Clean Project 再编译。
