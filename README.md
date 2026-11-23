# FerryQR

A privacy-first QR scanner for Android that ferries each code to the right app. Where most scanners stop at the text of a code, FerryQR hands the code to the app or system feature that can act on it.

[中文说明](README_zh.md)

## Features

- **Opens codes in their own apps:** Links go to the app that handles them; Wi-Fi, contacts, events, passkeys, eSIMs, phone numbers, messages, email and map locations go to the matching part of the phone. FerryQR also reads some specific codes and shows their content, so far the information in boarding pass codes.
- **Strong scanning:** The advanced scanning found in Alipay and WeChat, such as automatic zoom and picking one of several codes, is here too. Images on the phone can be scanned by sharing them to FerryQR or opening them with it, and a screenshot shared to FerryQR quickly reads a code on the screen.
- **One scanner in place of many:** Codes meant for a particular app, WeChat mini program codes included, open straight in that app after a scan in FerryQR, so its own scanner is no longer needed. Some apps need the [LSPosed module](#lsposed-module).
- **History and favorites:** Scanned codes are kept on the phone as history, and entries can be added to favorites. Favorites stay at the top of the history and can be renamed and reordered; up to four can be added to the shortcuts shown when the FerryQR icon is long pressed.
- **Shows codes:** Tapping an entry in the history draws its code again, to be scanned by another phone or saved as an image. The code can be switched to another format to suit the reader, enlarged, and shown at full brightness, so it scans more reliably.
- **Creates codes and barcodes:** FerryQR draws every format ZXing writes and suggests the one that suits the text best. Codes are ready to share, copy or save once created.
- **Quick Settings tile:** Once added to Quick Settings, it starts scanning from the notification shade.

## Install

FerryQR needs Android 11 or later on a 64-bit ARM phone. Download the APK from [Releases](../../releases).

## LSPosed module

Some codes lead to a feature or page inside an app only when that app's own scanner reads them. With FerryQR enabled as an LSPosed module, FerryQR ferries their content straight into those apps, so one scanner does what is otherwise spread across them. Without the module, FerryQR can at most open such an app's scanner for the code to be scanned again; opening the code in the browser first, for the browser to pass it on to the app, is also worth a try. DiDi, Meituan and WeChat are supported so far. Alipay, Mi Home and Xiaomi Account have entries other apps can open, so they work without the module.

1. In LSPosed, enable FerryQR and tick DiDi, Meituan and WeChat in its scope.
2. Restart those apps.
3. Open FerryQR and check in the settings, at the top left of the scanner, that the module is active for each app.

## Privacy

FerryQR has no internet access: what it scans, keeps and creates is processed and stored on the phone only, and the settings can keep codes that may hold sensitive information out of history. FerryQR asks for the permissions below for these features.

- **Camera:** Scanning with the camera. Without it, images can still be scanned, which needs no permission at all: they can be picked in FerryQR, shared to it or opened with it.
- **App list:** Finding which installed apps open a code, so result cards can offer "Open in" an app and the ferry page can show each app's status. Without it, FerryQR cannot tell which apps are installed, and those ways to open a code may not appear.
- **Wi-Fi state:** Telling whether the phone supports Wi-Fi Easy Connect. Android grants it at install without asking; on systems that can turn it off, Wi-Fi Easy Connect codes cannot be handed over without it.
- **Clipboard:** The create page reads the clipboard once, as it opens, and fills in its text automatically. Without it, the text has to be pasted by hand.

## License

[MIT](LICENSE). FerryQR uses [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp), [ZXing](https://github.com/zxing/zxing) and [WeChatQRCode](https://github.com/jenly1314/WeChatQRCode), all licensed under Apache 2.0.
