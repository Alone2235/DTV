package com.example.douyintv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 作者主页：列出该作者的作品（/aweme/v1/web/aweme/post/）。
 *
 * <h3>★ 数据源隔离（本轮重点修复）★</h3>
 * 本页**只**呈现该作者作品集，绝不复用首页推荐流的列表对象。
 * 选中某条进入播放器时，来源标记为 [FeedBus.SOURCE_AUTHOR] ——
 * 播放器据此决定"滑到底"时是继续拉该作者的作品，而不是混入推荐流。
 * 这解决了「在作者页还能滑到首页推荐流」的问题。
 *
 * <h3>★ TV 硬性规则适配 ★</h3>
 * <ul>
 *   <li>ListView → RecyclerView（带 nextFocus 语义、强 ViewHolder 契约）</li>
 *   <li>不用 OnItemClickListener（触摸监听），改由 item 自身的 click
 *       —— D-Pad 的 OK 键在 Android 上本就派发为 click</li>
 *   <li>翻页/加载完成后显式 requestFocus 到列表首项，焦点不凭空消失</li>
 *   <li>局部失败只改状态栏文字，不崩溃</li>
 * </ul>
 */
class AuthorActivity : Activity() {

    companion object {
        private const val TAG = "Author"
        private const val PAGE = 20

        /**
         * 一行 3 个卡片。
         * 旧版用 LinearLayoutManager 单列 —— 电视横向空间全浪费，
         * 一屏只能看到一个视频，这是"不像 TV 应用"最直观的一处。
         */
        private const val SPAN_COUNT = 3
    }

    private lateinit var layoutManager: GridLayoutManager

    private lateinit var recycler: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvStatus: TextView

    /** ★ 本页自己的列表 —— 与 FeedBus 里的推荐流完全隔离 */
    private val items = ArrayList<DouyinApi.VideoItem>()
    private lateinit var adapter: VideoGridAdapter

    private var secUid = ""
    private var authorName = ""
    private var maxCursor = 0L
    private var hasMore = true
    private var loading = false
    private var firstLoadDone = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_list)

        recycler = findViewById(R.id.recyclerView) as RecyclerView
        tvTitle = findViewById(R.id.tvTitle) as TextView
        tvSub = findViewById(R.id.tvSub) as TextView
        tvStatus = findViewById(R.id.tvStatus) as TextView

        secUid = intent.getStringExtra("sec_uid") ?: ""
        authorName = intent.getStringExtra("author") ?: "作者"

        tvTitle.text = TextCompat.forTv("@$authorName")
        tvSub.text = "作品列表"
        tvStatus.text = "加载中…"

        // ★ 网格：电视横向空间大，一行 3 个卡片
        layoutManager = GridLayoutManager(this, SPAN_COUNT)
        adapter = VideoGridAdapter(items) { pos -> playAt(pos) }
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.setHasFixedSize(false)
        // 规则③防焦点飞出：网格内部交给 GridLayoutManager 自己找焦点
        recycler.isFocusable = true
        recycler.isFocusableInTouchMode = true
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val last = layoutManager.findLastVisibleItemPosition()
                if (adapter.size() > 0 && last >= adapter.size() - 3) loadMore()
            }
        })

        if (secUid.isBlank()) {
            tvStatus.text = "缺少作者 ID"
            return
        }
        loadMore()
    }

    private fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        tvStatus.text = "加载作品中…"
        val c = maxCursor
        val cookie = Prefs.getCookie(this)

        Thread {
            val r = try {
                DouyinApi.fetchUserPosts(cookie, secUid, c, PAGE)
            } catch (e: Exception) {
                Log.e(TAG, "加载作品异常", e); null
            }
            handler.post {
                if (isFinishing) return@post      // ★ 修复：Activity 已销毁不再碰视图
                loading = false
                if (r == null) {
                    // ★ 局部失败：只提示，不崩
                    tvStatus.text = "加载失败：" + DouyinApi.lastError
                    return@post
                }
                if (r.videos.isEmpty()) {
                    hasMore = false
                    tvStatus.text = if (items.isEmpty()) "该作者没有作品" else "没有更多作品了"
                    return@post
                }

                // 去重（接口在不同 cursor 下偶尔会重复下发同一条）
                val known = HashSet<String>()
                for (v in items) known.add(v.awemeId)
                val fresh = ArrayList<DouyinApi.VideoItem>()
                for (v in r.videos) {
                    if (!known.contains(v.awemeId)) {
                        known.add(v.awemeId)
                        fresh.add(v)
                    }
                }
                if (fresh.isEmpty()) {
                    hasMore = false
                    tvStatus.text = "没有更多作品了"
                    return@post
                }

                // VideoGridAdapter 内部已封装 notify 逻辑（首次 replace、后续 insert）
                adapter.append(fresh)

                maxCursor = r.maxCursor
                hasMore = r.hasMore && r.maxCursor != c
                tvStatus.text = "共 " + items.size + " 条　OK 播放　↑↓ 滚动"

                // ★ 首次加载完成后显式把焦点交给列表首项 —— 规则要求
                //   "页面切换后必须显式指定默认焦点，不能焦点凭空消失"
                if (!firstLoadDone) {
                    firstLoadDone = true
                    recycler.post {
                        val v = recycler.layoutManager?.findViewByPosition(0)
                        if (v != null) v.requestFocus()
                        else recycler.requestFocus()
                    }
                }
            }
        }.start()
    }

    /**
     * 以该作者的全部作品为播放列表进入播放器。
     *
     * ★ 走 FeedBus 而非 Intent+JSON：避免 Binder 事务上限导致的"点进去播不了"。
     */
    private fun playAt(pos: Int) {
        if (pos < 0 || pos >= items.size) return
        try {
            FeedBus.put(items, pos, FeedBus.SOURCE_AUTHOR)
            startActivity(Intent(this, PlayerActivity::class.java))
            // ★ 82.x 修复：不再 finish 本页 —— 播放器返回后回到作者页（保留上下文），
            //   而不是直接丢回主界面。
        } catch (e: Exception) {
            Log.e(TAG, "进入播放器失败", e)
            tvStatus.text = "播放失败: " + e.message
        }
    }

    /** 返回键：优先交给列表滚动处理，否则退出本页 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            finish(); return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // 本页列表随 Activity 一起释放，不跨页持有
        items.clear()
        super.onDestroy()
    }
}
