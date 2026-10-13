package com.example.douyintv

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 统一的本地配置存取（Cookie 等）
 */
object Prefs {
    private const val NAME = "douyin_tv_prefs"
    private const val KEY_COOKIE = "cookie"

    fun ctx(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getCookie(context: Context): String = ctx(context).getString(KEY_COOKIE, "") ?: ""

    fun setCookie(context: Context, raw: String) {
        ctx(context).edit().putString(KEY_COOKIE, normalize(raw)).apply()
    }

    fun hasCookie(context: Context): Boolean = getCookie(context).isNotBlank()

    fun clearCookie(context: Context) {
        ctx(context).edit().remove(KEY_COOKIE).apply()
    }

    // ---- 浏览器真实 UA（登录推送时由推送页 JS 捕获）----
    // ★ 写操作（点赞/收藏/评论）风控要求：请求 UA 必须与登录会话的浏览器一致，
    //   否则按「异地登录」处理 → 踢下线网页端 + 返回未登录。
    fun getBrowserUa(): String = App.appContext()?.let { ctx(it).getString("browser_ua", "") ?: "" } ?: ""
    fun setBrowserUa(context: Context, v: String) = ctx(context).edit().putString("browser_ua", v).apply()

    // ---- 互动安全凭据（webid / msToken / uid）----
    // ★ 关键认知：抖音在浏览器端把 webid / msToken 也写进 Cookie，
    //   登录 Cookie 里直接就有真实值。写操作必须用浏览器真实的这一套，
    //   本地随机串（旧版方案）会被风控判定为异常 → 踢下线网页端。
    //   Prefs 里的生成值只作为 Cookie 缺失时的最后兜底。
    fun getMsToken(): String = App.appContext()?.let { ctx(it).getString("ms_token", "") ?: "" } ?: ""
    fun setMsToken(context: Context, v: String) = ctx(context).edit().putString("ms_token", v).apply()
    fun getWebid(): String = App.appContext()?.let { ctx(it).getString("webid", "") ?: "" } ?: ""
    fun setWebid(context: Context, v: String) = ctx(context).edit().putString("webid", v).apply()
    fun getUid(): String = App.appContext()?.let { ctx(it).getString("interact_uid", "") ?: "" } ?: ""
    fun setUid(context: Context, v: String) = ctx(context).edit().putString("interact_uid", v).apply()

    /** 从 Cookie 分号串里取某个键的值（找不到返回空串） */
    fun cookieValue(cookie: String, key: String): String =
        cookie.split(";").map { it.trim() }.firstOrNull { it.startsWith(key + "=") }
            ?.substringAfter("=") ?: ""

    /** 写操作/会话相关的关键 Cookie 键是否齐全（缺哪个列出来，方便提示重新完整复制） */
    fun missingKeyCookies(cookie: String): List<String> {
        // ★ 注意：webid / msToken 不在 Cookie 里（在浏览器 localStorage），
        //   不能列入缺失检查，否则每次都会误报。它们走 WebProtect 凭据导入。
        val need = listOf("sessionid", "ttwid", "s_v_web_id", "UIFID", "passport_csrf_token")
        return need.filter { cookieValue(cookie, it).isEmpty() }
    }

    /** 首次启动自动生成合规格式的 webid / msToken / uid（无则生成，有则保留） */
    fun ensureInteractCreds(context: Context) {
        try {
            if (getWebid().isBlank() || getMsToken().isBlank() || getUid().isBlank()) {
                setWebid(context, genWebid())
                setMsToken(context, genMsToken())
                setUid(context, genUid())
            }
        } catch (_: Throwable) {}
    }

    // ---- WebProtect 凭据（EC 签名 + ticket，myDV 方案）----
    // 互动接口（点赞/收藏/评论）强制要求 WebProtect 层：
    //   · ec_privateKey：浏览器 web_protect.js 生成的 EC P256 私钥（LocalStorage）
    //   · ticket / ts_sign / server_cert：SDK 与服务器握手拿到的票据（LocalStorage）
    // 由用户从浏览器 LocalStorage 提取后导入（myDV 同款流程），App 用它复刻 EC 签名。
    fun getEcPrivateKey(): String = App.appContext()?.let { ctx(it).getString("wp_ec_private", "") ?: "" } ?: ""
    fun setEcPrivateKey(context: Context, v: String) = ctx(context).edit().putString("wp_ec_private", v).apply()
    fun getTicket(): String = App.appContext()?.let { ctx(it).getString("wp_ticket", "") ?: "" } ?: ""
    fun setTicket(context: Context, v: String) = ctx(context).edit().putString("wp_ticket", v).apply()
    fun getTsSign(): String = App.appContext()?.let { ctx(it).getString("wp_ts_sign", "") ?: "" } ?: ""
    fun setTsSign(context: Context, v: String) = ctx(context).edit().putString("wp_ts_sign", v).apply()
    fun getServerCert(): String = App.appContext()?.let { ctx(it).getString("wp_server_cert", "") ?: "" } ?: ""
    fun setServerCert(context: Context, v: String) = ctx(context).edit().putString("wp_server_cert", v).apply()

    /** 是否已配置 WebProtect 凭据（EC 私钥 + 票据齐全） */
    fun hasWebProtect(): Boolean =
        getEcPrivateKey().isNotBlank() && getTicket().isNotBlank() && getTsSign().isNotBlank()

    // ---- WebProtect 自动薅取（Cookie 里能解出来的部分）----
    /**
     * webid 藏在 `web_sign_token` JWT 的 payload.id 里（base64url）。
     * 浏览器 localStorage 里的 webid 与它是同一个值；Cookie 里能直接解出来。
     */
    fun extractWebidFromCookie(cookie: String): String {
        try {
            val wt = cookieValue(cookie, "web_sign_token")
            if (wt.isBlank()) return ""
            val payload = wt.split(".").getOrNull(1) ?: return ""
            val pad = payload.length % 4
            val b = android.util.Base64.decode(
                (if (pad == 0) payload else payload + "====".take(4 - pad)).replace('-', '+').replace('_', '/'),
                android.util.Base64.DEFAULT)
            val o = org.json.JSONObject(String(b, Charsets.UTF_8))
            return o.optString("id", "")
        } catch (_: Throwable) {
            return ""
        }
    }

    /**
     * 从 Cookie 里的 `bd_ticket_guard_client_data`（base64 的 JSON）解出
     * 客户端公钥 bd-ticket-guard-ree-public-key 与各版本号配置。
     * 返回 Pair<公钥, 版本配置JSON>，解不出返回 (空, 空)。
     */
    fun parseTicketGuardClientData(cookie: String): Pair<String, String> {
        try {
            val raw = cookieValue(cookie, "bd_ticket_guard_client_data")
            if (raw.isBlank()) return "" to ""
            val b = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
            val o = org.json.JSONObject(String(b, Charsets.UTF_8))
            val pub = o.optString("bd-ticket-guard-ree-public-key", "")
            return pub to o.toString()
        } catch (_: Throwable) {
            return "" to ""
        }
    }

    /**
     * 解析用户粘贴的 localStorage 导出文本（每行 key=value / key:value），
     * 按关键字归档到 WebProtect 凭据槽位。返回「识别到的槽位名列表」。
     */
    fun importLocalStorageCreds(context: Context, text: String): List<String> {
        val saved = ArrayList<String>()
        try {
            text.lines().forEach { line ->
                val l = line.trim()
                if (l.isEmpty() || l.startsWith("//") || l.startsWith("#")) return@forEach
                val eq = l.indexOf('=')
                val idx = if (eq > 0) eq else l.indexOf(':')
                if (idx <= 0) return@forEach
                val k = l.substring(0, idx).trim()
                val v = l.substring(idx + 1).trim().trim('"').trim()
                if (k.isEmpty() || v.isEmpty()) return@forEach
                val kk = k.lowercase()
                when {
                    kk.contains("ec_private") || kk.contains("private_key") || kk.contains("client_key") ||
                        (kk.contains("private") && kk.contains("key")) ->
                        { setEcPrivateKey(context, v); saved.add("ec_private") }
                    kk.contains("ts_sign") -> { setTsSign(context, v); saved.add("ts_sign") }
                    kk.contains("ticket") -> { setTicket(context, v); saved.add("ticket") }
                    kk.contains("server_cert") || kk.contains("ree_public") || kk.contains("client_cert") ->
                        { setServerCert(context, v); saved.add("server_cert") }
                    kk == "mstoken" -> { setMsToken(context, v); saved.add("msToken") }
                    kk.contains("webid") -> { setWebid(context, v); saved.add("webid") }
                }
            }
        } catch (_: Throwable) {}
        return saved
    }

    private val rnd = java.util.Random()

    /** 抓包 webid=7641967146125510207 —— 19 位数字 */
    private fun genWebid(): String {
        val sb = StringBuilder(19)
        sb.append(1 + rnd.nextInt(9))   // 首位非 0
        for (i in 1 until 19) sb.append(rnd.nextInt(10))
        return sb.toString()
    }

    /** 抓包 msToken —— base64url 风格随机串（180~220 字符，含 -_） */
    private fun genMsToken(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val len = 180 + rnd.nextInt(40)
        val sb = StringBuilder(len)
        for (i in 0 until len) sb.append(chars[rnd.nextInt(chars.length)])
        return sb.toString()
    }

    /** 抓包 uid=9204116cf0083739b4ace392acbd8c39 —— 32 位 hex */
    private fun genUid(): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder(32)
        for (i in 0 until 32) sb.append(chars[rnd.nextInt(16)])
        return sb.toString()
    }

