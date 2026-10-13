package com.example.douyintv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 搜索结果适配器（横向卡片：左封面 / 右文字）
 * ==========================================
 * 与首页的 VideoGridAdapter（网格封面墙）不同：搜索结果信息密度更高，
 * 照云视听的做法用「海报 + 标题 + 来源行」，一屏能看清更多结果。
 *
 * 复用与首页同一套 ImageLoader（占位图 / 兜底图 / 复用防错位都在它内部）。
 */
class SearchResultAdapter(
    private val items: MutableList<DouyinApi.VideoItem>,
    private val onClick: (position: Int) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.ivCover) as ImageView
        val title: TextView = v.findViewById(R.id.tvTitle) as TextView
        val meta: TextView = v.findViewById(R.id.tvMeta) as TextView
        val topic: TextView = v.findViewById(R.id.tvTopic) as TextView
        val author: TextView = v.findViewById(R.id.tvAuthor) as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    fun replaceAll(list: List<DouyinApi.VideoItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (position < 0 || position >= items.size) return
        val it = items[position]

        holder.title.text =
            if (it.desc.isBlank()) "（无标题）" else TextCompat.forTv(it.desc)

        // 元信息：时长 + 点赞
        val sb = StringBuilder()
        val dur = fmtDur(it.duration)
        if (dur.isNotEmpty()) sb.append(dur)
        if (it.diggCount > 0) {
            if (sb.isNotEmpty()) sb.append("  ·  ")
            sb.append("赞 ").append(fmtCount(it.diggCount))
        }
        if (it.commentCount > 0) {
            if (sb.isNotEmpty()) sb.append("  ·  ")
            sb.append("评论 ").append(fmtCount(it.commentCount))
        }
        holder.meta.text = sb.toString()

        // 话题标签
        val t = it.topics.firstOrNull()
        if (t.isNullOrBlank()) {
            holder.topic.visibility = View.GONE
        } else {
            holder.topic.visibility = View.VISIBLE
            holder.topic.text = "#" + TextCompat.forTv(t)
        }

        holder.author.text = "@" + (it.author.ifBlank { "未知" })

        ImageLoader.load(it.coverUrl, holder.cover, it.awemeId)
        holder.itemView.setOnClickListener { onClick(position) }
    }

    private fun fmtDur(sec: Int): String {
        if (sec <= 0) return ""
        val m = sec / 60
        val s = sec % 60
        return if (m > 0) String.format("%d:%02d", m, s) else String.format("0:%02d", s)
    }

    private fun fmtCount(n: Int): String =
        if (n >= 10000) String.format("%.1f万", n / 10000f) else n.toString()
}
