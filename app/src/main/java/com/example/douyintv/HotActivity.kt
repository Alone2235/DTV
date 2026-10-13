package com.example.douyintv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 热搜榜（独立页面）
 * ==================
 * 从首页左侧导航「热搜」进入，与搜索页彻底分开：
 *   · 这里只负责「看榜」——拉热搜榜、点词直接进搜索页看结果
 *   · 搜索页只管输入/联想/结果，不再混排热搜
 *
 * 热搜接口匿名可用（不需要登录 Cookie，也不需要任何签名），
 * 所以这页在没登录的情况下也能正常出内容。
 */
class HotActivity : Activity() {

    private companion object {
        const val TAG = "Hot"
        const val SPAN = 2
    }

    private lateinit var recycler: RecyclerView
    private lateinit var placeholder: View
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvPlaceholder: TextView
    private lateinit var btnRetry: TextView
    private lateinit var tvCount: TextView

    private val words = ArrayList<DouyinApi.HotWord>()
    private lateinit var adapter: HotAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(R.layout.activity_hot)

            tvCount = findViewById(R.id.tvCount) as TextView
            recycler = findViewById(R.id.hotRecycler) as RecyclerView
            placeholder = findViewById(R.id.placeholder)
            pbLoading = findViewById(R.id.pbLoading) as ProgressBar
            tvPlaceholder = findViewById(R.id.tvPlaceholder) as TextView
            btnRetry = findViewById(R.id.btnRetry) as TextView

            adapter = HotAdapter(words) { pos -> openSearch(pos) }
            recycler.layoutManager = GridLayoutManager(this, SPAN)
            recycler.adapter = adapter
            recycler.isFocusable = true
            recycler.isFocusableInTouchMode = true

            btnRetry.setOnClickListener { load() }
            load()
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate 失败", t)
            try { tvPlaceholder.text = "热搜页异常: " + t.javaClass.simpleName } catch (_: Throwable) {}
        }
    }

    private fun load() {
        if (loading) return
        loading = true
        showPlaceholder("正在加载热搜榜…", true, false)
        recycler.visibility = View.GONE

        Thread {
            val list = try { DouyinApi.fetchHotSearch() } catch (e: Exception) { null }
            handler.post {
                if (isFinishing) return@post
                loading = false
                if (list == null || list.isEmpty()) {
                    showPlaceholder(
                        "热搜榜加载失败\n\n" + DouyinApi.lastError.ifEmpty { "网络不可达" },
                        false, true
                    )
                    return@post
                }
                words.clear()
                words.addAll(list)
                adapter.notifyDataSetChanged()
                hidePlaceholder()
                tvCount.text = "共 " + list.size + " 条"
                recycler.visibility = View.VISIBLE
                recycler.post { recycler.requestFocus() }
            }
        }.start()
    }

    /** 点热搜词 → 带着词进搜索页直接搜（搜索页自己负责出结果） */
    private fun openSearch(pos: Int) {
        if (pos < 0 || pos >= words.size) return
        val i = Intent(this, SearchActivity::class.java)
        i.putExtra("kw", words[pos].word)
        startActivity(i)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

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

    // ================================================================
    private class HotAdapter(
        private val data: List<DouyinApi.HotWord>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<HotAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val rank: TextView = v.findViewById(R.id.tvRank) as TextView
            val word: TextView = v.findViewById(R.id.tvWord) as TextView
            val hot: TextView = v.findViewById(R.id.tvHot) as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_hot_word, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            if (position < 0 || position >= data.size) return
            val h = data[position]
            // 前三名标红（榜单惯例）
            holder.rank.text = (position + 1).toString()
            holder.rank.setTextColor(
                if (position < 3) 0xFFFF4D4FL.toInt() else 0xFF9AA0A6.toInt()
            )
            holder.word.text = TextCompat.forTv(h.word)
            holder.hot.text = fmtHot(h.hotValue)
            holder.itemView.setOnClickListener { onClick(position) }
        }

        private fun fmtHot(n: Long): String =
            if (n >= 10000) String.format("%.1f万", n / 10000f) else n.toString()
    }
}
