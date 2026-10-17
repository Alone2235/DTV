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
import android.widget.ProgressBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页 —— 左侧垂直导航 + 右侧视频网格（参照抖音 PC 网页版）
 *
 * 与旧版的根本区别：
 *   旧版 MainActivity 是个「路由」：拉完 feed 就直接跳播放器，没有列表这一层。
 *   现在它才是真正的首页：网格浏览 → 选中某条 → 才进 [PlayerActivity]。
 *
 * TV 硬性规则落地清单：
 *   ① 所有导航项 / 卡片 focusable=true + focusableInTouchMode=true
 *   ② 焦点态用 @drawable/focus_nav_item · @drawable/focus_card（描边 + 提亮）
 *   ③ 右侧列表 = RecyclerView（替换掉原来的 ScrollView），
 *      并开启 descendantFocusability / focusable，配 GridLayoutManager
 *   ④ onCreate 末尾显式 requestFocus 到"推荐"；切分类后焦点回网格首位
 *   6  加载中 / 加载失败 / 空数据三种占位都走同一块 placeholder
 *
 * 焦点流设计（D-Pad 只有上下左右 + 确定 + 返回）：
 *   侧边栏 ←→ 网格          用 nextFocusRight / nextFocusLeft 显式打通
 *   网格内部                交给 GridLayoutManager 自动找焦点
 *   返回键                  优先退出占位态，其次才 finish
 */
class MainActivity : Activity() {

    private companion object {
        const val TAG = "Main"

        /** 网格列数：1920 宽屏下 260dp 卡片约可放 6 列，取 4 更宽松好按 */
        const val SPAN_COUNT = 5

        const val NAV_RECOMMEND = 0

        /**
         * 恢复点有效期 —— 从「最后一次证明还在播的时刻」起算（ts 与心跳取较晚者）。
         * 5 分钟足以覆盖"看完一个短视频、再往下滑、进程被杀"的整个窗口。
         */
        const val RESUME_WINDOW_MS = 5 * 60_000L
    }

    // ---- 侧边栏（★ 一步直达：内容在上、个人在下、登录沉底）----
    private lateinit var navRecommend: TextView
    private lateinit var navSearch: TextView
    private lateinit var navHistory: TextView
    private lateinit var navCollect: TextView
    private lateinit var navSettings: TextView
    private lateinit var navLogin: TextView

    // ---- 内容区 ----
    private lateinit var tvSectionTitle: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvHint: TextView
    private lateinit var gridView: RecyclerView
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var adapter: VideoGridAdapter
    // 改版：焦点信息栏（卡片纯封面后，标题/作者在此显示）
    private lateinit var tvInfoTitle: TextView
    private lateinit var tvInfoMeta: TextView
    private lateinit var infobar: android.view.View

    // ---- 占位区 ----
    private lateinit var placeholder: View
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvPlaceholder: TextView
    private lateinit var btnRetry: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val items = ArrayList<DouyinApi.VideoItem>()
    private var loading = false
    private var currentNav = NAV_RECOMMEND

    /**
     * 加载代际号 —— 防「迟到的响应盖掉新内容」。
     * 每次发起加载先自增并记下，回调时对不上就丢弃。
     * 修的是这个 bug：点分类→选类别→切回推荐，分类的响应稍后才回来，
     * 把推荐流内容覆盖成刚才那个分类的内容。
     */
    private var loadGen = 0

    // ================================================================
    // 分类定义（★ 真实可用：拉推荐流后按关键词/话题筛选）
    // ================================================================
    // 抖音的分类流接口（tab/feed?tag_id=）与搜索接口都需要额外签名，
    // 且 tag_id 无法凭猜测得到，所以采用「推荐流 + 内容筛选」的方式：
    // 拉若干轮推荐流，按关键词匹配标题 / 话题 / 作者名，归入对应分类。
    // 好处：不依赖任何不可用接口，点进去一定能看到内容。
    private val CATEGORY_KEYWORDS: LinkedHashMap<String, List<String>> = linkedMapOf(
        "推荐" to emptyList(),
        "美食" to listOf("美食", "吃", "料理", "做饭", "探店", "火锅", "小吃", "菜"),
        "游戏" to listOf("游戏", "王者", "吃鸡", "手游", "电竞", "对决", "通关"),
        "影视" to listOf("电影", "剧", "影视", "解说", "片段", "演技"),
        "音乐" to listOf("音乐", "歌", "翻唱", "演唱", "乐器", "弹唱"),
        "健身" to listOf("健身", "运动", "减肥", "瑜伽", "跑步", "锻炼", "拉伸"),
        "萌宠" to listOf("猫", "狗", "宠物", "萌宠", "动物", "铲屎"),
        "搞笑" to listOf("搞笑", "沙雕", "段子", "逗", "笑", "整活"),
        "旅行" to listOf("旅行", "旅游", "风景", "自驾", "露营", "徒步"),
        "生活" to listOf("生活", "日常", "记录", "vlog", "分享", "技巧")
    )

