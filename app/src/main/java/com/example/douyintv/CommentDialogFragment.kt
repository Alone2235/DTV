package com.example.douyintv

import android.app.Dialog
import android.app.DialogFragment
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 评论悬浮小窗 —— 右侧窄面板，**不覆盖视频画面主体**。
 *
 * <h3>为什么回到 DialogFragment（而不是全屏 CommentActivity）</h3>
 * 独立 Activity 会让宿主 [PlayerActivity] 走 onPause → 视频被暂停，
 * "边看边刷评论"这件事直接做不成，跳来跳去体验很差。
 * DialogFragment 是独立窗口，层级高于 SurfaceView，且**不触碰宿主生命周期**，
 * 视频继续播。
 *
 * <h3>为什么用 android.app.DialogFragment 而不是 support.v4 的</h3>
 * 宿主 PlayerActivity 继承的是 android.app.Activity（不是 FragmentActivity），
 * 用 support 版必须改基类 + 引 support 包。
 * 而 minSdk = 16 ≥ API 11，系统自带 DialogFragment 完全够用 ——
 * 零新依赖、零基类改动、零风险。
 *
 * <h3>为什么不遮挡画面</h3>
 * 面板贴右边缘、只占屏宽约 1/3。抖音视频绝大多数是竖屏 9:16，
 * 在 16:9 电视上按真实比例显示时画面只占中间一条，两侧本就是黑边，
 * 面板正好落在黑边区。横屏视频会重叠一部分，所以：
 *   · 面板半透明
 *   · 关键：clearFlags(FLAG_DIM_BEHIND) —— 否则整屏会被压暗，视频就看不见了
 *     （这是这类悬浮层最容易漏的一步，我上一版 CommentDialog 专门注释过）
 *
 * <h3>与已废弃的 CommentDialog 的区别</h3>
 * 那个贴底部、占 62% 高，会盖住画面下缘 —— 这正是它被换掉的原因。
 */
class CommentDialogFragment : DialogFragment() {

    companion object {
        private const val TAG = "CmtDlg"

        private const val PAGE = 20

        /** 面板占屏宽比例。0.34 ≈ 1/3，横屏视频下遮挡最小又不挤 */
        private const val WIDTH_RATIO = 0.34f

        /** 面板占屏高比例，留上下边距让"这是浮层"更明确 */
        private const val HEIGHT_RATIO = 0.88f

        private const val ARG_AWEME = "aweme_id"
        private const val ARG_AUTHOR = "author"
        private const val ARG_DESC = "desc"

        /** 调用方统一用这个构造，避免各处手拼 key 拼错（key 拼错的后果是空白窗） */
        fun newInstance(item: DouyinApi.VideoItem): CommentDialogFragment {
            val f = CommentDialogFragment()
            f.arguments = Bundle().apply {
                putString(ARG_AWEME, item.awemeId)
                putString(ARG_AUTHOR, item.author)
                putString(ARG_DESC, item.desc)
            }
            return f
        }
    }

    private lateinit var recycler: RecyclerView
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var placeholder: View
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvPlaceholder: TextView
    private lateinit var btnRetry: TextView
    private lateinit var tvClose: TextView

    private val data = ArrayList<DouyinApi.CommentItem>()
    private lateinit var adapter: CommentAdapter

    private var awemeId = ""
    private var author = ""
    private var desc = ""

    private var cursor = 0L
    private var hasMore = true
    private var loading = false
    private val handler = Handler(Looper.getMainLooper())

