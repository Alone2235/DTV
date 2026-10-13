package com.example.douyintv

import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/**
 * 视频卡片列表适配器（作者主页 / 话题页 / 搜索结果共用）。
 *
 * <h3>★ TV 硬性规则适配 ★</h3>
 * <ul>
 *   <li><b>RecyclerView.Adapter + 强制 ViewHolder</b> —— 取代原 BaseAdapter +
 *       convertView 手搓复用。后者没有强制的复用契约，item 一复杂就掉帧。</li>
 *   <li><b>item 可聚焦</b> —— 焦点高亮由 item_video.xml 的 focus_card selector 提供，
 *       代码只负责把 OK 键事件转成播放动作。</li>
 *   <li><b>不停留在触摸事件上</b> —— 用 setOnClickListener 是因为 D-Pad 的
 *       OK 键在 Android 上本来就派发为 click；真正要避免的是 onTouch。</li>
 * </ul>
 *
 * <h3>两个容易踩的坑（都已在代码里规避）</h3>
 * <ol>
 *   <li><b>封面错位</b>：列表快速滚动时，先发出的图片请求可能后返回，
 *       把上一行的图贴到当前行。这里用 awemeId 作为 tag，
 *       ImageLoader 内部会比对 tag 后才真正 setImageBitmap。</li>
 *   <li><b>emoji 方块</b>：文案过 TextCompat.forTv 过滤 ——
 *       Android 4.2 没有 emoji 字形，直接渲染就是满屏方块。</li>
 * </ol>
 */
class VideoCardAdapter(
    private val data: MutableList<DouyinApi.VideoItem>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<VideoCardAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.itemCover) as ImageView
        val title: TextView = v.findViewById(R.id.itemTitle) as TextView
        val sub: TextView = v.findViewById(R.id.itemSub) as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (position < 0 || position >= data.size) return
        val item = data[position]

        // tag = awemeId：防止列表复用后旧请求回来贴错行
        ImageLoader.load(item.coverUrl, holder.cover, tag = item.awemeId)

        holder.title.text = TextCompat.forTv(
            if (item.desc.isBlank()) "(无标题)" else item.desc
        )

        val dur = if (item.duration > 0) "　${item.duration / 1000}s" else ""
        holder.sub.text = "赞 " + fmt(item.diggCount) +
                "　评论 " + fmt(item.commentCount) + dur

        holder.itemView.setOnClickListener { onClick(position) }
    }

    /** 12345 → 1.2万 */
    private fun fmt(n: Int): String =
        if (n >= 10000) String.format("%.1f万", n / 10000f) else n.toString()
}
