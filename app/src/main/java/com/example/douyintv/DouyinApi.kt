package com.example.douyintv

import java.net.URLEncoder
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 抖音 Web API 客户端
 * 使用 X-Bogus 签名 (算法已用 Python 验证与官方 f2 实现一致)
 */
object DouyinApi {

    private const val TAG = "DouyinApi"

    /**
     * ⚠️ 路径末尾**不能有斜杠**！
     * 实测（各请求 4 次）：
     *   /aweme/v1/web/tab/feed/   → 稳定只返回 2 条  ← 带斜杠 = 被降级
     *   /aweme/v1/web/tab/feed    → 稳定返回 12 条   ← 正确
     * 且该推荐流**每次请求都返回全新内容（实测批次间零重叠）**，
     * 所以「无限刷」= 反复请求本接口，而不是靠 max_cursor 翻页。
     */
    private const val FEED_URL = "https://www.douyin.com/aweme/v1/web/tab/feed"
    private const val USER_POST_URL = "https://www.douyin.com/aweme/v1/web/aweme/post"
    private const val COMMENT_URL = "https://www.douyin.com/aweme/v1/web/comment/list"

    /** 各接口共用的尾部参数（★顺序敏感：签名串必须与实际请求串逐字一致） */
    private const val COMMON_TAIL =
        "&pc_client_type=1&version_code=170400&version_name=17.4.0" +
        "&cookie_enabled=true&screen_width=1920&screen_height=1080" +
        "&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome" +
        "&browser_version=122.0.0.0&os_name=Windows&os_version=10" +
        "&cpu_core_num=8&device_memory=8&platform=PC&downlink=10" +
        "&effective_type=4g&round_trip_time=50"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /** 互动接口专用 UA：对齐真实抓包（Edge 154 / Chromium 154）—— 签名与请求头必须一致 */
    private const val INTERACT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

    data class VideoItem(
        val awemeId: String,
        val desc: String,
        val author: String,
        val videoUrl: String,
        val coverUrl: String = "",
        val duration: Int = 0,
        val secUid: String = "",     // 作者 sec_uid —— 请求作者主页用
        val diggCount: Int = 0,      // 点赞数
        val commentCount: Int = 0,   // 评论数
        val topics: List<String> = emptyList(),  // 话题名（来自 text_extra）
        val altUrls: List<String> = emptyList(), // 备用播放地址（主链失效时依次重试）
        val isPortrait: Boolean = false,          // 竖屏视频 —— 卡片角标用（解析层按宽高判定）
        val ts: Long = 0                           // 观看时间戳（本地历史用，接口解析恒为 0）
    )

    /** 一条评论（含图片/视频评论的媒体与评论者主页信息） */
    data class CommentItem(
        val text: String,
        val user: String,
        val diggCount: Int = 0,
        val replyCount: Int = 0,
        val secUid: String = "",       // 评论者 sec_uid —— 点评论进作者主页
        val imageUrl: String = "",     // 评论图片（首图直链）
        val videoCover: String = ""    // 评论视频封面（有视频评论时）
    )

    data class FeedResult(
        val videos: List<VideoItem>,
        val maxCursor: Long,
        val hasMore: Boolean
    )

    private var strictClient: OkHttpClient? = null
    private var relaxedClient: OkHttpClient? = null

    /** 最近一次失败原因（供 UI 显示，电视上没法看 logcat） */
    @Volatile
    var lastError: String = ""
        private set

    /** 是否因老设备信任库过时而降级到宽松校验（诊断面板显示） */
    @Volatile
    var tlsDowngraded: Boolean = false
        private set

    /** 供播放器下载视频复用（带 TLS1.2 + 证书降级） */
    fun httpClient(): OkHttpClient = getClient()

    /**
     * 「不怕证书问题」的 client —— 电视 CA 库过期时用。
     * OkHttp 层会自动降级，但 ExoPlayer 若自己发请求（如解析 302）就拿不到这层保护，
     * 所以把宽松 client 也暴露出来供这类调用使用。
     */
    fun relaxedHttpClient(): OkHttpClient = try {
        getRelaxedClient()
    } catch (e: Exception) {
        Log.w(TAG, "宽松 client 获取失败，退回严格: ${e.message}")
        getClient()
    }

    private fun baseBuilder() = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cache(null)

    /** 正常客户端：走系统信任链校验 */
    private fun getClient(): OkHttpClient {
        strictClient?.let { return it }
        synchronized(this) {
            strictClient?.let { return it }
            val b = baseBuilder()
            try {
                val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    .apply { init(null as KeyStore?) }.trustManagers[0] as X509TrustManager
                Tls12SocketFactory.create(tm)?.let { b.sslSocketFactory(it, tm) }
            } catch (e: Exception) {
                Log.w(TAG, "TLS1.2 失败: ${e.message}")
            }
            return b.build().also { strictClient = it }
        }
    }

    /**
     * 宽松客户端：接受任意证书。
     *
     * 专治 Android 4.x 电视 —— 酷开的 CA 库存停留在出厂年份且无 GMS 更新，
     * 抖音证书链的根 CA 不在其中，握手报
     *   "Trust anchor for certification path not found"。
     * 换 TLS 协议栈(Conscrypt)没用，因为信任库仍读系统 CA。
     *
     * 仅在严格校验失败后兜底，正常设备（手机）永远走不到这里。
     */
    private fun getRelaxedClient(): OkHttpClient {
        relaxedClient?.let { return it }
        synchronized(this) {
            relaxedClient?.let { return it }
            val b = baseBuilder()
            try {
                val tm = RelaxedTrustManager()
                val ctx = SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
                b.sslSocketFactory(Tls12SocketFactory(ctx.socketFactory as SSLSocketFactory), tm)
                b.hostnameVerifier { _, _ -> true }
                Log.w(TAG, "宽松 TLS 已启用（老设备信任库过时）")
            } catch (e: Exception) {
                Log.e(TAG, "宽松 TLS 构建失败", e)
            }
            return b.build().also { relaxedClient = it }
        }
    }

