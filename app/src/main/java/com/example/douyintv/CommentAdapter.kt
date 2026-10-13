package com.example.douyintv

import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/**
 * 评论列表适配器。
 *
 * <h3>★ TV 硬性规则适配 ★</h3>
 * <ul>
 *   <li>RecyclerView + ViewHolder（原实现是 ArrayAdapter&lt;String&gt;，
 *       把所有内容拼成一个大字符串塞进两行布局 —— 既丢结构又难复用）</li>
 *   <li>emoji 过 TextCompat：Android 4.2 无 emoji 字形，直接渲染出方块</li>
 *   <li>item 可聚焦 + focus_card 高亮（item_comment.xml 提供）</li>
 * </ul>
 *
 * <h3>为什么不再拼字符串</h3>
 * 原实现 `user：text\n赞 N` 的做法有三个问题：
 *   1. 无法对作者名/正文分别设样式（作者名该用蓝色区分）
 *   2. 换行位置写死，长文本在电视上会挤成一团
 *   3. emoji 混在整串里，没法单独过滤
 * 改成结构化字段后，三者都自然解决。
 */
class CommentAdapter(
    private val data: MutableList<DouyinApi.CommentItem>,
    private val onUserClick: (secUid: String, name: String) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<CommentAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val author: TextView = v.findViewById(R.id.cmtAuthor) as TextView
        val text: TextView = v.findViewById(R.id.cmtText) as TextView
        val meta: TextView = v.findViewById(R.id.cmtMeta) as TextView
        val mediaBox: View = v.findViewById(R.id.cmtMediaBox)
        val media: ImageView = v.findViewById(R.id.ivMedia) as ImageView
        val mediaTag: TextView = v.findViewById(R.id.ivMediaTag) as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_comment, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = data.size

    /**
     * 建议调用方在批量追加后统一调 notifyItemRangeInserted，
     * 而不是反复 notifyDataSetChanged（后者会重建所有可见项，电视上掉帧明显）。
     * 这里保留本方法仅作为兜底。
     */
    override fun onBindViewHolder(holder: VH, position: Int) {
        if (position < 0 || position >= data.size) return
        val c = data[position]

        // 作者名与正文都要过 emoji 过滤：昵称里也常带 emoji
        val name = if (c.user.isBlank()) "匿名" else c.user
        holder.author.text = TextCompat.forTv(name)
        holder.text.text = TextCompat.forTv(c.text)

        val sb = StringBuilder("赞 ")
        sb.append(fmt(c.diggCount))
        if (c.replyCount > 0) {
            sb.append("　回复 ").append(c.replyCount)
        }
        holder.meta.text = sb.toString()

        // ★ 评论媒体：图片评论 / 视频评论封面
        val mediaUrl = if (c.imageUrl.isNotBlank()) c.imageUrl else c.videoCover
        if (mediaUrl.isNotBlank()) {
            holder.mediaBox.visibility = View.VISIBLE
            holder.mediaTag.visibility = if (c.videoCover.isNotBlank()) View.VISIBLE else View.GONE
            ImageLoader.load(mediaUrl, holder.media, c.user)
        } else {
            holder.mediaBox.visibility = View.GONE
        }

        // ★ 点评论 → 看评论者主页（有 sec_uid 才可跳）
        holder.itemView.setOnClickListener {
            if (c.secUid.isNotBlank()) onUserClick(c.secUid, name)
        }
    }

    /** 12345 → 1.2万 */
    private fun fmt(n: Int): String =
        if (n >= 10000) String.format("%.1f万", n / 10000f) else n.toString()
}
