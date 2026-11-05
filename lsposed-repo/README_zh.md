# FerryQR

注重隐私的 Android 扫码应用，将二维码送到对应的应用中。多数扫码应用扫完只给出文本内容，FerryQR 则将二维码直接交给能处理它的应用或系统功能。

[English](https://github.com/lcebot/ferryqr/blob/main/README.md)

## 功能

- **在对应的应用中打开。** 链接交给能处理它的应用；Wi-Fi、联系人、日程、通行密钥、eSIM、电话、短信、邮件和地图位置交给系统中对应的功能。
- **识别能力更强。** 支付宝、微信的扫一扫中自动放大、从多个二维码中选择等进阶能力，FerryQR 同样具备。本地图片中的二维码也能通过“分享至”或“打开方式”交给 FerryQR 识别。
- **代替各应用自带的扫一扫。** 各应用的专用二维码，包括微信小程序码，经 FerryQR 扫描后直接在对应的应用中打开，不必再进入各应用自己的扫一扫。部分应用需要启用 [LSPosed 模块](#lsposed-模块)。
- **历史记录与收藏。** 扫描过的二维码保存在本地历史记录中，其中的条目可加入收藏。收藏固定显示在历史记录顶部，支持重命名；可将最多 4 条收藏添加到长按 FerryQR 图标弹出的快捷方式中。点击条目可重绘二维码，供他人扫描或保存为图片。
- **生成二维码。** 支持 QR、Aztec、Data Matrix 和 PDF417 格式，生成后可分享、复制或保存。
- **快捷设置磁贴。** 添加到控制中心后，下拉即可开始扫码。

## 安装

需要 Android 11 及以上版本和 64 位 ARM 处理器。在 [Releases](https://github.com/lcebot/ferryqr/releases) 页面下载 APK 安装即可。

## LSPosed 模块

部分二维码只有经对应应用的扫一扫识别，才能进入该应用中的特定功能或页面。在 LSPosed 中启用 FerryQR 模块后，FerryQR 会将二维码的内容直接传递给这类应用，在 FerryQR 一处完成原本分散在各应用中的扫码。不启用模块时，FerryQR 最多只能打开这类应用的扫码界面，需要再扫一次；也可尝试先在浏览器中打开，再由浏览器跳转到应用。目前已适配微信、滴滴和美团。支付宝提供接收外部扫描结果的官方接口，无需模块即可使用。

1. 在 LSPosed 中启用 FerryQR，并在作用域中勾选微信、滴滴和美团。
2. 重启这些应用。
3. 打开 FerryQR，在扫码界面左上角的设置中确认模块已在各个应用中生效。

## 隐私

FerryQR 只申请相机权限，没有联网能力。二维码的内容只会传递给打开它的应用。历史记录保存在本地，生成二维码页面只在打开时读取一次剪贴板。不授予相机权限时，仍能识别图片中的二维码。

## 许可证

[MIT](https://github.com/lcebot/ferryqr/blob/main/LICENSE)。FerryQR 使用了 [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp)、[ZXing](https://github.com/zxing/zxing) 和 [WeChatQRCode](https://github.com/jenly1314/WeChatQRCode)，三者均采用 Apache 2.0 许可证。
