package com.example.douyintv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 接口自检页 —— 用设备上已保存的 Cookie **实测**分类 / 搜索接口。
 *
 * <h3>为什么要有这一页</h3>
 * 分类要正确的 `tag_id`、搜索要正确的 endpoint + 参数组合。
 * 这些值不能靠猜：猜错了用户点开就是"没反应"，比不做更糟。
 * 本页把所有候选接口挨个真请求一遍，把 HTTP 码 / status_code / 实际条数
 * 直接显示在屏幕上（电视上看不到 logcat），跑一次就知道哪条路通。
 *
 * <h3>为什么不改 DouyinApi</h3>
 * 需求要求"不要改动网络解析"。本页只调用它的公开入口
 * （[DouyinApi.httpClient] / [DouyinApi.fetchFeed]）和已有的 [XBogus]，
 * 不去动既有解析逻辑 —— 新能力是"加"，不是"改"。
 *
 * <h3>签名自洽说明</h3>
 * X-Bogus 是对**我们自己组装的参数串**签的，只要 URL 里带的参数串
 * 与签名时用的完全一致，签名就是自洽的。
 * 所以本页试的是「endpoint + 参数组合」是否被服务端接受，
 * 而不是签名算法对不对（算法已在推荐流上验证过）。
 */
class ApiProbeActivity : Activity() {

    companion object {
        private const val TAG = "ApiProbe"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        private const val REFERER = "https://www.douyin.com/"
    }

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: LineAdapter
    private lateinit var tvStatus: TextView
    private lateinit var btnRerun: TextView
    private lateinit var placeholder: View
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvPlaceholder: TextView

    private val lines = ArrayList<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(R.layout.activity_api_probe)

            recycler = findViewById(R.id.recyclerView) as RecyclerView
            tvStatus = findViewById(R.id.tvStatus) as TextView
            btnRerun = findViewById(R.id.btnRerun) as TextView
            placeholder = findViewById(R.id.placeholder)
            pbLoading = findViewById(R.id.pbLoading) as ProgressBar
            tvPlaceholder = findViewById(R.id.tvPlaceholder) as TextView

            adapter = LineAdapter(lines)
            recycler.layoutManager = LinearLayoutManager(this)
            recycler.adapter = adapter
            recycler.isFocusable = true
            recycler.isFocusableInTouchMode = true

            btnRerun.setOnClickListener { runAll() }

            // 规则④：默认焦点给日志本体，↑↓ 直接滚动
            recycler.post { recycler.requestFocus() }

