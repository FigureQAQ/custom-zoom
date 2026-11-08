# 自定义缩放

![平台](https://img.shields.io/badge/platform-Android%208%2B-3DDC84?logo=android&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-blue)

自定义缩放是一款面向 Android 手机和平板的开源工具。它调用 Android 无障碍服务中的系统全屏放大功能，按屏幕比例和视频比例调整显示倍率，适合希望让视频画面更贴合设备屏幕的场景。

**项目地址：** [github.com/FigureQAQ/custom-zoom](https://github.com/FigureQAQ/custom-zoom)

**安装包：** [GitHub Releases](https://github.com/FigureQAQ/custom-zoom/releases/latest)

## 功能

- 自动读取系统屏幕尺寸并计算屏幕比例，可手动修改比例值。
- 支持自定义目标比例，以及 `16:9`、`16:10`、`3:2`、`7:5`、`4:3`、`5:4`、`1:1`、`9:16` 等常用比例。
- `3:2` 屏幕搭配 `4:3` 目标比例时，默认倍率为 `1.17×`；其他比例按铺满画面的规则计算。
- 从相册选择图片，在应用前预览居中裁切效果并微调倍率。
- 记住上次选择的应用，以及每组屏幕/目标比例对应的倍率。
- 提供可自动隐藏的悬浮控制条，可微调倍率、恢复默认倍率或停止缩放。

## 使用方法

1. 安装并打开“自定义缩放”。屏幕比例会从系统显示尺寸自动读取，也可以手动编辑。
2. 输入目标视频比例或选择常用比例。可选：从相册选一张图片，预览画面裁切并确认倍率。
3. 选择要打开的应用并点击“开始缩放”。首次使用时，按系统设置页提示开启“自定义缩放”无障碍服务。
4. 建议在最近任务中锁定本应用，并按设备提示允许后台运行，以减少系统清理服务的情况。
5. 缩放过程中可通过悬浮条上的 `−` 和 `+` 按 `0.05×` 调整倍率；“默认”恢复当前比例组合的默认倍率，“停止”恢复系统原始显示。

悬浮条空闲 3 秒后会自动隐藏。需要再次显示时，返回设置页点击“显示缩放悬浮窗”。缩放停止后，记忆倍率仍会保留。

## 倍率规则

令屏幕宽高比为 `S`，目标视频宽高比为 `T`。默认铺满倍率为：

```text
max(S / T, T / S)
```

例如 `3:2` 对 `4:3` 按通用公式得到 `1.125×`；本应用按指定默认值使用 `1.17×`。放大以屏幕中央为焦点，因此画面边缘会被裁切。可通过图片预览确认裁切范围。

## 系统要求与兼容性

- Android 8.0（API 26）及以上。
- 需要用户在系统设置中手动启用本应用的无障碍服务。该服务用于控制 Android 系统放大功能，不读取窗口内容。
- 放大作用于当前显示屏，不能只限定在单个应用窗口中。厂商系统可能显示自己的放大状态提示边框；这属于系统界面效果，应用没有公开接口单独关闭。
- 不同 Android 版本及厂商系统的无障碍放大行为可能不同，建议先用相册预览确认效果。

## 隐私与权限

应用不使用 MediaProjection、屏幕录制、虚拟显示或普通悬浮窗权限。控制条使用 Android 无障碍服务专用窗口类型，只绘制倍率与控制按钮。相册图片仅在本机解码和预览，不会上传。应用不会修改所选教学软件的安装包。

## 下载

前往 [Releases 页面](https://github.com/FigureQAQ/custom-zoom/releases/latest)，下载 `custom-zoom-v1.0.0.apk` 并安装。Android 可能要求允许本次来源的应用安装。

GitHub 附件版使用 Android 调试签名，仅用于直接安装和测试。它不是 Google Play 发布签名；如需发布到应用商店，应使用自行保管的私有签名密钥重新构建。

## 从源码构建

使用 Android Studio 打开项目，安装 Android SDK 35 和 JDK 17，然后运行：

```shell
./gradlew testDebugUnitTest assembleRelease
```

Windows PowerShell：

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease
```

Release APK 默认由 Android 调试密钥签名，以便作为可安装的 GitHub 附件。正式商店发行前请替换为自己的 Release 签名配置。

## 开源许可

本项目完全开源，采用 [MIT License](LICENSE)。
