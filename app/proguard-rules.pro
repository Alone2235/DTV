# WebView
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keepattributes JavascriptInterface, *Annotation*
-keep class android.webkit.** { *; }

# OkHttp
-dontwarn okhttp3.**, okio.**, javax.annotation.**, org.conscrypt.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# 本项目
-keep class com.example.douyintv.** { *; }

# --- 引擎1: ExoPlayer 2.18.1 ---
-dontwarn com.google.android.exoplayer2.**
-keep class com.google.android.exoplayer2.** { *; }
-keep interface com.google.android.exoplayer2.** { *; }
-dontwarn com.google.common.**
-keep class com.google.common.** { *; }

# --- 引擎2: IJKPlayer (native 库需保留类/方法名) ---
-keep class tv.danmaku.ijk.media.player.** { *; }
-keep class tv.danmaku.ijk.media.player.IjkMediaPlayer { *; }
-keep class tv.danmaku.ijk.media.player.ffmpeg.** { *; }
-dontwarn tv.danmaku.ijk.media.**

# --- 引擎3: 系统 MediaPlayer 无需规则 ---

# --- NanoHTTPD (局域网配置服务) ---
-dontwarn fi.iki.elonen.**
-keep class fi.iki.elonen.** { *; }
-keep class com.example.douyintv.ConfigServer { *; }

# --- ZXing (二维码) ---
-dontwarn com.google.zxing.**
-keep class com.google.zxing.** { *; }

# --- Conscrypt (老 Android 的现代 TLS 栈) ---
-dontwarn org.conscrypt.**
-keep class org.conscrypt.** { *; }
-keep class com.android.org.conscrypt.** { *; }

# Kotlin
-dontwarn kotlin.**
