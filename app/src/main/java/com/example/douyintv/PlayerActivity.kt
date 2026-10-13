package com.example.douyintv

import tv.danmaku.ijk.media.player.IjkMediaPlayer
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import okhttp3.Request

/**
 * 刷视频界面
 *
 * 遥控器：↑/↓ 换视频（快到底自动加载） | ←/→ 快退/快进 10s
 *         OK 暂停/继续 | 菜单键 打开功能菜单 | BACK 退出
 * 触  屏：上/下滑换视频 | 左/右滑快退快进 | 单击暂停
 *
 * 菜单里可进：作者主页 / 评论区 / 播放模式（自动连播·单集循环）/ 切换引擎 / 设置
 * 控制栏 3 秒自动淡出，不再遮挡画面。
 */
class PlayerActivity : Activity(), SurfaceHolder.Callback, VideoEngine.Listener, PlayerMemoryAware {

    companion object {
        private const val TAG = "Player"
        private const val REFERER = "https://www.douyin.com/"
        private const val SWIPE_MIN = 70f
        private const val SEEK_STEP = 10000L
        private const val HIDE_DELAY = 3000L

        // ---- 画面比例 ----
        // ★ v11 方案：SurfaceView 铺满全屏，比例交给解码器原生 letterbox。
        //   这正是 v11「播放完全正常」的做法 —— 不需要任何容器尺寸约束。
        //   旧的手动档位（拉伸 / 16:9 / 4:3）靠 AspectFrame 改容器尺寸实现，
        //   而那正是在 Mali450 上产生黑方块的机制，已整体移除。

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        /** 评论小窗的 Fragment tag（用于判断是否已经开着） */
        private const val TAG_COMMENT = "frag_comment"

    /**
     * IJK 连刷节流：1.5 秒内不允许连切。
     * 早期版本不崩正是因为切换慢（native 线程有充分时间退出）；
     * 性能优化后切换变快 → ff_read 撞 OMX 析构竞态 → 第 5 个必崩。
     * 节流只影响快速连按，正常看视频节奏完全无感。
     */
    private const val IJK_SWITCH_GUARD_MS = 1500L

    // ---- C1: 内存探针触发重建的阈值（按机型内存比例，不写死）----
    // 判读依据：mem_log.txt 里 PLAY#/seq 行的 native= / fd= / rss= 曲线。
    // 老设备（647MB/96MB 堆）实测稳定值：native~25MB、fd~100 上下；此处按
    // DeviceProfile.memoryClassMB 等比放大，正常设备不会过早误触发。
    private val nativeHeapLimitMB: Int get() = DeviceProfile.memoryClassMB * 4 / 5   // 96MB→76，256MB→204
    private val fdLimit: Int = 180                                                     // fd 上限设备相关，保守通用
    private val rssLimitMB: Int get() = DeviceProfile.memoryClassMB * 15 / 4           // 96MB→360，256MB→960
    private const val MIN_PLAYED_BEFORE_RESTART = 3 // 至少播 3 个才允许探针触发，避免起播瞬间误判
    }

    private lateinit var surface: SurfaceView
    private lateinit var aspectFrame: AspectFrame
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var tvAuthor: TextView
    private lateinit var tvDesc: TextView
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var tvStatus: TextView
    private lateinit var ivPause: TextView
    private lateinit var seekBar: ProgressBar
    private lateinit var progressBar: ProgressBar
    private lateinit var tvHint: TextView

    /** 底栏功能按钮，顺序 = 遥控器 ←→ 的导航顺序 */
    private lateinit var btnViews: List<TextView>
    private var btnFocus = 0

    private var engines: List<VideoEngine> = emptyList()
    private var engineIndex = 0

    private var items: List<DouyinApi.VideoItem> = emptyList()
    private var currentIndex = 0
    private var hasMore = true
    private var loadingMore = false

    private var holderReady: SurfaceHolder? = null
    private var lastVw = 0
    /** 当前视频是否已 prepare 成功 —— 用于区分「首次起播」与「Surface 重建仅重绑」 */
    private var preparedOnce = false
    private var lastVh = 0

    private var playToken = 0
    /** 81.4: 上次已就绪的 token（onPrepared 判重防虚涨） */
    private var lastPreparedToken = -1
    /** 黑屏看门狗：当前视频是否已出过首帧（onVideoSize = 出帧信号） */
    private var firstFrameForToken = -1
    /** 黑屏看门狗：当前视频是否已自动重载过一次（防死循环） */
    private var blackRetriedForToken = -1
    /** 比例容器是否已为当前视频应用过（每视频一次，防比例抖动反复重建 Surface） */
    private var aspectAppliedToken = -1
    /** 弹幕层 */
    private var danmaku: DanmakuView? = null

    // ---- 播放诊断（给真机排查用：卡在“网络喂不饱”还是“解码/渲染跟不上”）----
    private var perfLine = ""

