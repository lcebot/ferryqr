# OpenCV and the WeChat detector bind Java classes from native code by name.
-keep class org.opencv.** { *; }
-keep class com.king.wechat.qrcode.** { *; }
-dontwarn org.opencv.**

# The optional LSPosed entry, named in META-INF/xposed/java_init.list and created reflectively by
# the framework, so it keeps its name and its public no-arg constructor.
-keep class io.github.lcebot.ferryqr.hook.HookEntry { *; }
-adaptresourcefilecontents META-INF/xposed/java_init.list
-dontwarn io.github.libxposed.annotation.**