            runAll()
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate 失败", t)
        }
    }

    // ================================================================
    // 自检主流程
    // ================================================================
    private fun runAll() {
        if (running) return
        running = true
        lines.clear()
        adapter.notifyDataSetChanged()
        setLoading(true, "正在自检…")

        Thread {
            val cookie = Prefs.getCookie(this)
            add("Dtv · 接口自检")
            add("Cookie 长度: ${cookie.length} 字符")
            add("时间: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.getDefault()).format(java.util.Date()))
            add("")
            add("说明：每项给出 HTTP 码 / status_code / 拿到条数")
            add("———————————————————————————")

            stepBaseline(cookie)
            stepTabFeedNoTag(cookie)
            stepSearchA(cookie)
            stepSearchB(cookie)
            stepSearchC(cookie)

            add("")
            add("———————————————————————————")
            add("自检结束。把屏幕内容念给我 / 截图即可。")
            add("判定口径：")
            add("  · 「推荐流」若 0 条 → Cookie 或网络本身有问题，先解决这个")
            add("  · 哪个候选给出 status_code=0 且有数据 → 那就是可用的接口")
            running = false
            handler.post {
                setLoading(false, "")
                tvStatus.text = "自检完成，共 ${lines.size} 行　↑↓ 滚动"
            }
        }.start()
    }

    /** 0. 基线：既有推荐流。它通，才说明 Cookie/网络正常 */
    private fun stepBaseline(cookie: String) {
        add("")
        add("【0】基线 · 既有推荐流（证明 Cookie/网络可用）")
        val r = try {
            DouyinApi.fetchFeed(cookie, 0L, 12)
        } catch (e: Exception) {
            add("    异常: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (r == null) {
            add("    ✗ 失败 · lastError=${DouyinApi.lastError}")
        } else {
            add("    ✓ 返回 ${r.videos.size} 条, hasMore=${r.hasMore}")
            if (r.videos.isNotEmpty()) {
                val v = r.videos[0]
                add("    样例: 「${v.desc.take(18)}」 by @${v.author}")
            }
        }
    }

    /** 1. tab/feed 不带 tag_id —— 看它是否是分类流的正确入口 */
    private fun stepTabFeedNoTag(cookie: String) {
        add("")
        add("【1】/aweme/v1/web/tab/feed （不带 tag_id）")
        val params = commonParams() + "&count=12&refresh_index=1"
        val url = "https://www.douyin.com/aweme/v1/web/tab/feed"
        probe("tab/feed 无tag", url, params, cookie)
    }

    /** 2. 搜索候选 A：general/search/single */
    private fun stepSearchA(cookie: String) {
        add("")
        add("【2】搜索候选A · /aweme/v1/web/general/search/single/")
        val kw = URLEncoder.encode("猫", "UTF-8")
        val params = commonParams() +
            "&keyword=$kw&search_channel=aweme_general&search_source=normal_search" +
            "&query_correct_type=1&is_filter_search=0&offset=0&count=10"
        probe("搜索A", "https://www.douyin.com/aweme/v1/web/general/search/single/", params, cookie)
    }

    /** 3. 搜索候选 B：search/item */
    private fun stepSearchB(cookie: String) {
        add("")
        add("【3】搜索候选B · /aweme/v1/web/search/item/")
        val kw = URLEncoder.encode("猫", "UTF-8")
        val params = commonParams() +
            "&keyword=$kw&search_channel=aweme_general&search_source=normal_search" +
            "&offset=0&count=10"
        probe("搜索B", "https://www.douyin.com/aweme/v1/web/search/item/", params, cookie)
    }

    /** 4. 搜索候选 C：discover/search（另一套命名） */
    private fun stepSearchC(cookie: String) {
        add("")
        add("【4】搜索候选C · /aweme/v1/web/discover/search/")
        val kw = URLEncoder.encode("猫", "UTF-8")
        val params = commonParams() + "&keyword=$kw&offset=0&count=10"
        probe("搜索C", "https://www.douyin.com/aweme/v1/web/discover/search/", params, cookie)
    }

    /** 各接口共用的头部参数（与推荐流保持同一风格） */
    private fun commonParams(): String =
        "device_platform=webapp&aid=6383&channel=channel_pc_web" +
            "&pc_client_type=1&version_code=190500&version_name=19.5.0" +
            "&cookie_enabled=true&screen_width=1920&screen_height=1080" +
            "&browser_language=zh-CN&browser_platform=Win32" +
            "&browser_name=Chrome&browser_version=122.0.0.0"

    /**
     * 真正发一次请求并把结果写进日志。
     * 失败也要把原因原样显示 —— 这一页的价值就在于"不美化结果"。
     */
    private fun probe(tag: String, base: String, params: String, cookie: String) {
        val bogus = try {
            XBogus.generate(params, UA)
        } catch (e: Exception) {
            add("    ✗ XBogus 生成失败: ${e.message}")
            return
        }
        val url = "$base?$params&X-Bogus=$bogus"
        add("    URL: ${url.take(110)}…")

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
                .build()

            val resp = DouyinApi.httpClient().newCall(req).execute()
            resp.use { r ->
                val code = r.code()
                val body = try { r.body()?.string() ?: "" } catch (e: Exception) { "" }
                add("    HTTP $code · body ${body.length} 字符")

                if (body.isBlank()) {
                    add("    ✗ 空响应体")
                    return
                }
                // 抖音业务层状态码
                val statusCode = try {
                    JSONObject(body).optInt("status_code", -999)
                } catch (e: Exception) { -999 }
                add("    status_code=$statusCode")

                val count = countItems(body)
                add("    条目数=$count")
                if (count == 0) {
                    add("    前 160 字符: " + body.take(160).replace("\n", " "))
                } else {
                    add("    → 这条通！tag=$tag")
                }
            }
        } catch (e: Exception) {
            add("    ✗ 异常 ${e.javaClass.simpleName}: ${e.message}")
            Log.w(TAG, "probe $tag 失败", e)
        }
    }

    /** 数一数响应里最可能的列表字段有多少条 */
    private fun countItems(body: String): Int {
        return try {
            val o = JSONObject(body)
            var best = 0
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = o.opt(k)
                if (v is JSONArray && v.length() > best) best = v.length()
                // 有些接口把列表藏在 data 里
                if (v is JSONObject) {
                    val inner = v.keys()
                    while (inner.hasNext()) {
                        val ik = inner.next()
                        val iv = v.opt(ik)
                        if (iv is JSONArray && iv.length() > best) best = iv.length()
                    }
                }
            }
            best
        } catch (e: Exception) { 0 }
    }

    // ================================================================
    // UI 辅助
    // ================================================================
    private fun add(s: String) {
        handler.post {
            lines.add(s)
            adapter.notifyItemInserted(lines.size - 1)
            recycler.scrollToPosition(lines.size - 1)
        }
    }

    private fun setLoading(on: Boolean, text: String) {
        placeholder.visibility = if (on) View.VISIBLE else View.GONE
        recycler.visibility = if (on) View.GONE else View.VISIBLE
        pbLoading.visibility = if (on) View.VISIBLE else View.GONE
        tvPlaceholder.text = text
        if (!on) tvStatus.text = "就绪"
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            finish(); return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ================================================================
    // 极简日志适配器（条目不可聚焦 → D-Pad 滚动整个列表）
    // ================================================================
    private class LineAdapter(private val data: List<String>) :
        RecyclerView.Adapter<LineAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.probeLine) as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_probe_line, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.tv.text = data[position]
        }
    }
}
