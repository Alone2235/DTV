package com.example.dtvremote

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Application：全局崩溃捕获。
 *
 * 为什么必须加：遥控端在真机上"还没启动就闪退"，而电视/手机都没法接 logcat。
 * 这里把崩溃堆栈写进 filesDir/last_crash.txt，下次启动由 MainActivity 弹窗显示，
 * 用户截个图就能定位（和 DTV 电视端同一套做法）。
 *
 * 另外做「ViewPager2 失败自愈」：若崩溃堆栈里出现 pager/recyclerview 相关帧，
 * 直接把 vp_disabled 标记落盘 —— 下次启动跳过 ViewPager2，降级到可用的翻页实现，
 * 保证 App 一定能起来。
 */
class RemoteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = "thread=${t.name}\n\n$sw"
                File(filesDir, CRASH_FILE).writeText(text)
                // ★ 失败自愈：疑似翻页控件导致 → 下次禁用
                val lower = text.lowercase()
                if (lower.contains("viewpager") || lower.contains("pager") || lower.contains("recyclerview")) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean("vp_disabled", true).apply()
                }
            } catch (_: Throwable) {
                // 崩溃处理器自身不能再崩
            }
            def?.uncaughtException(t, e)
        }
    }

    companion object {
        private const val CRASH_FILE = "last_crash.txt"
        private const val PREFS = "dtv_remote"

        fun lastCrash(ctx: Context): String? = try {
            val f = File(ctx.filesDir, CRASH_FILE)
            if (f.exists()) f.readText() else null
        } catch (_: Throwable) { null }

        fun clearCrash(ctx: Context) {
            try { File(ctx.filesDir, CRASH_FILE).delete() } catch (_: Throwable) {}
        }
    }
}
