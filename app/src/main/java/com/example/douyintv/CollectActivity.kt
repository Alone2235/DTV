package com.example.douyintv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 收藏列表（本地）——「我的」二级菜单进入
 * ======================================
 * 收藏时本地保存完整视频信息（Prefs.toggleCollect），这里网格展示，点卡片续看。
 * 固定集合：播放器里滑到底不自动加载（FeedBus.SOURCE_COLLECT）。
 */
class CollectActivity : Activity() {

    companion object {
        private const val TAG = "Collect"
        private const val SPAN_COUNT = 3
    }

    private lateinit var recycler: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvStatus: TextView
    private lateinit var adapter: VideoGridAdapter
    private val items = ArrayList<DouyinApi.VideoItem>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_list)

        recycler = findViewById(R.id.recyclerView) as RecyclerView
        tvTitle = findViewById(R.id.tvTitle) as TextView
        tvSub = findViewById(R.id.tvSub) as TextView
        tvStatus = findViewById(R.id.tvStatus) as TextView

        tvTitle.text = TextCompat.forTv("我的收藏")
        tvSub.text = "收藏的视频"

        adapter = VideoGridAdapter(items) { pos -> playAt(pos) }
        recycler.layoutManager = GridLayoutManager(this, SPAN_COUNT)
        recycler.adapter = adapter
        recycler.setHasFixedSize(false)
        recycler.isFocusable = true
        recycler.isFocusableInTouchMode = true

        try {
            items.addAll(Prefs.getCollectedItems(this))
            adapter.notifyDataSetChanged()
            tvStatus.text = if (items.isEmpty()) "暂无收藏（播放页菜单 → 收藏）"
            else "共 " + items.size + " 条　OK 播放　↑↓ 滚动"
            if (items.isNotEmpty()) {
                recycler.post {
                    val v = recycler.layoutManager?.findViewByPosition(0)
                    if (v != null) v.requestFocus() else recycler.requestFocus()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取收藏失败", e)
            tvStatus.text = "读取收藏失败"
        }
    }

    private fun playAt(pos: Int) {
        if (pos < 0 || pos >= items.size) return
        FeedBus.put(items, pos, FeedBus.SOURCE_COLLECT)
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
        items.clear()
        super.onDestroy()
    }
}
