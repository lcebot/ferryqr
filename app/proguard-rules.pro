# OpenCV and the WeChat detector bind Java classes from native code by name.
-keep class org.opencv.** { *; }
-keep class com.king.wechat.qrcode.** { *; }
-dontwarn org.opencv.**