    // ---- 播放引擎偏好 (0=ExoPlayer, 1=IJK, 2=系统) ----
    // ★ 默认给 IJK ★：用户实机对比三个引擎，IJK 明显最流畅
    //   （Mali450 那代硬解驱动不稳，IJK 的软解+跳环路滤波反而更顺）
    fun getEngine(context: Context): Int = ctx(context).getInt("engine", 1)

    fun setEngine(context: Context, idx: Int) {
        ctx(context).edit().putInt("engine", idx).apply()
    }

    // ---- 播完行为 (true = 单集循环, false = 自动连播) ----
    fun isLoopOne(context: Context): Boolean = ctx(context).getBoolean("loop_one", false)

    fun setLoopOne(context: Context, v: Boolean) {
        ctx(context).edit().putBoolean("loop_one", v).apply()
    }

    // ---- 横屏过滤开关 ----
    // 默认 false（不过滤）：竖屏视频在电视上左右有黑边，但内容完整。
    // 打开后只保留「宽 >= 高」的视频，信息流观感更统一。
    fun isLandscapeOnly(context: Context): Boolean =
        ctx(context).getBoolean("landscape_only", false)

    fun setLandscapeOnly(context: Context, v: Boolean) {
        ctx(context).edit().putBoolean("landscape_only", v).apply()
    }

