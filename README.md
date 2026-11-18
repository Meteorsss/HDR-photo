# HDR Photo

本地 Android 14+（API 34）相册，面向 Ultra HDR 照片。HDR 实际显示取决于屏幕、系统和厂商实现。

**维护入口：** [现有 PR #1](https://github.com/Meteorsss/HDR-photo/pull/1) / `codex/android16-performance-liquid-glass`。`main` 不是这轮交互改动的源码。[已发行的 1.1.7](https://github.com/Meteorsss/HDR-photo/releases/tag/v1.1.7) 保持不变，1.1.8 是 PR 中的待测版本。

## 操作

- 缩略图右上角保留 **HDR**；全屏查看带增益图的原图时，长按比较 SDR 底图，松手恢复 HDR，不修改文件。可双击或双指缩放。
- 点底栏、拖动底栏选择器，或在列表中向左/右滑，切换照片与相册。底栏模糊背后的实际内容，文字不参与模糊。
- 在相册列表或某个相册内刷新，保持页面和列表位置。照片返回到当前相册，再返回到相册列表。
- 全屏左右滑动翻页；上滑看详情，点击面板外的照片、下滑或按返回关闭详情。详情内容较长时先滚回顶部再下拉。
- 支持同目录配对视频、带 Motion Photo 容器元数据的内嵌视频。部分媒体授权可从“管理可见照片”增选。

## 构建与签名

JDK 17、Android SDK 36、Gradle 8.13：`gradle testDebugUnitTest lintDebug assembleDebug`。PR 的 Actions 提供 `hdr-photo-debug-apk` 供测试，不发布 Release。PR 构建不读取正式版密钥。

长期发布时应在受信任的构建机配置 `HDR_KEYSTORE`（绝对路径）、`HDR_STORE_PASSWORD`、`HDR_KEY_ALIAS`、`HDR_KEY_PASSWORD`，单独执行 `gradle assembleRelease` 并核对签名指纹。私钥应由维护者安全保管并备份，不能提交。旧 1.1.7 使用 debug 证书；若新证书不同，不能覆盖安装，需要一次卸载重装（会清应用缓存，不删除系统照片）。

## 真机回归

1. 交替打开 Ultra HDR、普通 JPEG/HEIC；长按/松手、双指和取消手势后确认颜色、缩放、右上角角标正确，原图不变。
2. 一组实际连续照片：快速往返滑动；低速逐张拖动；大图未就绪时应保留当前照片或预览，不插黑帧。
3. 在明、暗主题下上下滚动多色缩略图，检查底栏毛玻璃随内容改变且文字清晰；拖动胶囊后松手切换。
4. 在相册和具体相册刷新；打开照片后连续返回两次；上下滑动长详情，点击外侧/下拉退出详情而不是退出照片。
5. 在首次拒绝、部分授权、全部授权下测试。交替打开 LIVE 和无视频的 HEIC，后者不显示 LIVE。
