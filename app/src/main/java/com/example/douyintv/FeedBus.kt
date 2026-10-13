package com.example.douyintv

/**
 * 跨 Activity 传递播放列表的内存总线。
 *
 * <h3>为什么不用 Intent extra + JSON</h3>
 * <ol>
 *   <li><b>违反红线</b>：项目禁止 Gson/Moshi。</li>
 *   <li><b>会被系统拒绝</b>：Intent extra 走 Binder，事务缓冲上限约 1MB。
 *       一页 20 条视频带封面 URL、作者、文案，序列化后轻易逼近上限 →
 *       抛 TransactionTooLargeException → 用户看到的就是
 *       「作者页点进去无法播放 / 直接闪退」。</li>
 *   <li><b>纯浪费</b>：在 1GB 内存的电视上，序列化 + 反序列化一份完整列表
 *       既耗 CPU 又产生大量临时字符串，直接触发 GC 抖动。</li>
 * </ol>
 * 进程内静态引用传递是零拷贝的 —— 代价是需要显式 clear() 防止泄漏。
 *
 * <h3>泄漏防护契约</h3>
 * 拿到数据的 Activity 必须在 onDestroy 里调 clear()，
 * 否则这条列表会一直强引用它的字符串字段（每条约 1KB），
 * 长刷视频时累积起来相当可观。
 */
object FeedBus {

    /** 待播放列表。null 表示没有待处理数据 */
    @Volatile
    private var list: List<DouyinApi.VideoItem>? = null

    /** 从第几条开始播（作者页点某条进入时需要） */
    @Volatile
    private var startIndex: Int = 0

    /**
     * 数据来源，用于"数据源隔离"：
     * feed = 首页推荐流，author = 作者主页，search = 搜索。
     * 播放器靠它决定"滑到底"时是拉推荐流还是继续该作者的作品。
     */
    @Volatile
    private var source: String = SOURCE_FEED

    const val SOURCE_FEED = "feed"
    const val SOURCE_AUTHOR = "author"
    const val SOURCE_SEARCH = "search"
    const val SOURCE_HISTORY = "history"
    const val SOURCE_COLLECT = "collect"
    const val SOURCE_SHARE = "share"      // 手机推送的单条视频

    /**
     * 投递播放列表。
     *
     * @param items      列表本体（不复制，调用方之后不应再改它）
     * @param startIndex 起始下标，越界会被纠正为 0
     * @param src        来源标记，见 SOURCE_*
     */
    fun put(items: List<DouyinApi.VideoItem>, startIndex: Int, src: String) {
        this.list = items
        this.startIndex = if (startIndex < 0 || startIndex >= items.size) 0 else startIndex
        this.source = src
    }

    /** 取出列表；没有则返回 null */
    fun peek(): List<DouyinApi.VideoItem>? = list

    /** 取出起始下标 */
    fun startIndex(): Int = startIndex

    /** 取出数据来源标记 */
    fun source(): String = source

    /** 是否投递过数据 */
    fun available(): Boolean = list?.isNotEmpty() == true

    /**
     * 释放引用。
     *
     * 播放器 onDestroy 时调用 —— 不调用的话，整条列表（含所有字符串字段）
     * 会随进程一直存活。
     */
    fun clear() {
        list = null
        startIndex = 0
        source = SOURCE_FEED
    }
}