    // ---------------- 播放页自我重建（治 IJK 的 native 资源累积）----------------
    /**
     * 已连播的视频数量。
     *
     * 为什么要计数：真机实测，在同一个 PlayerActivity 里连播时，
     * IJK 的 Graphics 内存从 7.6MB 一路涨到 34.6MB（约 3~4 个硬解 overlay），
     * 且退出播放页也不回落 —— 源码里 overlay 只在 SDL_Vout 销毁时才批量释放，
     * 而单次 release 并不能保证 vout 被销毁。系统 MemFree 被挤到 27MB 后
     * 进程就被杀掉，表现为「刷着刷着退回首页」。
     */
    private var playedCount = 0
    /** ★ 本位置是否已连播够久（>30s）→ 作为「这次恢复是健康的」信号，用来清零崩溃连击 */
    private var healthyMarked = false
    /** KILLCHASE：切换计数（写文件定位"到点必死"的确定性触发） */
    private var killSwitch = 0
    // ★ 内存探针：每 2 秒采样 Java/Native 内存写盘（被杀前轨迹分析用），
    //   同时兼任「泄漏驱动的主动重建」触发器（见 maybeRestartByMemory）
    private val memTick = object : Runnable {
        private var seq = 0
        override fun run() {
            try {
                seq++
                val jused = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024
                val jmax = Runtime.getRuntime().maxMemory() / 1024 / 1024
                val nheap = try { android.os.Debug.getNativeHeapAllocatedSize() / 1024 / 1024 } catch (_: Throwable) { -1 }
                val sb = StringBuilder()
                sb.append(java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date()))
                sb.append(" seq=").append(seq)
                sb.append(" java=").append(jused).append("/").append(jmax).append("MB")
                if (nheap >= 0) sb.append(" native=").append(nheap).append("MB")
                val img = try { ImageLoader.cacheSize() / 1024 / 1024 } catch (_: Throwable) { -1 }
                if (img >= 0) sb.append(" img=").append(img).append("MB")
                val f = java.io.File(filesDir, "mem_log.txt")
                val prev = if (f.exists()) f.readText() else ""
                val lines = prev.split("\n").filter { it.isNotBlank() }
                val keep = lines.takeLast(299)
                f.writeText((keep + sb.toString()).joinToString("\n") + "\n")
            } catch (_: Throwable) {}
            // ★ C1：泄漏逼近危险水位 → 在系统 LMK / 内核 OOM 之前主动重建。
            //   旧的固定「第 4 个自杀」假设是"固件第 5 次起播杀"——已被"其他引擎不死"
            //   证伪（泄漏是 IJK 特有，非固件/进程级），故改为内存驱动：健康时不打扰。
            maybeRestartByMemory()
            handler.postDelayed(this, 2000)
        }
    }

    /** 正在自我重建：onDestroy 里据此跳过 FeedBus.clear()，否则列表会被清空导致重建后无片可播 */
    private var restarting = false

    /**
     * 连播多少个视频后重建播放页（纯兜底计数）。
     *
     * ★ 82.x 起由「固定计数」改为「内存探针优先、计数兜底」：
     *   旧值 4 建立在"固件第 5 次起播杀"假设上（81.1），但"Exo/VLC/系统播放器
     *   都不死"的事实证明第 5 个死是 IJK 特有的 native 资源泄漏（OMX/MediaCodec/
     *   vout overlay 反复申请释放），不是固件计数。计数只做最后保险，
     *   真正触发交给 maybeRestartByMemory() 的阈值探针 —— 健康视频一次都不重启。
     */
    /** 连播多少视频后重建播放页（纯兜底计数；高配机器放大到 60，避免误杀） */
    private val maxPlayBeforeRestart: Int get() = if (DeviceProfile.isLegacy) 20 else 60
    /** 是否处于「缓冲中」—— IJK 没有 bufferedPercentage API，用它替代水位显示 */
    private var buffering = false
    private val perfTick = object : Runnable {
        override fun run() {
            try { samplePerf() } catch (_: Throwable) {}
            handler.postDelayed(this, 2000)
        }
    }
    private var controlsVisible = true

    /**
     * 数据来源（feed / author / search）。
     * 用于「数据源隔离」：滑到底时按来源决定继续拉推荐流还是该作者作品，
     * 避免在作者页滑着滑着混进首页推荐。
     */
    private var feedSource = FeedBus.SOURCE_FEED

    /**
     * 是否处于暂停态。
     *
     * ★ 控制栏自动隐藏策略完全依赖这个标志：
     *   播放中 → 显示后 3 秒自动隐藏
     *   暂停中 → 保持可见，不自动隐藏（用户要操作）
     *
     * 之前是反的（播放常驻、暂停反而消失），根因是"暂停"分支传了
     * autoHide=false 却在其它 15 处被重新 showControls() 覆盖了计时器状态。
     */
    private var paused = false

    // ---- 错误抖动抑制 ----
    private var lastErrorAt = 0L
    private var retrySameEngine = 0

    /** 播放倍速（手机遥控可调；Exo/IJK 生效） */
    private var playSpeed = 1.0f

    /** 当前使用第几条线路（0 = 主链，其余为备用链） */
    private var urlAttempt = 0

    /** 最近错误（电视上看不到 logcat，放进菜单里可查） */
    private val errHistory = ArrayList<String>()

    private val handler = Handler(Looper.getMainLooper())
    private val headers = mapOf("Referer" to REFERER, "User-Agent" to UA)

    private val hideTask = Runnable { hideControls() }

    private val progressTick = object : Runnable {
        private var aliveSeq = 0
        override fun run() {
            try {
                val e = currentEngine()
                val pos = e.position()
                val dur = e.duration()
                if (dur > 0) {
                    seekBar.progress = ((pos.toFloat() / dur) * 1000f).toInt().coerceIn(0, 1000)
                    tvPos.text = fmt(pos)
                    tvDur.text = fmt(dur)
                }
                // ★ 手机遥控状态快照（ConfigServer /status 读它展示）
                try {
                    val snap = PlayerBus.snapshot
                    snap.title = cur()?.desc ?: ""
                    snap.author = cur()?.author ?: ""
                    snap.positionMs = pos
                    snap.durationMs = dur
                    snap.playing = !paused && e.isPlaying()
                    val aid = cur()?.awemeId
                    snap.digged = aid?.let { id -> try { Prefs.isDigged(this@PlayerActivity, id) } catch (_: Exception) { false } } == true
                    snap.collected = aid?.let { id -> try { Prefs.isCollected(this@PlayerActivity, id) } catch (_: Exception) { false } } == true
                    snap.engine = currentEngine().name
                    snap.danmaku = Prefs.getDanmaku()
                    snap.speed = playSpeed
                } catch (_: Throwable) {}
                // ★ 恢复点心跳 ★
                //   恢复窗口的过期判定必须基于「进程此刻还活着」，而不是
                //   「上一个视频的起播时刻」—— 否则看一个视频超过窗口再往下滑，
                //   崩溃时恢复点会被误判过期清掉 → 用户被丢回推荐页（还回不去）。
                //   每 5 秒只写一个 long，开销可忽略。
                if (items.isNotEmpty() && ++aliveSeq % 10 == 0) {
                    try { Prefs.touchResumeAlive(this@PlayerActivity) } catch (_: Throwable) {}
                }
                // ★ 健康信号 ★
                //   用「本位置连播超过 30 秒」判定这次恢复是健康的，而不是
                //   「起播成功」—— 崩溃恰恰发生在起播成功之后的下一次切换，
                //   用起播成功清零的话，连崩计数永远累加不起来（熔断形同虚设）。
                if (!healthyMarked && pos > 30_000L) {
                    healthyMarked = true
                    try { Prefs.resetCrashStreak(this@PlayerActivity) } catch (_: Throwable) {}
                }
                // ★ 黑屏看门狗 ★
                //   起播后位置正常往前走但首帧迟迟不到（onVideoSize 未触发）= 黑屏。
                //   自动重载一次（每视频最多一次），把「黑屏卡死」变成「黑屏后自动恢复」。
                //   buffering 时不触发（那只是缓冲中，画面本来就没出来）。
                if (preparedOnce && !paused && !buffering &&
                    firstFrameForToken != playToken && blackRetriedForToken != playToken &&
                    pos > 5000L) {
                    blackRetriedForToken = playToken
                    Log.w(TAG, "黑屏看门狗：位置 ${pos}ms 仍无首帧 → 自动重载一次")
                    flash("黑屏，自动重载中…")
                    playCurrent()
                }
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 500)
        }
    }

    private val gestureDetector: GestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!controlsVisible) { showControls(); return true }
                togglePlayPause(); return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { togglePlayPause(); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (Math.abs(dy) > Math.abs(dx)) {
                    if (dy < -SWIPE_MIN) nextVideo() else if (dy > SWIPE_MIN) prevVideo()
                } else {
                    if (dx < -SWIPE_MIN) doSeek(SEEK_STEP) else if (dx > SWIPE_MIN) doSeek(-SEEK_STEP)
                }
                return true
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            setContentView(R.layout.activity_player)

            surface = findViewById(R.id.surface) as SurfaceView

        // ★ 播放页用不到任何封面图：把首页积累的图片缓存压到 2MB ★
        //   这台电视空闲内存常年只有 30MB 左右，让出这十几 MB
        //   能给解码器/缓冲留出余量，显著降低被系统杀掉(退回首页)的概率。
        try { ImageLoader.trimTo(2 * 1024 * 1024) } catch (_: Throwable) {}
            aspectFrame = findViewById(R.id.aspectFrame) as AspectFrame
            topBar = findViewById(R.id.topBar)
            bottomBar = findViewById(R.id.bottomBar)
            tvAuthor = findViewById(R.id.tvAuthor) as TextView
            tvDesc = findViewById(R.id.tvDesc) as TextView
            tvPos = findViewById(R.id.tvPos) as TextView
            tvDur = findViewById(R.id.tvDur) as TextView
            tvStatus = findViewById(R.id.tvStatus) as TextView
            ivPause = findViewById(R.id.ivPause) as TextView
            seekBar = findViewById(R.id.seekBar) as ProgressBar
            progressBar = findViewById(R.id.progressBar) as ProgressBar
            tvHint = findViewById(R.id.tvHint) as TextView
            danmaku = try { findViewById(R.id.danmaku) as DanmakuView } catch (_: Throwable) { null }
            PlayerBus.attach(remoteListener)
            PlayerBus.snapshot.inPlayer = true
            if (Prefs.getDanmaku()) applyDanmaku()

            // ★ 功能按钮直接摆在底栏：←→ 就能选中、OK 进入
            //   （不再让用户去猜「菜单键」在哪 —— 电视遥控器还可能压根没有 MENU 键）
            btnViews = listOf(
                findViewById(R.id.btnPlay) as TextView,
                findViewById(R.id.btnAuthor) as TextView,
                findViewById(R.id.btnTopic) as TextView,
                findViewById(R.id.btnComment) as TextView,
                findViewById(R.id.btnSearch) as TextView,
                findViewById(R.id.btnDigg) as TextView,
                findViewById(R.id.btnCollect) as TextView,
                findViewById(R.id.btnMenu) as TextView
            )
            btnViews.forEachIndexed { i, v ->
                v.setOnClickListener { FocusAnim.press(v); triggerBtn(i) }
                v.setOnFocusChangeListener { view, has -> FocusAnim.scale(view, has) }
            }
            tvHint.text = "←→ 选择　OK 进入"

            // ★ 播放列表改从内存总线取（零拷贝），不再解析 Intent 里的 JSON。
            //   原实现用 gson 反序列化大列表：既违反"禁 Gson"红线，
            //   又可能因 Binder 事务上限（~1MB）抛 TransactionTooLargeException
            //   → 用户看到的就是「作者页点进去播不了」。
            items = FeedBus.peek() ?: emptyList()
            currentIndex = FeedBus.startIndex()
                .coerceIn(0, maxOf(0, items.size - 1))
            feedSource = FeedBus.source()
            // 推荐流 / 作者页能继续加载；搜索 / 历史是固定集合，不再翻页
            hasMore = feedSource == FeedBus.SOURCE_FEED || feedSource == FeedBus.SOURCE_AUTHOR

            // IJK native 库：软解兜底引擎必需。★ 用户实测三个引擎里 IJK 最流畅 ★
            // （Mali450 那代硬解驱动不稳，软解反而更顺）
            try {
                IjkMediaPlayer.loadLibrariesOnce(null)
                IjkMediaPlayer.native_profileBegin("libijkplayer.so")
            } catch (e: Throwable) {
                Log.w(TAG, "IJK 库加载失败: ${e.message}")
            }

            val list = mutableListOf<VideoEngine>()
            // ★ 引擎顺序与 v11 一致：ExoPlayer 第一 ★
            //   （v11 反编译确认：Exo → IJK → 系统；默认 0 = ExoPlayer）
            //   ExoEngine 实现已逐行对齐 v11（setVideoSurfaceHolder +
            //   ProgressiveMediaSource），黑方块/比例问题随之一并消除。
            try { list.add(ExoEngine(this, headers)); Log.i(TAG, "✓ ExoPlayer") }
            catch (t: Throwable) { Log.e(TAG, "✗ ExoPlayer: ${t.javaClass.simpleName}: ${t.message}") }
            try { list.add(IjkEngine(headers)); Log.i(TAG, "✓ IJKPlayer") }
            catch (t: Throwable) { Log.e(TAG, "✗ IJK: ${t.javaClass.simpleName}: ${t.message}") }
            try { list.add(SystemEngine(this, headers)); Log.i(TAG, "✓ 系统播放器") }
            catch (t: Throwable) { Log.e(TAG, "✗ 系统播放器: ${t.message}") }
            try { list.add(VlcEngine(this, headers)); Log.i(TAG, "✓ VLC") }
            catch (t: Throwable) { Log.e(TAG, "✗ VLC: ${t.javaClass.simpleName}: ${t.message}") }
            engines = list
            Log.i(TAG, "共 ${engines.size} 个引擎: ${engines.joinToString { it.name }}")
            if (engines.isEmpty()) tvStatus.text = "没有可用的播放引擎"
            // 把可用引擎直接显示在底栏 —— 用户在电视上就能确认「到底有几个能用的」，
            // 不用再靠猜（这一步就是为了排查「只剩系统播放器可用」那类问题）
            tvHint.text = if (engines.isEmpty()) "无可用引擎"
            else "引擎 ${engines.joinToString("/") { it.name }}　←→ 选择"

            engineIndex = if (engines.isEmpty()) 0
            else Prefs.getEngine(this).coerceIn(0, engines.size - 1)

            surface.holder.addCallback(this)
            // 不再 setType(SURFACE_TYPE_PUSH_BUFFERS)：API16+ 已废弃，
            // 部分老 ROM 上反而干扰渲染；也不让 SurfaceView 抢焦点。
            // （SurfaceView 尺寸保持 match_parent —— 任何尺寸调整都会引起
            //   Surface 重建，是黑方块/卡顿的来源）

            Log.d(TAG, "视频=${items.size} 引擎=${engines.getOrNull(engineIndex)?.name}")
            updateInfo()
            showControls()
            try { (application as App).activePlayer = this } catch (_: Throwable) {}
            // 前台服务提升进程优先级（对齐 B 站 TV 存活策略）
            try {
                val si = Intent(this, PlayService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(si) else startService(si)
            } catch (_: Throwable) {}
            // 音频焦点：让系统识别为"正在播放的媒体 App"
            try {
                val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                am.requestAudioFocus(null, android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN)
            } catch (_: Throwable) {}
            handler.removeCallbacks(memTick)
            handler.postDelayed(memTick, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "onCreate crash", e)
            throw e
        }
    }

    private fun currentEngine(): VideoEngine = engines[engineIndex]
    private fun cur(): DouyinApi.VideoItem? = items.getOrNull(currentIndex)

    // ---------------- 控制栏 ----------------
    // ★ 用 visibility 直接切换，不用 ViewPropertyAnimator：
    //   老电视 ROM 上 animate().alpha() 经常不生效或停在中间态，
    //   表现就是「控制栏死活不隐藏」——用户反馈过这个。
    /**
     * 显示控制栏，并按当前播放状态决定是否启动自动隐藏倒计时。
     *
     * ★ 这是显示控制栏的【唯一入口】★
     *   不要在别处直接改 topBar/bottomBar 的 visibility，也不要另建一套计时器。
     *   之前 16 处散落调用互相 removeCallbacks + postDelayed，
     *   正是「播放时控制栏常驻不消失」「翻页不重置计时」的根因。
     */
    private fun showControls() {
        controlsVisible = true
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        topBar.alpha = 1f
        bottomBar.alpha = 1f
        restartHideTimer()
    }

    /**
     * 重置自动隐藏倒计时。
     *
     * 切视频、翻页、任意按键后都必须走到这里 —— 否则会出现
     * 「刚翻页就被上一次留下的计时器隐藏掉」。
     * 暂停态下不启动倒计时（控制栏要留在屏幕上供操作）。
     */
    private fun restartHideTimer() {
        handler.removeCallbacks(hideTask)
        if (!paused) {
            handler.postDelayed(hideTask, HIDE_DELAY)
        }
    }

    private fun hideControls() {
        controlsVisible = false
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
    }

    /**
     * 采样播放状态，拼成一行诊断显示。
     *
     * 判读方法（给真机排查用）：
     *   · 缓冲领先很小(pos 紧贴 buf) 且卡  → 网络喂不饱（降码率/加缓冲才有效）
     *   · 缓冲领先很大却还卡               → 解码或渲染跟不上（换引擎/换渲染路径才有用）
     */
    private fun samplePerf() {
        val eng = try { currentEngine() } catch (_: Throwable) { return }
        val pos = try { eng.position() } catch (_: Throwable) { 0L }
        val buf = try { eng.bufferedMs() } catch (_: Throwable) { pos }
        val lead = ((buf - pos).coerceAtLeast(0L)) / 1000f
        val kbps = DouyinApi.Diag.lastBitrate / 1000
        val gear = DouyinApi.Diag.lastGear
        // Surface 尺寸也要显示：视频层尺寸 != 屏幕尺寸时，老 ROM 的合成器可能
        // 回退到软件缩放，那是另一条卡顿路径（和缺硬解是两回事）
        val sw = try { surface.width } catch (_: Throwable) { 0 }
        val sh = try { surface.height } catch (_: Throwable) { 0 }
        perfLine = "%s %s %s · 源%dx%d · 画%dx%d · %dkbps%s · 领先%.1fs".format(
            eng.name,
            eng.decoderInfo(),
            if (eng.isPlaying()) "▶" else "‖",
            lastVw, lastVh, sw, sh, kbps,
            if (gear.isEmpty()) "" else "·" + gear,
            lead
        )
        if (buffering) perfLine += "　⟳缓冲中"
        updateInfo()
    }

    /**
     * C1：内存/资源逼近危险水位 → 主动重建播放页。
     *
     * 为什么必要：这台 647MB / 无 swap 的老电视上，IJK 每切一个视频，
     * native 侧（OMX/MediaCodec/vout overlay）会漏一点不可回收的内存，
     * 大约第 5 个触顶被系统 LMK / 内核 OOM 杀掉 → 无 tombstone、无崩溃日志，
     * 用户看到的就是「刷着刷着直接退回推荐页」。Exo（复用实例）/VLC（自管
     * MediaCodec 生命周期）没有这条泄漏路径，所以从来不死。
     *
     * 本方法在系统动手之前主动离场（killProcess + 恢复点回原位），
     * 把「被杀丢回推荐页」变成「受控重建、回到同一位置」。健康视频不触发。
     *
     * ★ 阈值需真机校准：对照 mem_log.txt 的 native=/fd=/rss= 曲线，
     *   取「系统杀之前」的稳定值再留 20% 余量。默认值偏保守（宁早勿晚）。
     */
    private fun maybeRestartByMemory() {
        // ★ 内存自杀探针只对老设备生效 ★
        //   阈值是按 IJK 泄漏的老电视调的（native/FD/RSS 逼近触顶）。
        //   高配机器 RSS/FD 正常就高，照旧阈值会误触发 → 播着播着整个 App 重载，
        //   用户看到的就是「黑屏 → 自动重新加载」。高配只靠 count 兜底（值更大）。
        if (!DeviceProfile.isLegacy) return
        if (restarting || isFinishing || playedCount < MIN_PLAYED_BEFORE_RESTART) return
        try {
            val nheap = try { (android.os.Debug.getNativeHeapAllocatedSize() / 1024 / 1024).toInt() } catch (_: Throwable) { -1 }
            val fds = try { java.io.File("/proc/self/fd").list()?.size ?: 0 } catch (_: Throwable) { -1 }
            val rss = try {
                val st = java.io.File("/proc/self/status").readText()
                st.lineSequence().firstOrNull { it.startsWith("VmRSS:") }
                    ?.substring(6)?.trim()?.split(" ")[0]?.toLongOrNull()?.div(1024)?.toInt() ?: -1
            } catch (_: Throwable) { -1 }
            val hit = (nheap > nativeHeapLimitMB) || (fds > fdLimit) || (rss > rssLimitMB)
            if (hit) {
                Prefs.trace(this, "MEM_TRIGGER nheap=${nheap}MB fd=$fds rss=${rss}MB played=$playedCount -> restart")
                Log.w(TAG, "内存逼近危险水位(native=${nheap}MB fd=$fds rss=${rss}MB) → 主动重建")
                restartSelf()
            }
        } catch (_: Throwable) {}
    }

    /**
     * 自我重建播放页 —— 用来把 IJK 累积的 native 资源（vout / 硬解 overlay）
     * 彻底放掉。实测重建一次能回收约 9MB Graphics。
     *
     * 列表不重新传（走 FeedBus 零拷贝），只把「当前播到第几条」写回去，
     * 新实例会从同一条继续，用户感知就是「当前这个视频重播了一下」。
     */
    private fun restartSelf() {
        if (isFinishing || restarting) return
        try {
            restarting = true
            // ★ 必须落盘恢复点，而且要用【当前要播的那条】的 index ★
            //   原来这里只调 FeedBus.put —— 那是纯内存总线，killProcess 之后
            //   跟着进程一起没了。真正让用户回到原位的只有 Prefs 里的恢复 JSON，
            //   而它的 idx 还停在上一个视频（saveResumeState 只在 onPrepared 时写）
            //   → 重启后总是回到上一条重播。这里先补一次保存，把目标条写死。
            saveResumeState()
            // ★ 标记「计划内重启」★
            //   killProcess 在冷启动侧看起来和崩溃一模一样。不标记的话，
            //   「探针/计数触发的重启」会被当成连续崩溃累积 → 误把用户强制切到别的引擎
            //   （IJK 明明是用户选的、也更流畅）。
            Prefs.markPlannedRestart(this)
            Prefs.trace(this, "SELF_KILL idx=$currentIndex played=$playedCount resume+planned 已同步落盘")
            Log.i(TAG, "自我重启（切换点）：已连播 $playedCount 个，绕泄漏触顶")
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (t: Throwable) {
            restarting = false
            Log.w(TAG, "重启失败，继续原样播放: " + t.message)
        }
    }

    private fun flash(msg: String) {
        tvStatus.text = msg
        handler.postDelayed({ if (tvStatus.text == msg) tvStatus.text = "" }, 1700)
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    private fun updateInfo() {
        val it = cur()
        if (it == null) { tvAuthor.text = ""; tvDesc.text = "无视频"; return }
        tvAuthor.text = "@${it.author}"
        val flags = buildString {
            append("${currentIndex + 1}/${items.size}")
            append("　赞 ${it.diggCount}")
            if (it.commentCount > 0) append("　评论 ${it.commentCount}")
        }
        tvDesc.text = if (perfLine.isEmpty()) "$flags\n${it.desc}"
        else "$flags\n$perfLine\n${it.desc}"

        // 底栏按钮随视频内容刷新
        if (::btnViews.isInitialized) {
            btnViews[1].text = "@${it.author}"
            val topic = it.topics.firstOrNull()
            btnViews[2].text = if (topic != null) "#$topic" else "# 无话题"
            btnViews[2].alpha = if (topic != null) 1f else 0.45f
            btnViews[3].text = "评论 ${it.commentCount}"
            // ★ 赞 / 藏状态回显（本地记忆；已赞/已藏用品牌红）
            val digged = try { Prefs.isDigged(this, it.awemeId) } catch (_: Exception) { false }
            val collected = try { Prefs.isCollected(this, it.awemeId) } catch (_: Exception) { false }
            btnViews[5].text = if (digged) "赞 ✓" else "赞"
            btnViews[5].setTextColor(if (digged) 0xFFFE2C55.toInt() else 0xFFFFFFFF.toInt())
            btnViews[6].text = if (collected) "⭐ ✓" else "⭐"
            btnViews[6].setTextColor(if (collected) 0xFFFE2C55.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    // ---------------- 播放 ----------------
    /** 该视频的全部可选线路：主链 + 备用链（url_list 里的其余地址） */
    private fun candidateUrls(): List<String> {
        val it = cur() ?: return emptyList()
        val list = mutableListOf<String>()
        if (it.videoUrl.isNotBlank()) list.add(it.videoUrl)
        it.altUrls.forEach { u -> if (u.isNotBlank() && u !in list) list.add(u) }
        return list
    }

    private fun playCurrent() {
        // ★ KILLCHASE：切换计数 + 线程 + 调用栈写文件（绕开 ROM logcat 过滤）
        killSwitch++
        // ★ 82.x: 计数只是兜底（真触发是 maybeRestartByMemory 的内存探针）。
        //   播放页用不到任何封面图：每次切视频把图片缓存全清掉，
        //   把内存让给解码器/缓冲（1GB 老电视上这是被系统杀的关键推手之一）。
        try { ImageLoader.trimTo(0) } catch (_: Throwable) {}
        if (playedCount >= maxPlayBeforeRestart && !restarting) {
            Prefs.trace(this, "RESTART_GUARD hit played=$playedCount idx=$currentIndex")
            restartSelf()
            return
        }
        try {
            val sb = StringBuilder()
            sb.append("switch=").append(killSwitch)
            sb.append(" idx=").append(currentIndex)
            sb.append(" t=").append(SystemClock.elapsedRealtime())
            sb.append(" thread=").append(Thread.currentThread().name)
            sb.append("\n").append(android.util.Log.getStackTraceString(Throwable()).take(1200)).append("\n")
            val f = java.io.File(filesDir, "killchase.txt")
            val prev = if (f.exists()) f.readText() else ""
            val lines = (prev.split("\n") + sb.toString().split("\n")).filter { it.isNotBlank() }.takeLast(399)
            f.writeText(lines.joinToString("\n") + "\n")
        } catch (_: Throwable) {}

        val it = cur() ?: return
        if (holderReady == null) return

        // ★ 按「线路序号」取地址：出错时先换线路，而不是盲目换引擎
        //   （URL 失效时换引擎毫无意义 —— 三个引擎拿同一条死链都放不出来）
        val urls = candidateUrls()
        if (urls.isEmpty()) {
            tvStatus.text = "这条没有可用播放地址"
            return
        }
        val idx = urlAttempt.coerceIn(0, urls.size - 1)
        val rawUrl = urls[idx]

        maybeLoadMore()
        playToken++
        val token = playToken
        val engine = currentEngine()

        // ★ 不再 engine.release() ★
        //   以前这里每次都 release，导致 prepare() 里的「复用实例」分支永远走不到，
        //   等于每次切视频都重建一个播放器：ExoPlayer 的 release 是异步的 →
        //   新旧实例并存 → 连播几十个视频后内存碎片累积 → 被系统杀掉退回首页。
        //   现在改由各引擎的 prepare() 内部复用（Exo stop+换源 / IJK reset+换源）。
        //   真正需要释放的场景是「换引擎」——那里已经显式 release 旧引擎了。
        preparedOnce = false    // 新视频 → 未就绪
        healthyMarked = false    // 换了视频 → 健康计时重新开始
        engine.listener = this
        engine.holder = holderReady

        paused = false          // ★ 切新视频 → 回到播放态
        ivPause.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        tvPos.text = "00:00"
        tvDur.text = "00:00"
        seekBar.progress = 0
        updateInfo()
        // ★ 这里同时做两件事：显示控制栏 + 重置自动隐藏倒计时。
        //   「翻页不重置计时」就是因为以前缺少这个统一入口。
        showControls()

        Thread {
            val url = urlForEngine(engine, rawUrl)
            if (token != playToken) return@Thread
            handler.post {
                if (token == playToken && !isFinishing) {
                    Log.d(TAG, "[${engine.name}] 线路${idx + 1}/${urls.size} ${url.take(80)}")
                    engine.prepare(url)
                }
            }
        }.start()
    }

    /** 引擎是否在播（防御性包装） */
    private fun isPlayingSafe(): Boolean = try { currentEngine().isPlaying() } catch (_: Throwable) { false }

    /** 作者页续拉用的 cursor（数据源隔离：作者页滑到底继续拉该作者作品） */
    private var authorCursor = 0L

    private fun maybeLoadMore() {
        if (loadingMore || !hasMore || items.isEmpty()) return
        if (currentIndex < items.size - 2) return

        loadingMore = true
        val cookie = Prefs.getCookie(this)
        Thread {
            val r = try {
                when (feedSource) {
                    // ★ 数据源隔离：作者页滑到底拉【该作者的作品】，绝不混入首页推荐流
                    FeedBus.SOURCE_AUTHOR -> {
                        val sec = items.firstOrNull()?.secUid
                        if (sec.isNullOrBlank()) null
                        else DouyinApi.fetchUserPosts(cookie, sec, authorCursor, 20)
                            .also { if (it != null) authorCursor = it.maxCursor }
                    }
                    else -> DouyinApi.fetchFeed(cookie)
                }
            } catch (e: Exception) { null }
            handler.post {
                loadingMore = false
                if (r == null || r.videos.isEmpty()) { Log.d(TAG, "拉取失败"); return@post }
                val known = items.map { it.awemeId }.toHashSet()
                val fresh = r.videos.filter { it.awemeId !in known }
                if (fresh.isEmpty()) { Log.d(TAG, "本批全重复"); return@post }
                items = items + fresh
                Log.d(TAG, "追加 ${fresh.size} 条 → 共 ${items.size}")
                updateInfo()
                // ★ 81.4: 加载更多后若播放已结束（onComplete 触发的加载）→ 自动续播新内容
                try {
                    if (!isPlayingSafe()) {
                        currentIndex++
                        playCurrent()
                    }
                } catch (_: Throwable) {}
            }
        }.start()
    }

    private fun urlForEngine(engine: VideoEngine, raw: String): String {
        // 系统 MediaPlayer 与 IJK 都走【系统自带 HTTP 栈】——
        // 不经全局宽松 TLS 的 HttpsURLConnection，Android 4.2 的 TLS/CA 太老
        // 直接播 https 会握手失败。统一先解 302 拿新鲜 CDN 地址，再降级 http。
        if (engine !is IjkEngine && engine !is SystemEngine) return raw
        var u = raw
        if (u.contains("/aweme/v1/play/")) u = resolve302(u)
        if (u.startsWith("https://")) u = "http://" + u.substring("https://".length)
        return u
    }

    private fun resolve302(url: String): String = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA).header("Referer", REFERER).build()
        // ★ 用宽松 client：电视 CA 库过期时严格 client 握手失败 →
        //   catch 后返回原 URL → IJK 又把它降级成 http → 这条线路直接废掉
        val nr = DouyinApi.relaxedHttpClient().newBuilder()
            .followRedirects(false).followSslRedirects(false).build()
        val resp = nr.newCall(req).execute()
        val code = resp.code(); val loc = resp.header("Location"); resp.close()
        if (code in 300..399 && !loc.isNullOrBlank()) loc else url
    } catch (e: Exception) { url }

    // ---------------- 底栏功能按钮 ----------------
    /** ←→ 在底栏按钮间移动焦点（仅在控件可见时） */
    private fun moveBtnFocus(delta: Int) {
        if (!::btnViews.isInitialized || btnViews.isEmpty()) return
        val n = btnViews.size
        btnFocus = ((btnFocus + delta) % n + n) % n
        btnViews[btnFocus].requestFocus()
        showControls()
        tvStatus.text = ""
    }

    private fun triggerBtn(i: Int) {
        when (i) {
            0 -> togglePlayPause()
            1 -> openAuthor(cur())
            2 -> openTopic(cur())
            3 -> openComments(cur())
            4 -> openSearch()
            5 -> doDigg()      // ★ 点赞直达
            6 -> doCollect()   // ★ 收藏直达
            7 -> showMenu()    // 触屏菜单入口
        }
    }

    /**
     * 话题作品 —— 直接走搜索接口。
     *
     * 改前这里是死入口：challenge/aweme 对 X-Bogus 回空 body、challenge/detail
     * 的 rec_list 全 null，所以只弹一句"暂无法打开"。那是搜索还没打通时的降级。
     *
     * 现在搜索已实测可用（登录 Cookie + 干净参数、不带任何签名即可出结果），
     * 点话题就直接拿话题名去搜 —— 话题词本身是强相关关键词，搜出来就是该话题
     * 下的内容，与抖音话题页效果基本一致。
     */
    private fun openTopic(it: DouyinApi.VideoItem?) {
        val name = it?.topics?.firstOrNull()
        if (name.isNullOrBlank()) { flash("这条视频没有话题"); return }
        val i = Intent(this, SearchActivity::class.java)
        i.putExtra("kw", name)
        startActivity(i)
    }

    /**
     * 搜索 —— 直接打开搜索页。
     *
     * 改前这里也是死入口（"搜索接口受风控限制，暂无法使用"），同样是搜索没打通
     * 时的降级文案。现在搜索实测可用，这里只负责跳转。
     */
    private fun openSearch() {
        startActivity(Intent(this, SearchActivity::class.java))
    }

    /** 错误历史 —— 电视没有 logcat，只能这样回看 */
    private fun showErrorHistory() {
        val msg = if (errHistory.isEmpty()) "本轮没有播放错误 ✓"
        else errHistory.joinToString("\n\n")
        try {
            AlertDialog.Builder(this)
                .setTitle("最近播放错误（${errHistory.size}）")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .setOnDismissListener { showControls() }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "显示错误历史失败", e)
        }
    }

    // ---------------- 播放器内菜单 ----------------
    private fun showMenu() {
        val it = cur()
        val loopMode = if (Prefs.isLoopOne(this)) "单集循环" else "自动连播"
        val engineName = engines.getOrNull(engineIndex)?.name ?: "无"
        val n = candidateUrls().size
        val landscapeOn = Prefs.isLandscapeOnly(this)
        // ★ 点赞/收藏状态回显（本地记忆）
        val digged = it?.awemeId?.let { id -> try { Prefs.isDigged(this, id) } catch (_: Exception) { false } } == true
        val collected = it?.awemeId?.let { id -> try { Prefs.isCollected(this, id) } catch (_: Exception) { false } } == true
        val labels = arrayOf(
            "作者主页 · @${it?.author ?: "?"}",
            "查看评论 · ${it?.commentCount ?: 0} 条",
            if (digged) "👍 已赞 · 取消点赞" else "👍 点赞",
            if (collected) "⭐ 已收藏 · 取消收藏" else "⭐ 收藏",
            "换线路 · 当前 ${urlAttempt + 1}/${maxOf(1, n)}",
            "切换引擎 · 当前 $engineName",
            "播放模式 · $loopMode",
            "竖屏过滤 · ${if (landscapeOn) "仅横屏" else "全部"}",
            "弹幕 · ${if (Prefs.getDanmaku()) "开" else "关"}（评论区文案）",
            "最近错误（${errHistory.size} 条）",
            "打开设置 / 重新配置 Cookie"
        )
        showControls()
        try {
            AlertDialog.Builder(this)
                .setTitle(it?.desc?.take(30)?.ifBlank { "菜单" } ?: "菜单")
                .setItems(labels) { _, which ->
                    when (which) {
                        0 -> openAuthor(it)
                        1 -> openComments(it)
                        2 -> doDigg()
                        3 -> doCollect()
                        4 -> {
                            // 手动换线路（不自动重试）
                            val n = candidateUrls().size
                            if (n <= 1) flash("这条只有一条线路")
                            else {
                                urlAttempt = (urlAttempt + 1) % n
                                flash("线路 ${urlAttempt + 1}/$n")
                                playCurrent()
                            }
                        }
                        5 -> {
                            // 手动换引擎：必须 release 掉旧实例，
                            // 否则它会继续持有 Surface（画面残留/黑块）
                            try { engines[engineIndex].release() } catch (_: Exception) {}
                            engineIndex = (engineIndex + 1) % engines.size
                            urlAttempt = 0
                            flash("引擎：${engines[engineIndex].name}")
                            playCurrent()
                        }
                        6 -> {
                            val v = !Prefs.isLoopOne(this)
                            Prefs.setLoopOne(this, v)
                            flash(if (v) "播放模式：单集循环" else "播放模式：自动连播")
                        }
                        7 -> {
                            // ★ 竖屏过滤开关（用户要求的信息流过滤）
                            //   只影响【之后拉取】的视频：已经在列表里的不追溯移除，
                            //   否则用户按一下开关当前视频就消失了，体验很怪。
                            val v = !Prefs.isLandscapeOnly(this)
                            Prefs.setLandscapeOnly(this, v)
                            flash(if (v) "只显示横屏视频（下次加载生效）" else "显示全部视频（下次加载生效）")
                        }
                        8 -> toggleDanmaku()
                        9 -> showErrorHistory()
                        10 -> {
                            startActivity(Intent(this, SettingsActivity::class.java))
                            finish()
                        }
                    }
                    showControls()
                }
                .setOnCancelListener { showControls() }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "菜单失败", e)
        }
    }

    /**
     * 把抖音 status_msg 翻译成可行动的提示：
     * 未登录类 → 大概率浏览器端已被风控踢下线，需重新复制 Cookie；
     * verify/captcha → 触发验证，稍后再试。
     */
    private fun interactErr(msg: String): String {
        val m = msg.lowercase()
        return when {
            m.contains("未登录") || m.contains("login") || m.contains("请登录") || m.contains("need login") ->
                "登录态失效：浏览器端可能已被踢下线，请重新复制完整 Cookie"
            m.contains("verify") || m.contains("captcha") || m.contains("验证") || m.contains("risk") ->
                "触发风控验证：请稍后再试，或重新复制 Cookie"
            else -> msg.take(40)
        }
    }

    // ---------------- 弹幕（评论区文案滚动） ----------------
    private fun toggleDanmaku() {
        val on = !Prefs.getDanmaku()
        Prefs.setDanmaku(this, on)
        flash(if (on) "弹幕：开（读取评论区）" else "弹幕：关")
        applyDanmaku()
    }

    private fun applyDanmaku() {
        val d = danmaku ?: return
        if (!Prefs.getDanmaku()) {
            d.visibility = View.GONE
            d.setRunning(false)
            return
        }
        d.visibility = View.VISIBLE
        d.setRunning(!paused)
        loadDanmaku()
    }

    /** 后台拉当前视频评论区，喂给弹幕层（30 条封顶） */
    private fun loadDanmaku() {
        if (!Prefs.getDanmaku()) return
        val it = cur() ?: return
        val cookie = Prefs.getCookie(this)
        Thread {
            val texts = try {
                DouyinApi.fetchComments(cookie, it.awemeId, 0, 30)
                    ?.map { c -> (if (c.user.isNotBlank()) "${c.user}：" else "") + c.text }
                    ?: emptyList()
            } catch (_: Exception) { emptyList() }
            handler.post {
                if (isFinishing || !Prefs.getDanmaku()) return@post
                danmaku?.setContent(texts)
            }
        }.start()
    }

    private fun syncDanmakuPause() {
        if (!Prefs.getDanmaku()) return
        try { danmaku?.setRunning(!paused) } catch (_: Throwable) {}
    }

    // ---------------- 手机遥控（PlayerBus 命令执行） ----------------
    private val remoteListener = object : PlayerBus.Listener {
        override fun onCommand(cmd: String, arg: String?) {
            try {
                when (cmd) {
                    "next" -> nextVideo()
                    "prev" -> prevVideo()
                    "digg" -> doDigg()
                    "collect" -> doCollect()
                    "toggle" -> togglePlayPause()
                    "pause" -> { if (currentEngine().isPlaying()) { currentEngine().pause(); paused = true; showControls() } }
                    "play" -> { if (!currentEngine().isPlaying()) { currentEngine().start(); paused = false; showControls() } }
                    "seek" -> arg?.toLongOrNull()?.let { doSeek(it) }
                    "line" -> { urlAttempt = (urlAttempt + 1) % maxOf(1, candidateUrls().size); playCurrent() }
                    "engine" -> {
                        try { engines[engineIndex].release() } catch (_: Exception) {}
                        engineIndex = (engineIndex + 1) % engines.size
                        urlAttempt = 0
                        flash("引擎：${engines[engineIndex].name}")
                        playCurrent()
                    }
                    "danmaku" -> toggleDanmaku()
                    "loop" -> { val v = !Prefs.isLoopOne(this@PlayerActivity); Prefs.setLoopOne(this@PlayerActivity, v) }
                    "landscape" -> { val v = !Prefs.isLandscapeOnly(this@PlayerActivity); Prefs.setLandscapeOnly(this@PlayerActivity, v) }
                    "speed" -> {
                        // ★ 支持绝对倍速：arg 是数字直接设（按住快进用），否则循环档位
                        val abs = arg?.toFloatOrNull()
                        if (abs != null && abs in 0.5f..3f) {
                            playSpeed = abs
                        } else {
                            val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                            var idx = 0
                            for (i in speeds.indices) if (speeds[i] == playSpeed) idx = i
                            playSpeed = speeds[(idx + 1) % speeds.size]
                        }
                        try { currentEngine().setSpeed(playSpeed) } catch (_: Throwable) {}
                        flash("倍速 ${playSpeed}x")
                    }
                    "search" -> if (!arg.isNullOrBlank()) openSearchWith(arg)
                    "push" -> if (!arg.isNullOrBlank()) pushVideoToPlayer(arg)
                    "reload" -> playCurrent()
                }
            } catch (e: Exception) {
                Log.e(TAG, "遥控命令失败: ${e.message}")
            }
        }
    }

    private fun openSearchWith(kw: String) {
        SearchBus.push(kw)
        try {
            val i = Intent(this, SearchActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            i.putExtra("kw", kw)
            startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "遥控搜索拉起失败: ${e.message}")
        }
    }

    /** 手机分享链接 → 解析 → 当前列表替换为这条视频并播放 */
    private fun pushVideoToPlayer(raw: String) {
        Thread {
            val cookie = Prefs.getCookie(this)
            val id = try {
                val share = DouyinApi.extractShareUrl(raw)
                if (share == null) null
                else DouyinApi.extractAwemeId(DouyinApi.resolveShareUrl(share))
                    ?: DouyinApi.extractAwemeId(share)
            } catch (_: Exception) { null }
            val item = if (id != null) DouyinApi.fetchVideoDetail(cookie, id) else null
            handler.post {
                if (isFinishing) return@post
                if (item == null) { flash("推送的视频解析失败"); return@post }
                FeedBus.put(listOf(item), 0, FeedBus.SOURCE_SHARE)
                items = listOf(item)
                currentIndex = 0
                feedSource = FeedBus.SOURCE_SHARE
                hasMore = false
                urlAttempt = 0
                retrySameEngine = 0
                playCurrent()
                flash("已播放推送的视频 ✓")
            }
        }.start()
    }

    /**
     * 点赞 / 取消点赞 —— ★ 纯本地 ★
     * 服务器侧 WebProtect 方案太麻烦且易触发风控（实测会踢下线网页端），
     * 用户拍板改成本地记忆：状态只存本机，用于回显与收藏页，不发任何请求。
     */
    private fun doDigg() {
        val v = cur() ?: return
        val it = v
        val now = try { Prefs.toggleDigg(this, it.awemeId) } catch (_: Exception) { false }
        flash(if (now) "已点赞 ✓（本地）" else "已取消点赞（本地）")
        try { updateInfo() } catch (_: Throwable) {}
    }

    /**
     * 收藏 / 取消收藏 —— ★ 纯本地 ★（同上：不做服务器写操作，只记本机状态）
     */
    private fun doCollect() {
        val v = cur() ?: return
        val it = v
        val now = try { Prefs.toggleCollect(this, it) } catch (_: Exception) { false }
        flash(if (now) "已收藏 ✓（本地）" else "已取消收藏（本地）")
        try { updateInfo() } catch (_: Throwable) {}
    }

    private fun openAuthor(it: DouyinApi.VideoItem?) {
        if (it == null || it.secUid.isBlank()) { flash("拿不到作者信息"); return }
        val i = Intent(this, AuthorActivity::class.java)
        i.putExtra("sec_uid", it.secUid)
        i.putExtra("author", it.author)
        startActivity(i)
    }

    /**
     * 打开评论 —— 右侧悬浮小窗（DialogFragment）。
     *
     * 需求明确「跳转离开播放器体验很差」，故从全屏 CommentActivity 改为小窗。
     * DialogFragment 是独立窗口、不触碰宿主生命周期，所以**视频继续播**，
     * 真正实现"边看视频边刷评论"。
     * 面板只占右侧约 1/3 宽：竖屏视频在 16:9 屏上两侧本就是黑边，
     * 面板正落在黑边区，不遮画面主体。
     */
    private fun openComments(it: DouyinApi.VideoItem?) {
        if (it == null) return
        try {
            // 已经开着就不重复弹（连按两次会叠两个窗口）
            if (fragmentManager.findFragmentByTag(TAG_COMMENT) != null) return
            CommentDialogFragment.newInstance(it)
                .show(fragmentManager, TAG_COMMENT)
        } catch (e: Exception) {
            Log.e(TAG, "打开评论失败", e)
            flash("打开评论失败：${e.message}")
        }
    }

    // ---------------- 引擎回调 ----------------
    override fun onPrepared() {
        // ★ 81.4: 同一 playToken 只处理一次（Exo seek/缓冲恢复会重发 STATE_READY → onPrepared 虚涨
        //   虚涨会让 playedCount 提前到上限触发自杀 —— 快进几次就闪断）
        if (lastPreparedToken == playToken) return
        lastPreparedToken = playToken
        // ★ 81.5: 正常起播成功 → 恢复点 retry 归零（熔断只在连续崩时累加）
        try {
            val rj = Prefs.loadResume(this)
            if (rj.isNotBlank()) {
                val ro = org.json.JSONObject(rj)
                if (ro.optInt("retry", 0) > 0) {
                    ro.put("retry", 0)
                    Prefs.saveResume(this, ro.toString())
                }
            }
        } catch (_: Throwable) {}
        // ★ 连播计数：到上限就重建播放页，防止 IJK native 资源堆积被杀
        playedCount++
        // ★ 81.5: 不再挂 1.5s 炸弹（会在下一视频加载途中引爆）→ 改由 playCurrent 开头检查
        // 播放成功 → 清空重试/抖动计数（下次出错仍可先原地重试一次）
        retrySameEngine = 0
        lastErrorAt = 0L
        preparedOnce = true     // ★ 已就绪：Surface 重建时只需重新绑定，不必重播
        try { currentEngine().setSpeed(playSpeed) } catch (_: Throwable) {}
        paused = false          // ★ 进入播放态 → 控制栏恢复「到点自动隐藏」
        // ★ 起播成功 = 缓冲结束：Exo 只发「缓冲中」，没有对应的结束回调，
        //   不在这里清掉的话，诊断行会永久挂着「⟳缓冲中」（IJK 有 BUFFERING_END）
        buffering = false
        handler.post {
            progressBar.visibility = View.GONE
            tvStatus.text = ""
            seekBar.progress = 0
            // 弹幕开着 → 新视频自动换弹幕文案
            if (Prefs.getDanmaku()) applyDanmaku()
        }
        Prefs.setEngine(this, engineIndex)
        // ★ 崩溃自愈：每次视频成功起播就落盘恢复点。
        //   native 崩（IJK 释放竞态）→ 进程瞬死 → onDestroy 不执行 → 残留；
        //   冷启动 MainActivity 读到残留 → 直接回到本视频继续播。
        saveResumeState()
        // ★ 观看历史：每次起播成功记录一条（纯本地，去重上限 100）
        try { cur()?.let { Prefs.addHistory(this, it) } } catch (_: Throwable) {}
        handler.removeCallbacks(progressTick)
        handler.post(progressTick)
        Prefs.trace(this, "PLAYING idx=${currentIndex + 1}/${items.size} engine=${currentEngine().name}")
        snapMem("PLAY#" + playedCount + " idx=" + (currentIndex + 1))
        Log.d(TAG, "✓ [${currentEngine().name}] 播放中")
    }

    /**
     * 根源排查探针：每起播一个视频记一行内存快照（写进 mem_log.txt 与 memTick 同文件）。
     * 连播 6~8 个后拉出文件看曲线：rss/native/fd 哪个在线性上涨、每个视频涨多少，
     * 就能确认「第 5 个必死」到底是哪种资源漏到触顶 —— 而不是笼统归因"固件计数"。
     */
    private fun snapMem(tag: String) {
        try {
            var rss = -1L
            var vmSize = -1L
            try {
                for (ln in java.io.File("/proc/self/status").readLines()) {
                    if (ln.startsWith("VmRSS:")) rss = ln.substring(6).trim().split(" ")[0].toLong() / 1024
                    else if (ln.startsWith("VmSize:")) vmSize = ln.substring(7).trim().split(" ")[0].toLong() / 1024
                }
            } catch (_: Throwable) {}
            val fds = try { java.io.File("/proc/self/fd").list()?.size ?: -1 } catch (_: Throwable) { -1 }
            val nheap = try { android.os.Debug.getNativeHeapAllocatedSize() / 1024 / 1024 } catch (_: Throwable) { -1L }
            val jused = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024
            val line = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date()) +
                " " + tag + " rss=" + rss + "MB vmsize=" + vmSize + "MB native=" + nheap +
                "MB java=" + jused + "MB fd=" + fds
            java.io.File(filesDir, "mem_log.txt").appendText(line + "\n")
        } catch (_: Throwable) {}
    }

    /** 序列化当前播放位置 + 完整列表 → 落盘（org.json，项目红线禁 Gson/Moshi） */
    private fun saveResumeState() {
        try {
            val arr = org.json.JSONArray()
            for (it in items) {
                val o = org.json.JSONObject()
                o.put("id", it.awemeId)
                o.put("d", it.desc)
                o.put("a", it.author)
                o.put("u", it.videoUrl)
                o.put("c", it.coverUrl)
                o.put("dur", it.duration)
                o.put("sec", it.secUid)
                o.put("digg", it.diggCount)
                o.put("cmt", it.commentCount)
                o.put("pt", it.isPortrait)
                val t = org.json.JSONArray()
                for (x in it.topics) t.put(x)
                o.put("t", t)
                val al = org.json.JSONArray()
                for (x in it.altUrls) al.put(x)
                o.put("alt", al)
                arr.put(o)
            }
            val root = org.json.JSONObject()
            root.put("items", arr)
            root.put("idx", currentIndex)
            root.put("src", feedSource)
            root.put("ts", System.currentTimeMillis())
            root.put("retry", 0)   // 崩溃重启后 MainActivity 会 +1
            Prefs.saveResume(this, root.toString())
        } catch (_: Exception) {}
    }

    /** 低内存（onTrimMemory）→ 暂停+GC 给内存喘息（B站动态缓冲的App层等价） */
    override fun onLowMemory() {
        Log.w(TAG, "低内存回调 → 暂停+GC 防杀")
        try {
            if (currentIndex < 0) return
            val eng = currentEngine()
            val wasPlaying = !paused
            eng.pause()
            System.gc()
            handler.postDelayed({
                if (!isFinishing && wasPlaying && !paused) {
                    try { currentEngine().start() } catch (_: Throwable) {}
                }
            }, 800)
        } catch (_: Throwable) {}
    }

    override fun onError(msg: String) {
        Log.e(TAG, "✗ [${currentEngine().name}] $msg")
        handler.post {
            progressBar.visibility = View.GONE
            noteError(msg)

            // ★ 不自动做任何动作 ★
            //   用户明确要求：换引擎、换线路都手动决定。
            //   之前自动换引擎会在三个引擎间来回刷屏；自动换线路也会在
            //   直链过期时反复重试 → 表现为「没一会就提示一下」。
            //   现在只把原因显示出来，让用户自己按菜单处理。
            tvStatus.text = "播放失败：${shortMsg(msg)}　→ 菜单可换线路 / 换引擎"
            paused = true       // ★ 出错等同暂停态：控制栏留在屏幕上，方便立刻换线路/换引擎
            showControls()
            syncDanmakuPause()
        }
    }

    private fun noteError(msg: String) {
        errHistory.add(0, "${currentEngine().name}: ${shortMsg(msg)}")
        while (errHistory.size > 6) errHistory.removeAt(errHistory.size - 1)
    }

    private fun shortMsg(m: String): String = m.replace("\n", " ").replace("\r", " ").take(42)

    override fun onComplete() {
        // 单集循环：重播当前；否则自动连播下一条
        if (Prefs.isLoopOne(this)) {
            Log.d(TAG, "单集循环 → 重播")
            playCurrent()
            return
        }
        if (currentIndex < items.size - 1) {
            currentIndex++
            playCurrent()
        } else {
            // ★ 修复：hasMore=false 时 maybeLoadMore 直接 return，别再谎报"正在加载更多…"
            if (!hasMore) {
                flash("已到最后一条")
                return
            }
            maybeLoadMore()
            flash("正在加载更多…")
        }
    }

    override fun onInfo(msg: String) {
        buffering = msg.contains("缓冲")
        handler.post { if (msg.isNotEmpty()) tvStatus.text = msg }
    }

    // ---------------- 画面比例（回到 v11 做法） ----------------
    //   ★ 不碰布局、不碰容器尺寸 ★
    //   SurfaceView 铺满全屏，由解码器按 SCALE_TO_FIT 原生 letterbox。
    //   两种历史做法都踩过雷：
    //     ① 运行时改 SurfaceView.layoutParams → Surface 尺寸变化 → 回调链
    //     ② AspectFrame 容器按比例约束 → 同样触发 Surface 尺寸变化
    //   在 Mali450 上两者的表现都是「画面黑方块 + 卡顿」，故全部移除。
    override fun onVideoSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        lastVw = w
        lastVh = h
        // ★ 出帧信号：黑屏看门狗靠它区分「黑屏」与「正常在播」
        firstFrameForToken = playToken
        val ratio = w.toFloat() / h.toFloat()
        Log.d(TAG, "videoSize ${w}x$h → ratio=$ratio")

        // ★ 比例容器每视频只应用一次（防 onVideoSize 多次回调 → 反复重建 Surface → 黑屏重载）
        if (aspectAppliedToken != playToken) {
            aspectAppliedToken = playToken
            // ★ 非 Exo 引擎跳过容器缩放：容器 resize 会销毁/重建 SurfaceView 的 Surface，
            //   IJK/系统引擎不托管 Surface 生命周期 → 重建 = 整段重播（黑屏→重新加载）。
            //   Exo 由 setVideoSurfaceHolder 托管，重建仅重绑、不重播。
            if (currentEngine() is ExoEngine) {
                handler.post { applyAspect(ratio) }
            } else {
                Log.d(TAG, "非 Exo 引擎：跳过比例容器调整（防黑屏重载）")
            }
        }
        handler.post {
            tvHint.text = "视频 ${w}×${h}　比例 ${"%.2f".format(ratio)}"
        }
    }

    /** 按比例约束画面容器；比例恒定则不动（避免 Surface 反复重建） */
    private fun applyAspect(ratio: Float) {
        if (!::aspectFrame.isInitialized) return
        if (ratio <= 0f || ratio.isNaN()) return
        aspectFrame.aspectRatio = ratio
    }

    // 说明：旧版的「比例诊断横幅」与 AspectFrame 一并移除 ——
    // 它们是黑方块问题的来源，v11 里都没有。

    // ---------------- Surface ----------------
    override fun surfaceCreated(h: SurfaceHolder) {
        holderReady = h
        val e = currentEngine()
        if (preparedOnce && e is ExoEngine) {
            // ★ Surface 因比例容器调整尺寸而重建 ★
            //   ExoEngine 用 setVideoSurfaceHolder 托管 Surface，重新绑定即恢复画面；
            //   不重新 prepare → 视频不会从头重播、不闪。
            Log.d(TAG, "Surface 重建 → 仅重新绑定（不重播）")
            e.holder = h
        } else {
            playCurrent()
        }
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {
        Log.d(TAG, "surfaceChanged ${w}x$h2")
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        playToken++
        val e = currentEngine()
        // ★ ExoEngine 用 setVideoSurfaceHolder 托管 Surface：销毁时它自行解绑，
        //   这里不能 release —— 否则比例容器调整尺寸时会整段重播。
        //   IJK / 系统播放器不托管，销毁时必须释放，否则持有无效 Surface 会崩。
        if (e !is ExoEngine) {
            try { e.release() } catch (_: Exception) {}
        }
        holderReady = null
        // ★ 清掉上一帧引用，防止 surface 被销毁后仍有残留画面
        lastVw = 0
        lastVh = 0
    }

    // ---------------- 共用操作 ----------------
    /** IJK 引擎连刷节流（Exo/系统引擎不限制） */
    private var lastIJKSwitchAt = 0L
    private fun ijkSwitchAllowed(): Boolean {
        if (currentEngine().name != "IJKPlayer") return true
        val now = SystemClock.uptimeMillis()
        if (now - lastIJKSwitchAt < IJK_SWITCH_GUARD_MS) {
            flash("连刷太快，等一下…")
            return false
        }
        lastIJKSwitchAt = now
        return true
    }

    private fun nextVideo() {
        if (!ijkSwitchAllowed()) return
        if (currentIndex < items.size - 1) {
            currentIndex++; urlAttempt = 0; retrySameEngine = 0; playCurrent()
        } else if (hasMore) {
            maybeLoadMore(); flash("加载更多…")
        } else flash("已是最后一条")
    }

    private fun prevVideo() {
        if (!ijkSwitchAllowed()) return
        if (currentIndex > 0) {
            currentIndex--; urlAttempt = 0; retrySameEngine = 0; playCurrent()
        } else flash("已是第一条")
    }

    private fun togglePlayPause() {
        val e = currentEngine()
        if (e.isPlaying()) {
            e.pause()
            paused = true                 // ★ 暂停 → 控制栏保持可见（不自动隐藏）
            ivPause.text = "❚❚"
            ivPause.visibility = View.VISIBLE
        } else {
            e.start()
            paused = false                // ★ 播放 → 控制栏到点自动隐藏
            ivPause.text = "▶"
            handler.postDelayed({ ivPause.visibility = View.GONE }, 450)
        }
        showControls()
        syncDanmakuPause()
    }

    private fun doSeek(deltaMs: Long) {
        val e = currentEngine()
        val dur = e.duration().coerceAtLeast(1L)
        val p = (e.position() + deltaMs).coerceIn(0L, dur)
        e.seekTo(p)
        flash("${fmt(p)} / ${fmt(dur)}")
        showControls()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    // ---------------- 遥控器 ----------------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
                showControls()
                return super.dispatchKeyEvent(event)   // 音量交给系统
            }
            if (handleTvKey(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleTvKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { prevVideo(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { nextVideo(); return true }
            // 控件可见 → ←→ 在功能按钮间移动；控件隐藏 → ←→ 快退/快进
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (controlsVisible) { moveBtnFocus(-1); return true }
                doSeek(-SEEK_STEP); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (controlsVisible) { moveBtnFocus(1); return true }
                doSeek(SEEK_STEP); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                // 焦点已在按钮上 → 交回系统派发 click（避免双重触发）
                if (::btnViews.isInitialized && btnViews.contains(currentFocus)) return false
                togglePlayPause(); return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { nextVideo(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { prevVideo(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_SETTINGS -> {
                showMenu(); return true              // ★ 改成弹层菜单，不再跳出
            }
            KeyEvent.KEYCODE_BACK -> { finish(); return true }
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        // 诊断采样：每 2 秒刷新一次播放状态（onPause 里停掉）
        handler.postDelayed(perfTick, 1500)
    }

    override fun onPause() {
        handler.removeCallbacks(perfTick)
        super.onPause()
        try { currentEngine().pause() } catch (_: Exception) {}
        paused = true           // ★ 离开前台 → 暂停态，控制栏保持可见
        showControls()
        syncDanmakuPause()      // 离开前台 → 弹幕停（省电，回来恢复）
    }

    override fun onDestroy() {
        try { (application as App).activePlayer = null } catch (_: Throwable) {}
        PlayerBus.detach(remoteListener)
        PlayerBus.snapshot.inPlayer = false
        try { danmaku?.setRunning(false) } catch (_: Throwable) {}
        try { stopService(Intent(this, PlayService::class.java)) } catch (_: Throwable) {}
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            am.abandonAudioFocus(null)
        } catch (_: Throwable) {}
        // ★ 正常退出清恢复点（崩溃时 onDestroy 不执行 → 恢复点残留 → 冷启动恢复）
        try { Prefs.clearResume(this) } catch (_: Exception) {}
        handler.removeCallbacks(perfTick)
        playToken++
        // 停止进度条更新
        handler.removeCallbacks(progressTick)
        // 清除所有待处理的回调
        handler.removeCallbacksAndMessages(null)
        // 释放播放器资源
        engines.forEach { try { it.release() } catch (_: Exception) {} }
        try { IjkMediaPlayer.native_profileEnd() } catch (_: Exception) {}
        // ★ 释放内存总线引用 —— 不清理的话整条列表（每条约 1KB 字符串）
        //   会随进程一直存活，长刷视频时累积可观。
        //   ★ 例外：自我重建时不能清，否则新实例拿不到列表、直接无片可播。
        if (!restarting) FeedBus.clear()
        super.onDestroy()
    }
}