    // ---- 在线更新（GitHub 渠道）清单地址 ----
    fun getUpdateUrl(): String = App.appContext()?.let { ctx(it).getString("update_url", "") ?: "" } ?: ""
    fun setUpdateUrl(context: Context, v: String) = ctx(context).edit().putString("update_url", v).apply()

    // ---- 弹幕开关（默认关） ----
    fun getDanmaku(): Boolean = App.appContext()?.let { ctx(it).getBoolean("danmaku_on", false) } ?: false
    fun setDanmaku(context: Context, v: Boolean) = ctx(context).edit().putBoolean("danmaku_on", v).apply()

    // ---- 手机遥控配对码（6 位数字；首次自动生成，可手动重生成） ----
    // ★ 用 java.util.Random：ThreadLocalRandom 是 API 21+，本机 API 17 会 NoSuchMethodError
    private val pairRnd = java.util.Random()

    fun getPairCode(): String {
        val c = App.appContext() ?: return "000000"
        var code: String = ctx(c).getString("pair_code", "") ?: ""
        if (code.isBlank() || code.length != 6) {
            code = String.format("%06d", pairRnd.nextInt(1000000))
            ctx(c).edit().putString("pair_code", code).apply()
        }
        return code
    }

    fun regeneratePairCode(context: Context): String {
        val code: String = String.format("%06d", pairRnd.nextInt(1000000))
        ctx(context).edit().putString("pair_code", code).apply()
        return code
    }

    // ---- 手机遥控：tv_uuid + 手机白名单（配对后长期授权） ----
    fun getTvUuid(): String {
        val c = App.appContext() ?: return ""
        var u = ctx(c).getString("tv_uuid", "") ?: ""
        if (u.isBlank()) {
            val chars = "abcdef0123456789"
            val sb = StringBuilder()
            val r = java.util.Random()
            for (i in 0 until 32) sb.append(chars[r.nextInt(chars.length)])
            u = sb.toString()
            ctx(c).edit().putString("tv_uuid", u).apply()
        }
        return u
    }

