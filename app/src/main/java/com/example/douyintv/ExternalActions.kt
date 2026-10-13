package com.example.douyintv

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * 外部唤醒动作（HTTP /cmd 与 WebSocket 8090 共用）
 *
 * 背景：以前「检查更新 / 设置页 / 退出」等功能只能在 App 内点击，
 * 外部（手机遥控、脚本、自动化）无法触发。这里把它们抽成统一入口，
 * 两条通道语义一致，不会出现「HTTP 能调、WS 调不了」的半残状态。
 *
 * 支持的 action：
 *   checkupdate  检查更新（有新版 → 前台弹窗，可点立即升级）
 *   upgrade      直接下载新版并调起安装器
 *   settings     打开设置页
 *   about        打开关于页
 *   home         回到首页
 *   exit         退出当前页面
 *   reload       重载当前视频
 */
object ExternalActions {

    private const val TAG = "ExternalActions"

    fun handle(ctx: Context, action: String, arg: String = "") {
        when (action) {
            "checkupdate" -> update(ctx, interactive = true)
            "upgrade" -> update(ctx, interactive = false)
            "settings" -> open(ctx, SettingsActivity::class.java)
            "about" -> open(ctx, AboutActivity::class.java)
            "home" -> open(ctx, MainActivity::class.java)
            "exit" -> {
                val a = App.currentActivity() ?: return
                a.runOnUiThread { try { a.finish() } catch (_: Throwable) {} }
            }
            "reload" -> PlayerBus.send("reload", arg)
        }
    }

    /** 打开指定页面 */
    private fun open(ctx: Context, cls: Class<*>) {
        try {
            val i = Intent(ctx, cls)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ctx.startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "打开页面失败: ${e.message}")
        }
    }

    private fun toast(ctx: Context, msg: String) {
        val a = App.currentActivity() ?: return
        a.runOnUiThread {
            try { Toast.makeText(a, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
        }
    }

    /**
     * 检查/执行更新。
     * interactive=true → 有新版弹窗确认；false → 直接下载安装。
     * 没有前台界面时退化为「直接下载安装」（否则外部调用会静默无反应）。
     */
    private fun update(ctx: Context, interactive: Boolean) {
        Thread {
            val info = try { UpdateChecker.check(ctx) } catch (_: Throwable) { null }
            if (info == null) {
                if (interactive) toast(ctx, "已是最新版本")
                return@Thread
            }
            val act = App.currentActivity()
            if (!interactive || act == null) {
                val f = UpdateChecker.download(ctx, info.apkUrl) { }
                if (f != null) UpdateChecker.install(ctx) else toast(ctx, "更新下载失败")
                return@Thread
            }
            act.runOnUiThread {
                try {
                    android.app.AlertDialog.Builder(act)
                        .setTitle("发现新版本 ${info.versionName}")
                        .setMessage(info.notes.ifBlank { "（无更新说明）" })
                        .setPositiveButton("立即升级") { _, _ ->
                            toast(ctx, "开始下载更新…")
                            Thread {
                                val f = UpdateChecker.download(ctx, info.apkUrl) { }
                                if (f != null) UpdateChecker.install(ctx) else toast(ctx, "更新下载失败")
                            }.start()
                        }
                        .setNegativeButton("稍后", null)
                        .show()
                } catch (_: Throwable) {}
            }
        }.start()
    }
}
