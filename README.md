# FerryQR

FerryQR is a QR code scanner for Android. Point the camera at a code and FerryQR opens it in the matching app. If no app fits, it shows the content of the code, which you can copy, search or share.

[中文说明](README_zh.md)

## Install

FerryQR needs Android 11 or later on a 64-bit ARM (arm64-v8a) phone. Download the APK from the [Releases](../../releases) page and install it. The optional [LSPosed module](#lsposed-module) is enabled separately.

## Scan results

> When there are several codes, you can pick the one to open.

**Links:** If an app on your phone can open the link, the link opens in that app. Otherwise the address is shown and you can open it in your browser.

**Alipay codes:** Open directly in Alipay.

**WeChat codes:** WeChat does not accept scan results from other apps, so when the camera finds a WeChat code, FerryQR can only take you to the WeChat scanner, where you scan the code again; for a code in an image, the result card tells you to choose the image from the album in the WeChat scanner. With the [LSPosed module](#lsposed-module), the code opens directly in WeChat without another scan.

**WeChat mini program codes:** FerryQR reads the round mini program codes with three eye markers. Mini programs only open in WeChat: with the LSPosed module the code opens directly in WeChat; without it, the result card shows the code drawn again from its content: scan a code the camera found again in the WeChat scanner, and for a code in an image, choose the image from the scanner's album. The few mini program codes that cannot be read are still recognised by their shape, and a result card lets you decide whether to open them in WeChat; nothing is sent until you tap it.

**Wi-Fi:** Shows the network name and password. Most networks can be connected to directly.

**Wi-Fi Easy Connect:** Opens the system Wi-Fi Easy Connect setup when the phone supports it.

**Contacts and events:** Can be saved to your contacts or calendar.

**Passkeys:** Opens your installed password manager so you can use a saved passkey.

**eSIM activation codes:** Opens the eSIM setup on your phone. If your phone does not accept this, copy the activation code and add the eSIM from the SIM settings.

**Phone numbers, text messages, email addresses, map locations and app links:** Open in the matching app.

**Anything else:** Shown as text.

## LSPosed module

FerryQR is also an optional LSPosed module for WeChat, DiDi and Meituan, whose codes only open properly from their own scanners. After FerryQR takes you to one of these scanners, the module has the app open the code you just scanned at once, without aiming again or choosing between several codes.

To enable it:

1. In LSPosed, enable FerryQR with WeChat, DiDi and Meituan in its scope.
2. Restart those apps.
3. Open FerryQR; the scanner screen confirms that the module is ready.

With the module:

- Result cards offer Open in WeChat in place of Scan again in WeChat and Open WeChat scanner.
- WeChat codes and mini program codes in history open directly in WeChat; without the module, they cannot be opened in WeChat from history.
- WeChat mini program codes open directly in WeChat.
- The create page can open its text in WeChat.
- DiDi bike and short link codes, and Meituan bike, shop and grocery codes, open directly in those apps, and their result cards offer Open in DiDi or Open in Meituan.

Each app is handled the way its version changes affect least:

- In WeChat, the module hooks Android camera APIs and the result class of WeChat's QR code library, whose name stays the same across WeChat versions. For a moment it shows the scanner a small placeholder code in place of the camera image, and the result read from it carries the content you scanned. A mini program code FerryQR read is returned the same way, as a mini program result; one it could not read is shown to the scanner as a picture of the code. WeChat's viewfinder keeps showing the real camera.
- In DiDi, the module only hooks Android camera APIs, and the scanner reads a code drawn from the content you scanned in place of the camera image.
- In Meituan, the module closes Meituan's camera screen as soon as it opens and returns the content you scanned, as if the camera had read it.

Settings, at the top left of the scanner, show for each app whether the module is enabled and loaded, as LSPosed reports it, so the apps need not be running. When FerryQR is updated, apps that are already running usually take the new version without a restart; the settings say so when one is still needed. WeChat can also be switched to Camera, which shows every code to its camera, as for DiDi, in case a WeChat update stops the result from being replaced.

The module accepts requests from FerryQR alone. Without LSPosed, FerryQR works as described above.

## History

The history button at the top right lists the codes you have scanned, grouped by day. Tap one to open, copy or share it again; the code is drawn again too, and tapping it enlarges it for someone else to scan, while holding it shares, copies or saves it as an image. Swipe an entry left to delete it, with an option to undo, or right to add it to or remove it from favorites. The clear button at the top of the history page removes every entry except favorites.

Codes you use often can be kept as favorites: tap the star on a result card or on the create page, or swipe right or long press an entry in history. Favorites stay at the top of the history page, can be renamed, and fold away when there are more than 4. From the long press menu, up to 4 favorites can also be added to the app shortcuts, which open when you long press the FerryQR icon.

## Creating codes

The create button at the top right turns text into a code. When it opens, it fills in text from the clipboard and shows the code right away, and the code updates as you edit the text. Besides regular QR codes you can choose Aztec, Data Matrix or PDF417. If the text is too long for the chosen type, a message below the text field says so. You can share the code as an image to any app, copy it to the clipboard, save it to Pictures/FerryQR, or add it to favorites. The text can also be opened directly in Alipay, and with the LSPosed module in WeChat as well; when it is a DiDi or Meituan code, the page offers that app too.

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
