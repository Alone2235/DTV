package com.example.douyintv

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Calendar

/**
 * 观看历史（纯本地）—— ★ 按日期分组：今天 / 昨天 / 更早
 * ========================================================
 * 播放页每次起播成功写入一条记录（Prefs.addHistory，带时间戳 ts）。
 * 这里按 ts 分组展示，每组一个分隔标题 + 网格卡片；点卡片进播放器续看。
 */
class HistoryActivity : Activity() {

    companion object {
        private const val TAG = "History"
        private const val SPAN_COUNT = 3
    }

    private lateinit var recycler: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvStatus: TextView
    private lateinit var adapter: HistorySectionAdapter

    /** 展平后的全部视频（不含分组标题）—— 播放器用 */
    private val flatVideos = ArrayList<DouyinApi.VideoItem>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_list)

        recycler = findViewById(R.id.recyclerView) as RecyclerView
        tvTitle = findViewById(R.id.tvTitle) as TextView
        tvSub = findViewById(R.id.tvSub) as TextView
        tvStatus = findViewById(R.id.tvStatus) as TextView

        tvTitle.text = TextCompat.forTv("观看历史")
        tvSub.text = "按日期分组 · 最多 100 条"

        // 读取 + 分组（本地读很快，主线程直接做）
        try {
            val all = Prefs.getHistory(this)
            val sections = buildSections(all)
            flatVideos.clear()
            sections.forEach { flatVideos.addAll(it.items) }

            adapter = HistorySectionAdapter(sections) { item, pos ->
                playAt(item, pos)
            }
            val lm = GridLayoutManager(this, SPAN_COUNT)
            lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.isHeader(position)) SPAN_COUNT else 1
            }
            recycler.layoutManager = lm
            recycler.adapter = adapter
            recycler.setHasFixedSize(false)
            recycler.isFocusable = true
            recycler.isFocusableInTouchMode = true

            tvStatus.text = if (flatVideos.isEmpty()) "暂无观看记录"
            else "共 " + flatVideos.size + " 条　OK 播放　↑↓ 滚动"
            if (flatVideos.isNotEmpty()) {
                recycler.post {
                    val v = recycler.layoutManager?.findViewByPosition(0)
                    if (v != null) v.requestFocus() else recycler.requestFocus()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取历史失败", e)
            tvStatus.text = "读取历史失败"
        }
    }

    /** 按 ts 分三组：今天 / 昨天 / 更早（同组内保持最新在前） */
    private fun buildSections(all: List<DouyinApi.VideoItem>): List<Section> {
        val map = linkedMapOf<String, MutableList<DouyinApi.VideoItem>>()
        for (v in all) {
            map.getOrPut(labelOf(v.ts)) { mutableListOf() }.add(v)
        }
        return map.map { Section(it.key, it.value) }
    }

    private fun labelOf(ts: Long): String {
        if (ts <= 0) return "更早"
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = ts }
        val sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return "今天"
        now.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        return if (yesterday) "昨天" else "更早"
    }

    /** 点卡片 → 以历史列表为播放列表进入播放器（零拷贝走 FeedBus） */
    private fun playAt(item: DouyinApi.VideoItem, pos: Int) {
        val idx = flatVideos.indexOfFirst { it.awemeId == item.awemeId }
        val start = if (idx >= 0) idx else pos
        FeedBus.put(flatVideos, start.coerceIn(0, maxOf(0, flatVideos.size - 1)), FeedBus.SOURCE_HISTORY)
        startActivity(Intent(this, PlayerActivity::class.java))
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
    // 分组数据 + 适配器
    // ================================================================
    class Section(val title: String, val items: List<DouyinApi.VideoItem>)

    class HistorySectionAdapter(
        private val sections: List<Section>,
        private val onClick: (DouyinApi.VideoItem, Int) -> Unit
    ) : RecyclerView.Adapter<HistorySectionAdapter.VH>() {

        private val TYPE_HEADER = 1
        private val TYPE_CARD = 0

        /** 展平（String=分组标题 / VideoItem=卡片） */
        private val flat = ArrayList<Any>()

        init {
            for (s in sections) {
                flat.add(s.title)
                flat.addAll(s.items)
            }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            var headerTv: TextView? = null
            var cover: ImageView? = null
            var pbCover: View? = null
            var title: TextView? = null
            var meta: TextView? = null
            var duration: TextView? = null
            var vertical: TextView? = null
        }

        fun isHeader(position: Int): Boolean = flat.getOrNull(position) is String

        override fun getItemViewType(position: Int): Int =
            if (flat.getOrNull(position) is String) TYPE_HEADER else TYPE_CARD

        override fun getItemCount(): Int = flat.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return if (viewType == TYPE_HEADER) {
                // 分组标题：代码创建（纯文本 + 留白）
                val tv = TextView(parent.context)
                tv.textSize = 20f
                tv.setTypeface(null, Typeface.BOLD)
                tv.setTextColor(0xFFFFFFFF.toInt())
                tv.setPadding(0, (24 * parent.context.resources.displayMetrics.density).toInt(),
                    0, (8 * parent.context.resources.displayMetrics.density).toInt())
                val lp = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                tv.layoutParams = lp
                VH(tv).apply { headerTv = tv }
            } else {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_video_card, parent, false)
                VH(v).apply {
                    cover = v.findViewById(R.id.ivCover)
                    pbCover = v.findViewById(R.id.pbCover)
                    title = v.findViewById(R.id.tvTitle)
                    meta = v.findViewById(R.id.tvMeta)
                    duration = v.findViewById(R.id.tvDuration)
                    vertical = v.findViewById(R.id.tvVertical)
                }
            }
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val data = flat.getOrNull(position) ?: return
            if (data is String) {
                holder.headerTv?.text = TextCompat.forTv(data)
                return
            }
            val it = data as DouyinApi.VideoItem
            holder.title?.text = if (it.desc.isBlank()) "（无标题）" else it.desc
            val sb = StringBuilder("@").append(if (it.author.isBlank()) "未知" else it.author)
            if (it.diggCount > 0) sb.append("  赞 ").append(fmt(it.diggCount))
            holder.meta?.text = sb.toString()
            holder.duration?.text = fmtDur(it.duration)
            holder.vertical?.visibility = if (it.isPortrait) View.VISIBLE else View.GONE
            if (holder.cover != null) {
                ImageLoader.load(it.coverUrl, holder.cover!!, it.awemeId)
            }
            holder.itemView.setOnClickListener { _ -> onClick(it, position) }
        }

        private fun fmtDur(ms: Int): String {
            if (ms <= 0) return "--:--"
            val s = ms / 1000
            return String.format("%02d:%02d", s / 60, s % 60)
        }

        private fun fmt(n: Int): String =
            if (n >= 10000) String.format("%.1f万", n / 10000f) else n.toString()
    }
}
