package com.example.douyintv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 在线更新（GitHub Releases 渠道）
 * ==============================
 * 渠道设计：
 *   · 版本清单：仓库根目录 version.json（raw.githubusercontent.com 直读，无鉴权、无频率限制困扰）
 *   · APK 本体：GitHub Releases 的 assets（打 tag 发布时上传）
 *   · 客户端流程：拉清单 → 比对 versionCode → 有新版则弹窗（更新说明）→ 下载到
 *     filesDir/update.apk → 复用 FileProvider 唤起系统安装器（4.2 需手动确认，已知约束）
 *
 * 清单格式（version.json）：
 * {
 *   "versionCode": 116,
 *   "versionName": "2.16",
 *   "apkUrl": "https://github.com/<user>/<repo>/releases/download/v2.16/Dtv-2.16-release.apk",
 *   "notes": "1. xxx\n2. yyy",
 *   "force": false
 * }
 */
object UpdateChecker {

    private const val TAG = "Update"

    /** 版本清单地址（GitHub 仓库 Alone2235/DTV，raw 直读；国内可用 jsDelivr 镜像） */
    const val DEFAULT_MANIFEST = "https://raw.githubusercontent.com/Alone2235/DTV/main/version.json"
    /** jsDelivr 镜像（raw 被墙时备用，Prefs 可切换） */
    const val CDN_MANIFEST = "https://cdn.jsdelivr.net/gh/Alone2235/DTV@main/version.json"

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
        val force: Boolean
    )

    /** 拉取版本清单；失败返回 null（静默失败，不打扰用户） */
    fun fetchManifest(ctx: Context): Info? {
        val url = Prefs.getUpdateUrl().ifBlank { DEFAULT_MANIFEST }
        if (url.contains("CHANGE_ME")) return null      // 还没配置仓库 → 视为没有更新渠道
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "DTV-Updater")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val o = JSONObject(text)
            Info(
                o.optInt("versionCode", 0),
                o.optString("versionName", ""),
                o.optString("apkUrl", ""),
                o.optString("notes", ""),
                o.optBoolean("force", false)
            )
        } catch (e: Exception) {
            Log.w(TAG, "清单获取失败: ${e.message}")
            null
        }
    }

    /** 与当前安装版本比对；有新版返回 Info，否则 null */
    fun check(ctx: Context): Info? {
        val info = fetchManifest(ctx) ?: return null
        val cur = try { BuildConfig.VERSION_CODE } catch (_: Throwable) { 0 }
        return if (info.versionCode > cur && info.apkUrl.isNotBlank()) info else null
    }

    /**
     * 下载 APK 到 filesDir/update.apk（覆盖旧文件）。
     * onProgress(0..100)；成功返回文件，失败返回 null。
     */
    fun download(ctx: Context, apkUrl: String, onProgress: (Int) -> Unit): File? {
        val target = File(ctx.filesDir, "update.apk")
        return try {
            val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DTV-Updater")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    var got = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (total > 0) onProgress((got * 100 / total).toInt())
                    }
                }
            }
            conn.disconnect()
            onProgress(100)
            target
        } catch (e: Exception) {
            Log.e(TAG, "下载失败: ${e.message}")
            null
        }
    }

    /** 唤起系统安装器（走 FileProvider，targetSdk 26 不允许裸 file://） */
    fun install(ctx: Context): Boolean {
        return try {
            val f = File(ctx.filesDir, "update.apk")
            if (!f.exists()) return false
            val uri: Uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, ctx.packageName + ".fileprovider", f)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            Log.e(TAG, "唤起安装器失败: ${e.message}")
            false
        }
    }
}
