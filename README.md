# HDR Photo

一个面向 Android 16 手机的本地图片浏览应用。

## 功能

- 启动后读取系统相册并以网格浏览所有图片。
- Ultra HDR 图片在网格右上角显示 `HDR` 角标。
- 点进图片后进入全屏浏览，隐藏 `HDR` 角标。
- 全屏浏览窗口请求 HDR color mode，用系统 `ImageDecoder` 保留 Ultra HDR gain map，让 Android 16 的 HDR 屏幕按系统能力显示 Ultra HDR。
- 支持双击缩放、双指缩放、拖拽查看。

## 构建 APK

仓库内已经包含 GitHub Actions：

1. 打开 GitHub 仓库的 **Actions**。
2. 运行 `Build Android APK` workflow，或推送到 `main` 触发构建。
3. 在 workflow 结果的 Artifacts 中下载 `hdr-photo-debug-apk`。

## 说明

Ultra HDR 显示依赖 Android 14+ 系统、HDR 屏幕、系统图形栈和设备厂商实现。这个应用不会把 Ultra HDR 转成普通 SDR 图，而是在放大浏览时尽量保留 gain map 并请求 HDR 输出。
