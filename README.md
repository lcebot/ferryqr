# FerryQR

FerryQR is a QR code scanner for Android. Point the camera at a code and FerryQR opens it in the matching app. If no app fits, it shows the content of the code, which you can copy, search or share.

[中文说明](README_zh.md)

## Scan results

> When there are several codes, you can pick the one to open.

**Links:** If an app on your phone can open the link, the link opens in that app. Otherwise the address is shown and you can open it in your browser.

**Alipay and WeChat codes:** Alipay codes open directly in Alipay. WeChat does not accept scan results from other apps, so when FerryQR detects a WeChat code it can only take you to the WeChat scanner, where you scan the code again.

**Wi-Fi:** Shows the network name and password. Most networks can be connected to directly.

**Contacts and events:** Can be saved to your contacts or calendar.

**Passkeys:** Opens your installed password manager so you can use a saved passkey.

**eSIM activation codes:** Opens the eSIM setup on your phone. If your phone does not accept this, copy the activation code and add the eSIM from the SIM settings.

**Phone numbers, text messages, email addresses, map locations and app links:** Open in the matching app.

**Anything else:** Shown as text.

## Tips

A code does not need to be in the middle of the picture. FerryQR reads it anywhere in view.

When a code is small or far away, FerryQR zooms in by itself. You can also pinch to zoom.

If a code still will not scan, tap the reset button to return to the default zoom and restart the camera.

In low light, use the flashlight button.

You can pick an image from your gallery to scan in FerryQR, or share an image to FerryQR from another app.

You can add FerryQR as a Quick Settings tile to open it quickly.

## Privacy

FerryQR only asks for camera access and has no internet access. It never sends the content of a code to anyone other than the app you open it in. Without camera access, you can still scan codes in gallery images.

## License

[MIT](LICENSE). FerryQR uses [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) and [WeChatQRCode](https://github.com/jenly1314/WeChatQRCode), both licensed under Apache 2.0.