    /** 当前选中的分类名 */
    private var activeCategory = "推荐"

    /** 分类 Tab 控件（用于切换选中态） */
    private val tabViews = ArrayList<TextView>()

    private lateinit var tabScroll: View
    private lateinit var tabContainer: android.widget.LinearLayout
    private var lastCursor = 0L
    private var hasMore = true

    /** 首页 UI 是否已建（恢复直通时不建，等真正回到前台再补建） */
    private var uiReady = false

    /** 恢复直通后紧接着的那次 onResume 是「路过」（播放页马上盖上来），不要建 UI */
    private var skipNextResumeUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // 无 Cookie 直接去登录页（登录入口现收在「我的」页，无登录态引导去登录）
            if (!Prefs.hasCookie(this)) {
                ensureUi()
                Log.d(TAG, "无 Cookie → 登录页")
                openLogin()
                return
            }

            // ★ 崩溃自愈：冷启动检测恢复点（播放中实时存的 + 心跳的）。
            //   进程被杀 → 直接回到崩前视频；正常退出已 clearResume。
            //   过期判定看「最后一次证明还在播的时刻」（见 tryRestorePlayback）。
            //   ★ 放在建首页 UI 之前 ★：每 4 个视频一次的自杀重启都走这里，
            //   恢复直通时跳过 setContentView/侧栏/列表初始化/拉推荐流 ——
            //   这些在 Cortex-A7 上是黑屏窗口里白白烧掉的大头。
            val resumeJson = Prefs.loadResume(this)
            Prefs.trace(this, "COLD_START resume_blank=${resumeJson.isBlank()} len=${resumeJson.length}")
            if (resumeJson.isNotBlank() && tryRestorePlayback(resumeJson)) {
                return
            }

            ensureUi()

            // 进入首页立刻拉推荐流
            loadFeed(NAV_RECOMMEND, reset = true)

