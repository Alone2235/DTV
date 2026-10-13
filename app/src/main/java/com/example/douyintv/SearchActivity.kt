package com.example.douyintv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 搜索页（三段式：键盘 / 联想 / 结果）
 * ====================================
 *  ① 左栏：输入框（+ 清空 / 退格）+ 6 列字母数字键盘
 *  ② 中栏：猜你想搜（实时联想，点一下直接搜）
 *  ③ 右栏：结果列表（海报 + 标题，滚到底自动翻页）
 *
 * ★ 键盘只要字母数字 ★
 *   实测抖音搜索接口认拼音（打 meishi 自己纠错成美食），联想接口还会直接返回
 *   中文候选（meishi → 美食教程 / 美食测评），所以不需要内置拼音词库。
 *   老电视盒子基本没有输入法，键盘是自绘的，遥控器上下左右 + OK 就能打字。
 *
 * ★ 搜索不带签名 ★
 *   带 a_bogus 反而触发 verify_check；干净请求按登录 Cookie 放行（实测见 DouyinApi）。
 *
 * 与热搜榜彻底分开：热搜在独立的 HotActivity（左侧导航「热搜」）。
 */
class SearchActivity : Activity() {

    private companion object {
        const val TAG = "Search"
        const val SUG_DELAY = 320L          // 联想防抖：老设备别每敲一下就发请求
        const val SPAN_RESULT = 1           // 结果用竖向列表（横向卡片）

        /** 6 列键盘：照参考图，字母 5 行 + 数字 1 行 */
        val KEY_ROWS = listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ1234", "567890")
    }

    // 左栏
    private lateinit var etKeyword: EditText
    private lateinit var btnClear: TextView
    private lateinit var btnBack: TextView
    private lateinit var keyboardBox: LinearLayout
    private lateinit var tvPhoneHint: TextView

    // 中栏
    private lateinit var sugRecycler: RecyclerView
    private lateinit var sugBox: View
    private lateinit var tvSugTitle: TextView

    // 右栏
    private lateinit var tvTitle: TextView
    private lateinit var tvCount: TextView
    private lateinit var resultRecycler: RecyclerView
    private lateinit var placeholder: View
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvPlaceholder: TextView
    private lateinit var btnRetry: TextView

    private val typed = StringBuilder()
    private val sugWords = ArrayList<String>()
    private val items = ArrayList<DouyinApi.VideoItem>()

    private lateinit var sugAdapter: SugAdapter
    private lateinit var resultAdapter: SearchResultAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var sugTask: Runnable? = null

    private var keyword = ""
    private var loading = false
    private var hasMore = false
    private var loadGen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(R.layout.activity_search)

            etKeyword = findViewById(R.id.etKeyword) as EditText
            btnClear = findViewById(R.id.btnClear) as TextView
            btnBack = findViewById(R.id.btnBack) as TextView
            keyboardBox = findViewById(R.id.keyboardBox) as LinearLayout
            tvPhoneHint = findViewById(R.id.tvPhoneHint) as TextView
            sugRecycler = findViewById(R.id.sugRecycler) as RecyclerView
            sugBox = findViewById(R.id.sugBox)
            tvSugTitle = findViewById(R.id.tvSugTitle) as TextView
            tvTitle = findViewById(R.id.tvTitle) as TextView
            tvCount = findViewById(R.id.tvCount) as TextView
            resultRecycler = findViewById(R.id.resultRecycler) as RecyclerView
            placeholder = findViewById(R.id.placeholder)
            pbLoading = findViewById(R.id.pbLoading) as ProgressBar
            tvPlaceholder = findViewById(R.id.tvPlaceholder) as TextView
            btnRetry = findViewById(R.id.btnRetry) as TextView

            sugAdapter = SugAdapter(sugWords) { w -> doSearch(w) }
            sugRecycler.layoutManager = LinearLayoutManager(this)
            sugRecycler.adapter = sugAdapter
            sugRecycler.isFocusable = true
            sugRecycler.isFocusableInTouchMode = true

