# FerryQR

A privacy-first QR scanner for Android that ferries each code to the right app. Where most scanners stop at the text of a code, FerryQR hands the code to the app or system feature that can act on it.

[中文说明](https://github.com/lcebot/ferryqr/blob/main/README_zh.md)

## Features

- **Opens codes in their own apps.** Links go to the app that handles them; Wi-Fi, contacts, events, passkeys, eSIMs, phone numbers, messages, email and map locations go to the matching part of the phone.
- **Strong scanning.** The advanced scanning found in Alipay and WeChat, such as automatic zoom and picking one of several codes, is here too. Images on the phone can be scanned by sharing them to FerryQR or opening them with it.
- **One scanner in place of many.** Codes meant for a particular app, WeChat mini program codes included, open straight in that app after a scan in FerryQR, so its own scanner is no longer needed. Some apps need the [LSPosed module](#lsposed-module).
- **History and favorites.** Scanned codes are kept on the phone as history, and entries can be added to favorites. Favorites stay at the top of the history and can be renamed; up to four can be added to the shortcuts shown when the FerryQR icon is long pressed. Tapping an entry draws its code again, to be scanned by another phone or saved as an image.
- **Creates codes.** QR, Aztec, Data Matrix and PDF417 codes, ready to share, copy or save once created.
- **Quick Settings tile.** Once added to Quick Settings, it starts scanning from the notification shade.

## Install

FerryQR needs Android 11 or later on a 64-bit ARM phone. Download the APK from [Releases](https://github.com/lcebot/ferryqr/releases).

## LSPosed module

Some codes lead to a feature or page inside an app only when that app's own scanner reads them. With FerryQR enabled as an LSPosed module, FerryQR ferries their content straight into those apps, so one scanner does what is otherwise spread across them. Without the module, FerryQR can at most open such an app's scanner for the code to be scanned again; opening the code in the browser first, for the browser to pass it on to the app, is also worth a try. WeChat, DiDi and Meituan are supported so far. Alipay takes scan results from other apps through its official entry, so it works without the module.

1. In LSPosed, enable FerryQR and tick WeChat, DiDi and Meituan in its scope.
2. Restart those apps.
3. Open FerryQR and check in the settings, at the top left of the scanner, that the module is active for each app.

## Privacy

FerryQR asks only for the camera and has no internet access. A code's content is passed only to the app it is opened in. History stays on the phone, and the create page reads the clipboard only once, as it opens. Without camera access, images can still be scanned.

## License

[MIT](https://github.com/lcebot/ferryqr/blob/main/LICENSE). FerryQR uses [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp), [ZXing](https://github.com/zxing/zxing) and [WeChatQRCode](https://github.com/jenly1314/WeChatQRCode), all licensed under Apache 2.0.
