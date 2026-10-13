package com.example.douyintv

import android.content.Context
import android.content.Intent
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 局域网 Cookie 配置服务
 * 电视上跑一个 HTTP 服务，手机/电脑浏览器打开 → 粘贴 Cookie → 保存
 */
class ConfigServer(
    private val appCtx: Context,
    private val onSaved: (String) -> Unit
) : NanoHTTPD(PORT) {

    companion object {
        const val PORT = 8088
        private const val TAG = "ConfigServer"

        /** App 级常驻实例（App.onCreate 启动；LoginActivity 不再自建/自关） */
        @Volatile
        var appServer: ConfigServer? = null
            private set

        /** 启动常驻服务（幂等：已在跑就复用） */
        fun startApp(ctx: Context) {
            if (appServer != null) return
            synchronized(this) {
                if (appServer != null) return
                try {
                    val s = ConfigServer(ctx) { /* 常驻实例的 Cookie 保存回调为空：登录页自己刷新 */ }
                    s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                    appServer = s
                    Log.i(TAG, "✓ 遥控/配置服务常驻启动: http://${localIp()}:$PORT")
                } catch (e: Exception) {
                    Log.e(TAG, "服务启动失败: ${e.message}")
                }
            }
        }

        /** 本机局域网 IPv4 */
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }

        /** 分享链接/aweme_id → 详情 → FeedBus → 拉起播放页（WS 与 HTTP 命令共用） */
        fun pushVideoStatic(ctx: Context, arg: String) {
            val cookie = Prefs.getCookie(ctx)
            Thread {
                val item = try {
                    val id = if (arg.trim().matches(Regex("""\d{5,}"""))) arg.trim()
                    else {
                        val share = DouyinApi.extractShareUrl(arg)
                        if (share == null) null
                        else DouyinApi.extractAwemeId(DouyinApi.resolveShareUrl(share))
                            ?: DouyinApi.extractAwemeId(share)
                    }
                    if (id != null) DouyinApi.fetchVideoDetail(cookie, id) else null
                } catch (_: Exception) { null }
                if (item == null) return@Thread
                FeedBus.put(listOf(item), 0, FeedBus.SOURCE_SHARE)
                try {
                    val i = android.content.Intent(ctx, PlayerActivity::class.java)
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    ctx.startActivity(i)
                } catch (e: Exception) {
                    Log.e(TAG, "推送拉起播放页失败: ${e.message}")
                }
            }.start()
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        Log.d(TAG, "${session.method} $uri")
        return when {
            uri == "/" || uri.startsWith("/index") -> page()
            uri == "/save" -> save(session)
            uri == "/savecreds" -> saveCreds(session)
            // ★ 手机遥控客户端（配对 + 命令 + 列表 + 热搜 + 白名单 + APK 分发）
            uri == "/remote" -> remotePage()
            uri == "/pair" -> pair(session)
            uri == "/auth" -> auth(session)
            uri == "/clients" -> clients(session)
            uri == "/clients/del" -> clientsDel(session)
            uri == "/clients/clear" -> clientsClear(session)
            uri == "/apk/info" -> apkInfo(session)
            uri == "/apk/upload" -> apkUpload(session)
            uri == "/apk/install" -> apkInstall(session)
            uri == "/cmd" -> cmd(session)
            uri == "/lists" -> lists(session)
            uri == "/hot" -> hot(session)
            // ★ 手机输入关键词 → 电视执行搜索（电视上打中文很麻烦，手机输入舒服得多）
            uri == "/search" -> doSearch(session)
            uri == "/status" -> status(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
        }
    }

    // ---------------- 手机遥控：配对 / 命令 / 列表 / 热搜 ----------------

    // ---- 配对失败限速（防暴力破解） ----
    @Volatile
    private var pairFails = 0

    @Volatile
    private var pairLockedUntil = 0L

    /**
     * 配对：需 6 位码。
     *
     * ★ 安全修复：
     *   1. 原来响应里回显 `"code": 配对码` —— 局域网任何人调一次 /pair 就能拿到码，
     *      配对机制形同虚设。现在**绝不回显配对码**。
     *   2. 增加失败限速：连续 5 次失败 → 锁定 60 秒**并作废旧码重新生成**（防止 6 位码被爆破）。
     */
    private fun pair(session: IHTTPSession): Response {
        val now = System.currentTimeMillis()
        if (now < pairLockedUntil) {
            return newFixedLengthResponse(
                Response.Status.OK, "application/json; charset=utf-8",
                """{"ok":false,"error":"too_many_attempts","retryAfterMs":${pairLockedUntil - now}}""")
        }
        val p = session.parameters
        val code = (p["code"]?.firstOrNull() ?: "").trim()
        val ok = code.isNotBlank() && code == Prefs.getPairCode()
        if (ok) {
            pairFails = 0
            val cu = p["client_uuid"]?.firstOrNull() ?: ""
            val name = p["name"]?.firstOrNull() ?: "手机"
            if (cu.isNotBlank()) Prefs.addAllowedClient(appCtx, cu, name)
        } else {
            pairFails++
            if (pairFails >= 5) {
                pairFails = 0
                pairLockedUntil = now + 60_000
                try { Prefs.regeneratePairCode(appCtx) } catch (_: Throwable) {}   // 旧码作废
            }
        }
        return newFixedLengthResponse(
            Response.Status.OK, "application/json; charset=utf-8",
            """{"ok":$ok,"uuid":"${Prefs.getTvUuid()}"}""")
    }

    /** 握手鉴权：?uuid=<client_uuid> 在白名单即放行（WS 连接前先用它验证） */
    private fun auth(session: IHTTPSession): Response {
        val uuid = session.parameters["uuid"]?.firstOrNull() ?: ""
        return newFixedLengthResponse(
            Response.Status.OK, "application/json; charset=utf-8",
            """{"ok":${Prefs.isAllowed(uuid)},"uuid":"${Prefs.getTvUuid()}"}""")
    }

    /** 绑定手机列表（电视设置页用） */
    private fun clients(session: IHTTPSession): Response {
        if (!authed(session)) return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "未授权")
        val arr = org.json.JSONArray()
        val c = App.appContext() ?: return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", arr.toString())
        try {
            val raw = (Prefs.ctx(c).getString("allow_clients", "[]") ?: "[]")
            arr.put(org.json.JSONArray(raw))
        } catch (_: Throwable) {}
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", arr.toString())
    }

    private fun clientsDel(session: IHTTPSession): Response {
        if (!authed(session)) return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "未授权")
        val uuid = session.parameters["uuid"]?.firstOrNull() ?: ""
        Prefs.delAllowedClient(appCtx, uuid)
        return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "已删除")
    }

    private fun clientsClear(session: IHTTPSession): Response {
        if (!authed(session)) return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "未授权")
        Prefs.clearAllowedClients(appCtx)
        return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "已清空")
    }

    // ---------------- APK 分发（升级安装：分片上传 → 唤起系统安装器） ----------------
    private fun apkInfo(session: IHTTPSession): Response {
        if (!authed(session)) return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "未授权")
        val version = try { BuildConfig.VERSION_NAME } catch (_: Throwable) { "2.x" }
        val src = File(appCtx.applicationInfo.sourceDir)
        val size = if (src.exists()) src.length() else 0L
        val space = try {
            val sf = android.os.StatFs(appCtx.filesDir.path)
            sf.availableBytes
        } catch (_: Throwable) { 0L }
        return newFixedLengthResponse(
            Response.Status.OK, "application/json; charset=utf-8",
            """{"version":"$version","size":$size,"space":$space}""")
    }

    /** 分片上传：?name=update.apk&offset=<已有字节数>，body 为这一片二进制 */
    private fun apkUpload(session: IHTTPSession): Response {
        if (!authed(session)) return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "未授权")
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val offset = session.parameters["offset"]?.firstOrNull()?.toLongOrNull() ?: 0L
            val target = File(appCtx.filesDir, "update.apk")
            // ★ NanoHTTPD 的 postData：小 body 存成字符串，大 body 存成临时文件路径。
            //   两者都要处理，否则二进制分片（>几十KB）会变成「把内容当路径」→ 上传失败。
            val pd = files["postData"] ?: ""
            val chunk = if (pd.isBlank()) null
            else {
                val f = File(pd)
                if (f.exists() && f.isFile) f
                else {
                    val tmp = File(appCtx.filesDir, "chunk.tmp")
                    tmp.writeBytes(pd.toByteArray(Charsets.ISO_8859_1))
                    tmp
                }
            }
            if (chunk == null || chunk.length() == 0L) {
                newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "空分片")
            } else {
                val raf = java.io.RandomAccessFile(target, "rw")
                raf.seek(offset)
                val buf = ByteArray(64 * 1024)
                val fin = chunk.inputStream()
                var n: Int
                while (fin.read(buf).also { n = it } > 0) raf.write(buf, 0, n)
                fin.close()
                raf.close()
                chunk.delete()
                newFixedLengthResponse(
                    Response.Status.OK, "application/json; charset=utf-8",
                    """{"received":${target.length()}}""")
            }
        } catch (e: Exception) {
            Log.e(TAG, "APK 分片失败: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "失败: ${e.message}")
        }
    }

    /** 唤起系统安装器（Android 4.2 无 root → 用户手动点确认） */
    private fun apkInstall(session: IHTTPSession): Response {
        if (!authed(session)) return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "未授权")
        return try {
            val target = File(appCtx.filesDir, "update.apk")
            if (!target.exists()) return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "没找到安装包")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                appCtx, appCtx.packageName + ".fileprovider", target)
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            appCtx.startActivity(i)
            newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "已唤起安装器，请在电视上点确认")
        } catch (e: Exception) {
            Log.e(TAG, "安装器唤起失败: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "失败: ${e.message}")
        }
    }

    /** 校验：?code= 配对码 或 ?uuid= 白名单，二选一 */
    private fun authed(session: IHTTPSession): Boolean {
        val p = session.parameters
        val code = p["code"]?.firstOrNull() ?: ""
        val uuid = p["uuid"]?.firstOrNull() ?: ""
        return (code.isNotBlank() && code == Prefs.getPairCode()) || Prefs.isAllowed(uuid)
    }

    /** 遥控命令入口：?code=配对码 或 ?uuid=白名单 均可 */
    private fun cmd(session: IHTTPSession): Response {
        val p = session.parameters
        if (!authed(session)) {
            return newFixedLengthResponse(
                Response.Status.OK, "text/plain; charset=utf-8", "未授权（配对码错误或不在白名单）")
        }
        val action = p["action"]?.firstOrNull() ?: ""
        val arg = p["arg"]?.firstOrNull() ?: ""
        return try {
            when (action) {
                "next", "prev", "digg", "collect", "toggle", "pause", "play",
                "line", "engine", "danmaku", "loop", "landscape", "seek" -> {
                    PlayerBus.send(action, arg)
                    newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "ok")
                }
                // ★ 界面按键注入（与 WSServer 同语义）：方向键 / OK / 返回 / 菜单
                "dpad_up" -> keyResp(android.view.KeyEvent.KEYCODE_DPAD_UP)
                "dpad_down" -> keyResp(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                "dpad_left" -> keyResp(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
                "dpad_right" -> keyResp(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                "ok" -> keyResp(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                "back" -> keyResp(android.view.KeyEvent.KEYCODE_BACK)
                "menu" -> keyResp(android.view.KeyEvent.KEYCODE_MENU)
                "key" -> {
                    arg.trim().toIntOrNull()?.let { App.sendKey(it) }
                    newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "ok")
                }
                "seekto" -> {
                    arg.trim().toLongOrNull()?.let { PlayerBus.send("seek", it.toString()) }
                    newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "ok")
                }
                // ★ 外部唤醒入口：此前更新等功能只有启动时静默检查，没有外部可调用入口
                "checkupdate" -> {
                    startUpdateCheck(interactive = true)
                    newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "ok")
                }
                "upgrade" -> {
                    startUpdateCheck(interactive = false)
                    newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "ok")
                }
                "settings" -> { openActivity(SettingsActivity::class.java); okResp() }
                "about" -> { openActivity(AboutActivity::class.java); okResp() }
                "home" -> { openActivity(MainActivity::class.java); okResp() }
                "exit" -> {
                    App.currentActivity()?.let { a -> a.runOnUiThread { try { a.finish() } catch (_: Throwable) {} } }
                    okResp()
                }
                "reload" -> { PlayerBus.send("reload", arg); okResp() }
                "search" -> {
                    // ★ 手机遥控传的是 arg（不是 kw）：直接推搜索词并拉起搜索页
                    if (arg.isBlank()) newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "关键词为空")
                    else {
                        SearchBus.push(arg)
                        try {
                            val i = Intent(appCtx, SearchActivity::class.java)
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            i.putExtra("kw", arg)
                            appCtx.startActivity(i)
                        } catch (e: Exception) { Log.e(TAG, "拉起搜索页失败: ${e.message}") }
                        newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "ok")
                    }
                }
                "push" -> {
                    pushVideo(arg)
                    newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "已推送")
                }
                "volumeup" -> { vol(5); newFixedLengthResponse(Response.Status.OK, "text/plain", "ok") }
                "volumedown" -> { vol(-5); newFixedLengthResponse(Response.Status.OK, "text/plain", "ok") }
                "mute" -> { mute(); newFixedLengthResponse(Response.Status.OK, "text/plain", "ok") }
                "home" -> {
                    try {
                        val i = Intent(appCtx, MainActivity::class.java)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        appCtx.startActivity(i)
                    } catch (_: Exception) {}
                    newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
                }
                else -> newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "未知命令 $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "cmd 失败: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "失败: ${e.message}")
        }
    }

    /** 手机端历史 / 收藏列表（json；点条目 → push 到电视播放） */
    private fun lists(session: IHTTPSession): Response {
        if (!authed(session)) return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "未授权（配对码错误或不在白名单）")
        val code = session.parameters["code"]?.firstOrNull() ?: ""
        if (code != Prefs.getPairCode()) return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "配对码错误")
        val type = session.parameters["type"]?.firstOrNull() ?: "history"
        val items = when (type) {
            "collect" -> Prefs.getCollectedItems(appCtx)
            else -> Prefs.getHistory(appCtx)
        }
        val arr = org.json.JSONArray()
        for (it in items) {
            val o = org.json.JSONObject()
            o.put("id", it.awemeId); o.put("title", it.desc); o.put("author", it.author)
            o.put("cover", it.coverUrl)
            arr.put(o)
        }
        return newFixedLengthResponse(
            Response.Status.OK, "application/json; charset=utf-8", arr.toString())
    }

    private fun hot(session: IHTTPSession): Response {
        if (!authed(session)) return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "未授权（配对码错误或不在白名单）")
        val code = session.parameters["code"]?.firstOrNull() ?: ""
        if (code != Prefs.getPairCode()) return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "配对码错误")
        val words = DouyinApi.fetchHotSearch() ?: emptyList()
        val arr = org.json.JSONArray()
        for (w in words) arr.put(w.word)
        return newFixedLengthResponse(
            Response.Status.OK, "application/json; charset=utf-8", arr.toString())
    }

    /** 按键注入的统一返回（手机遥控方向键/返回/菜单走这里） */
    private fun keyResp(keyCode: Int): Response {
        val ok = App.sendKey(keyCode)
        return newFixedLengthResponse(
            Response.Status.OK, "text/plain; charset=utf-8",
            if (ok) "ok" else "no-foreground")
    }

    private fun okResp(): Response =
        newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "ok")

    /** 外部唤醒：打开指定页面 */
    private fun openActivity(cls: Class<*>) {
        try {
            val i = Intent(appCtx, cls)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            appCtx.startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "打开页面失败: ${e.message}")
        }
    }

    private fun uiToast(msg: String) {
        val a = App.currentActivity() ?: return
        a.runOnUiThread {
            try {
                android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 外部唤醒：检查/执行更新。
     * interactive=true → 有新版弹窗（可点立即升级）；false → 直接下载并调起安装器。
     * 此前更新只有「启动 3 秒静默检查」，无法从外部触发。
     */
    private fun startUpdateCheck(interactive: Boolean) {
        Thread {
            val info = try { UpdateChecker.check(appCtx) } catch (_: Throwable) { null }
            if (info == null) {
                if (interactive) uiToast("已是最新版本")
                return@Thread
            }
            if (!interactive) {
                val f = UpdateChecker.download(appCtx, info.apkUrl) { }
                if (f != null) UpdateChecker.install(appCtx) else uiToast("更新下载失败")
                return@Thread
            }
            val act = App.currentActivity()
            if (act == null) {
                // 没有前台界面（如在播放页直接调用）→ 退化为直接下载安装
                val f = UpdateChecker.download(appCtx, info.apkUrl) { }
                if (f != null) UpdateChecker.install(appCtx)
                return@Thread
            }
            act.runOnUiThread {
                try {
                    android.app.AlertDialog.Builder(act)
                        .setTitle("发现新版本 ${info.versionName}")
                        .setMessage(info.notes.ifBlank { "（无更新说明）" })
                        .setPositiveButton("立即升级") { _, _ ->
                            uiToast("开始下载更新…")
                            Thread {
                                val f = UpdateChecker.download(appCtx, info.apkUrl) { }
                                if (f != null) UpdateChecker.install(appCtx) else uiToast("更新下载失败")
                            }.start()
                        }
                        .setNegativeButton("稍后", null)
                        .show()
                } catch (_: Throwable) {}
            }
        }.start()
    }

    private fun status(session: IHTTPSession): Response {
        val cookie = Prefs.getCookie(appCtx)
        val missing = if (cookie.isBlank()) emptyList() else Prefs.missingKeyCookies(cookie)
        val creds = mutableListOf<String>()
        if (Prefs.getEcPrivateKey().isBlank()) creds.add("EC私钥")
        if (Prefs.getTicket().isBlank()) creds.add("ticket")
        if (Prefs.getTsSign().isBlank()) creds.add("ts_sign")
        val reqUuid = session.parameters["uuid"]?.firstOrNull() ?: ""
        val isAllowed = Prefs.isAllowed(reqUuid)
        val s = PlayerBus.snapshot
        val version = try { BuildConfig.VERSION_NAME } catch (_: Throwable) { "2.x" }
        val sb = StringBuilder()
        sb.append("{\"hasCookie\":").append(Prefs.hasCookie(appCtx))
        sb.append(",\"isAllowed\":").append(isAllowed)
        sb.append(",\"code\":\"").append(Prefs.getPairCode()).append("\"")
        sb.append(",\"uuid\":\"").append(Prefs.getTvUuid()).append("\"")
        sb.append(",\"version\":\"").append(version).append("\"")
        sb.append(",\"allowCount\":").append(Prefs.getAllowList().size)
        sb.append(",\"missing\":[").append(missing.joinToString(",") { "\"$it\"" }).append("]")
        sb.append(",\"wpMissing\":[").append(creds.joinToString(",") { "\"$it\"" }).append("]")
        sb.append(",\"player\":{")
        sb.append("\"inPlayer\":").append(s.inPlayer)
        sb.append(",\"title\":").append(org.json.JSONObject.quote(s.title))
        sb.append(",\"author\":").append(org.json.JSONObject.quote(s.author))
        sb.append(",\"pos\":").append(s.positionMs)
        sb.append(",\"dur\":").append(s.durationMs)
        sb.append(",\"playing\":").append(s.playing)
        sb.append(",\"digged\":").append(s.digged)
        sb.append(",\"collected\":").append(s.collected)
        sb.append(",\"engine\":").append(org.json.JSONObject.quote(s.engine))
        sb.append(",\"danmaku\":").append(s.danmaku)
        sb.append("}}")
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", sb.toString())
    }

    private fun vol(delta: Int) {
        try {
            val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (cur + delta).coerceIn(0, max), 0)
        } catch (_: Throwable) {}
    }

    private fun mute() {
        try {
            val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, if (cur > 0) 0 else 8, 0)
        } catch (_: Throwable) {}
    }

    /** 分享链接或 aweme_id → 详情 → FeedBus → 拉起播放页（手机遥控"推视频"） */
    private fun pushVideo(arg: String) {
        val cookie = Prefs.getCookie(appCtx)
        Thread {
            val item = try {
                val id = if (arg.trim().matches(Regex("""\d{5,}"""))) arg.trim()
                else {
                    val share = DouyinApi.extractShareUrl(arg)
                    if (share == null) null
                    else DouyinApi.extractAwemeId(DouyinApi.resolveShareUrl(share))
                        ?: DouyinApi.extractAwemeId(share)
                }
                if (id != null) DouyinApi.fetchVideoDetail(cookie, id) else null
            } catch (_: Exception) { null }
            if (item == null) return@Thread
            FeedBus.put(listOf(item), 0, FeedBus.SOURCE_SHARE)
            try {
                val i = Intent(appCtx, PlayerActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                appCtx.startActivity(i)
            } catch (e: Exception) {
                Log.e(TAG, "推送拉起播放页失败: ${e.message}")
            }
        }.start()
    }

    /**
     * 导入浏览器 localStorage 的 WebProtect 凭据（EC 私钥 / ticket / ts_sign / server_cert）。
     * 文本格式：每行 key=value 或 key:value（可在浏览器控制台粘贴 localStorage 导出）。
     */
    private fun saveCreds(session: IHTTPSession): Response {
        if (!authed(session)) return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "未授权（配对码错误或不在白名单）")
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val raw = (files["postData"] ?: "").trim()
            if (raw.isBlank()) {
                newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "内容为空，未保存")
            } else {
                val saved = Prefs.importLocalStorageCreds(appCtx, raw)
                newFixedLengthResponse(
                    Response.Status.OK, "text/plain; charset=utf-8",
                    if (saved.isEmpty()) "没有识别到 WebProtect 凭据键。\n确认导出了 https://www.douyin.com 的 localStorage（key=value 每行一个）"
                    else "已导入: ${saved.joinToString(", ")} ✓")
            }
        } catch (e: Exception) {
            Log.e(TAG, "凭据保存失败", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "失败: ${e.message}")
        }
    }

    private fun save(session: IHTTPSession): Response {
        if (!authed(session)) return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "未授权（配对码错误或不在白名单）")
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val raw = (files["postData"] ?: "").trim()
            // ★ 浏览器真实 UA：推送页 JS 用 ?ua=… 带上（写操作风控要求 UA 与会话一致）
            val ua = session.parameters["ua"]?.firstOrNull() ?: ""
            if (raw.isBlank()) {
                newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "内容为空，未保存")
            } else {
                Prefs.setCookie(appCtx, raw)
                if (ua.isNotBlank()) Prefs.setBrowserUa(appCtx, ua)
                // ★ 自动薅取 Cookie 里能解出的 WebProtect 凭据：
                //   webid ← web_sign_token JWT；客户端公钥 ← bd_ticket_guard_client_data；
                //   ts_sign ← bd_ticket_guard_ts_sign_id（仅供展示，真实签名仍需 localStorage 导入）
                val c = Prefs.getCookie(appCtx)
                val auto = mutableListOf<String>()
                val webid = Prefs.extractWebidFromCookie(c)
                if (webid.isNotBlank()) { Prefs.setWebid(appCtx, webid); auto.add("webid=${webid.take(6)}…") }
                val (pub, cfg) = Prefs.parseTicketGuardClientData(c)
                if (pub.isNotBlank()) {
                    Prefs.setServerCert(appCtx, pub)
                    auto.add("客户端公钥(" + pub.take(8) + "…)")
                    Prefs.setTicket(appCtx, cfg) // 暂存配置 JSON，签名实现用
                }
                val tsId = Prefs.cookieValue(c, "bd_ticket_guard_ts_sign_id")
                if (tsId.isNotBlank()) Prefs.setTsSign(appCtx, tsId)
                onSaved(c)
                val missing = Prefs.missingKeyCookies(c)
                val warn = if (missing.isEmpty()) " ✓ Cookie 完整，可刷视频+点赞收藏"
                    else "\n⚠ 缺少关键 Cookie: ${missing.joinToString(", ")}\n请用浏览器全选复制（含 msToken/webid），否则点赞会被判定未登录"
                Log.d(TAG, "Cookie 已保存, 长度=${c.length}, 缺=${missing.joinToString(",")}, 自动薅取=${auto.joinToString(",")}")
                newFixedLengthResponse(
                    Response.Status.OK, "text/plain; charset=utf-8",
                    "保存成功 ✓  电视已可以刷视频了$warn" +
                    (if (auto.isEmpty()) "" else "\n自动薅取: ${auto.joinToString("、")}")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存失败", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "保存失败: ${e.message}")
        }
    }

    /**
     * 手机端提交搜索关键词。
     * 同时用两条通道送达：内存总线（页面已在前台时立即生效）+ Intent（页面没开时拉起）。
     *
     * ★ 线程安全：本方法跑在 NanoHTTPD 工作线程，SearchBus.push 的内部回调
     *   （SearchActivity 的 UI 更新）会自行 post 回主线程 —— 见 SearchBus.register。
     */
    private fun doSearch(session: IHTTPSession): Response {
        return try {
            val kw = (session.parameters["kw"]?.firstOrNull() ?: "").trim()
            if (kw.isEmpty()) {
                return newFixedLengthResponse(
                    Response.Status.OK, "text/plain; charset=utf-8", "关键词为空")
            }
            SearchBus.push(kw)
            try {
                val i = Intent(appCtx, SearchActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                i.putExtra("kw", kw)
                appCtx.startActivity(i)
            } catch (e: Exception) {
                Log.d(TAG, "拉起搜索页失败: ${e.message}")
            }
            Log.d(TAG, "收到搜索词: $kw")
            newFixedLengthResponse(
                Response.Status.OK, "text/plain; charset=utf-8",
                "已发送到电视 ✓  \n关键词：$kw")
        } catch (e: Exception) {
            Log.e(TAG, "搜索词处理失败", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "失败: ${e.message}")
        }
    }

    /** 手机遥控客户端页面：配对 + 触控板 + 控制按钮 + 搜索 + 推视频 + 历史/收藏列表 */
    private fun remotePage(): Response {
        val html = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>Dtv 遥控器</title>
<style>
  *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
  body{margin:0;min-height:100vh;background:#0b0b0f;color:#fff;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;padding:16px 14px 40px}
  h1{font-size:18px;margin:2px 0 10px}
  .card{background:#16161d;border:1px solid #23232e;border-radius:16px;padding:14px;margin-bottom:12px}
  .row{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
  input{flex:1;min-width:120px;padding:11px;background:#0e0e13;color:#fff;border:1px solid #2a2a35;border-radius:10px;font-size:15px;outline:none}
  button{padding:11px 14px;background:#2a2a35;color:#fff;border:0;border-radius:10px;font-size:14px;cursor:pointer;transition:.12s}
  button:active{transform:scale(.96)}
  button.brand{background:#E91E63}
  button.on{background:#E91E63}
  .status{font-size:13px;color:#8a8a99;line-height:1.6}
  .status b{color:#fff}
  .red{color:#FE2C55}
  .pad{height:190px;background:#0e0e13;border:1px solid #2a2a35;border-radius:14px;position:relative;overflow:hidden;touch-action:none}
  .pad .hint{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;color:#4a4a58;font-size:13px;pointer-events:none;line-height:2}
  .grid{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}
  .chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}
  .chip{background:#23232e;border-radius:20px;padding:6px 12px;font-size:12px;color:#c8c8d4;cursor:pointer}
  .li{display:flex;gap:10px;align-items:center;padding:8px 0;border-bottom:1px solid #1d1d24;cursor:pointer}
  .li img{width:64px;height:36px;object-fit:cover;border-radius:6px;background:#000}
  .li .t{font-size:13px;line-height:1.4;color:#dcdce4;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
  .li .a{font-size:11px;color:#8a8a99;margin-top:2px}
  .progress{height:4px;background:#23232e;border-radius:2px;margin-top:8px;overflow:hidden}
  .progress i{display:block;height:100%;background:#E91E63;width:0}
  .lab{font-size:12px;color:#8a8a99;margin:10px 0 6px}
  .muted{color:#5a5a6a}
  a{color:#64B5F6}
</style>
</head>
<body>
  <h1>📺 Dtv 遥控器</h1>
  <div class="card">
    <div class="row">
      <input id="code" placeholder="输入电视上的配对码（6 位）" inputmode="numeric" maxlength="6">
      <button class="brand" onclick="pair()">配对</button>
      <button onclick="regenerate()">换码</button>
    </div>
    <div class="status" id="st">未配对</div>
  </div>

  <div class="card">
    <div class="status" id="now">—</div>
    <div class="progress"><i id="bar"></i></div>
  </div>

  <div class="card">
    <div class="pad" id="pad">
      <div class="hint">轻触 播放/暂停<br>左右滑 上一条/下一条<br>上下滑 快退/快进</div>
    </div>
    <div class="row" style="margin-top:10px">
      <button onclick="cmd('prev')">⏮ 上一条</button>
      <button onclick="cmd('toggle')">⏯ 播放/暂停</button>
      <button onclick="cmd('next')">⏭ 下一条</button>
    </div>
  </div>

  <div class="card">
    <div class="grid">
      <button id="bDigg" class="brand" onclick="cmd('digg')">👍 赞</button>
      <button id="bCollect" onclick="cmd('collect')">⭐ 藏</button>
      <button onclick="cmd('danmaku')">💬 弹幕</button>
      <button onclick="cmd('line')">🔀 线路</button>
      <button onclick="cmd('engine')">🔧 引擎</button>
      <button onclick="cmd('loop')">🔁 循环</button>
      <button onclick="cmd('landscape')">🖥 横屏</button>
      <button onclick="cmd('mute')">🔇 静音</button>
      <button onclick="cmd('volumeup')">🔊 +</button>
      <button onclick="cmd('volumedown')">🔊 −</button>
      <button onclick="cmd('home')">🏠 首页</button>
    </div>
  </div>

  <div class="card">
    <div class="lab">搜视频（电视播放/搜索页）</div>
    <div class="row">
      <input id="kw" placeholder="关键词">
      <button class="brand" onclick="doSearch()">搜索</button>
    </div>
    <div class="chips" id="hot"></div>
  </div>

  <div class="card">
    <div class="lab">推视频到电视（抖音分享链接 / aweme_id）</div>
    <div class="row">
      <input id="push" placeholder="粘贴抖音分享链接">
      <button class="brand" onclick="doPush()">推送到电视</button>
    </div>
  </div>

  <div class="card">
    <div class="row">
      <button onclick="loadList('history')">观看历史</button>
      <button onclick="loadList('collect')">我的收藏</button>
    </div>
    <div id="list"></div>
  </div>

<script>
var code = localStorage.getItem('dtv_code') || '';
document.getElementById('code').value = code;
function pair(){
  code = document.getElementById('code').value.trim();
  if(code.length<6){alert('请输入 6 位配对码');return}
  fetch('/pair?code='+code).then(r=>r.json()).then(j=>{
    if(j.ok){localStorage.setItem('dtv_code', code);setStatus('已配对 ✓ 电视码 '+j.code)}
    else setStatus('配对失败：码不对（电视码 '+j.code+'）');
  });
}
function setStatus(t){document.getElementById('st').textContent = t}
function u(s){return '/cmd?code='+encodeURIComponent(code)+'&action='+s}
function cmd(a,arg){fetch(u(a)+(arg!==undefined?'&arg='+encodeURIComponent(arg):'')).catch(()=>{})}
function doSearch(){var k=document.getElementById('kw').value.trim();if(k)cmd('search',k)}
function doPush(){var v=document.getElementById('push').value.trim();if(v){cmd('push',v);document.getElementById('push').value='';alert('已推送到电视')}}
function hotWords(){
  fetch('/hot?code='+code).then(r=>r.json()).then(j=>{
    if(!Array.isArray(j))return;
    var h=document.getElementById('hot');h.innerHTML='';
    j.forEach(w=>{var s=document.createElement('span');s.className='chip';s.textContent=w;s.onclick=function(){cmd('search',w)};h.appendChild(s)});
  }).catch(()=>{});
}
function loadList(type){
  fetch('/lists?code='+code+'&type='+type).then(r=>r.json()).then(arr=>{
    var el=document.getElementById('list');
    if(!Array.isArray(arr)||!arr.length){el.innerHTML='<div class="muted" style="padding:10px 0">暂无内容</div>';return}
    el.innerHTML='';
    arr.forEach(v=>{
      var d=document.createElement('div');d.className='li';
      d.innerHTML='<img src="'+(v.cover||'')+'"><div><div class="t">'+v.title+'</div><div class="a">@'+(v.author||'')+'</div></div>';
      d.onclick=function(){cmd('push',v.id)};
      el.appendChild(d);
    });
  }).catch(()=>{});
}
// 触控板
(function(){
  var pad=document.getElementById('pad'),sx=0,sy=0,t0=0;
  pad.addEventListener('touchstart',function(e){var t=e.touches[0];sx=t.clientX;sy=t.clientY;t0=Date.now()});
  pad.addEventListener('touchend',function(e){
    var t=e.changedTouches[0],dx=t.clientX-sx,dy=t.clientY-sy,dt=Date.now()-t0;
    if(Math.abs(dx)<24 && Math.abs(dy)<24){if(dt<350)cmd('toggle');return}
    if(Math.abs(dx)>Math.abs(dy)){cmd(dx<0?'next':'prev')}
    else if(Math.abs(dy)>70){cmd('seek',dy<0?'10000':'-10000')}
  });
})();
// 状态轮询
function poll(){
  fetch('/status').then(r=>r.json()).then(j=>{
    if(code && j.code && code!==j.code){setStatus('⚠ 配对码已变，请重新配对');return}
    if(!code){setStatus('未配对（电视码 '+j.code+'）');return}
    var p=j.player||{};
    var el=document.getElementById('now');
    if(p.inPlayer){el.innerHTML='▶ <b>'+p.title+'</b><br>@'+p.author+' · '+(p.engine||'')+(p.danmaku?' · 弹幕开':'')+' · '+fmt(p.pos)+'/'+fmt(p.dur)}
    else{el.innerHTML='电视未在播放（去电视上打开播放器）'}
    var bar=document.getElementById('bar');
    if(p.dur>0)bar.style.width=(p.pos/p.dur*100)+'%';
    document.getElementById('bDigg').className=p.digged?'brand on':'brand';
    document.getElementById('bCollect').className=p.collected?'on':'';
  }).catch(()=>{});
}
function fmt(ms){ms=ms|0;var s=Math.floor(ms/1000);return (Math.floor(s/60))+':'+('0'+(s%60)).slice(-2)}
function regenerate(){fetch('/pair?code='+code).catch(()=>{});setTimeout(()=>{fetch('/status').then(r=>r.json()).then(j=>{setStatus('电视新码：'+j.code+'（用新码重新配对）')})},300)}
poll();setInterval(poll,1200);hotWords();
</script>
</body>
</html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun page(): Response {
        val html = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Dtv · Cookie 配置</title>
<style>
  *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
  body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
       padding:20px;background:#0b0b0f;color:#fff;
       font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
  .card{width:100%;max-width:600px;background:#16161d;border:1px solid #23232e;
        border-radius:20px;padding:26px;box-shadow:0 24px 70px rgba(0,0,0,.65)}
  h1{margin:0 0 4px;font-size:21px;letter-spacing:.3px}
  .sub{color:#8a8a99;font-size:13px;margin-bottom:18px}
  .status{padding:11px 14px;border-radius:11px;font-size:13px;margin-bottom:16px;font-weight:500}
  .ok{background:rgba(76,175,80,.14);color:#81c784;border:1px solid rgba(76,175,80,.25)}
  .no{background:rgba(233,30,99,.14);color:#f06292;border:1px solid rgba(233,30,99,.25)}
  textarea{width:100%;height:190px;background:#0e0e13;color:#dcdce4;border:1px solid #2a2a35;
           border-radius:12px;padding:13px;font-size:12px;line-height:1.55;resize:vertical;
           outline:none;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;transition:.15s}
  textarea:focus{border-color:#E91E63;box-shadow:0 0 0 3px rgba(233,30,99,.12)}
  textarea::placeholder{color:#4a4a58}
  button{width:100%;margin-top:14px;padding:15px;border:0;border-radius:12px;background:#E91E63;
         color:#fff;font-size:16px;font-weight:600;cursor:pointer;transition:.15s}
  button:hover{background:#d1165a}
  button:active{transform:scale(.985)}
  button:disabled{opacity:.55;cursor:default}
  .tip{margin-top:16px;padding-top:14px;border-top:1px solid #23232e;color:#6a6a7a;font-size:12px;line-height:1.85}
  .tip b{color:#9a9aa8;font-weight:600}
  .sep{height:1px;background:#23232e;margin:20px 0}
  .lab{font-size:13px;color:#9a9aa8;margin-bottom:9px;font-weight:600}
  input{width:100%;padding:14px;background:#0e0e13;color:#fff;border:1px solid #2a2a35;
        border-radius:12px;font-size:16px;outline:none;transition:.15s}
  input:focus{border-color:#E91E63;box-shadow:0 0 0 3px rgba(233,30,99,.12)}
  input::placeholder{color:#4a4a58}
  .sres{margin-top:10px;font-size:13px;color:#81c784;white-space:pre-line;min-height:18px}
  textarea.short{height:96px;font-size:12px}
  button.s2{background:#2a2a35}
  button.s2:hover{background:#343440}
  code{background:#0e0e13;padding:2px 6px;border-radius:5px;color:#c8c8d4;font-size:11px}
</style>
</head>
<body>
<div class="card">
  <h1>Dtv</h1>
  <div class="sub">把电脑浏览器的 Cookie 粘贴进来，点保存</div>
  <div id="st" class="status no">检测中…</div>
  <textarea id="ck" placeholder="支持两种格式：&#10;&#10;1) 分号串：  ttwid=xxx; odin_tt=yyy; ...&#10;&#10;2) Cookie Editor 导出的 JSON 数组&#10;[{&quot;name&quot;:&quot;ttwid&quot;,&quot;value&quot;:&quot;xxx&quot;}, ...]"></textarea>
  <button id="btn" onclick="save()">保存到电视</button>
  <div class="sep"></div>
  <div class="lab">WebProtect 凭据（点赞/收藏用，可选）</div>
  <textarea id="wp" class="short" placeholder="浏览器 F12 → Console 执行下面命令复制结果，粘到这里点导入：&#10;&#10;copy(Object.entries(localStorage).filter(x=&gt;x[1].length&gt;40).map(x=&gt;x[0]+&#39;=&#39;+x[1]).join(&#39;\n&#39;))"></textarea>
  <button id="wpbtn" onclick="saveCreds()">导入 WebProtect 凭据</button>
  <div class="sres" id="wpres"></div>
  <div class="sep"></div>
  <div class="lab">手机输入关键词 → 电视上搜索</div>
  <input id="kw" placeholder="例如：美食 / 游戏 / 猫咪" autocomplete="off">
  <button id="sbtn" onclick="doSearch()">在电视上搜索</button>
  <div class="sres" id="sres"></div>
  <div class="tip">
    <b>怎么拿 Cookie？</b><br>
    电脑 Chrome 登录 <code>douyin.com</code> → F12 → Application → Cookies → <code>https://www.douyin.com</code> → 全选复制<br>
    或装 <b>Cookie Editor</b> 插件 → Export → 选 JSON
  </div>
</div>
<script>
function refresh(){
  fetch('/status').then(function(r){return r.json()}).then(function(j){
    var el=document.getElementById('st');
    if(j.hasCookie){
      var extra=[];
      if(j.missing && j.missing.length>0){el.className='status no';extra.push('缺 Cookie: '+j.missing.join('/'))}
      else {el.className='status ok'}
      if(j.wpMissing && j.wpMissing.length>0){extra.push('缺 WebProtect: '+j.wpMissing.join('/'))}
      el.textContent = extra.length>0 ? '✓ 已配置，但 '+extra.join('；') : '✓ 电视已配置 Cookie + WebProtect，可以点赞/收藏了';
    }
    else{el.className='status no';el.textContent='✗ 尚未配置 Cookie'}
  }).catch(function(){});
}
function saveCreds(){
  var v=document.getElementById('wp').value.trim();
  if(!v){alert('请先粘贴 localStorage 内容');return}
  var b=document.getElementById('wpbtn');
  b.disabled=true;b.textContent='导入中…';
  fetch('/savecreds',{method:'POST',headers:{'Content-Type':'text/plain'},body:v})
    .then(function(r){return r.text()})
    .then(function(t){
      document.getElementById('wpres').textContent=t;
      document.getElementById('wp').value='';
      b.disabled=false;b.textContent='导入 WebProtect 凭据';
      refresh();
    })
    .catch(function(e){document.getElementById('wpres').textContent='失败: '+e;b.disabled=false;b.textContent='导入 WebProtect 凭据'});
}
function save(){
  var v=document.getElementById('ck').value.trim();
  if(!v){alert('请先粘贴 Cookie');return}
  var b=document.getElementById('btn');
  b.disabled=true;b.textContent='保存中…';
  fetch('/save?ua='+encodeURIComponent(navigator.userAgent),
        {method:'POST',headers:{'Content-Type':'text/plain'},body:v})
    .then(function(r){return r.text()})
    .then(function(t){
      alert(t);
      document.getElementById('ck').value='';
      b.disabled=false;b.textContent='保存到电视';
      refresh();
    })
    .catch(function(e){alert('保存失败: '+e);b.disabled=false;b.textContent='保存到电视'});
}
function doSearch(){
  var k=document.getElementById('kw').value.trim();
  if(!k){alert('请先输入关键词');return}
  var b=document.getElementById('sbtn');
  b.disabled=true;b.textContent='发送中…';
  fetch('/search?kw='+encodeURIComponent(k))
    .then(function(r){return r.text()})
    .then(function(t){
      document.getElementById('sres').textContent=t;
      b.disabled=false;b.textContent='在电视上搜索';
    })
    .catch(function(e){
      document.getElementById('sres').textContent='发送失败: '+e;
      b.disabled=false;b.textContent='在电视上搜索';
    });
}
document.getElementById('kw').addEventListener('keydown',function(e){
  if(e.key==='Enter')doSearch();
});
refresh();
</script>
</body>
</html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }
}