            resultAdapter = SearchResultAdapter(items) { pos -> playAt(pos) }
            resultRecycler.layoutManager = LinearLayoutManager(this)
            resultRecycler.adapter = resultAdapter
            resultRecycler.isFocusable = true
            resultRecycler.isFocusableInTouchMode = true
            setupLoadMore()

            buildKeyboard()
            btnClear.setOnClickListener { clearInput() }
            btnBack.setOnClickListener { backspace() }
            btnRetry.setOnClickListener { if (keyword.isEmpty()) resetAll() else doSearch(keyword) }
            renderPhoneHint()

            val kw0 = intent?.getStringExtra("kw")
            if (!kw0.isNullOrBlank()) {
                typed.setLength(0); typed.append(kw0); syncInput(); doSearch(kw0)
            } else {
                resetAll()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate 失败", t)
            try { tvPlaceholder.text = "搜索页异常: " + t.javaClass.simpleName } catch (_: Throwable) {}
        }
    }

    // ================================================================
    // 键盘（6 列）
    // ================================================================
    private fun buildKeyboard() {
        val dp = resources.displayMetrics.density
        val kh = (46 * dp).toInt()
        val kw = (52 * dp).toInt()
        val gap = (5 * dp).toInt()

        for (r in KEY_ROWS) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = gap
            row.layoutParams = lp
            for (ch in r) {
                row.addView(makeKey(ch.toString(), kh, kw, gap) { typeChar(ch.toString()) })
            }
            keyboardBox.addView(row)
        }

        // 功能行：空格 / 搜索
        val fn = LinearLayout(this)
        fn.orientation = LinearLayout.HORIZONTAL
        fn.addView(makeKey("空格", kh, (52 * dp).toInt() + 5 + 52 * 2 + 10, gap) { typeChar(" ") })
        fn.addView(makeKey("搜索", kh, kw, gap) { submitTyped() })
        keyboardBox.addView(fn)
    }

    private fun makeKey(label: String, h: Int, w: Int, gap: Int, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.gravity = Gravity.CENTER
        tv.isFocusable = true
        tv.isFocusableInTouchMode = true
        tv.background = resources.getDrawable(R.drawable.focus_chip)
        tv.setTextColor(resources.getColorStateList(R.color.btn_text))
        tv.textSize = 16f
        val lp = LinearLayout.LayoutParams(w, h)
        lp.rightMargin = gap
        tv.layoutParams = lp
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun typeChar(s: String) {
        if (typed.length >= 24) return
        typed.append(s); syncInput(); scheduleSuggest()
    }

    private fun backspace() {
        if (typed.isEmpty()) return
        typed.deleteCharAt(typed.length - 1); syncInput(); scheduleSuggest()
    }

    private fun clearInput() {
        typed.setLength(0); syncInput(); scheduleSuggest()
    }

    private fun submitTyped() {
        val q = typed.toString().trim()
        if (q.isEmpty()) return
        doSearch(q)
    }

    /** 输入框只做展示（老盒子没输入法，聚焦它也没用），所以不抢焦点 */
    private fun syncInput() {
        try { etKeyword.setText(typed.toString()) } catch (_: Exception) {}
    }

    private fun renderPhoneHint() {
        val ip = ConfigServer.localIp()
        tvPhoneHint.text = if (ip == null) "" else "手机输入：浏览器打开\n$ip:${ConfigServer.PORT}"
    }

    // ================================================================
    // 联想
    // ================================================================
    private fun scheduleSuggest() {
        sugTask?.let { handler.removeCallbacks(it) }
        val q = typed.toString().trim()
        if (q.isEmpty()) {
            // ★ 输入为空 → 显示搜索历史（本地，点词即搜）
            showSearchHistory()
            return
        }
        val task = Runnable {
            Thread {
                val list = try { DouyinApi.fetchSuggest(q) } catch (e: Exception) { null }
                handler.post {
                    if (isFinishing) return@post
                    if (typed.toString().trim() != q) return@post   // 输入已变，丢弃乱序结果
                    sugWords.clear()
                    if (list != null) sugWords.addAll(list)
                    sugAdapter.notifyDataSetChanged()
                    // ★ 两段式：有联想词才显示（嵌在结果区顶部），无词则收起
                    if (::tvSugTitle.isInitialized) tvSugTitle.text = "猜你想搜"
                    sugBox.visibility = if (sugWords.isEmpty()) View.GONE else View.VISIBLE
                }
            }.start()
        }
        sugTask = task
        handler.postDelayed(task, SUG_DELAY)
    }

    /** 输入为空：热搜榜（匿名接口）+ 搜索历史 合并展示（点词即搜） */
    private fun showSearchHistory() {
        val hist = try { Prefs.getSearchHistory(this) } catch (e: Exception) { emptyList() }
        // 先立即显示本地历史（快），热搜异步合入
        if (::sugAdapter.isInitialized) {
            sugWords.clear()
            sugWords.addAll(hist)
            sugAdapter.notifyDataSetChanged()
        }
        if (::tvSugTitle.isInitialized) tvSugTitle.text = if (hist.isEmpty()) "热搜榜" else "热搜 · 历史"
        if (::sugBox.isInitialized) sugBox.visibility = View.VISIBLE

        // ★ 后台拉热搜词（匿名可用），合并到列表最前
        Thread {
            val hot = try {
                DouyinApi.fetchHotSearch()?.map { it.word } ?: emptyList()
            } catch (e: Exception) { emptyList() }
            handler.post {
                if (isFinishing) return@post
                if (typed.toString().trim().isNotEmpty()) return@post   // 用户已开始输入，丢弃
                val merged = (hot + hist).distinct()
                if (merged.isEmpty()) {
                    if (::sugBox.isInitialized) sugBox.visibility = View.GONE
                    return@post
                }
                sugWords.clear()
                sugWords.addAll(merged)
                sugAdapter.notifyDataSetChanged()
                if (::tvSugTitle.isInitialized) tvSugTitle.text = "热搜 · 历史"
                if (::sugBox.isInitialized) sugBox.visibility = View.VISIBLE
            }
        }.start()
    }

    // ================================================================
    // 搜索（带代际保护：迟到的响应不覆盖新搜索）
    // ================================================================
    private fun doSearch(kw: String) {
        if (kw.isBlank()) return
        // ★ 幂等：ConfigServer 会同时走 SearchBus + Intent 两条通道，
        //   同一关键词已在加载时直接忽略第二次调用（避免重复网络请求）
        if (keyword == kw && loading) return
        keyword = kw
        // ★ 搜索历史：每次搜索记录一条（本地）
        try { Prefs.addSearchHistory(this, kw) } catch (_: Throwable) {}
        // ★ 两段式：开始搜索后收起联想条，结果占满右栏
        if (::sugBox.isInitialized) sugBox.visibility = View.GONE
        val gen = ++loadGen
        loading = true
        hasMore = false
        items.clear()
        resultAdapter.notifyDataSetChanged()
        tvTitle.text = "全部" + kw + "的结果"
        tvCount.text = ""
        showPlaceholder("正在搜索「" + kw + "」…", true, false)

        Thread {
            val cookie = Prefs.getCookie(this)
            val r = try { DouyinApi.searchVideos(cookie, kw, 0) } catch (e: Exception) { null }
            handler.post {
                if (gen != loadGen) return@post     // ★ 已被新搜索取代
                if (isFinishing) return@post
                loading = false
                if (r == null || r.videos.isEmpty()) {
                    showPlaceholder(
                        "「" + kw + "」没搜到内容\n\n" + DouyinApi.lastError +
                            "\n\n可到「登录」页重新推送 Cookie，或换个词。",
                        false, true
                    )
                } else {
                    items.clear(); items.addAll(r.videos)
                    resultAdapter.notifyDataSetChanged()
                    hasMore = r.hasMore
                    hidePlaceholder()
                    tvCount.text = "共 " + items.size + " 条"
                    resultRecycler.post { resultRecycler.requestFocus() }
                }
            }
        }.start()
    }

    private fun setupLoadMore() {
        resultRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading || !hasMore || keyword.isEmpty()) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= resultAdapter.itemCount - 3) loadMore()
            }
        })
    }

    private fun loadMore() {
        if (loading || !hasMore || keyword.isEmpty()) return
        val kw = keyword
        val gen = ++loadGen
        loading = true
        tvCount.text = "加载更多…"
        Thread {
            val cookie = Prefs.getCookie(this)
            val r = try { DouyinApi.searchVideos(cookie, kw, items.size) } catch (e: Exception) { null }
            handler.post {
                if (gen != loadGen) return@post
                if (isFinishing) return@post
                loading = false
                if (r != null && r.videos.isNotEmpty()) {
                    items.addAll(r.videos)
                    resultAdapter.notifyDataSetChanged()
                    hasMore = r.hasMore
                    tvCount.text = "共 " + items.size + " 条"
                } else {
                    hasMore = false
                    tvCount.text = "共 " + items.size + " 条 · 已到底"
                }
            }
        }.start()
    }

    // ================================================================
    // 播放 / 导航
    // ================================================================
    private fun playAt(pos: Int) {
        if (pos < 0 || pos >= items.size) return
        FeedBus.put(items, pos, FeedBus.SOURCE_SEARCH)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun resetAll() {
        keyword = ""
        loadGen++
        items.clear()
        resultAdapter.notifyDataSetChanged()
        hidePlaceholder()
        tvTitle.text = "搜索结果"
        tvCount.text = ""
        typed.setLength(0)
        syncInput()
        showSearchHistory()
        keyboardBox.post {
            (keyboardBox.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.requestFocus()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.getStringExtra("kw")?.takeIf { it.isNotBlank() }?.let { kw ->
            typed.setLength(0); typed.append(kw); syncInput(); doSearch(kw)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            // 有结果 → 先把结果清掉回到输入态；已经是输入态 → 退出
            if (items.isNotEmpty()) { resetAll(); return true }
            finish(); return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        // ★ 跨线程安全：SearchBus 的 push 可能来自 NanoHTTPD 工作线程
        //   （手机浏览器 :8088/search），UI 更新必须 post 回主线程
        SearchBus.register { kw ->
            handler.post {
                typed.setLength(0); typed.append(kw); syncInput(); doSearch(kw)
            }
        }
    }

    override fun onPause() {
        SearchBus.unregister()
        super.onPause()
    }

    override fun onDestroy() {
        sugTask?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ================================================================
    private fun showPlaceholder(text: String, loading: Boolean, showRetry: Boolean) {
        placeholder.visibility = View.VISIBLE
        pbLoading.visibility = if (loading) View.VISIBLE else View.GONE
        tvPlaceholder.text = text
        btnRetry.visibility = if (showRetry) View.VISIBLE else View.GONE
        if (showRetry) btnRetry.post { btnRetry.requestFocus() }
    }

    private fun hidePlaceholder() {
        placeholder.visibility = View.GONE
    }

    /** 联想词适配器（复用热搜条目布局） */
    private class SugAdapter(
        private val data: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<SugAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvWord)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_hot_word, parent, false)
            // 联想条目不需要榜单那两列
            (v.findViewById(R.id.tvRank) as? TextView)?.visibility = View.GONE
            (v.findViewById(R.id.tvHot) as? TextView)?.visibility = View.GONE
            return VH(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            if (position < 0 || position >= data.size) return
            val w = data[position]
            holder.tv.text = TextCompat.forTv(w)
            holder.itemView.setOnClickListener { onClick(w) }
        }
    }
}