            // ★ 规则④：显式指定默认焦点 ★
            //   放在 post 里，等布局完成后再 requestFocus，否则会被系统清掉。
            navRecommend.post {
                navRecommend.requestFocus()
                Log.d(TAG, "默认焦点 → 推荐")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate 失败", t)
            try { tvPlaceholder.text = "启动异常: ${t.javaClass.simpleName}: ${t.message}" } catch (_: Throwable) {}
        }
    }

    /** 建首页 UI（布局 + 侧栏 + 列表 + 分类 Tab）。恢复直通时跳过，回到前台才补建 */
    private fun ensureUi() {
        if (uiReady) return
        uiReady = true
        setContentView(R.layout.activity_main)
        bindViews()
        setupNav()
        setupGrid()
        buildCategoryTabs()   // ★ 分类 Tab 常驻
        // ★ 在线更新：启动 3 秒后静默检查（GitHub 渠道），有新版才弹窗
        navRecommend.postDelayed({ checkUpdate() }, 3000)
    }

    /**
     * 检查更新（GitHub Releases 渠道）：
     * 拉 version.json 比对 versionCode → 有新版弹「更新说明 + 立即升级」→ 下载 → 调起系统安装器。
     * 失败静默（不打扰用户）；未配置仓库地址时直接跳过。
     */
    private fun checkUpdate() {
        Thread {
            val info = try { UpdateChecker.check(this) } catch (_: Throwable) { null } ?: return@Thread
            handler.post {
                if (isFinishing) return@post
                try {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("发现新版本 ${info.versionName}")
                        .setMessage(
                            "当前版本 ${BuildConfig.VERSION_NAME} → 新版本 ${info.versionName}\n\n" +
                            info.notes.ifBlank { "（无更新说明）" } + "\n\n点「立即升级」自动下载并调起安装。"
                        )
                        .setPositiveButton("立即升级") { _, _ -> downloadAndInstall(info) }
                        .setNegativeButton("稍后", null)
                        .setOnDismissListener { showControlsIfAny() }
                        .show()
                } catch (_: Throwable) {}
            }
        }.start()
    }

    private fun downloadAndInstall(info: UpdateChecker.Info) {
        android.widget.Toast.makeText(this, "开始下载更新…", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val f = UpdateChecker.download(this, info.apkUrl, info.sha256) { p ->
                if (p % 25 == 0) Log.d("Update", "下载 $p%")
            }
            handler.post {
                if (f == null) {
                    android.widget.Toast.makeText(this, "下载失败，请检查网络", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    UpdateChecker.install(this, info.sha256)
                }
            }
        }.start()
    }

    /** 弹窗关闭后把控制栏恢复（各页实现不同，这里尽力而为） */
    private fun showControlsIfAny() {
        try { navRecommend.requestFocus() } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        // 从播放页返回时：若冷启动走了「恢复直通」没建首页 UI，这里补建并拉流
        if (!uiReady) {
            if (skipNextResumeUi) {
                skipNextResumeUi = false   // 播放页正盖上来，这次是路过
                return
            }
            ensureUi()
            loadFeed(NAV_RECOMMEND, reset = true)
            navRecommend.post { navRecommend.requestFocus() }
        }
    }

    // ================================================================
    // 视图绑定
    // ================================================================
    /**
     * 崩溃自愈恢复：解析恢复点 → FeedBus 交回列表 → 直接进播放页。
     * 连续在同一位置恢复 2 次（IJK 反复崩）→ 强制 VLC 兜底（引擎 3）。
     */
    private fun tryRestorePlayback(json: String): Boolean {
        try {
            val root = org.json.JSONObject(json)
            val now = System.currentTimeMillis()
            // ★ 过期判定改看「最后一次证明还在播的时刻」★
            //   JSON 里的 ts 只在起播那一刻写一次 = 【上一个视频的起播时间】，
            //   不是死亡时间。看一个视频超过窗口再往下滑，崩溃时 ts 早已过期，
            //   恢复点被白白清掉 → 用户被丢回推荐页、而且再也回不去。
            //   现在取 max(起播时间, 心跳时间)：心跳由播放中每 5 秒刷新。
            val lastAlive = maxOf(root.optLong("ts", 0L), Prefs.resumeAliveAt(this))
            val ageSec = (now - lastAlive) / 1000
            if (now - lastAlive > RESUME_WINDOW_MS) {
                Prefs.trace(this, "RESTORE expired age=${ageSec}s > ${RESUME_WINDOW_MS / 1000}s -> 推荐页")
                Log.d(TAG, "恢复点已过期(${ageSec}s) → 回首页")
                Prefs.clearResume(this)
                return false
            }
            val arr = root.getJSONArray("items")
            val list = ArrayList<DouyinApi.VideoItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = ArrayList<String>()
                val ta = o.optJSONArray("t")
                if (ta != null) for (j in 0 until ta.length()) t.add(ta.getString(j))
                val al = ArrayList<String>()
                val aa = o.optJSONArray("alt")
                if (aa != null) for (j in 0 until aa.length()) al.add(aa.getString(j))
                list.add(
                    DouyinApi.VideoItem(
                        o.getString("id"), o.getString("d"), o.getString("a"),
                        o.getString("u"), o.getString("c"), o.getInt("dur"),
                        o.getString("sec"), o.getInt("digg"), o.getInt("cmt"),
                        t, al, o.optBoolean("pt", false)
                    )
                )
            }
            if (list.isEmpty()) { Prefs.trace(this, "RESTORE empty_items -> 推荐页"); Prefs.clearResume(this); return false }
            val idx = root.getInt("idx").coerceIn(0, list.size - 1)
            val src = root.optString("src", FeedBus.SOURCE_FEED)
            val retry = root.optInt("retry", 0) + 1
            // ★ 81.5: retry 必须写回（原来只加不存 → >=2 永不成立 → 熔断死代码）
            root.put("retry", retry)
            Prefs.saveResume(this, root.toString())

            // ★ 先分清「计划内重启」和「真崩溃」★
            //   每 4 个视频一次的自杀（绕固件起播计数）在冷启动侧看起来和崩溃一模一样，
            //   不区分就会被当成连崩累积 → 误把用户从自选的 IJK 强制切到别的引擎。
            val planned = now - Prefs.plannedRestartAt(this) < 60_000L
            if (planned) {
                Prefs.trace(this, "RESTORE planned=true idx=$idx age=${ageSec}s -> 播放页")
                Log.i(TAG, "计划内重启恢复 → 第 ${idx + 1} 条")
            } else {
                val streak = Prefs.bumpCrashStreak(this)
                Prefs.trace(this, "RESTORE planned=false idx=$idx streak=$streak age=${ageSec}s -> 播放页")
                // ★ 82.x: 熔断策略改为「IJK 崩 1 次 → 自动降级 Exo」，不再等崩 2 次才切 VLC。
                //   真机实测（10-08）：IJK 在这台 MStar 电视上每 4~6 个视频稳定 native 崩溃
                //   （3 条不同路径：Mali EGL abort / MediaCodec CSD / ff_read 撞 OMX freeNode，
                //   均见 /data/tombstones），逐个修是打地鼠。Exo（复用实例，不重建解码器）
                //   实测刷 18 个不崩。折中：开头几个视频享受 IJK 流畅，崩一次立刻切 Exo，
                //   保证后面不黑屏、也不会落到声音更差的 VLC。
                //   注意：仅当当前引擎是 IJK 时降级；用户手动选的其它引擎不覆盖。
                val curEngine = Prefs.getEngine(this)
                if (streak >= 1 && curEngine == 1) {
                    Prefs.setEngine(this, 0)   // 0 = ExoPlayer（复用实例，这台机上不崩）
                    Prefs.resetCrashStreak(this)
                    Log.w(TAG, "IJK 崩溃（${streak} 次）→ 自动降级 ExoPlayer 引擎")
                }
            }
            FeedBus.put(list, idx, src)
            // 恢复直通：本次没建首页 UI，紧接着的 onResume 是「路过」，标记跳过
            skipNextResumeUi = true
            startActivity(Intent(this, PlayerActivity::class.java))
            return true
        } catch (e: Exception) {
            Prefs.trace(this, "RESTORE parse_fail ${e.javaClass.simpleName}: ${e.message} -> 推荐页")
            Log.w(TAG, "恢复点解析失败: ${e.message}")
            Prefs.clearResume(this)
            return false
        }
    }

    private fun bindViews() {
        navRecommend = findViewById(R.id.navRecommend) as TextView
        navSearch = findViewById(R.id.navSearch) as TextView
        navHistory = findViewById(R.id.navHistory) as TextView
        navCollect = findViewById(R.id.navCollect) as TextView
        navSettings = findViewById(R.id.navSettings) as TextView
        navLogin = findViewById(R.id.navLogin) as TextView

        tvSectionTitle = findViewById(R.id.tvSectionTitle) as TextView
        tvCount = findViewById(R.id.tvCount) as TextView
        tvHint = findViewById(R.id.tvHint) as TextView
        gridView = findViewById(R.id.gridView) as RecyclerView
        tvInfoTitle = findViewById(R.id.tvInfoTitle) as TextView
        tvInfoMeta = findViewById(R.id.tvInfoMeta) as TextView
        infobar = findViewById(R.id.infobar)

        tabScroll = findViewById(R.id.tabScroll)
        tabContainer = findViewById(R.id.tabContainer) as android.widget.LinearLayout
        placeholder = findViewById(R.id.placeholder)
        pbLoading = findViewById(R.id.pbLoading) as ProgressBar
        tvPlaceholder = findViewById(R.id.tvPlaceholder) as TextView
        btnRetry = findViewById(R.id.btnRetry) as TextView
    }

    // ================================================================
    // 侧边栏
    // ================================================================
    private fun setupNav() {
        // 规则①：可操作控件必须显式可聚焦（老 ROM 上布局里的 focusable 有时不生效，
        //   代码里再兜一次，成本极低但能避免"看得见按不到"）
        val navs = arrayOf(navRecommend, navSearch, navHistory, navCollect, navSettings, navLogin)
        for (n in navs) {
            n.isFocusable = true
            n.isFocusableInTouchMode = true
            // 规则③防焦点飞出：侧边栏在左，右键一律进网格
            n.nextFocusRightId = R.id.gridView
            // ★ 统一动效：焦点放大(1.08) + 按下回弹（FocusAnim 全应用同入口）
            n.setOnFocusChangeListener { v, has -> FocusAnim.scaleTo(v, has, 1.08f) }
        }

        // ★ 一步直达：点哪进哪，不经过任何中转页
        navRecommend.setOnClickListener { switchNav(NAV_RECOMMEND) }
        navSearch.setOnClickListener { openSearch() }
        navHistory.setOnClickListener { openHistory() }
        navCollect.setOnClickListener { openCollect() }
        navSettings.setOnClickListener { openSettings() }
        navLogin.setOnClickListener { openLogin() }
    }

    private fun openSearch() {
        startActivity(Intent(this, SearchActivity::class.java))
    }

    private fun openHistory() {
        startActivity(Intent(this, HistoryActivity::class.java))
    }

    private fun openCollect() {
        startActivity(Intent(this, CollectActivity::class.java))
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
    }

    /** 点「推荐」：回首页并切回推荐档（分类 Tab 常驻，点 Tab 即切频道） */
    private fun switchNav(nav: Int) {
        if (currentNav == nav) return
        currentNav = nav
        markNavSelected()
        activeCategory = "推荐"
        markCategorySelected()
        tvSectionTitle.text = "推荐"
        loadFeed(NAV_RECOMMEND, reset = true)
        gridView.post { gridView.requestFocus() }
    }

    private fun markNavSelected() {
        navRecommend.isSelected = currentNav == NAV_RECOMMEND
    }

    // ================================================================
    // 网格
    // ================================================================
    private fun setupGrid() {
        layoutManager = GridLayoutManager(this, SPAN_COUNT)
        gridView.layoutManager = layoutManager
        adapter = VideoGridAdapter(items) { pos -> playAt(pos) }
        // 改版：焦点项 -> 信息栏
        adapter.onFocusItem = { title, meta ->
            tvInfoTitle.text = title
            tvInfoMeta.text = meta
            // 改版：没有内容时隐藏信息栏，避免出现一条空黑条
            infobar.visibility = if (title.isBlank() && meta.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        }
        gridView.adapter = adapter
        // 规则③：RecyclerView 自身可聚焦 + 子项优先获得焦点
        gridView.isFocusable = true
        gridView.isFocusableInTouchMode = true
        // 规则③防飞出：网格在右，左键回侧边栏
        gridView.nextFocusLeftId = R.id.navRecommend

        // 滚到底自动翻页（cursor 分页）
        gridView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val total = layoutManager.itemCount
                val last = layoutManager.findLastVisibleItemPosition()
                if (hasMore && !loading && total > 0 && last >= total - 3) {
                    // 分类 Tab 选中「推荐」→ 推荐流翻页；其它分类 → 分类搜索翻页
                    if (activeCategory != "推荐") loadCategoryFeed(activeCategory, false)
                    else loadFeed(NAV_RECOMMEND, reset = false)
                }
            }
        })
    }

    /** 选中某条 → 交给内存总线 → 进播放页 */
    private fun playAt(pos: Int) {
        if (pos < 0 || pos >= items.size) return
        val source = FeedBus.SOURCE_FEED
        // 走内存总线而不是 Intent extra：
        //   传大列表会逼近 Binder 1MB 上限 → TransactionTooLarge → "点进去播不了"
        FeedBus.put(items, pos, source)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    // ================================================================
    // 分类：Tab 条（★ 常驻主界面顶部，点 Tab 即切频道）
    // ================================================================
    /** 构建分类 Tab（只构建一次；主界面常驻显示） */
    private fun buildCategoryTabs() {
        if (tabViews.isNotEmpty()) return      // 已构建过

        val dp = resources.displayMetrics.density
        val padH = (18 * dp).toInt()
        for ((name, _) in CATEGORY_KEYWORDS) {
            val tv = TextView(this)
            tv.text = name
            tv.textSize = 17f
            tv.gravity = android.view.Gravity.CENTER
            tv.isFocusable = true
            tv.isFocusableInTouchMode = true
            tv.setPadding(padH, 0, padH, 0)
            tv.height = (38 * dp).toInt()
            tv.background = resources.getDrawable(R.drawable.tab_item_bg)
            tv.setTextColor(resources.getColorStateList(R.color.tab_text))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = (10 * dp).toInt()
            tv.layoutParams = lp
            tv.setOnClickListener { FocusAnim.press(tv); onCategoryClick(name) }
            tv.setOnFocusChangeListener { v, has -> FocusAnim.scaleTo(v, has, 1.08f) }
            tabContainer.addView(tv)
            tabViews.add(tv)
        }
        markCategorySelected()
    }

    private fun onCategoryClick(name: String) {
        if (activeCategory == name && items.isNotEmpty()) return
        activeCategory = name
        markCategorySelected()
        tvSectionTitle.text = name   // ★ 顶栏标题随分类频道切换
        loadCategoryFeed(name, reset = true)
        gridView.post { gridView.requestFocus() }
    }

    private fun markCategorySelected() {
        val names = CATEGORY_KEYWORDS.keys.toList()
        tabViews.forEachIndexed { i, tv ->
            tv.isSelected = i < names.size && names[i] == activeCategory
        }
    }

    /**
     * 分类内容加载 —— 直接用搜索接口（真分类）
     *
     * 改前：拉若干轮推荐流再按关键词过滤，命中随机、常常空手 —— 用户吐槽
     *       "只从推荐流里选，分了个寂寞"。
     * 改后：分类名直接当关键词打搜索接口（搜索已实测可用：登录 Cookie +
     *       干净参数，不带任何签名），点「美食」就是真的美食内容。
     *
     * 「推荐」档不走搜索，仍是推荐流。
     */
    private fun loadCategoryFeed(cat: String, reset: Boolean) {
        // 「推荐」= 原生推荐流
        if (cat == "推荐") {
            loadFeed(NAV_RECOMMEND, reset)
            return
        }

        // ★ 抢占式加载：不再 if (loading) return（那会让切导航后的新加载被旧请求挡回去），
        //   改为自增代际、旧响应回来时对不上就丢弃。
        val gen = ++loadGen
        loading = true
        val offset = if (reset) 0 else items.size
        if (reset) {
            items.clear()
            adapter.replaceAll(items)
            lastCursor = 0L
            showPlaceholder("正在加载「" + cat + "」…", loading = true, showRetry = false)
            gridView.visibility = View.GONE
        }

        Thread {
            val cookie = Prefs.getCookie(this)
            val r = try {
                DouyinApi.searchVideos(cookie, cat, offset)
            } catch (e: Exception) {
                null
            }
            handler.post {
                if (gen != loadGen) return@post   // ★ 已被更新的加载取代，丢弃这份迟到结果
                if (isFinishing) return@post      // ★ 修复：Activity 已销毁不再碰视图
                loading = false
                if (r == null || r.videos.isEmpty()) {
                    if (items.isEmpty()) {
                        showPlaceholder(
                            "「" + cat + "」没取到内容\n\n" + DouyinApi.lastError +
                                "\n\n可在设置页重新推送一次 Cookie。",
                            loading = false, showRetry = true
                        )
                        gridView.visibility = View.GONE
                    } else {
                        hasMore = false
                        tvHint.text = "没有更多了"
                    }
                } else {
                    if (reset) adapter.replaceAll(r.videos) else adapter.append(r.videos)
                    hidePlaceholder()
                    gridView.visibility = View.VISIBLE
                    tvCount.text = "共 " + adapter.itemCount + " 条"
                    tvHint.text = "分类 · 搜索「" + cat + "」"
                    hasMore = r.hasMore
                    lastCursor = (offset + r.videos.size).toLong()
                }
            }
        }.start()
    }

    // ================================================================
    // 数据加载
    // ================================================================
    private fun loadFeed(nav: Int, reset: Boolean) {
        // ★ 同上：抢占式，靠代际丢弃迟到的旧响应
        val gen = ++loadGen
        loading = true
        if (reset) {
            items.clear()
            adapter.notifyDataSetChanged()
            lastCursor = 0L
            hasMore = true
            showPlaceholder("正在加载…", loading = true, showRetry = false)
        } else {
            tvHint.text = "加载更多…"
        }

        Thread {
            var result: DouyinApi.FeedResult? = null
            var err: String? = null
            var netOk = true
            try {
                val cookie = Prefs.getCookie(this)
                // ★ cursor 分页：fetchFeed 的第二个参数就是 maxCursor
                //   （底层早就支持，只是旧首页"拉一次就跳播放器"从没用上）
                result = DouyinApi.fetchFeed(
                    cookie,
                    maxCursor = if (reset) 0L else lastCursor
                )
            } catch (e: Exception) {
                Log.e(TAG, "feed 异常", e)
                err = e.message
            }
            // ★ 网络探测必须放后台线程：buildDiagnosis 原来在主线程同步执行
            //   OkHttp（connectTimeout 20s）→ 失败占位显示时 UI 卡死甚至 ANR。
            //   只在失败时才探测，成功路径零额外开销。
            if (result == null) {
                try { netOk = DouyinApi.probe() } catch (_: Exception) {}
            }

            val r = result
            handler.post {
                if (gen != loadGen) return@post   // ★ 已被更新的加载取代（例如切了导航），丢弃
                if (isFinishing) return@post      // ★ 修复：Activity 已销毁不再碰视图
                loading = false
                if (r != null && r.videos.isNotEmpty()) {
                    lastCursor = r.maxCursor
                    hasMore = r.hasMore
                    if (reset) adapter.replaceAll(r.videos) else adapter.append(r.videos)
                    hidePlaceholder()
                    gridView.visibility = View.VISIBLE
                    tvCount.text = "共 ${adapter.size()} 条"
                    tvHint.text = if (hasMore) "↓ 继续加载" else "已到底"
                    Log.d(TAG, "加载 ${r.videos.size} 条，累计 ${adapter.size()}")
                } else {
                    if (reset) {
                        // 空数据 vs 加载失败，给不同占位（规则 6）
                        val diag = buildDiagnosis(netOk)
                        showPlaceholder(
                            if (r != null) "这里还没有内容\n\n换个分类试试？" else diag,
                            loading = false, showRetry = true
                        )
                        gridView.visibility = View.GONE
                    } else {
                        // ★ 修复：翻页失败（异常）≠ 没有更多。
                        //   原来 r==null 也走"没有更多了"+hasMore=false，
                        //   一次瞬时网络抖动就永久停止翻页且无法重试。
                        if (r == null) {
                            tvHint.text = "加载失败（${DouyinApi.lastError.ifEmpty { err ?: "网络异常" }}）↓ 继续下滑重试"
                            Log.w(TAG, "feed 翻页失败: ${err ?: DouyinApi.lastError}")
                        } else {
                            tvHint.text = "没有更多了"
                            hasMore = false
                        }
                    }
                }
            }
        }.start()
    }

    // ================================================================
    // 占位视图（规则 6：Loading / 网络错误 / 空数据）
    // ================================================================
    private fun showPlaceholder(text: String, loading: Boolean, showRetry: Boolean) {
        placeholder.visibility = View.VISIBLE
        this.pbLoading.visibility = if (loading) View.VISIBLE else View.GONE
        tvPlaceholder.text = text
        btnRetry.visibility = if (showRetry) View.VISIBLE else View.GONE
        if (showRetry) {
            // 规则④：占位态下把焦点交给重试按钮，否则遥控器无处可去
            btnRetry.post { btnRetry.requestFocus() }
        }
    }

    private fun hidePlaceholder() {
        placeholder.visibility = View.GONE
    }

    /** 失败诊断（电视上看不到 logcat，把线索直接摆出来） */
    private fun buildDiagnosis(netOk: Boolean): String {
        val sb = StringBuilder("加载失败\n\n")
        sb.append("原因: ").append(DouyinApi.lastError.ifEmpty { "未知（无异常）" }).append("\n\n")
        sb.append("系统时间: ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
            .append("\n  （时间不对会让 TLS 证书校验失败）\n")
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE)
            .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
        sb.append("机型: ").append(DeviceProfile.summary).append("\n")
        sb.append("TLS 后端: ").append(App.tlsBackend).append("\n")
        sb.append("证书校验: ")
            .append(if (DouyinApi.tlsDowngraded) "已降级（老设备信任库缺根 CA）" else "严格")
            .append("\n")
        sb.append("Cookie: ").append(Prefs.getCookie(this).length).append(" 字符\n")
        sb.append("网络: ").append(if (netOk) "可达" else "不可达 ✗")
        return sb.toString()
    }

    // ================================================================
    // 遥控器
    // ================================================================
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // 规则 5①：返回键优先处理弹层/占位，再退回上一级
                KeyEvent.KEYCODE_BACK -> {
                    if (placeholder.visibility == View.VISIBLE) {
                        hidePlaceholder()
                        gridView.visibility = View.VISIBLE
                        gridView.post { gridView.requestFocus() }
                        return true
                    }
                    finish()
                    return true
                }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                    openSettings()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