    /** 是否证书/TLS 层问题（决定要不要降级） */
    private fun isTlsProblem(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth++ < 10) {
            if (e is SSLException || e is CertificateException) return true
            val m = e.message ?: ""
            if (m.contains("Trust anchor") || m.contains("CertPathValidator")) return true
            e = e.cause
        }
        return false
    }

    /** 发请求；证书校验失败时自动降级重试一次 */
    private fun exec(req: Request): Response = try {
        getClient().newCall(req).execute()
    } catch (e: Exception) {
        if (!isTlsProblem(e)) throw e
        Log.w(TAG, "严格 TLS 失败 → 宽松重试: ${e.message}")
        tlsDowngraded = true
        getRelaxedClient().newCall(req).execute()
    }

    /** 网络可达性探测（复用同样的降级逻辑，供诊断面板） */
    fun probe(url: String = "https://www.douyin.com/"): Boolean = try {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        val r = exec(req)
        val code = r.code()
        r.close()
        Log.d(TAG, "probe $url → HTTP $code (宽松=${tlsDowngraded})")
        code in 200..499
    } catch (e: Exception) {
        Log.w(TAG, "probe 失败: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    /**
     * 请求推荐 feed
     * 参数列表与签名必须完全一致（顺序敏感）
     */
    fun fetchFeed(cookie: String, maxCursor: Long = 0, count: Int = 12): FeedResult? {
        lastError = ""
        try {
            val params = buildParams(maxCursor, count)

            // 生成 X-Bogus 签名
            val xBogus = try {
                XBogus.generate(params, UA)
            } catch (e: Exception) {
                lastError = "X-Bogus 生成失败: ${e.message}"
                Log.e(TAG, "XBogus 生成失败: ${e.message}")
                return null
            }

            val fullUrl = "$FEED_URL?$params&X-Bogus=$xBogus"
            Log.d(TAG, "→ feed max_cursor=$maxCursor")
            Log.d(TAG, "  X-Bogus=$xBogus")
            Log.d(TAG, "  URL=${fullUrl.take(160)}")

            val req = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .header("Cookie", cookie)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()

            val resp = exec(req)
            val code = resp.code()
            val body = resp.body()?.string() ?: ""
            Log.d(TAG, "← HTTP $code body=${body.length}B")

            if (code != 200) {
                lastError = "抖音返回 HTTP $code"
                Log.e(TAG, "✗ HTTP $code")
                return null
            }
            if (body.isEmpty()) {
                lastError = "抖音返回空响应（签名或 Cookie 被拒）"
                Log.e(TAG, "✗ 空响应（签名或参数错误）")
                return null
            }

            return parseFeed(body)
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "fetchFeed 异常: ${e.javaClass.simpleName}: ${e.message}", e)
            return null
        }
    }

    /**
     * 构建请求参数（顺序与签名一致，不可改动）
     */
    private fun buildParams(maxCursor: Long, count: Int): String {
        return "device_platform=webapp" +
                "&aid=6383" +
                "&channel=channel_pc_web" +
                "&aweme_id=0" +
                "&max_cursor=$maxCursor" +
                "&locate_query=false" +
                "&show_live_replay_strategy=1" +
                "&need_time_list=1" +
                "&time_list_query=0" +
                "&whale_cut_token=" +
                "&cut_version=1" +
                "&count=$count" +
                "&publish_video_strategy_type=2" +
                "&pc_client_type=1" +
                "&version_code=170400" +
                "&version_name=17.4.0" +
                "&cookie_enabled=true" +
                "&screen_width=1920" +
                "&screen_height=1080" +
                "&browser_language=zh-CN" +
                "&browser_platform=Win32" +
                "&browser_name=Chrome" +
                "&browser_version=122.0.0.0" +
                "&os_name=Windows" +
                "&os_version=10" +
                "&cpu_core_num=8" +
                "&device_memory=8" +
                "&platform=PC" +
                "&downlink=10" +
                "&effective_type=4g" +
                "&round_trip_time=50"
    }

    // ============================================================
    // 作者主页作品  （实测：status_code=0, 20 条, max_cursor 有效）
    // ============================================================
    fun fetchUserPosts(cookie: String, secUid: String, maxCursor: Long = 0, count: Int = 20): FeedResult? {
        lastError = ""
        if (secUid.isBlank()) { lastError = "缺少作者 ID"; return null }
        return try {
            val params = "device_platform=webapp&aid=6383&channel=channel_pc_web" +
                    "&sec_user_id=$secUid&max_cursor=$maxCursor&locate_item_id=&count=$count" +
                    "&locate_query=false&show_live_replay_strategy=1&need_time_list=1&time_list_query=0" +
                    "&whale_cut_token=&cut_version=1&publish_video_strategy_type=2&from_user_page=1" +
                    "&update_version_code=170400" + COMMON_TAIL
            val xb = XBogus.generate(params, UA)
            if (xb.isEmpty()) { lastError = "签名失败"; return null }
            val req = Request.Builder()
                .url("$USER_POST_URL?$params&X-Bogus=$xb")
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/user/$secUid")
                .header("Cookie", cookie)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            val resp = exec(req)
            val code = resp.code()
            val body = resp.body()?.string() ?: ""
            Log.d(TAG, "← 主页 HTTP $code body=${body.length}B")
            if (code != 200 || body.isEmpty()) {
                lastError = "主页 HTTP $code"
                return null
            }
            parseFeed(body)
        } catch (e: Exception) {
            lastError = "主页: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "fetchUserPosts 失败", e)
            null
        }
    }

    // ============================================================
    // 评论列表  （实测：status_code=0, 19 条评论）
    // ============================================================
    fun fetchComments(cookie: String, awemeId: String, cursor: Long = 0, count: Int = 20): List<CommentItem>? {
        lastError = ""
        return try {
            val params = "device_platform=webapp&aid=6383&channel=channel_pc_web" +
                    "&aweme_id=$awemeId&cursor=$cursor&count=$count&item_type=0" +
                    "&insert_ids=&whale_cut_token=&cut_version=1&rcFT=" + COMMON_TAIL
            val xb = XBogus.generate(params, UA)
            if (xb.isEmpty()) { lastError = "签名失败"; return null }
            val req = Request.Builder()
                .url("$COMMENT_URL?$params&X-Bogus=$xb")
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/video/$awemeId")
                .header("Cookie", cookie)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            val resp = exec(req)
            val code = resp.code()
            val body = resp.body()?.string() ?: ""
            Log.d(TAG, "← 评论 HTTP $code body=${body.length}B")
            if (code != 200 || body.isEmpty()) {
                lastError = "评论 HTTP $code"
                return null
            }
            // ★ 改用系统 org.json —— 项目红线禁止 Gson/Moshi。
            //   org.json 随 Android 框架提供，零依赖、零方法数开销。
            val root = JSONObject(body)
            val arr = root.optJSONArray("comments") ?: return emptyList()
            val out = mutableListOf<CommentItem>()
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.optJSONObject(i) ?: continue
                    val userObj = o.optJSONObject("user")
                    // ★ 评论图片（images[].url_list[0]）
                    var imgUrl = ""
                    val imgs = o.optJSONArray("images")
                    if (imgs != null && imgs.length() > 0) {
                        val ul = imgs.optJSONObject(0)?.optJSONArray("url_list")
                        if (ul != null && ul.length() > 0) {
                            imgUrl = ul.optString(0, "").let { if (it.startsWith("//")) "https:$it" else it }
                        }
                    }
                    // ★ 评论视频封面（video.cover.url_list[0]）
                    var vidCover = ""
                    val cv = o.optJSONObject("video")?.optJSONObject("cover")?.optJSONArray("url_list")
                    if (cv != null && cv.length() > 0) {
                        vidCover = cv.optString(0, "").let { if (it.startsWith("//")) "https:$it" else it }
                    }
                    out.add(
                        CommentItem(
                            o.optString("text", ""),
                            userObj?.optString("nickname", "?") ?: "?",
                            o.optInt("digg_count", 0),
                            o.optInt("reply_comment_total", 0),
                            userObj?.optString("sec_uid", "") ?: "",
                            imgUrl,
                            vidCover
                        )
                    )
                } catch (_: Exception) {
                    // 单条解析失败不影响整页评论
                }
            }
            Log.d(TAG, "✓ 评论 ${out.size} 条")
            out
        } catch (e: Exception) {
            lastError = "评论: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "fetchComments 失败", e)
            null
        }
    }

    // ============================================================
    // 热搜榜  （★ 实测匿名可用：不需要 Cookie、status_code=0）
    // ============================================================
    // 这是本项目唯一无需登录态就能拿到真实内容的接口。
    // 搜索接口（general/search/single、search/item）需要 a_bogus 签名，
    // X-Bogus 一律被拦（status_code=2483），故搜索页以热搜词为入口。
    private const val HOT_URL = "https://www.douyin.com/aweme/v1/web/hot/search/list/"

    /** 一条热搜 */
    data class HotWord(val word: String, val hotValue: Long, val groupId: String = "")

    /** 热搜榜公共参数（与推荐流同一风格，顺序敏感） */
    private const val HOT_HEAD =
        "device_platform=webapp&aid=6383&channel=channel_pc_web" +
        "&pc_client_type=1&version_code=170400&version_name=17.4.0" +
        "&cookie_enabled=true&screen_width=1920&screen_height=1080" +
        "&browser_language=zh-CN&browser_platform=Win32" +
        "&browser_name=Chrome&browser_version=122.0.0.0" +
        "&os_name=Windows&os_version=10&cpu_core_num=8&device_memory=8&platform=PC" +
        "&downlink=10&effective_type=4g&round_trip_time=50"

    /**
     * 拉热搜榜。
     * @return null = 请求失败（lastError 有原因）；空列表 = 接口通但无数据
     */
    fun fetchHotSearch(): List<HotWord>? {
        lastError = ""
        try {
            val params = HOT_HEAD + "&detail_list=1"
            val xb = XBogus.generate(params, UA)
            if (xb.isEmpty()) { lastError = "签名失败"; return null }

            val req = Request.Builder()
                .url("$HOT_URL?$params&X-Bogus=$xb")
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()

            val resp = exec(req)
            val code = resp.code()
            val body = resp.body()?.string() ?: ""
            Log.d(TAG, "← 热搜 HTTP $code body=${body.length}B")
            if (code != 200 || body.isEmpty()) {
                lastError = "热搜 HTTP $code"
                return null
            }

            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return emptyList()
            val arr = data.optJSONArray("word_list") ?: return emptyList()

            val out = mutableListOf<HotWord>()
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.optJSONObject(i) ?: continue
                    val w = o.optString("word", "")
                    if (w.isEmpty()) continue
                    out.add(HotWord(w, o.optLong("hot_value", 0L), o.optString("group_id", "")))
                } catch (_: Exception) {
                    // 单条失败不影响整榜
                }
            }
            Log.d(TAG, "✓ 热搜 ${out.size} 条")
            return out
        } catch (e: Exception) {
            lastError = "热搜: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "fetchHotSearch 失败", e)
            return null
        }
    }

    // ============================================================
    // 搜索（a_bogus 签名）★ 移植 ABogus.kt 后新增
    // ============================================================
    // 实测结论（本地 Python 端用同一算法验证）：
    //   · 热搜榜 + a_bogus         → status_code=0  可用
    //   · 搜索建议 search/sug      → status_code=0  可用 ★
    //   · 综合搜索 general/search  → status_code=2483（需「有效登录态 + msToken + a_bogus」）
    // 所以搜索走「先真接口、失败自动降级」的渐进增强路线，不做假入口。
    private const val SEARCH_URL = "https://www.douyin.com/aweme/v1/web/general/search/single/"
    private const val SUG_URL = "https://www.douyin.com/aweme/v1/web/search/sug/"

    private const val SEARCH_HEAD =
        "device_platform=webapp&aid=6383&channel=channel_pc_web" +
        "&pc_client_type=1&version_code=170400&version_name=17.4.0" +
        "&cookie_enabled=true&screen_width=1920&screen_height=1080" +
        "&browser_language=zh-CN&browser_platform=Win32" +
        "&browser_name=Chrome&browser_version=122.0.0.0" +
        "&os_name=Windows&os_version=10&cpu_core_num=8&device_memory=8&platform=PC"

    /** 上次搜索是否打到了真实接口（用于界面如实提示数据来源） */
    @Volatile
    var lastSearchReal = false
        private set

    /**
     * 搜索联想词。实测不需要任何签名、也不需要登录态即可拿到候选。
     * @return null 表示请求失败
     */
    fun fetchSuggest(keyword: String): List<String>? {
        try {
            val kw = URLEncoder.encode(keyword, "UTF-8")
            // ★ 与搜索同理：不带任何签名（实测可用；带签名反而可能触发校验）
            val params = SEARCH_HEAD + "&keyword=" + kw + "&count=10"
            val req = Request.Builder()
                .url(SUG_URL + "?" + params)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .header("Accept", "application/json, text/plain, */*")
                .build()
            val body = exec(req).body()?.string() ?: ""
            if (body.isEmpty()) return null
            val root = JSONObject(body)
            if (root.optInt("status_code", -1) != 0) {
                Log.d(TAG, "联想词 status_code=${root.optInt("status_code")}")
                return null
            }
            val out = mutableListOf<String>()
            val arr = root.optJSONArray("sug_list")
                ?: root.optJSONArray("data") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val w = o.optString("content", "").ifEmpty { o.optString("word", "") }
                if (w.isNotEmpty()) out.add(w)
            }
            Log.d(TAG, "✓ 联想词 ${out.size} 条")
            return out
        } catch (e: Exception) {
            Log.e(TAG, "fetchSuggest 失败", e)
            return null
        }
    }

    /**
     * 综合搜索（真实接口）★★ 关键：不要带任何签名 ★★
     *
     * 本地实测（同一登录 Cookie、同一参数集，三组对照）：
     *   · 不带签名            → status_code=0，10 条真实结果   ✅
     *   · 带空 a_bogus=       → search_nil_type=verify_check，0 条 ❌
     *   · 带任意 a_bogus 值   → search_nil_type=verify_check，0 条 ❌
     *   · 不带 Cookie         → status_code=2483，0 条          ❌
     *
     * 也就是说：抖音对「声明了签名但签不对」的请求反而更严格；干净的不带签名
     * 请求会按登录态放行。所以这里绝不加 a_bogus / X-Bogus —— 加了就是自找
     * verify_check。（这个结论推翻了此前"必须自产 a_bogus"的判断。）
     *
     * @return null = 请求失败或没有结果，调用方降级处理
     */
    fun searchVideos(cookie: String, keyword: String, offset: Int = 0): FeedResult? {
        lastSearchReal = false
        lastError = ""
        try {
            val kw = URLEncoder.encode(keyword, "UTF-8")
            val params = SEARCH_HEAD +
                "&search_channel=aweme_general&enable_history=1&keyword=" + kw +
                "&search_source=normal_search&query_correct_type=1&is_filter_search=0" +
                "&offset=" + offset + "&count=20"

            val req = Request.Builder()
                .url(SEARCH_URL + "?" + params)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .apply { if (cookie.isNotEmpty()) header("Cookie", cookie) }
                .build()

            val resp = exec(req)
            val body = resp.body()?.string() ?: ""
            Log.d(TAG, "← 搜索 HTTP ${resp.code()} body=${body.length}B")
            if (body.isEmpty()) {
                lastError = "搜索响应为空（Cookie 可能失效）"
                return null
            }

            val root = JSONObject(body)
            val sc = root.optInt("status_code", -1)
            if (sc != 0) {
                lastError = "搜索 status_code=$sc" + (if (sc == 2483) "（需要登录 Cookie）" else "")
                Log.d(TAG, "搜索被拒: $lastError")
                return null
            }
            val nilInfo = (root.optJSONObject("search_nil_info") ?: JSONObject())
                .optString("search_nil_type", "")
            if (nilInfo == "verify_check") {
                lastError = "触发风控 verify_check（Cookie 需更新）"
                return null
            }

            val arr = root.optJSONArray("data") ?: return FeedResult(emptyList(), 0L, false)
            val wrapped = JSONArray()
            for (i in 0 until arr.length()) {
                try {
                    val aw = arr.optJSONObject(i)?.optJSONObject("aweme_info") ?: continue
                    wrapped.put(aw)
                } catch (_: Exception) {
                }
            }
            if (wrapped.length() == 0) {
                lastError = "搜索无结果"
                return null
            }
            val fake = JSONObject()
            fake.put("status_code", 0)
            fake.put("aweme_list", wrapped)
            val fr = parseFeed(fake.toString())
            lastSearchReal = fr != null && fr.videos.isNotEmpty()
            Log.d(TAG, "✓ 搜索「$keyword」${fr?.videos?.size ?: 0} 条")
            return fr
        } catch (e: Exception) {
            lastError = "搜索失败: ${e.javaClass.simpleName}"
            Log.e(TAG, "searchVideos 失败", e)
            return null
        }
    }

    /**
     * 从 video.bit_rate[] 里挑【码率最低】的一档，返回 (主链, 该档其余地址)。
     *
     * 为什么需要它：URL 上的 ratio 参数实测被抖音忽略（五种取值返回同一文件），
     * 唯一能真正降码率的就是 bit_rate 各档自己的 play_addr。
     * 老设备上把 2.79Mbps 换成 0.96Mbps，是「能不能流畅播」的分水岭。
     *
     * @return null 表示这条视频没有 bit_rate 数组，调用方走原策略
     */
    /** 诊断用：最近一次选中档的码率与档名（播放页把它打在屏幕上，用来判断是否网络喂不饱） */
    object Diag {
        @Volatile var lastBitrate: Int = 0
        @Volatile var lastGear: String = ""
    }

    /**
     * 从 video.bit_rate[] 里挑一档，返回 (主链, 该档其余地址)。
     *
     * ★★★ 必须跳过 HEVC 档 ★★★（真机实测得出的结论）
     *   设备：创维 8S52 / MStar "nike" 平台 / Android 4.2。
     *   它的硬解清单里只有 OMX.MS.AVC.Decoder(H.264)、MVC、VP8、VC1、AVS……
     *   —— 没有任何 HEVC 解码器。
     *   而抖音同一条视频的不同档编码并不一致（接口里 is_h265 / is_bytevc1 标明）：
     *       540_3_1       0.96Mbps → HEVC(hvc1)   ← 之前"选最低码率"正好挑中它
     *       normal_1080_0 4.57Mbps → H.264(avc1)
     *   真机 logcat 铁证（选到 HEVC 档时的表现）：
     *       E/IJKMEDIA: mediacodec_select_callback: onSelectCodec failed
     *       E/IJKMEDIA: amc: no suitable codec
     *       I/IJKMEDIA: VideoCodec: avcodec, hevc     ← 回落 FFmpeg 软解
     *       结果就是卡 + 音画不同步（软解 HEVC 在四核 A7 上根本追不上音频）。
     *   所以：只在 H.264 档里挑最低码率；全为 HEVC 时才退回不限编码（至少能播）。
     *
     * 另：接口参数 support_h265=0 实测无效（加了返回内容一模一样），
     *     真正的开关就是这里的 is_h265 字段。
     *
     * @return null 表示没有 bit_rate 数组，调用方走原策略
     */
    /**
     * 选播放地址。返回 (主链, 备用链列表)；null 表示没有 bit_rate，调用方走原策略。
     *
     * 三条真机验证过的规则（创维 8S52 / MStar nike / Android 4.2）：
     *
     * ① 必须避开 HEVC —— 该机硬解只有 OMX.MS.AVC.Decoder(H.264)，没有 HEVC。
     *    选到 HEVC 档时的 logcat：
     *        E/IJKMEDIA: amc: no suitable codec
     *        I/IJKMEDIA: VideoCodec: avcodec, hevc   ← 回软解，卡 + 音画不同步
     *    接口里 is_h265 / is_bytevc1 标明编码，据此过滤。
     *
     * ② 必须避开「纯视频流」—— 这是"大部分视频没声音"的根因。
     *    bit_rate[] 各档的 play_addr 有两种形态：
     *        .../media-video-avc1/?...   →  只有视频轨！用了就没声音
     *        .../media-audio-und-mp4a/   →  只有音频轨
     *    而主链 play_addr 是音视频复用的（探测确认含 AAC 音轨）。
     *    真机日志（选到纯视频档时）：
     *        V/IJKMEDIA: setDataSource: path .../media-video-avc1/?...&br=428
     *    所以优先挑「音视频复用」的地址，其次主链兜底。
     *
     * ③ 码率仍取较低者（省带宽+省解码），但只在满足①②的候选里比。
     */
    private fun pickLowestBitrate(videoObj: JSONObject): Pair<String, List<String>>? {
        val arr = videoObj.optJSONArray("bit_rate") ?: return null
        if (arr.length() == 0) return null

        fun urlsOf(o: JSONObject): List<String> {
            val ul = o.optJSONArray("url_list") ?: return emptyList()
            val out = ArrayList<String>()
            for (k in 0 until ul.length()) {
                var u = ul.optString(k, "")
                if (u.startsWith("//")) u = "https:$u"
                if (u.startsWith("http")) out.add(u)
            }
            return out
        }

        // 是否纯视频流（无音轨的 URL）
        fun isVideoOnly(us: List<String>): Boolean =
            us.isNotEmpty() && us[0].contains("media-video-")
        fun isAudioOnly(us: List<String>): Boolean =
            us.isNotEmpty() && us[0].contains("media-audio-")

        var muxRate = Int.MAX_VALUE; var muxUrls: List<String>? = null; var muxGear = ""
        var vidRate = Int.MAX_VALUE; var vidUrls: List<String>? = null; var vidGear = ""

        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val br = g.optInt("bit_rate", Int.MAX_VALUE)
            if (br <= 0) continue
            // ① 跳过 HEVC：本机硬解不支持
            if (g.optInt("is_h265", 0) == 1 || g.optInt("is_bytevc1", 0) == 1) continue
            val us = urlsOf(g.optJSONObject("play_addr") ?: continue)
            if (us.isEmpty() || isAudioOnly(us)) continue
            val gear = g.optString("gear_name", "")
            if (isVideoOnly(us)) {
                // ② 纯视频流：能硬解但没声音，只作最后兜底
                if (br < vidRate) { vidRate = br; vidUrls = us; vidGear = gear }
            } else {
                // ★ 82.x: 档位按机型分流（不再写死 540p）：
                //   · 老设备（软解）：优先 540p/480p —— 软解扛得住、画质可接受；
                //   · 正常设备（硬解）：优先 1080p/720p —— 硬解无压力，高清优先。
                //   目标档内取码率最低者（省带宽）；无目标档时回落最低码率。
                val preferHi = !DeviceProfile.isLegacy
                val t1 = if (preferHi) "1080" else "540"
                val t2 = if (preferHi) "720" else "480"
                val isTarget = gear.contains(t1) || gear.contains(t2)
                if (isTarget && br < muxRate) { muxRate = br; muxUrls = us; muxGear = gear }
                else if (!isTarget && !muxGear.contains(t1) && !muxGear.contains(t2) && br < muxRate) {
                    muxRate = br; muxUrls = us; muxGear = gear
                }
            }
        }

        // ③ 首选音视频复用；实在没有才用纯视频（至少画面能动）
        //    另外把主链 play_addr 也塞进备选（它通常是复用的，且能兜住时效）
        val main = urlsOf(videoObj.optJSONObject("play_addr") ?: JSONObject())

        val pick: List<String>
        val gear: String
        val rate: Int
        if (muxUrls != null) {
            pick = muxUrls; gear = muxGear; rate = muxRate
        } else if (main.isNotEmpty()) {
            pick = main; gear = "主链"; rate = 0
        } else if (vidUrls != null) {
            pick = vidUrls; gear = vidGear + "(纯视频,无声)"; rate = vidRate
        } else {
            return null
        }

        Diag.lastBitrate = rate
        Diag.lastGear = gear
        Log.d(TAG, "选档: " + gear + " " + rate + "bps 主链=" + pick.first().take(70))
        return pick.first() to (pick.drop(1) + main).distinct().filter { it != pick.first() }
    }

    private fun parseFeed(json: String): FeedResult? {
        try {
            // ★ 系统 org.json（红线禁 Gson）
            val root = JSONObject(json)

            val status = root.optInt("status_code", -1)
            Log.d(TAG, "status_code=$status")
            if (status != 0) {
                val msg = root.optString("status_msg", "")
                lastError = "抖音 status_code=$status, msg=$msg"
                Log.e(TAG, "✗ 状态码异常: $status, msg=$msg")
                return null
            }

            val list = root.optJSONArray("aweme_list")
            if (list == null || list.length() == 0) {
                lastError = "aweme_list 为空（Cookie 可能被风控）"
                Log.e(TAG, "✗ aweme_list 为空")
                return null
            }

            // ★ 横屏过滤开关（用户要求）：竖屏视频在电视上左右大片黑边
            val landscapeOnly = App.landscapeOnly()
            var skippedPortrait = 0

            val videos = mutableListOf<VideoItem>()
            for (i in 0 until list.length()) {
                try {
                    val obj = list.optJSONObject(i) ?: continue
                    val awemeId = obj.optString("aweme_id", "")
                    if (awemeId.isEmpty()) continue
                    val desc = obj.optString("desc", "")
                    val authorObj = obj.optJSONObject("author")
                    val author = authorObj?.optString("nickname", "") ?: ""
                    val secUid = authorObj?.optString("sec_uid", "") ?: ""
                    val stats = obj.optJSONObject("statistics")
                    val digg = stats?.optInt("digg_count", 0) ?: 0
                    val cmtCount = stats?.optInt("comment_count", 0) ?: 0

                    // 话题标签（text_extra 里 type=1 的是话题）
                    val topics = mutableListOf<String>()
                    val te = obj.optJSONArray("text_extra")
                    if (te != null) {
                        for (k in 0 until te.length()) {
                            val n = te.optJSONObject(k)?.optString("hashtag_name", "") ?: ""
                            if (n.isNotEmpty() && n !in topics) topics.add(n)
                        }
                    }

                    val videoObj = obj.optJSONObject("video") ?: continue

                    // ★ 横屏过滤：视频真实宽高在 video.width / video.height
                    val vw = videoObj.optInt("width", 0)
                    val vh = videoObj.optInt("height", 0)
                    if (landscapeOnly && vw > 0 && vh > 0 && vh > vw) {
                        skippedPortrait++
                        continue
                    }

                    val playAddr = videoObj.optJSONObject("play_addr") ?: continue
                    val urlList = playAddr.optJSONArray("url_list") ?: continue
                    if (urlList.length() == 0) continue

                    // 选链策略（保守，回到用户认可的那版行为）：
                    //   **优先 /aweme/v1/play/** —— 它是接口 URL，
                    //   每次请求都 302 到**新鲜**的 CDN 地址；
                    //   而 url_list 里的直链是**带时效签名**的，放久了会失效
                    //   （这正是"刷一会儿就不放了"的来源）。
                    //   其余链接留作备用，供用户手动「换线路」。
                    val candidates = ArrayList<String>()
                    for (k in 0 until urlList.length()) {
                        var u = urlList.optString(k, "")
                        if (u.startsWith("//")) u = "https:$u"
                        if (u.startsWith("http")) candidates.add(u)
                    }
                    if (candidates.isEmpty()) continue

                    // ★★★ 老设备流畅度关键：从 bit_rate 里选【最低码率档】★★★
                    //   为什么不是改 URL 里的 ratio：实测无效 ——
                    //   同一条视频用 ratio=1080p/720p/540p/480p/360p 去请求，
                    //   抖音返回的是【同一个 42.61MB 的文件】，它根本不看这个参数。
                    //   真正能换清晰度的只有 video.bit_rate[] 里各档自带的 play_addr：
                    //   实测同一条视频有 20 档，码率从 0.96Mbps 到 4.57Mbps
                    //   （体积 15.4MB ~ 73MB）。老盒子（Cortex-A7 软解 + 小内存）
                    //   拉 2.79Mbps 那档必然卡；换最低档等于把带宽和解码压力同时砍到 1/3。
                    //   代价是画质下降 —— 但「能流畅播」优先于「更清晰」。
                    //   原始高清链仍留在备用线路里，用户按「换线路」可以切回去。
                    val lowPick = pickLowestBitrate(videoObj)

                    val videoUrl: String
                    val altUrls: List<String>
                    if (lowPick != null) {
                        videoUrl = lowPick.first
                        altUrls = (lowPick.second + candidates)
                            .distinct().filter { it != videoUrl }
                    } else {
                        // 没有 bit_rate 的（老视频等）退回原来的候选策略
                        videoUrl = candidates.firstOrNull { it.contains("/aweme/v1/play/") }
                            ?: candidates.firstOrNull {
                                it.contains("douyinvod.com") && !it.contains("web-prime")
                            } ?: candidates.first()
                        altUrls = candidates.filter { it != videoUrl }
                    }

                    val cover = videoObj.optJSONObject("cover")
                        ?.optJSONArray("url_list")
                        ?.let { if (it.length() > 0) it.optString(0, "") else "" } ?: ""
                    val duration = videoObj.optInt("duration", 0)

                    videos.add(VideoItem(awemeId, desc, author, videoUrl, cover, duration,
                                         secUid, digg, cmtCount, topics, altUrls,
                                         isPortrait = vh > vw))
                } catch (e: Exception) {
                    // 单条失败不影响整页
                    Log.w(TAG, "解析单条失败: ${e.message}")
                }
            }

            if (skippedPortrait > 0) {
                Log.d(TAG, "横屏过滤：跳过 $skippedPortrait 条竖屏视频")
            }

            val maxCursor = root.optLong("max_cursor", 0)
            val hasMore = root.optInt("has_more", 0) == 1
            Log.d(TAG, "✓ 解析 ${videos.size} 条视频, has_more=$hasMore, max_cursor=$maxCursor")
            return FeedResult(videos, maxCursor, hasMore)
        } catch (e: Exception) {
            Log.e(TAG, "parseFeed 异常: ${e.message}", e)
            return null
        }
    }

    // ============================================================
    // 手机推送到电视：分享链接解析 + 单视频详情
    // ============================================================
    /** 从分享文本里抠出首个 http(s) 链接（v.douyin.com 短链 / www.douyin.com 长链都行） */
    fun extractShareUrl(raw: String): String? {
        val m = Regex("""https?://[^\s，。；、)）】】]+""").find(raw) ?: return null
        return m.value.trim()
    }

    /** 跟随重定向取最终地址（OkHttp 自动跟随 302；短链 v.douyin.com → www.douyin.com/video/…） */
    fun resolveShareUrl(url: String): String {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .build()
            val resp = exec(req)
            val finalUrl = resp.request().url().toString()
            resp.close()
            Log.d(TAG, "分享链接解析: $url → $finalUrl")
            finalUrl
        } catch (e: Exception) {
            Log.e(TAG, "分享链接解析失败: ${e.message}")
            url
        }
    }

    /** 从最终 URL 里抠 aweme_id（/video/、/note/、/share/video/、aweme_id= 均支持） */
    fun extractAwemeId(url: String): String? {
        for (pat in listOf(
            Regex("""/(?:video|note|share/video|share/note)/(\d{5,})"""),
            Regex("""aweme_id=(\d{5,})"""),
            Regex("""modal_id=(\d{5,})""")
        )) {
            pat.find(url)?.let { return it.groupValues[1] }
        }
        return null
    }

    /** 按 aweme_id 拉单条视频详情（复用 parseFeed 的条目解析） */
    fun fetchVideoDetail(cookie: String, awemeId: String): VideoItem? {
        lastError = ""
        return try {
            val params = "device_platform=webapp&aid=6383&channel=channel_pc_web" +
                "&aweme_id=$awemeId" + COMMON_TAIL
            val xb = try {
                XBogus.generate(params, UA)
            } catch (e: Exception) {
                lastError = "签名失败: ${e.message}"
                return null
            }
            val fullUrl = "https://www.douyin.com/aweme/v1/web/aweme/detail?$params&X-Bogus=$xb"
            Log.d(TAG, "→ detail aweme_id=$awemeId")
            val req = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .header("Cookie", cookie)
                .header("Accept", "application/json, text/plain, */*")
                .build()
            val resp = exec(req)
            val code = resp.code()
            val body = resp.body()?.string() ?: ""
            if (code != 200 || body.isEmpty()) {
                lastError = "detail HTTP $code"
                return null
            }
            val root = JSONObject(body)
            val detail = root.optJSONObject("aweme_detail")
                ?: run { lastError = "无 aweme_detail: ${root.optString("status_msg", "")}"; return null }
            val wrapped = JSONObject()
                .put("status_code", 0)
                .put("aweme_list", JSONArray().put(detail))
            parseFeed(wrapped.toString())?.videos?.firstOrNull()
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }

    // 互动操作（点赞/收藏/评论）★ 写操作 ★
    // 真实抓包结构（用户提供的 DevTools 抓包，2026-10-09）：
    //   POST /aweme/v1/web/commit/item/digg/?
    //        device_platform…round_trip_time=100
    //        &webid=<19位>&uifid=<长串>&msToken=<长串>&a_bogus=<签>&verifyFp=<s_v_web_id>
    //        &fp=<同上>&uid=<32位hex>
    //   body: application/x-www-form-urlencoded → aweme_id=<id>&item_type=0&type=1|0
    //   ★ a_bogus 签的就是上面那一整串 query（不含 a_bogus 自己），
    //     所以生成签名用的 UA 必须与实际发送的 UA 完全一致（INTERACT_UA）。
    //   响应：{status_code:0, is_digg:0|1}；status_code≠0 时 status_msg 给出原因。

    data class InteractResult(val ok: Boolean, val msg: String)

    fun diggVideo(cookie: String, awemeId: String, digg: Boolean): InteractResult =
        interactPostForm("https://www.douyin.com/aweme/v1/web/commit/item/digg/", cookie,
            "aweme_id=" + awemeId + "&item_type=0&type=" + (if (digg) "1" else "0"))

    fun collectVideo(cookie: String, awemeId: String, collect: Boolean): InteractResult =
        interactPostForm("https://www.douyin.com/aweme/v1/web/commit/item/collect/", cookie,
            "aweme_id=" + awemeId + "&type=" + (if (collect) "1" else "0"))

    /**
     * 互动接口的公共浏览器指纹参数（字段顺序照抄抓包）。
     * a_bogus 是对这一整串做的签名，因此调用方必须原样拼进 URL。
     *
     * ★ 值必须用【登录浏览器 Cookie 里的真实 webid / msToken / uid】：
     *   抖音把这些凭据写进 Cookie，本地随机串会被风控判定异常 → 踢下线网页端。
     *   Cookie 缺失时才退到 Prefs 的本地生成值（provisionInteractCreds 会先尝试补齐）。
     */
    private fun interactParams(cookie: String): String {
        val uifid = uifidOf(cookie)
        val webid = Prefs.cookieValue(cookie, "webid").ifBlank { Prefs.getWebid() }
        val msToken = Prefs.cookieValue(cookie, "msToken").ifBlank { Prefs.getMsToken() }
        val uid = listOf("uid", "uid_tt").map { Prefs.cookieValue(cookie, it) }
            .firstOrNull { it.isNotEmpty() } ?: Prefs.getUid()
        val fp = verifyFp(cookie)
        return "device_platform=webapp&aid=6383&channel=channel_pc_web" +
            "&pc_client_type=1&pc_libra_divert=Windows&update_version_code=170400" +
            "&support_h265=1&support_dash=1&version_code=170400&version_name=17.4.0" +
            "&cookie_enabled=true&screen_width=1920&screen_height=1080" +
            "&browser_language=zh-CN&browser_platform=Win32&browser_name=Edge" +
            "&browser_version=154.0.0.0&browser_online=true" +
            "&engine_name=Blink&engine_version=154.0.0.0" +
            "&os_name=Windows&os_version=10&cpu_core_num=4&device_memory=8" +
            "&platform=PC&downlink=10&effective_type=4g&round_trip_time=100" +
            "&webid=" + webid + "&uifid=" + uifid +
            (if (msToken.isNotEmpty()) "&msToken=" + msToken else "") +
            (if (fp.isNotEmpty()) "&verifyFp=" + fp + "&fp=" + fp else "") +
            (if (uid.isNotEmpty()) "&uid=" + uid else "")
    }

    /**
     * 写操作的 UA：优先浏览器真实 UA（推送页 JS 捕获的 navigator.userAgent），
     * 没有则用 Edge/Chromium 154 兜底。a_bogus 必须用同一个 UA 生成。
     */
    private fun interactUA(): String = Prefs.getBrowserUa().ifBlank { INTERACT_UA }

    /**
     * Cookie 缺真实 webid / msToken 时的补救：以浏览器的 UA + Cookie 的身份
     * GET www.douyin.com 首页，从 Set-Cookie 里取服务端签发的 webid / msToken，
     * 合并进 Cookie 串。成功即返回补齐后的 Cookie（调用方回写 Prefs）。
     */
    private fun provisionInteractCreds(cookie: String): String {
        try {
            val req = Request.Builder()
                .url("https://www.douyin.com/")
                .header("User-Agent", interactUA())
                .header("Cookie", cookie)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            val resp = exec(req)
            val got = HashMap<String, String>()
            for (sc in resp.headers("Set-Cookie")) {
                val kv = sc.substringBefore(";").trim()
                val i = kv.indexOf('=')
                if (i > 0) got[kv.substring(0, i).trim()] = kv.substring(i + 1)
            }
            resp.close()
            var out = cookie
            for (k in listOf("msToken", "webid")) {
                val v = got[k]
                if (!v.isNullOrEmpty() && Prefs.cookieValue(out, k).isEmpty()) {
                    out = if (out.isBlank()) "$k=$v" else "$out; $k=$v"
                }
            }
            if (out != cookie) Log.d(TAG, "provision: 补齐 webid/msToken 成功")
            return out
        } catch (e: Exception) {
            Log.e(TAG, "provision 失败: " + e.message)
            return cookie
        }
    }

    /** UIFID：优先 cookie 里的 UIFID，退而取 UIFID_TEMP */
    private fun uifidOf(cookie: String): String {
        val a = cookieValue(cookie, "UIFID")
        return if (a.isNotEmpty()) a else cookieValue(cookie, "UIFID_TEMP")
    }

    fun createComment(cookie: String, awemeId: String, text: String, replyTo: String = ""): InteractResult {
        val kw = URLEncoder.encode(text, "UTF-8")
        val extra = if (replyTo.isBlank()) "" else "&reply_to_comment_id=" + replyTo
        return interact("https://www.douyin.com/aweme/v1/web/comment/create", cookie,
            "aweme_id=" + awemeId + "&text=" + kw + extra)
    }

    private fun interactPostForm(url: String, cookie: String, formBody: String): InteractResult {
        try {
            // ★ 写操作前：Cookie 若缺真实 webid/msToken，先用浏览器身份补一份，
            //   避免本地随机串触发风控踢下线网页端。
            var c = cookie
            if (Prefs.cookieValue(c, "webid").isEmpty() || Prefs.cookieValue(c, "msToken").isEmpty()) {
                c = provisionInteractCreds(c)
                if (c != cookie) {
                    try { Prefs.setCookie(App.appContext()!!, c) } catch (_: Exception) {}
                }
            }
            val ua = interactUA()
            val params = interactParams(c)
            // ★ a_bogus 对整个 query 签名；生成用的 UA 必须与发送 UA 一致
            val ab = ABogus(ua).generate(params)
            if (ab.isEmpty()) return InteractResult(false, "a_bogus 签名失败")
            val req = Request.Builder()
                .url(url + "?" + params + "&a_bogus=" + ab)
                .header("User-Agent", ua)
                .header("Referer", "https://www.douyin.com/")
                .header("Origin", "https://www.douyin.com")
                .header("Cookie", c)
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("uifid", uifidOf(c))
                .post(okhttp3.RequestBody.create(
                    okhttp3.MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8"), formBody))
                .build()
            val rb = req.newBuilder()
            // ★ 写接口的 CSRF 防伪：x-secsdk-csrf-token 取 cookie 里的 passport_csrf_token
            //   （浏览器端由 sec-sdk 自动带上，缺失会导致 403 / verify 校验失败）
            val csrf = cookieValue(c, "passport_csrf_token")
            if (csrf.isNotEmpty()) rb.header("x-secsdk-csrf-token", csrf)
            // ★ WebProtect / bd_ticket_guard 层（凭据导入齐全才发）。
            //   头名按公开资料实现，待真机抓包确认后微调（见 WebProtectSigner 注释）。
            if (Prefs.hasWebProtect()) {
                try {
                    val path = java.net.URL(url).path
                    val gd = WebProtectSigner.buildClientData(path)
                    if (gd != null) {
                        rb.header("x-bd-ticket-guard-client-data", gd)
                        rb.header("x-bd-ticket-guard-web-version", "2")
                        rb.header("x-bd-ticket-guard-iteration-version", "1")
                        val pub = Prefs.getServerCert()
                        if (pub.isNotBlank()) rb.header("x-bd-ticket-guard-ree-public-key", pub)
                        Log.d(TAG, "已附加 bd_ticket_guard 头, 数据长度=${gd.length}")
                    } else Log.w(TAG, "WebProtect 凭据不完整，跳过 guard 头")
                } catch (e: Exception) {
                    Log.w(TAG, "guard 头构造失败: ${e.message}")
                }
            }
            return parseInteract(exec(rb.build()))
        } catch (e: Exception) {
            Log.e(TAG, "互动失败: " + e.message, e)
            return InteractResult(false, e.javaClass.simpleName + ": " + e.message)
        }
    }

    private fun interact(url: String, cookie: String, bodyParams: String): InteractResult {
        try {
            val ua = interactUA()
            val params = bodyParams + COMMON_TAIL + "&verifyFp=" + verifyFp(cookie)
            val ab = ABogus(ua).generate(params)
            if (ab.isEmpty()) return InteractResult(false, "签名失败")
            val req = Request.Builder()
                .url(url + "?" + params + "&a_bogus=" + ab)
                .header("User-Agent", ua)
                .header("Referer", "https://www.douyin.com/")
                .header("Cookie", cookie)
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                .build()
            return parseInteract(exec(req))
        } catch (e: Exception) {
            Log.e(TAG, "互动失败: " + e.message, e)
            return InteractResult(false, e.javaClass.simpleName + ": " + e.message)
        }
    }

    private fun parseInteract(resp: okhttp3.Response): InteractResult {
        try {
            val code = resp.code()
            val body = resp.body()?.string() ?: ""
            Log.d(TAG, "← 互动 HTTP $code body=" + body.take(200))
            if (code != 200 || body.isEmpty()) return InteractResult(false, "HTTP $code 空响应")
            val root = JSONObject(body)
            val sc = root.optInt("status_code", -1)
            val msg = root.optString("status_msg", "").ifBlank { "status_code=$sc" }
            return if (sc == 0) InteractResult(true, "成功")
            else InteractResult(false, msg.ifBlank { "status_code=$sc" })
        } catch (e: Exception) {
            return InteractResult(false, "解析失败: " + e.message)
        }
    }

    private fun cookieValue(cookie: String, key: String): String =
        cookie.split(";").map { it.trim() }.firstOrNull { it.startsWith(key + "=") }
            ?.substringAfter("=") ?: ""

    private fun verifyFp(cookie: String): String = cookieValue(cookie, "s_v_web_id")
}
