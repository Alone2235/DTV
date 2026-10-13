package com.example.douyintv

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.multidex.MultiDexApplication
import android.util.Log
import org.conscrypt.Conscrypt
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.security.Security
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Application 启动:
 *   1. 安装 Conscrypt —— 解决 Android 4.x 的 TLS 缺口（抖音握手失败的真凶）
 *   2. 全局崩溃捕获 —— 电视没法接 logcat，崩溃堆栈留盘、下次启动显示
 *
 * ★ 继承 MultiDexApplication（而非裸 Application）
 *   本轮引入了 RecyclerView，dex 方法数会越过 65536 的单 dex 上限。
 *   API 17 属于「Dalvik 需要显式装载副 dex」的年代 —— MultiDexApplication
 *   会自动在 attachBaseContext 里完成安装，比手写 MultiDex.install(this)
 *   更不容易漏，也不会因为忘了调用而在低版本上启动即崩。
 */
class App : MultiDexApplication() {

    /** 活跃播放器实例 —— 低内存时通知它缩缓冲（B 站动态缓冲的 App 层等价物） */
    @Volatile
    var activePlayer: Any? = null

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            val p = activePlayer
            when {
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                    android.util.Log.w("App", "内存严重不足($level) → 清全部图片缓存 + 播放器降载")
                    ImageLoader.clear()
                    if (p is com.example.douyintv.PlayerMemoryAware) { try { p.onLowMemory() } catch (_: Throwable) {} }
                    System.gc()
                }
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                    android.util.Log.w("App", "低内存($level) → 清图片缓存 + 播放器降载")
                    ImageLoader.clear()
                    if (p is com.example.douyintv.PlayerMemoryAware) { try { p.onLowMemory() } catch (_: Throwable) {} }
                }
                level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                    android.util.Log.w("App", "UI 隐藏($level) → 清图片缓存")
                    ImageLoader.clear()
                }
            }
        } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "App"
        private const val CRASH_FILE = "last_crash.txt"

        /**
         * 官方签名指纹（SHA-256 大写十六进制，无冒号）。
         * 防篡改：运行时对安装包的签名做 SHA-256 与之比对，
         * 第三方重打包（apktool 改 + 自建 keystore 重签）必然不同指纹。
         * ★ 换签名 keystore 发布前必须重新计算并回填此值 ★
         *   （keytool -list -v -keystore xx.jks 的 SHA256 去掉冒号）
         */
        private const val OFFICIAL_SIG_HASH = "PENDING-REPLACE"		 

        /** 本机安装包的签名指纹（SHA-256 hex，大写）。null=尚未校验 */
        @Volatile
        var sigFingerprint: String? = null
            private set

        /** 是否与官方签名一致；null=未校验 */
        @Volatile
        var sigIsOfficial: Boolean? = null
            private set

        /**
         * 启动时签名自校验（防第三方重打包，配合 About 页指纹核对）。
         * 软校验：只记录结果并在 About 页警示，不阻断使用 ——
         * 指纹机制未稳定前绝不误杀正常安装。
         */
        fun checkSignature(ctx: Context) {
            try {
                val h = signatureHash(ctx) ?: return
                sigFingerprint = h
                sigIsOfficial = h.equals(OFFICIAL_SIG_HASH, ignoreCase = true)
                Log.i(TAG, "✓ 签名指纹=${h.take(16)}… 官方一致=${sigIsOfficial}")
            } catch (t: Throwable) {
                Log.w(TAG, "签名校验失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        private fun signatureHash(ctx: Context): String? = try {
            val pm = ctx.packageManager
            @Suppress("DEPRECATION")   // API 17 只能用 GET_SIGNATURES
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            if (info.signatures == null || info.signatures.isEmpty()) null
            else {
                val md = MessageDigest.getInstance("SHA-256")
                md.digest(info.signatures[0].toByteArray())
                    .joinToString("") { String.format("%02X", it) }
            }
        } catch (t: Throwable) {
            null
        }

        /** About 页用的可展示指纹（32 字符一行） */
        fun sigInfo(): String {
            val h = sigFingerprint ?: return "未校验（启动时自动计算）"
            return h.chunked(32).joinToString("\n")
        }

        /**
         * 应用级 Context。
         * 供「没有 Context 参数的解析层」读取配置用（如 DouyinApi.parseFeed
         * 需要知道横屏过滤开关开没开）。只持有 applicationContext，不会泄漏 Activity。
         */
        @Volatile
        private var appCtx: Context? = null

        /** 应用级 Context 的公开入口（设备能力检测等无参调用方使用） */
        fun appContext(): Context? = appCtx

        // ---------------- 手机遥控 → 电视界面按键注入 ----------------
        // 之前遥控端只能操控播放（上下条/暂停/点赞/倍速…），完全发不了方向键，
        // 等于操控不了电视界面；这里跟踪前台 Activity 并注入 KeyEvent 补齐。

        @Volatile
        private var actRef: java.lang.ref.WeakReference<android.app.Activity>? = null

        @Volatile
        private var tracking = false

        /** 懒注册 Activity 生命周期跟踪（首次发按键时注册，避免改 onCreate 调用顺序） */
        private fun ensureTracking() {
            if (tracking) return
            val app = appCtx as? Application ?: return
            tracking = true
            try {
                app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(a: android.app.Activity) {
                        actRef = java.lang.ref.WeakReference(a)
                    }
                    override fun onActivityPaused(a: android.app.Activity) {
                        if (actRef?.get() === a) actRef = null
                    }
                    override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
                    override fun onActivityStarted(a: android.app.Activity) {}
                    override fun onActivityStopped(a: android.app.Activity) {}
                    override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
                    override fun onActivityDestroyed(a: android.app.Activity) {}
                })
            } catch (_: Throwable) {}
        }

        /**
         * 向前台 Activity 注入一次按键（DOWN + UP）。
         * 支持方向键 / OK / 返回 / 菜单 —— 手机遥控因此能操作电视界面，而不只是播放。
         */
        fun sendKey(keyCode: Int): Boolean {
            ensureTracking()
            val a = actRef?.get() ?: return false
            return try {
                a.runOnUiThread {
                    try {
                        val t = android.os.SystemClock.uptimeMillis()
                        a.dispatchKeyEvent(android.view.KeyEvent(
                            t, t, android.view.KeyEvent.ACTION_DOWN, keyCode, 0))
                        a.dispatchKeyEvent(android.view.KeyEvent(
                            t, t, android.view.KeyEvent.ACTION_UP, keyCode, 0))
                    } catch (_: Throwable) {}
                }
                true
            } catch (_: Throwable) {
                false
            }
        }

        /** 遥控端用：当前是否有前台界面可接收按键 */
        fun hasForeground(): Boolean {
            ensureTracking()
            return actRef?.get() != null
        }

        /** 横屏过滤开关 —— 解析层调用入口 */
        fun landscapeOnly(): Boolean {
            val c = appCtx ?: return false
            return try {
                Prefs.isLandscapeOnly(c)
            } catch (e: Exception) {
                false
            }
        }

        /** TLS 后端描述（诊断面板显示） */
        @Volatile
        var tlsBackend: String = "未初始化"
            private set

        fun readCrash(ctx: Context): String? = try {
            val f = File(ctx.filesDir, CRASH_FILE)
            if (f.exists()) f.readText() else null
        } catch (e: Exception) {
            null
        }

        fun clearCrash(ctx: Context) {
            try { File(ctx.filesDir, CRASH_FILE).delete() } catch (_: Exception) {}
        }

        /**
         * 真·握手探测: 实际连一次抖音 443，看谈成什么协议/cipher
         * 这是判断"到底是 TLS 挂了还是 Cookie 挂了"的唯一硬证据。
         */
        fun tlsProbe(): String = try {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, null, null)
            val sock = ctx.socketFactory.createSocket("www.douyin.com", 443) as SSLSocket
            sock.soTimeout = 10000
            sock.startHandshake()
            val r = "${sock.session.protocol} / ${sock.session.cipherSuite}"
            try { sock.close() } catch (_: Exception) {}
            r
        } catch (t: Throwable) {
            "失败: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    override fun onCreate() {
        super.onCreate()
        appCtx = applicationContext
        DeviceProfile.compute(applicationContext)   // 机型能力检测（软解/硬解、档位、阈值分流）
        Prefs.ensureInteractCreds(applicationContext)   // ★ 点赞/收藏凭据：本地生成 webid/msToken/uid（首次）
        installConscrypt()
        installGlobalTls()
        installCrashHandler()
        checkSignature(appCtx!!)
        // ★ 遥控/配置服务常驻（手机客户端 + Cookie 推送都需要它一直在线）
        ConfigServer.startApp(applicationContext)
        // ★ 手机客户端底层：UDP 发现 + WebSocket 控制通道
        try { DiscoveryServer(applicationContext).start() } catch (t: Throwable) { Log.e(TAG, "发现服务启动失败: ${t.message}") }
        try { WSServer(applicationContext).start() } catch (t: Throwable) { Log.e(TAG, "WS 通道启动失败: ${t.message}") }
    }

    /**
     * 让 java.net.HttpsURLConnection 也走「宽松 TLS」。
     *
     * 为什么必须做：
     *   我们的 TLS 修复（Tls12SocketFactory + 证书降级）原本只在 OkHttp 层生效，
     *   而 ExoPlayer 的 DefaultHttpDataSource 直接 new HttpURLConnection ——
     *   在 Android 4.2 电视上它的 CA 库停在出厂年份 → 握手失败 →
     *   ERROR_CODE_IO_NETWORK_CONNECTION_FAILED。
     *   这就是「换了 ExoPlayer 就播不了、只有系统播放器能用」的根因。
     *
     *   官方 extension-okhttp 走不通：它依赖 okhttp 4.x，minSdk 21，
     *   本项目 minSdk 16（manifest merger 直接拒绝）。所以从全局连接层下手。
     *
     * 代价：全局放宽证书校验。本应用只访问抖音，权衡可接受。
     */
    private fun installGlobalTls() {
        try {
            val tm = RelaxedTrustManager()
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(tm), java.security.SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(
                Tls12SocketFactory(ctx.socketFactory as javax.net.ssl.SSLSocketFactory)
            )
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
            Log.i(TAG, "✓ 全局宽松 TLS 已装到 HttpsURLConnection")
        } catch (t: Throwable) {
            Log.e(TAG, "✗ 全局 TLS 安装失败", t)
        }
    }

    /** 把 Conscrypt 提到 provider 首位 → 之后所有 SSLContext 都走它 */
    private fun installConscrypt() {
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
            tlsBackend = "Conscrypt (provider #1)"
            Log.i(TAG, "✓ Conscrypt 已安装, TLS 后端=$tlsBackend")
        } catch (t: Throwable) {
            // 已存在或该 ROM 拒绝，退回系统 TLS
            tlsBackend = "系统 TLS (Conscrypt 未启用: ${t.javaClass.simpleName})"
            Log.e(TAG, "✗ Conscrypt 安装失败", t)
        }
    }

    private fun installCrashHandler() {
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(filesDir, CRASH_FILE).writeText(
                    "sdk=${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})\n" +
                    "model=${Build.MODEL} / ${Build.MANUFACTURER}\n" +
                    "thread=${t.name}\ntls=$tlsBackend\n\n" + sw
                )
            } catch (_: Throwable) {
                // 崩溃处理器自身不能再崩
            }
            def?.uncaughtException(t, e)
        }
    }
}
