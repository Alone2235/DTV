package com.example.douyintv

import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/**
 * 首页 / 作者页的视频网格适配器。
 *
 * 为什么必须用 RecyclerView（TV 硬性规则③）：
 *   1) ViewHolder 由框架强制复用，杜绝"每次 bind 都 findViewById"的开销
 *      —— 1GB 电视上这个开销能直接变成滚动掉帧；
 *   2) 原生支持 GridLayoutManager（规则要求的"网格预览列表"）；
 *   3) support-v7 的 RecyclerView 对 D-Pad 的 focusSearch 有专门处理，
 *      而 ListView / ScrollView 在遥控器场景下焦点经常飞出屏幕或直接丢失。
 *
 * 图片策略：全部交给 ImageLoader，它自带「加载中占位图 + 失败兜底图」
 * （对应规则 4②），适配器这边不做任何 setImageBitmap(null) 之类的裸操作。
 */
class VideoGridAdapter(
    private val items: MutableList<DouyinApi.VideoItem>,
    private val onClick: (position: Int) -> Unit
) : RecyclerView.Adapter<VideoGridAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.ivCover) as ImageView
        val pbCover: View = v.findViewById(R.id.pbCover)
        val title: TextView = v.findViewById(R.id.tvTitle) as TextView
        val meta: TextView = v.findViewById(R.id.tvMeta) as TextView
        val duration: TextView = v.findViewById(R.id.tvDuration) as TextView
        val vertical: TextView = v.findViewById(R.id.tvVertical) as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_card, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]

        // 标题：空文案给个占位，否则卡片看起来像坏了
        holder.title.text = if (it.desc.isBlank()) "（无标题）" else it.desc

        // 元信息：作者 + 点赞数
        val sb = StringBuilder()
        sb.append("@").append(if (it.author.isBlank()) "未知" else it.author)
        if (it.diggCount > 0) {
            sb.append("  赞 ").append(fmtCount(it.diggCount))
        }
        holder.meta.text = sb.toString()

        // 时长角标
        holder.duration.text = fmtDur(it.duration)

        // 竖屏标记：横屏 TV 上竖屏视频两侧是黑边，提前打角标告知
        // ★ 修复：原来 if 两个分支都是 GONE（死代码，角标从未显示）
        holder.vertical.visibility = if (it.isPortrait) View.VISIBLE else View.GONE

        // 封面 —— ImageLoader 内部有占位图 / 兜底图 / 复用防错位
        ImageLoader.load(it.coverUrl, holder.cover, it.awemeId)

        // 遥控器"确定" → 进入播放页（整卡可点击）
        holder.itemView.setOnClickListener { FocusAnim.press(holder.itemView); onClick(position) }

        // ★ 10 英尺焦点：聚焦时卡片轻微放大（Netflix / Apple TV 同款反馈），
        //   叠加 focus_card 的白描边 + 柔光，3 米外一眼能锁定当前卡片
        //   统一走 FocusAnim（ease-out 140ms，全应用手感一致）
        holder.itemView.setOnFocusChangeListener { v, has -> FocusAnim.scaleTo(v, has, 1.04f) }
    }

    /** 切换分类：整体替换 */
    fun replaceAll(newItems: List<DouyinApi.VideoItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** 分页追加 */
    fun append(newItems: List<DouyinApi.VideoItem>) {
        val from = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(from, newItems.size)
    }

    fun size(): Int = items.size

    fun itemAt(i: Int): DouyinApi.VideoItem? = items.getOrNull(i)

    private fun fmtDur(ms: Int): String {
        if (ms <= 0) return "--:--"
        val s = ms / 1000
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    private fun fmtCount(n: Int): String =
        if (n >= 10000) String.format("%.1f万", n / 10000f) else n.toString()
}
