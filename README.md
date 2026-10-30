# FerryQR

FerryQR is a QR code scanner for Android. Point the camera at a code and FerryQR opens it in the matching app. If no app fits, it shows the content of the code, which you can copy, search or share.

[中文说明](README_zh.md)

## Install

FerryQR needs Android 11 or later on a 64-bit ARM (arm64-v8a) phone. Download the APK from the [Releases](../../releases) page and install it. The optional [LSPosed module](#lsposed-module) is enabled separately.

## Scan results

> When there are several codes, you can pick the one to open.

**Links:** If an app on your phone can open the link, the link opens in that app. Otherwise the address is shown and you can open it in your browser.

**Alipay codes:** Open directly in Alipay.

**WeChat codes:** WeChat does not accept scan results from other apps, so when FerryQR detects a WeChat code it can only take you to the WeChat scanner, where you scan the code again. With the [LSPosed module](#lsposed-module), the code opens directly in WeChat without another scan.

**WeChat mini program codes:** FerryQR reads the round mini program codes with three eye markers. Mini programs only open in WeChat: with the LSPosed module the code opens directly in WeChat; without it, the result card shows the code drawn again from its content, and you scan it again in the WeChat scanner. The few mini program codes that cannot be read are still recognised by their shape, and a result card lets you decide whether to open them in WeChat; nothing is sent until you tap it.

**Wi-Fi:** Shows the network name and password. Most networks can be connected to directly.

**Wi-Fi Easy Connect:** Opens the system Wi-Fi Easy Connect setup when the phone supports it.

**Contacts and events:** Can be saved to your contacts or calendar.

**Passkeys:** Opens your installed password manager so you can use a saved passkey.

**eSIM activation codes:** Opens the eSIM setup on your phone. If your phone does not accept this, copy the activation code and add the eSIM from the SIM settings.

**Phone numbers, text messages, email addresses, map locations and app links:** Open in the matching app.

**Anything else:** Shown as text.

## LSPosed module

FerryQR is also an optional LSPosed module. After FerryQR takes you to the WeChat scanner, the module shows WeChat the code you just scanned in place of the camera image for a few seconds, so WeChat reads it at once, without aiming again or choosing between several codes. WeChat's viewfinder keeps showing the real camera.

To enable it:

1. In LSPosed, enable FerryQR with WeChat in its scope.
2. Restart WeChat.
3. Open FerryQR; the scanner screen confirms that the module is ready.

With the module:

- Result cards and history offer Open in WeChat in place of Scan again in WeChat.
- WeChat mini program codes open directly in WeChat.
- The create page can open its text in WeChat.

The module only hooks Android camera APIs inside the WeChat process, leaves WeChat's own code untouched, and accepts requests from FerryQR alone. Without LSPosed, FerryQR works as described above.

## History

The history button at the top right lists the codes you have scanned, grouped by day. Tap one to copy, open or share it again. The clear button at the top of the history page removes every entry.

## Creating codes

The create button at the top right turns text into a code. When it opens, it fills in text from the clipboard and shows the code right away, and the code updates as you edit the text. Besides regular QR codes you can choose Aztec, Data Matrix or PDF417. If the text is too long for the chosen type, a message below the text field says so. You can copy the code to the clipboard or save it to Pictures/FerryQR. The text can also be opened directly in Alipay, and with the LSPosed module in WeChat as well.

## Tips

A code does not need to be in the middle of the picture. FerryQR reads it anywhere in view.

When a code is small or far away, FerryQR zooms in by itself. You can also pinch to zoom.

If a code still will not scan, tap the reset button to return to the default zoom and restart the camera.

In low light, use the flashlight button.

You can pick an image from your gallery to scan in FerryQR, share an image to FerryQR from another app, or open an image with FerryQR. A shared or opened image does not start the camera; its result pops up as a card, and when the image holds several codes you choose one at the top of the card.

You can add FerryQR as a Quick Settings tile to open it quickly.

## Privacy

FerryQR only asks for camera access and has no internet access. It never sends the content of a code to anyone other than the app you open it in. Without camera access, you can still scan codes in gallery images.

History stays on your phone. The create page reads the clipboard once, when it opens. The LSPosed module only talks to WeChat on the phone itself.

## License

[MIT](LICENSE). FerryQR uses [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp), [ZXing](https://github.com/zxing/zxing) and [WeChatQRCode](https://github.com/jenly1314/WeChatQRCode), all licensed under Apache 2.0.