    // ================================================================
    // 生命周期
    // ================================================================
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // 半透明主题：不要系统默认的标题栏/白底
        return Dialog(
            activity,
            android.R.style.Theme_Translucent_NoTitleBar
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return try {
            inflater.inflate(R.layout.dialog_comments, container, false)
        } catch (t: Throwable) {
            Log.e(TAG, "inflate 失败", t)
            null
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            awemeId = arguments?.getString(ARG_AWEME) ?: ""
            author = arguments?.getString(ARG_AUTHOR) ?: ""
            desc = arguments?.getString(ARG_DESC) ?: ""

            isCancelable = true          // 返回键可直接关掉本窗

            bindViews(view)

            // 标题也可能含 emoji（昵称），同样过一遍降级，避免 4.2 上出方块
            tvTitle.text = TextCompat.forTv("@$author ${desc.take(16)}")

            // ★ 点评论 → 评论者主页（跳转独立页，返回回播放页）
            adapter = CommentAdapter(data) { sec, name ->
                try {
                    if (sec.isBlank()) { tvStatus.text = "该评论者无主页"; return@CommentAdapter }
                    val i = Intent(activity, AuthorActivity::class.java)
                    i.putExtra("sec_uid", sec)
                    i.putExtra("author", name)
                    startActivity(i)
                } catch (_: Exception) {}
            }
            layoutManager = LinearLayoutManager(activity)
            recycler.layoutManager = layoutManager
            recycler.adapter = adapter
            recycler.setHasFixedSize(false)
            recycler.isFocusable = true
            recycler.isFocusableInTouchMode = true
            recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val last = layoutManager.findLastVisibleItemPosition()
                    if (adapter.itemCount > 0 && last >= adapter.itemCount - 3) loadMore()
                }
            })

            tvClose.setOnClickListener { dismissAllowingStateLoss() }
            btnRetry.setOnClickListener { loadMore() }

            if (awemeId.isBlank()) {
                showPlaceholder("缺少视频 ID，无法加载评论", loading = false, showRetry = false)
                return
            }

            showPlaceholder("正在加载评论…", loading = true, showRetry = false)
            loadMore()
        } catch (t: Throwable) {
            Log.e(TAG, "onViewCreated 失败", t)
            try { tvPlaceholder.text = "评论窗异常: ${t.javaClass.simpleName}" } catch (_: Throwable) {}
        }
    }

    override fun onStart() {
        super.onStart()
        // ★ 窗口定位与尺寸全部在这里设 ★
        //   必须放 onStart：此时 dialog.window 才已存在。
        try {
            val d = dialog ?: return
            val w = d.window ?: return
            val dm = resources.displayMetrics

            w.setLayout(
                (dm.widthPixels * WIDTH_RATIO).toInt(),
                (dm.heightPixels * HEIGHT_RATIO).toInt()
            )
            // 贴右边缘、垂直居中 → 落在竖屏视频两侧的黑边区域
            w.setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            // ★★ 这两行是"不遮挡视频"的关键 ★★
            //   不清 DIM_BEHIND，整个屏幕（含视频）会被系统压暗成灰蒙蒙的一片。
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0f)

            // 规则④：显式把默认焦点交给评论列表，遥控器 ↑↓ 立刻能滚
            recycler.post {
                try {
                    recycler.requestFocus()
                } catch (e: Exception) {
                    Log.w(TAG, "requestFocus 失败: ${e.message}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onStart 设置窗口失败", t)
        }
    }

    private fun bindViews(v: View) {
        recycler = v.findViewById(R.id.recyclerView) as RecyclerView
        tvTitle = v.findViewById(R.id.tvTitle) as TextView
        tvStatus = v.findViewById(R.id.tvStatus) as TextView
        placeholder = v.findViewById(R.id.placeholder)
        pbLoading = v.findViewById(R.id.pbLoading) as ProgressBar
        tvPlaceholder = v.findViewById(R.id.tvPlaceholder) as TextView
        btnRetry = v.findViewById(R.id.btnRetry) as TextView
        tvClose = v.findViewById(R.id.tvClose) as TextView
    }

    // ================================================================
    // 数据
    // ================================================================
    private fun loadMore() {
        if (loading || !hasMore) return
        // ★ 修复：先取 context 再置 loading —— 原来 activity 为 null 时
        //   return 前 loading 已置 true，之后永远无法重试（fragment 假死）
        val ctx = activity ?: return
        loading = true
        tvStatus.text = "加载评论中…"
        val c = cursor
        val cookie = Prefs.getCookie(ctx)

        Thread {
            val list = try {
                DouyinApi.fetchComments(cookie, awemeId, c, PAGE)
            } catch (e: Exception) {
                Log.e(TAG, "加载评论异常", e); null
            }
            handler.post {
                // Fragment 可能已被关闭 —— 必须检查，否则会在已销毁视图上操作
                if (!isAdded || view == null) return@post
                loading = false

                if (list == null) {
                    tvStatus.text = "加载失败：" + DouyinApi.lastError
                    if (data.isEmpty()) {
                        showPlaceholder(
                            "评论加载失败\n" + DouyinApi.lastError.ifEmpty { "未知原因" },
                            loading = false, showRetry = true
                        )
                    }
                    return@post
                }

                if (list.isEmpty()) {
                    hasMore = false
                    if (data.isEmpty()) {
                        showPlaceholder("还没有评论", loading = false, showRetry = false)
                    } else {
                        tvStatus.text = "没有更多评论了"
                    }
                    return@post
                }

                placeholder.visibility = View.GONE
                recycler.visibility = View.VISIBLE

                val old = data.size
                data.addAll(list)
                if (old == 0) adapter.notifyDataSetChanged()
                else adapter.notifyItemRangeInserted(old, list.size)

                cursor += PAGE.toLong()
                tvStatus.text = "已加载 ${data.size} 条　↑↓ 滚动　返回 关闭"
            }
        }.start()
    }

    // ================================================================
    // 占位（规则 6）
    // ================================================================
    private fun showPlaceholder(text: String, loading: Boolean, showRetry: Boolean) {
        placeholder.visibility = View.VISIBLE
        recycler.visibility = View.GONE
        pbLoading.visibility = if (loading) View.VISIBLE else View.GONE
        tvPlaceholder.text = text
        btnRetry.visibility = if (showRetry) View.VISIBLE else View.GONE
        if (showRetry) btnRetry.post { btnRetry.requestFocus() }
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        data.clear()
        super.onDestroyView()
    }
}