    /** 白名单：JSON 数组 [{uuid, name, ts}] 里的 uuid 列表 */
    fun getAllowList(): List<String> {
        val c = App.appContext() ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(ctx(c).getString("allow_clients", "[]") ?: "[]")
            (0 until arr.length()).map { arr.optJSONObject(it)?.optString("uuid", "") ?: "" }
                .filter { it.isNotBlank() }
        } catch (_: Throwable) { emptyList() }
    }

    fun isAllowed(uuid: String): Boolean = uuid.isNotBlank() && getAllowList().contains(uuid)

    fun addAllowedClient(context: Context, uuid: String, name: String) {
        if (uuid.isBlank()) return
        try {
            val arr = org.json.JSONArray(ctx(context).getString("allow_clients", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                if (arr.optJSONObject(i)?.optString("uuid") == uuid) return   // 已在名单
            }
            val o = org.json.JSONObject()
            o.put("uuid", uuid); o.put("name", name.ifBlank { "手机" }); o.put("ts", System.currentTimeMillis())
            arr.put(o)
            ctx(context).edit().putString("allow_clients", arr.toString()).apply()
        } catch (_: Throwable) {}
    }

    fun delAllowedClient(context: Context, uuid: String) {
        try {
            val arr = org.json.JSONArray(ctx(context).getString("allow_clients", "[]") ?: "[]")
            val out = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o?.optString("uuid") != uuid) out.put(o)
            }
            ctx(context).edit().putString("allow_clients", out.toString()).apply()
        } catch (_: Throwable) {}
    }

    fun clearAllowedClients(context: Context) {
        ctx(context).edit().putString("allow_clients", "[]").apply()
    }

    // ================================================================
    // 崩溃自愈恢复点 —— ★ 用「普通文件 + 同步写」存，不用 SharedPreferences.apply() ★
    //
    // 为什么必须改成文件：
    //   SharedPreferences.apply() 是「先改内存、异步落盘」—— 进程被 killProcess
    //   （自我重启）或被固件 / LMK 杀掉时，异步写盘线程根本来不及跑 →
    //   恢复点、心跳、计划重启标记全部丢在内存里 → 冷启动 loadResume 拿到空/旧
    //   数据 → 判定「无恢复点 / 已过期」→ 用户被丢回推荐页。
    //   这就是「第 4/5 个视频黑屏、不加载、回推荐页」的真正根因 —— 恢复机制
    //   从来没能把状态写到盘上，一直没生效过。
    //
    //   File.writeText 是同步的：close() 之后数据已进内核页缓存。进程死了页缓存
    //   仍在（由内核负责回写 flash，与进程无关）→ 进程死亡不丢数据。
    // ================================================================

    /** 连击衰减窗口：超过这个间隔没再崩，计数从头算 */
    private const val CRASH_STREAK_WINDOW_MS = 3 * 60_000L

    private fun fResume(c: Context) = File(c.filesDir, "resume.json")
    private fun fAlive(c: Context) = File(c.filesDir, "resume_alive")
    private fun fPlanned(c: Context) = File(c.filesDir, "planned_restart")
    private fun fStreak(c: Context) = File(c.filesDir, "crash_streak")
    private fun fTrace(c: Context) = File(c.filesDir, "restore_trace.txt")
    private fun fHistory(c: Context) = File(c.filesDir, "history.json")
    private fun fSearchHistory(c: Context) = File(c.filesDir, "search_history.json")
    private fun fDigged(c: Context) = File(c.filesDir, "digged.json")
    private fun fCollected(c: Context) = File(c.filesDir, "collected.json")

    // ================================================================
    // 点赞 / 收藏状态（本地记忆；点赞存 id 集合，收藏存完整 VideoItem 供列表展示）
    // ================================================================
    /** 点赞：切换状态，返回切换后是否已赞 */
    fun toggleDigg(context: Context, awemeId: String): Boolean =
        toggleSet(fDigged(context), awemeId)

    fun isDigged(context: Context, awemeId: String): Boolean = inSet(fDigged(context), awemeId)

    /** 收藏：切换状态（存完整视频信息，供「我的 → 收藏」列表展示） */
    fun toggleCollect(context: Context, item: DouyinApi.VideoItem): Boolean {
        return try {
            val existing = getCollectedItems(context).toMutableList()
            val now = if (!existing.removeAll { it.awemeId == item.awemeId }) {
                existing.add(0, item); true
            } else false
            val arr = JSONArray()
            for (v in existing) arr.put(historyItemJson(v))
            fCollected(context).writeText(arr.toString())
            now
        } catch (_: Throwable) { false }
    }

    fun isCollected(context: Context, awemeId: String): Boolean =
        getCollectedItems(context).any { it.awemeId == awemeId }

    /** 收藏列表（完整视频信息，最新在前） */
    fun getCollectedItems(context: Context): List<DouyinApi.VideoItem> {
        return try {
            val f = fCollected(context)
            if (!f.exists()) return emptyList()
            parseItems(f.readText())
        } catch (_: Throwable) { emptyList() }
    }

    private fun toggleSet(f: File, id: String): Boolean {
        return try {
            val set = readSet(f).toMutableSet()
            val now = if (!set.remove(id)) { set.add(id); true } else false
            val arr = JSONArray()
            for (x in set) arr.put(x)
            f.writeText(arr.toString())
            now
        } catch (_: Throwable) { false }
    }

    private fun inSet(f: File, id: String): Boolean {
        return try {
            if (!f.exists()) false
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) if (arr.optString(i, "") == id) return true
            false
        } catch (_: Throwable) { false }
    }

    private fun readSet(f: File): List<String> {
        return try {
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) out.add(arr.optString(i, ""))
            out
        } catch (_: Throwable) { emptyList() }
    }

    // ================================================================
    // 搜索历史（纯本地，去重置顶，上限 20 条）
    // ================================================================
    private const val SEARCH_HISTORY_MAX = 20

    fun addSearchHistory(context: Context, kw: String) {
        val k = kw.trim()
        if (k.isEmpty()) return
        try {
            val f = fSearchHistory(context)
            val dedup = JSONArray()
            dedup.put(k)
            if (f.exists()) {
                val old = try { JSONArray(f.readText()) } catch (_: Exception) { JSONArray() }
                for (i in 0 until old.length()) {
                    val w = old.optString(i, "")
                    if (w.isNotEmpty() && w != k) dedup.put(w)
                }
            }
            val keep = JSONArray()
            for (i in 0 until minOf(dedup.length(), SEARCH_HISTORY_MAX)) {
                keep.put(dedup.getString(i))
            }
            f.writeText(keep.toString())
        } catch (_: Throwable) {}
    }

    fun getSearchHistory(context: Context): List<String> {
        try {
            val f = fSearchHistory(context)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) out.add(arr.optString(i, ""))
            return out
        } catch (_: Throwable) {
            return emptyList()
        }
    }

    fun clearSearchHistory(context: Context) {
        try { fSearchHistory(context).delete() } catch (_: Throwable) {}
    }

    // ================================================================
    // 观看历史（纯本地，不入库抖音）
    // ================================================================
    private const val HISTORY_MAX = 100

    /** 记录一条观看历史（同视频去重，最新在前，上限 100 条） */
    fun addHistory(context: Context, item: DouyinApi.VideoItem) {
        try {
            val f = fHistory(context)
            val dedup = JSONArray()
            dedup.put(historyItemJson(item))
            if (f.exists()) {
                val old = try { JSONArray(f.readText()) } catch (_: Exception) { JSONArray() }
                for (i in 0 until old.length()) {
                    val o = old.optJSONObject(i) ?: continue
                    if (o.optString("id", "") != item.awemeId) dedup.put(o)
                }
            }
            val keep = JSONArray()
            for (i in 0 until minOf(dedup.length(), HISTORY_MAX)) {
                keep.put(dedup.getJSONObject(i))
            }
            f.writeText(keep.toString())
        } catch (_: Throwable) {}
    }

    /** 读取观看历史（最新在前） */
    fun getHistory(context: Context): List<DouyinApi.VideoItem> {
        try {
            val f = fHistory(context)
            if (!f.exists()) return emptyList()
            return parseItems(f.readText())
        } catch (_: Throwable) {
            return emptyList()
        }
    }

    /** 清空观看历史 */
    fun clearHistory(context: Context) {
        try { fHistory(context).delete() } catch (_: Throwable) {}
    }

    /** 历史 / 收藏共用的 JSON 解析（VideoItem 数组） */
    private fun parseItems(json: String): List<DouyinApi.VideoItem> {
        val arr = JSONArray(json)
        val out = ArrayList<DouyinApi.VideoItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                DouyinApi.VideoItem(
                    o.optString("id", ""), o.optString("d", ""), o.optString("a", ""),
                    o.optString("u", ""), o.optString("c", ""), o.optInt("dur", 0),
                    o.optString("sec", ""), o.optInt("digg", 0), o.optInt("cmt", 0),
                    emptyList(), emptyList(), o.optBoolean("pt", false),
                    o.optLong("ts", 0L)
                )
            )
        }
        return out
    }

    private fun historyItemJson(item: DouyinApi.VideoItem): org.json.JSONObject {
        val o = org.json.JSONObject()
        o.put("id", item.awemeId)
        o.put("d", item.desc)
        o.put("a", item.author)
        o.put("u", item.videoUrl)
        o.put("c", item.coverUrl)
        o.put("dur", item.duration)
        o.put("sec", item.secUid)
        o.put("digg", item.diggCount)
        o.put("cmt", item.commentCount)
        o.put("pt", item.isPortrait)
        o.put("ts", System.currentTimeMillis())
        return o
    }

    /** 崩溃自愈：播放中实时保存恢复点（同步 + 原子替换，进程瞬死也不丢） */
    fun saveResume(context: Context, json: String) {
        try {
            val tmp = File(context.filesDir, "resume.json.tmp")
            tmp.writeText(json)
            if (fResume(context).exists()) fResume(context).delete()
            tmp.renameTo(fResume(context))
        } catch (_: Throwable) {}
    }

    fun loadResume(context: Context): String = try {
        val f = fResume(context)
        if (f.exists()) f.readText() else ""
    } catch (_: Throwable) { "" }

    /** 播放中周期调用：只写几秒时间戳（几字节，同步写开销可忽略） */
    fun touchResumeAlive(context: Context) {
        try { fAlive(context).writeText(System.currentTimeMillis().toString()) } catch (_: Throwable) {}
    }

    fun resumeAliveAt(context: Context): Long = try {
        fAlive(context).readText().trim().toLong()
    } catch (_: Throwable) { 0L }

    /** 标记「接下来这次进程结束是计划内的自我重启」，不是崩溃 */
    fun markPlannedRestart(context: Context) {
        try { fPlanned(context).writeText(System.currentTimeMillis().toString()) } catch (_: Throwable) {}
    }

    fun plannedRestartAt(context: Context): Long = try {
        fPlanned(context).readText().trim().toLong()
    } catch (_: Throwable) { 0L }

    /** 连崩计数 +1，返回累加后的值（窗口外自动从 0 重算） */
    fun bumpCrashStreak(context: Context): Int {
        return try {
            val now = System.currentTimeMillis()
            val parts = try { fStreak(context).readText().split(",") } catch (_: Throwable) { emptyList<String>() }
            val lastAt = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val old = if (now - lastAt > CRASH_STREAK_WINDOW_MS) 0 else (parts.getOrNull(0)?.toIntOrNull() ?: 0)
            val next = old + 1
            fStreak(context).writeText("$next,$now")
            next
        } catch (_: Throwable) { 1 }
    }

    /** 连续播过一段健康时长后清零（证明这个引擎此刻是能用的） */
    fun resetCrashStreak(context: Context) {
        try { fStreak(context).delete() } catch (_: Throwable) {}
    }

    fun clearResume(context: Context) {
        try { fResume(context).delete() } catch (_: Throwable) {}
        try { fAlive(context).delete() } catch (_: Throwable) {}
    }

    /**
     * 恢复链路追踪：把「自杀 / 冷启动恢复」的每一步同步写进文件。
     * 电视没法接 logcat —— 这就是定位「到底回没回得来」的地面真值。
     */
    fun trace(context: Context, msg: String) {
        try {
            val f = fTrace(context)
            f.appendText(System.currentTimeMillis().toString() + " " + msg + "\n")
            if (f.length() > 16 * 1024) {
                val lines = f.readLines()
                f.writeText(lines.takeLast(80).joinToString("\n") + "\n")
            }
        } catch (_: Throwable) {}
    }

    fun normalize(raw: String): String {
        val t = raw.trim()
        if (!t.startsWith("[")) return t
        return try {
            val arr = JSONArray(t)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val n = o.optString("name", "")
                val v = o.optString("value", "")
                // 缺字段的条目必须跳过：否则会往 cookie 串里塞 "noValue=" 这种垃圾项，
                // 既污染 Cookie 又可能让抖音判成非法请求。
                if (n.isBlank() || !o.has("value")) continue
                if (sb.isNotEmpty()) sb.append("; ")
                sb.append(n).append('=').append(v)
            }
            sb.toString()
        } catch (e: Exception) {
            // 解析失败就原样落库，交给上层判断
            t
        }
    }
}
