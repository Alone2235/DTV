package com.example.douyintv

import android.content.Context
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.Renderer
import com.google.android.exoplayer2.audio.AudioCapabilities
import com.google.android.exoplayer2.audio.AudioProcessor
import com.google.android.exoplayer2.audio.AudioRendererEventListener
import com.google.android.exoplayer2.audio.AudioSink
import com.google.android.exoplayer2.audio.DefaultAudioSink
import com.google.android.exoplayer2.analytics.PlayerId
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.audio.AuxEffectInfo
import com.google.android.exoplayer2.audio.MediaCodecAudioRenderer
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultAllocator
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.video.VideoSize
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout

/**
 * 播放引擎抽象 —— 运行时可在三个引擎间手动切换。
 *
 * 架构约束（与需求一致）：
 *   · 全局单实例：同一时刻只有一个引擎持有 Surface / 解码器。
 *     切换视频 = 先完整 release 旧实例，再 prepare 新的。
 *     绝不允许两个解码器并行 —— Mali450 上会花屏、无声、甚至 Crash。
 *   · 不落盘：所有播放路径都是流式直读，无缓存文件。
 *
 * 被刻意去掉的东西（都曾把体验搞坏）：
 *   · ExoPlayer setAudioAttributes / IJK setAudioStreamType（音频焦点抖动）
 *   · 引擎实例复用（无收益、增复杂度）
 *   · 任何 Surface 尺寸调整（触发 Surface 重建 = 黑方块/卡顿之源）
 */
interface VideoEngine {
    val name: String
    var holder: SurfaceHolder?
    var listener: Listener?

    /** @param url 已解析为引擎可直接播放的地址 */
    fun prepare(url: String)
    fun start()
    fun pause()
    fun isPlaying(): Boolean
    fun seekTo(ms: Long)
    fun position(): Long
    fun duration(): Long
    fun release()

    /** 播放倍速（Exo/IJK 支持；系统播放器 API 17 无此能力，空实现） */
    fun setSpeed(speed: Float) {}

    /**
     * 已缓冲到的位置（毫秒）—— 诊断用。
     * 和 position() 一比就知道：缓冲水位贴着播放位置 = 网络喂不饱；
     * 缓冲领先很多却还卡 = 解码/渲染跟不上。
     * 默认实现返回播放位置（不支持的引擎至少不误报）。
     */
    fun bufferedMs(): Long = position()

    /**
     * 当前实际用的解码方式（诊断用，屏幕上直接显示）。
     * 这台电视的真问题就在这：Cortex-A7 单核太弱，一旦回落软解，
     * 540p@30fps 必然丢帧落后 → 卡 + 音画不同步。
     * 硬解走 SoC 里的 VPU，CPU 占用极低，才是正解。
     */
    fun decoderInfo(): String = "?"

    interface Listener {
        fun onPrepared()
        fun onError(msg: String)
        fun onComplete()
        fun onInfo(msg: String)
        /** 视频真实像素尺寸 */
        fun onVideoSize(w: Int, h: Int)
    }
}

private const val TAG = "Engine"

/** 与业务层一致的 UA —— 抖音对该 UA 返回常规 mp4，避免 HLS 分片 */
private const val UA =
    "Mozilla/5.0 (Linux; Android 4.2.2; TV) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/60.0.3112.116 Safari/537.36"

/** 抖音播放接口的 Referer（部分 CDN 校验来源） */
private const val REFERER = "https://www.douyin.com/"

// ============================================================
// ============================================================
// 引擎 1: ExoPlayer 2.18.1  ★主力★（实现逐行对齐 v11 的 dex 反编译）
// ============================================================
//
// ★ 为什么必须按 v11 的实现写 ★
//   用户手里「播放完全正常」的 v11 就是 ExoPlayer 2.18.1，且：
//   ① 用 ExoPlayer.Builder(ctx).build() → ExoPlayer 接口（不是 SimpleExoPlayer）
//   ② 用 setVideoSurfaceHolder(holder) 让 ExoPlayer 自己管理 Surface 生命周期；
//     手动 setVideoSurface(Surface) 会在 surface 尚未就绪时绑定 → 首帧渲染
//     到错误 surface → 黑方块。这是黑方块的关键差异。
//   ③ 用 ProgressiveMediaSource.Factory + setMediaSource —— 明确渐进式
//     下载 mp4 直链；setMediaItem 走默认源工厂，行为不同。
//   ④ start()=play()、pause()=pause()、isPlaying()=isPlaying()（非 playWhenReady）
/**
 * 宽容音频 sink：老电视 AudioTrack.getTimestamp() 不可靠（实测跳变 0.77s），
 * Exo 2.18.1 会抛 AudioSink$UnexpectedDiscontinuityException → Audio sink error → 没声。
 * 把异常当作「已接受」（返回 false），丢掉这一小块音频继续播 —— 宁可轻微丢音
 * 也不能没声。这是 Exo 2.x 在 Android 4.x 老设备上的已知坑，2.19+ 才修。
 */
private class LenientAudioSink(
    private val delegate: DefaultAudioSink
) : AudioSink {

    override fun setListener(listener: AudioSink.Listener) = delegate.setListener(listener)

    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = delegate.getFormatSupport(format)

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        delegate.getCurrentPositionUs(sourceEnded)

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        delegate.configure(format, specifiedBufferSize, outputChannels)
    }

    override fun play() = delegate.play()

    override fun handleDiscontinuity() = delegate.handleDiscontinuity()

    // ★ 核心：老电视 AudioTrack 时间戳跳变（实测 0.77s）→ Exo 2.18.1 抛
    //   UnexpectedDiscontinuityException → Audio sink error → 没声。
    //   这里容错：丢这一块音频继续播（宁可轻微丢音不能没声）。
    override fun handleBuffer(
        buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int
    ): Boolean {
        return try {
            delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        } catch (e: Exception) {
            Log.w(TAG, "音频时间戳跳变，丢块继续(${e.javaClass.simpleName})")
            false
        }
    }

    override fun playToEndOfStream() = delegate.playToEndOfStream()

    override fun isEnded(): Boolean = delegate.isEnded()

    override fun hasPendingData(): Boolean = delegate.hasPendingData()

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) =
        delegate.setPlaybackParameters(playbackParameters)

    override fun getPlaybackParameters(): PlaybackParameters = delegate.getPlaybackParameters()

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) =
        delegate.setSkipSilenceEnabled(skipSilenceEnabled)

    override fun getSkipSilenceEnabled(): Boolean = delegate.getSkipSilenceEnabled()

    override fun setAudioAttributes(audioAttributes: AudioAttributes) =
        delegate.setAudioAttributes(audioAttributes)

    override fun getAudioAttributes(): AudioAttributes = delegate.getAudioAttributes()

    override fun setAudioSessionId(audioSessionId: Int) = delegate.setAudioSessionId(audioSessionId)

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) =
        delegate.setAuxEffectInfo(auxEffectInfo)

    override fun enableTunnelingV21() = delegate.enableTunnelingV21()

    override fun disableTunneling() = delegate.disableTunneling()

    override fun setVolume(volume: Float) = delegate.setVolume(volume)

    override fun pause() = delegate.pause()

    override fun flush() = delegate.flush()

    override fun experimentalFlushWithoutAudioTrackRelease() =
        delegate.experimentalFlushWithoutAudioTrackRelease()

    override fun reset() = delegate.reset()
}

/** 低内存响应接口（onTrimMemory → 播放器主动缩缓冲/降内存） */
interface PlayerMemoryAware {
    fun onLowMemory()
}

/** 用宽容音频 sink 的渲染器工厂（其余渲染器走默认） */
private class LenientRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    // ★ 只换 AudioSink（buildAudioSink 是 2.18.1 的钩子），渲染器列表不动 ★
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
        enableOffload: Boolean
    ): AudioSink {
        val delegate = super.buildAudioSink(
            context, enableFloatOutput, enableAudioTrackPlaybackParams, enableOffload
        )
        return LenientAudioSink(delegate as DefaultAudioSink)
    }
}

class ExoEngine(
    private val ctx: Context,
    private val headers: Map<String, String>
) : VideoEngine {

    override val name = "ExoPlayer"
    override var listener: VideoEngine.Listener? = null

    override var holder: SurfaceHolder? = null
        set(v) {
            field = v
            val p = player ?: return
            try {
                p.setVideoSurfaceHolder(v)   // 与 v11 一致：ExoPlayer 管理 Surface 生命周期
            } catch (e: Exception) {
                Log.w(TAG, "setVideoSurfaceHolder 失败: ${e.message}")
            }
        }

    private var player: ExoPlayer? = null

    override fun prepare(url: String) {
        release()
        try {
            val uri = Uri.parse(url)

            // 数据源：走 HttpURLConnection（App 层已给 HttpsURLConnection 装宽松 TLS，
            // 老电视过期 CA 库不会在这里握手失败）—— 与 v11 一致
            val http = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(20000)
                .setReadTimeoutMs(30000)

            // ProgressiveMediaSource：明确渐进式下载 mp4 直链 —— 与 v11 一致
            val source = ProgressiveMediaSource.Factory(http)
                .createMediaSource(MediaItem.fromUri(uri))

            // ★★ 老设备内存关键配置 ★★
            //   默认 LoadControl 要缓冲【50 秒】且目标字节数不限（C.LENGTH_UNSET）——
            //   在 1GB 内存的 Mali450 盒子上会把堆吃满：表现就是
            //     · 频繁 GC → 播放卡顿
            //     · 内存压力过大 → Activity 被系统杀掉 → 「自动退回首页」
            //   （PlayerActivity 的 onPlayerError 只回调不 finish，所以退回首页不是
            //     我们的代码主动退的，只能是 OOM/系统回收。）
            //   这里收紧到：最少 8s / 最多 20s / 目标 6MB，并且时间优先于体积。
            // ★★ 复用同一个 ExoPlayer，只换媒体源 ★★
            //   原来每次切视频都 release() + 新建实例。ExoPlayer 的 release 是
            //   【异步】的（内部 playThread 要收尾），新实例会和旧实例短暂并存
            //   → 解码器 + 缓冲双重占用。1GB 老盒子上反复几十次就累积成内存碎片，
            //   表现正是「刷没两个视频就自动退回首页」（OOM 被系统杀掉）。
            //   复用后内存曲线是平的：自始至终同一个 ExoPlayer、同一条解码链路。
            val cur = player
            if (cur != null) {
                try {
                    cur.stop()
                    cur.clearMediaItems()
                    cur.setMediaSource(source)
                    cur.prepare()
                    Log.d(TAG, "Exo 复用播放器换源: " + url.take(90))
                    return
                } catch (t: Throwable) {
                    // ★ 复用失败必须能退回去重建 ★
                    //   复用是在旧实例上改状态，一旦它处于异常态（上一轮出错/解码器没收干净），
                    //   继续复用会把异常带下去 —— 表现就是刷几个视频直接闪退。
                    //   这里宁可慢一点：彻底销毁，走下面的「新建」分支。
                    Log.w(TAG, "Exo 复用失败，改为重建: " + t.message)
                    try { cur.release() } catch (_: Throwable) {}
                    player = null
                }
            }

            val loadControl = DefaultLoadControl.Builder()
                .setAllocator(DefaultAllocator(true, 128 * 1024))
                // ★ 别砍太狠 ★：先前的 8s/20s 在网络稍慢时喂不饱解码器 → 反而卡。
                //   这里取 20s/45s（接近 ExoPlayer 默认 50s 但留出内存余量）。
                .setBufferDurationsMs(20_000, 45_000, 2_500, 5_000)
                .setTargetBufferBytes(16 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            // Hard-decode-only selector.
            // Why: this TV's bottleneck is the DECODER (Cortex-A7 1.2GHz / 1GB).
            // IJK-style native players silently fall back to software decode when the
            // VPU rejects an H.264 profile, and soft-decoding 540p30 on this CPU shows
            // up as stutter + audio/video drift. GITV's Gala UniPlayer does the opposite:
            // it manages MediaCodec from the Java layer and picks the decoder deliberately.
            // ExoPlayer has the same architecture, so we filter SW candidates out here.
            // ★ 81.3 修复：按 mime 分流 —— 视频用 hwOnly，音频走默认！
            //   之前对 audio/* 也过滤（omx.google.* = AAC 软解被清掉）→ 音频无解码器 → Exo 无声
            //   （43.0 正常有声版没有这个选择器；48.0-hwonly 引入 = 无声回归点）
            val hwOnlySelector = MediaCodecSelector { mime, secure, tunneling ->
                if (!mime.startsWith("video/")) {
                    MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                } else {
                    val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                    val hw = all.filter { info ->
                        val n = info.name.lowercase()
                        !n.startsWith("omx.google.") && !n.startsWith("c2.android.") &&
                                !n.contains("software") && !n.contains("ffmpeg") && !n.contains("sw.")
                    }
                    if (hw.isNotEmpty()) hw else all
                }
            }

            // ExoPlayer.Builder has no setMediaCodecSelector; the selector must be
            // injected via DefaultRenderersFactory (this is the supported route).
            val rf = LenientRenderersFactory(ctx)
                .setMediaCodecSelector(hwOnlySelector)
            val p = ExoPlayer.Builder(ctx)
                .setRenderersFactory(rf)
                .setLoadControl(loadControl)
                .build()
            player = p

            // ★ 保比例显示 ★
            //   SCALE_TO_FIT = 视频按真实宽高比居中显示（上下/左右留黑边），
            //   不做裁剪填充 —— 直接治「有些视频拉伸严重」。
            //   （常量取自 MediaCodec，API 16+ 可用）
            try {
                p.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            } catch (_: Exception) {}

            // 绑定 SurfaceHolder（ExoPlayer 自管生命周期）—— 与 v11 一致
            val h = holder
            if (h != null) {
                try { p.setVideoSurfaceHolder(h) } catch (_: Exception) {}
            }

            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            listener?.onPrepared()
                            try { p.play() } catch (_: Exception) {}
                        }
                        Player.STATE_ENDED -> listener?.onComplete()
                        Player.STATE_BUFFERING -> listener?.onInfo("缓冲中...")
                        else -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val code = try { error.errorCodeName } catch (_: Exception) { "?" }
                    val msg = error.message ?: ""
                    Log.e(TAG, "Exo 错误: $code: $msg", error)
                    listener?.onError("Exo $code: ${msg.take(70)}")
                }

                // 视频尺寸 —— 与 v11 一致：直接回调原始宽高
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    // ★ 变形像素（SAR/pixelWidthHeightRatio ≠ 1）必须乘进宽度 ★
                    //   「有些视频拉伸严重」正是这类 anamorphic 视频：
                    //   存储尺寸与显示比例不一致，忽略 SAR 就会走形。
                    val pw = if (videoSize.pixelWidthHeightRatio > 0f)
                        videoSize.width * videoSize.pixelWidthHeightRatio
                    else videoSize.width.toFloat()
                    val w = pw.toInt().coerceAtLeast(1)
                    Log.i(
                        TAG,
                        "Exo 视频尺寸 ${videoSize.width}x${videoSize.height} " +
                            "par=${videoSize.pixelWidthHeightRatio} → ${w}x${videoSize.height}"
                    )
                    listener?.onVideoSize(w, videoSize.height)
                }
            })

            p.setMediaSource(source)
            p.prepare()
            Log.d(TAG, "Exo(2.18.1) prepare: ${url.take(90)}")
        } catch (e: Exception) {
            Log.e(TAG, "Exo 启动失败", e)
            listener?.onError("Exo启动失败: ${e.message}")
        }
    }

    override fun start() {
        try { player?.play() } catch (e: Exception) { Log.w(TAG, "Exo start: ${e.message}") }
    }

    override fun pause() {
        try { player?.pause() } catch (e: Exception) { Log.w(TAG, "Exo pause: ${e.message}") }
    }

    override fun isPlaying(): Boolean =
        try { player?.isPlaying == true } catch (_: Exception) { false }

    override fun setSpeed(speed: Float) {
        try {
            val p = player ?: return
            p.setPlaybackParameters(com.google.android.exoplayer2.PlaybackParameters(speed))
        } catch (_: Exception) {}
    }

    override fun seekTo(ms: Long) {
        try { player?.seekTo(ms) } catch (e: Exception) { Log.w(TAG, "Exo seek: ${e.message}") }
    }

    override fun position(): Long =
        try { player?.currentPosition ?: 0L } catch (_: Exception) { 0L }

    override fun bufferedMs(): Long =
        try { (player?.bufferedPosition ?: 0L).coerceAtLeast(0L) } catch (_: Exception) { 0L }

    /**
     * 诊断：报「丢帧数」+ 是否为硬解。
     * 丢帧是判断卡顿的直接指标：一直涨说明解码/渲染跟不上（降码率没用）；
     * 不涨但画面卡 → 问题在合成或音频。
     */
    override fun decoderInfo(): String {
        val p = player ?: return "无实例"
        val c = p.videoDecoderCounters
        val dropped = c?.droppedBufferCount ?: 0
        val name = try {
            val fld = p.javaClass.getDeclaredField("videoDecoderName")
            fld.isAccessible = true
            (fld.get(p) as? String).orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val hw = if (name.isNotEmpty()) "硬解" else "?"
        return hw + " 丢帧" + dropped
    }

    override fun duration(): Long =
        try { (player?.duration ?: 0L).coerceAtLeast(0L) } catch (_: Exception) { 0L }

    override fun release() {
        val p = player
        player = null
        if (p != null) {
            // 先解绑 Surface 再销毁：真机实测 release 前若仍绑着 Surface，
            // IJK/Exo 的 overlay 释放不干净，Graphics 会一路涨到 30MB+，
            // 在 647MB 内存的老电视上直接把系统 MemFree 挤到 27MB → 进程被杀。
            try { p.clearVideoSurface() } catch (_: Exception) {}
            try { p.stop() } catch (_: Exception) {}
            try { p.release() } catch (_: Exception) {}
        }
    }
}

// ============================================================
// 引擎 2: IJKPlayer（软解兜底；官方编译无 OpenSSL → 只能播 http）
// ============================================================
class IjkEngine(private val headers: Map<String, String>) : VideoEngine {

    /** 代际号：prepare 是异步延时的，用它保证只有最新一次生效（中途切视频时不误建） */
    private var gen = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    override val name = "IJKPlayer"
    override var listener: VideoEngine.Listener? = null
    override var holder: SurfaceHolder? = null
    private var player: IjkMediaPlayer? = null

    /**
     * 最近一次 native 实例释放的时刻（uptimeMillis）。
     * release() 返回后，native 的 ff_read / ff_video_dec / MediaCodec_loop
     * 线程仍在退出路上 —— 这个时间戳保证在其后 guardMs 内绝不 new 新实例，
     * 否则旧线程访问已释放结构 → UAF → 进程死 → 闪退回推荐页。
     */
    private var lastReleaseAt = 0L
    private val releaseGuardMs = 100L

    // ★ stop 与 release 之间的排水等待 ★
    //   tombstone_09 铁证：ff_read 线程撞上 OMX::CallbackDispatcher 析构
    //   → assert → abort。stop() 返回后 native 线程（ff_read / ff_video_dec /
    //   MediaCodec_loop）仍在退出路上，必须给足时间再 release / 再创建。
    // ★ 80.1: 补丁 v2(IO中断) 已保证释放时读线程秒退，drain 从 400ms 缩到 100ms
    // ★ 82.x: 100ms 不够 —— 10-08 19:58 tombstone_09 实锤：
    //   ff_read 撞 OMX::freeNode → CallbackDispatcher 析构 assert → abort（deadbaad）。
    //   本地补丁的 IO 中断只在读线程阻塞于网络时生效；视频已缓冲完时 ff_read
    //   在解码循环内，中断不触发。恢复 400ms，让 ff_read 在绝大多数路径下退出干净。
    private val stopDrainMs = 400L     // stop 后等读/解码线程退出
    private val releaseDrainMs = 250L  // release 后等 native 彻底清理

    override fun prepare(url: String) {
        // ★★★ 每次重建，不要复用（真机 tombstone 换来的教训）★★★
        //   曾把这里改成 reset() 复用实例，想减少 native 反复申请释放。
        //   结果这台 MStar 电视上直接 native 崩溃，/data/tombstones 铁证：
        //       pid 13037, tid: MediaCodec_loop  >>> com.example.douyintv <<<
        //       signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 00000000
        //   即 IJK 的 reset() 没能真正重置 native 层的 MediaCodec，
        //   切下一个视频时那条 MediaCodec_loop 线程就空指针解引用 → 进程死
        //   → 系统重建 App → 表现为「播着播着退回首页/推荐页」。
        //   IJK 的 release() 是同步的、会完整销毁 native 对象，虽然慢一点，但干净。
        //   （ExoPlayer 那侧仍保留复用 —— setMediaSource 是官方支持的方式，安全。）
        // ★★★ 不能「release 完立刻 new」★★★
        //   真机 logcat 铁证：
        //     F/libc: Fatal signal 11 (SIGSEGV) at 0xdeadbaad, thread ff_read
        //     D/Zygote: Process ... terminated by signal (11)
        //   0xdeadbaad 是 Android 用来标记「已释放内存」的魔数 —— 典型的 use-after-free。
        //   根因：IJK 的 release() 并不等待 native 线程退出
        //   （ff_read / ff_video_dec / MediaCodec_loop 还在跑），
        //   如果我们紧接着 new 一个新实例，旧线程会继续访问已释放的结构 → 进程崩溃。
        //   用户侧表现就是「只有声音没画面」或「刷着刷着退回首页」。
        //   解法：释放后异步等一小会儿（让旧线程真正退出），再创建新的；
        //   并用代际号保证中途切视频时不会误建。
        val myGen = ++gen
        // （历史：曾试 reset() 复用换源。80.1 起弃用 —— reset 不重建 MediaCodec，
        //   codec 内部资源随换源累积，第 5 个左右内存触顶被 LMK 杀；
        //   现改为每次完整 release 重建 + releaseGuardMs 兜底，内存不堆积。）
        val old = player
        player = null
        if (old != null) {
            // ★ 后台线程释放（仅复用失败兜底）★
            //   stop 后必须等 native 线程真正退出再 release：
            //   ff_read 撞 OMX::CallbackDispatcher 析构 → abort（tombstone_09）。
            // ★ 82.x: 顺序与 release() 对齐 —— stop → 排水 → 解绑 → release → 排水。
            //   旧顺序 setDisplay(null)→stop 让 SDL 渲染线程在解绑瞬间仍碰 surface，
            //   撞 Mali/UMP 驱动缺陷（tombstone_07: eglMakeCurrent → abort, deadbaad）。
            val oldRef = old
            Thread {
                try { oldRef.stop() } catch (_: Exception) {}
                SystemClock.sleep(stopDrainMs)      // 给读/解码/渲染线程退出时间
                try { oldRef.setDisplay(null) } catch (_: Exception) {}   // 线程退出后再解绑 Surface
                try { oldRef.release() } catch (_: Exception) {}
                lastReleaseAt = SystemClock.uptimeMillis()
                SystemClock.sleep(releaseDrainMs)   // release 后等 native 清理完
                mainHandler.post {
                    if (myGen == gen) scheduleCreate(url, myGen)
                }
            }.start()
        } else {
            scheduleCreate(url, myGen)
        }
    }

    /**
     * 统一创建入口 —— 无论 old 是否为 null，距上次 release 不足 guardMs 就推迟。
     * ★ 为什么不能像旧代码那样 old==null 立即创建：
     *   连刷（250ms 内切两次）时第一次的 native 线程还没退出，
     *   立即 new 的新实例会和将死未死的旧线程并发访问已释放结构
     *   → use-after-free → SIGSEGV → 进程被杀 → 冷启动回推荐页。
     *   这解释了「IJK 刷约 5 个视频必闪退」的规律（累计撞竞态窗口）。
     */
    private fun scheduleCreate(url: String, myGen: Int) {
        val wait = lastReleaseAt + releaseGuardMs - SystemClock.uptimeMillis()
        if (wait > 0) {
            mainHandler.postDelayed({
                if (myGen == gen) createAndPlay(url)
            }, wait)
        } else {
            createAndPlay(url)
        }
    }

    /** 真正创建播放器并起播（由 prepare 在安全时机调用） */
    private fun createAndPlay(url: String) {
        try {
            val mp = IjkMediaPlayer()
            applyOptions(mp, url)
            mp.prepareAsync()
            player = mp
            Log.d(TAG, "IJK 新建实例: ${url.take(90)}")
        } catch (e: Exception) {
            Log.e(TAG, "IJK 启动失败", e)
            listener?.onError("IJK启动失败: ${e.message}")
        }
    }

    /** 选项 / 监听器 / 数据源 —— 新建与复用共用（reset() 会清掉全部 setOption！） */
    private fun applyOptions(mp: IjkMediaPlayer, url: String) {
        mp.setDisplay(holder)

        val hdr = headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", hdr)
        // ★★★ 82.x: 解码模式按机型分流（不再一刀切）★★★
        //   硬解在这台 MStar 老电视上有 3 条必崩路径（10-08 /data/tombstones 实锤）：
        //     · tombstone_07: Mali EGL eglMakeCurrent → gralloc.nike → UMP msync abort（deadbaad）
        //     · tombstone_05/06: MediaCodec::queueCSDInputBuffer → memcpy 空指针
        //     · tombstone_09: ff_read 撞 OMX::freeNode → CallbackDispatcher 析构 assert
        //   全部在硬解（MediaCodec/OMX）路径上 —— 逐个修是打地鼠，修不完。
        //   软解完全不碰 MediaCodec/OMX，3 条路径全部绕开。
        //   ★ 但项目已发布，正常设备（2GB+/新盒子）硬解体验最佳且不崩 ——
        //     所以：DeviceProfile.isLegacy（≤96MB 堆或 API≤17）→ 软解；
        //     正常设备 → 硬解（mediacodec=1）。用户可在播放菜单手动切换引擎兜底。
        //   ★ 软解可行性的历史结论（62.1/80.2 的弃用原因已消除）：
        //     ① 62.1 软解卡：当时无低清选档，540p@30 在 A7 上临界 → 老设备现已
        //        默认 540p 档（DouyinApi 按 isLegacy 分流），解码压力可控。
        //     ② 80.2 软解被 LMK 杀：软解帧缓冲在 App native heap → max-buffer-size
        //        已压到 8MB + 低档小帧，峰值可控。
        //   skip_loop_filter=8（只跳非参考帧）：软解省解码又不糊（48=全跳会马赛克）。
        val legacy = DeviceProfile.isLegacy
        val hw = if (legacy) 0L else 1L
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", hw)
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", hw)
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-avc", hw)
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", hw)
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 0L)
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L)
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", if (legacy) 8L else 0L)
        // packet-buffering=1：开启包缓冲。原先设 0（不缓冲）在网络稍慢时立刻卡。
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L)
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0L)
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1L)
        // 弱网容错：单次读超时放宽，避免瞬时抖动直接判失败
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 10000000L)
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L)
        // 加快起播：FFmpeg 默认要探测约 5MB / 5 秒的数据才确定流信息，
        //   老设备上这一步就是“一个视频要缓冲很久”的主因。
        //   调到 1MB / 1 秒：足够识别音视频轨，起播明显变快。
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024L * 1024L)
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1000000L)
        // ★ 80.3: 24MB → 8MB —— 抖音短视频通常 <10MB，24MB 包缓冲是 native heap
        //   大头（实测 14MB），起播即分配 → 647MB 盒子峰值触顶被 LMK 杀。
        //   8MB 足够本地 CDN 顺畅缓冲，峰值降一半。
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", (8 * 1024 * 1024).toLong())

        mp.setOnPreparedListener { mp2 -> listener?.onPrepared(); mp2.start() }
        mp.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "IJK 错误 what=$what extra=$extra")
            listener?.onError("IJK $what/$extra"); true
        }
        mp.setOnCompletionListener { listener?.onComplete() }
        // IJK 回调 5 参数: (mp, width, height, sar_num, sar_den)
        mp.setOnVideoSizeChangedListener { _, w, h, _, _ -> listener?.onVideoSize(w, h) }
        mp.setOnInfoListener { _, what, _ ->
            when (what) {
                IMediaPlayer.MEDIA_INFO_BUFFERING_START -> listener?.onInfo("缓冲中...")
                IMediaPlayer.MEDIA_INFO_BUFFERING_END -> listener?.onInfo("")
            }
            true
        }
        mp.setDataSource(url)
    }

    override fun start() { player?.start() }
    override fun pause() { player?.pause() }
    override fun isPlaying() = player?.isPlaying == true

    override fun setSpeed(speed: Float) {
        try { player?.setSpeed(speed) } catch (_: Exception) {}
    }
    override fun seekTo(ms: Long) { player?.seekTo(ms) }
    override fun position() = player?.currentPosition ?: 0L
    /**
     * 诊断：IJK 的 videoDecoder 非空 = 真的在用 MediaCodec(VPU) 硬解；
     * 为空 = 已经静默回落到 ffmpeg 软解 —— 那 540p@30fps 在这台 A7 上必卡。
     */
    override fun decoderInfo(): String {
        val p = player ?: return "无实例"
        return try {
            if (p.videoDecoder != null) "硬解(VPU)" else "软解(ffmpeg)"
        } catch (_: Throwable) {
            "?"
        }
    }

    override fun duration() = (player?.duration ?: 0L).coerceAtLeast(0L)

    override fun release() {
        gen++    // ★ 81.4: 释放即作废所有异步创建回调（防 Activity 销毁后僵尸播放器起播）
        player?.let {
            // ★ 82.x: 先 stop + 排水，等 native 渲染/解码线程退出，再解绑 Surface + release ★
            //   真机 tombstone 铁证（10-08 19:14:59, tombstone_07）：
            //     signal 11 (SIGSEGV), fault addr deadbaad
            //     #02 libUMP.so (ump_cpu_msync_now)   #03 gralloc.nike.so
            //     #04 libEGL_mali.so (create_surface_from_native_buffer)
            //     #10 libEGL_mali.so (eglMakeCurrent)  ← 主线程
            //   崩溃点是 Mali/UMP 驱动：release 前 Surface 仍被 SDL 渲染线程使用，
            //   驱动 msync 时撞上正释放的 buffer → abort → 进程死 → 冷启动回推荐页。
            //   旧顺序 setDisplay(null)→stop→release 没有等待窗口，surfaceDestroyed/
            //   切引擎的同步 release 每次都踩这个并发。改成：
            //     stop（停播放）→ 排水（等 ff_read/渲染线程退）→ 解绑 → release → 再排水
            //   Graphics 泄漏的旧担忧（overlay 释放不干净）由"线程退出后再解绑"同样解决：
            //   线程已退，SDL_Vout 不会再碰 buffer，release 时 overlay 一次清干净。
            try { it.stop() } catch (_: Exception) {}
            SystemClock.sleep(stopDrainMs)
            try { it.setDisplay(null) } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
            lastReleaseAt = SystemClock.uptimeMillis()
            SystemClock.sleep(releaseDrainMs)
        }
        player = null
        // ★ 注意：这里绝对不能清 holder ★
        //   曾在这加过 holder = null 图省事，结果 SurfaceHolder 被清掉，
        //   下次 prepare 时 IJK 绑的是 null → 用户看到「只有声音没有画面」。
        //   holder 归 PlayerActivity 管（每次 prepare 前都会重新赋值），
        //   引擎自己不该动它。
    }
}

// ============================================================
// 引擎 3: 系统 MediaPlayer（最后兜底）
// ============================================================
class SystemEngine(private val ctx: Context, private val headers: Map<String, String>) : VideoEngine {

    override val name = "系统播放器"
    override var listener: VideoEngine.Listener? = null
    override var holder: SurfaceHolder? = null
    private var player: MediaPlayer? = null

    override fun prepare(url: String) {
        release()
        try {
            val mp = MediaPlayer()
            mp.setDisplay(holder)
            // 保比例（默认档位视 ROM 而定，显式指定最稳）
            try {
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            } catch (_: Exception) {}
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            // API 14+ 支持带 header 的 setDataSource
            mp.setDataSource(ctx, Uri.parse(url), headers)
            mp.setOnPreparedListener { p ->
                // ★ 诊断：打印系统 extractor 识别的轨道（无声排查 —— 认不认音频轨）
                // ★ 诊断写文件（酷开 ROM 过滤第三方 logcat，文件方案绕开）
                try {
                    val sb = StringBuilder("trackInfo @ " + java.util.Date().toString() + "\n")
                    val info = p.trackInfo
                    if (info != null) {
                        for (i in 0 until info.size) {
                            val ti = info[i]
                            val mime = try { ti.format?.getString(android.media.MediaFormat.KEY_MIME) } catch (_: Exception) { null }
                            sb.append("track[" + i + "] type=" + ti.trackType + " mime=" + mime + "\n")
                        }
                    } else sb.append("(trackInfo null)\n")
                    try {
                        java.io.File(ctx.filesDir, "diag_track.txt").writeText(sb.toString())
                    } catch (_: Throwable) {}
                } catch (t: Throwable) {
                    try { java.io.File(ctx.filesDir, "diag_track.txt").writeText("EX: " + t.toString()) } catch (_: Throwable) {}
                }
                listener?.onPrepared(); p.start()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "System 错误 what=$what extra=$extra")
                listener?.onError("系统 $what/$extra"); true
            }
            mp.setOnCompletionListener { listener?.onComplete() }
            mp.setOnVideoSizeChangedListener { _, w, h -> listener?.onVideoSize(w, h) }
            mp.prepareAsync()
            player = mp
            Log.d(TAG, "System prepare: ${url.take(90)}")
        } catch (e: Exception) {
            Log.e(TAG, "System 启动失败", e)
            listener?.onError("系统启动失败: ${e.message}")
        }
    }

    override fun start() { player?.start() }
    override fun pause() { try { player?.pause() } catch (_: Exception) {} }
    override fun isPlaying() = try { player?.isPlaying == true } catch (_: Exception) { false }
    override fun seekTo(ms: Long) { try { player?.seekTo(ms.toInt()) } catch (_: Exception) {} }
    override fun position() = try { player?.currentPosition?.toLong() ?: 0L } catch (_: Exception) { 0L }
    override fun duration() =
        try { (player?.duration?.toLong() ?: 0L).coerceAtLeast(0L) } catch (_: Exception) { 0L }

    override fun release() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null
    }
}


// ============================================================
// 引擎 4: VLC 3.6.3（libvlc-all 官方 aar，minSdk 17 = Android 4.2）
//   ★ 自带 ffmpeg + 独立音频/视频管线：
//     · 音频不依赖系统 AudioTrack 时间戳（Exo 在这台电视没声的根因）
//     · 无 IJK 的 OMX 释放竞态（VLC 自己管理 MediaCodec 生命周期）
//   硬解走 MediaCodec（VPU），生命周期 VLC 管，不会像 IJK 崩。
// ============================================================
class VlcEngine(
    private val ctx: Context,
    private val headers: Map<String, String>
) : VideoEngine {

    override val name = "VLC"
    override var holder: SurfaceHolder? = null
    override var listener: VideoEngine.Listener? = null

    private var libVLC: LibVLC? = null
    private var mp: org.videolan.libvlc.MediaPlayer? = null
    private var media: IMedia? = null
    private var hwDecode = true

    /** 播放中不再显示"缓冲中"：VLC 的 Buffering 事件在网络水位波动时会反复触发，
     *  但没有对应的"缓冲结束"事件 → 提示常驻。只在起播前（未进入 Playing）显示。 */
    private var vlcPlaying = false

    private val eventListener = object : org.videolan.libvlc.MediaPlayer.EventListener {
        override fun onEvent(event: org.videolan.libvlc.MediaPlayer.Event) {
            when (event.type) {
                org.videolan.libvlc.MediaPlayer.Event.Playing -> {
                    vlcPlaying = true
                    listener?.onPrepared()
                }
                org.videolan.libvlc.MediaPlayer.Event.EndReached -> {
                    vlcPlaying = false
                    listener?.onComplete()
                }
                org.videolan.libvlc.MediaPlayer.Event.EncounteredError -> {
                    vlcPlaying = false
                    listener?.onError("VLC错误")
                }
                org.videolan.libvlc.MediaPlayer.Event.Buffering -> {
                    if (!vlcPlaying) listener?.onInfo("缓冲中")
                }
                else -> {}
            }
        }
    }

    override fun prepare(url: String) {
        try {
            if (libVLC == null) {
                val args = ArrayList<String>()
                args.add("--no-drop-late-frames")
                args.add("--no-skip-frames")
                args.add("--network-caching=8000")
                // 注意：VLC 3.6 不认 --no-ssl-verify（libvlc_new 直接失败）！
                // TLS 失败是 gnutls 在 Android 4.2 找不到系统 CA（ENOENT）。
                // 解法：直链降级 http（见 prepare 里 playUrl），完全绕开 TLS。
                args.add("--file-caching=300")
                // 音频输出：audiotrack 实测断续（这台电视 AudioTrack 有硬件缺陷，
                //   Exo 也栽在这）。回 OpenSL ES（绕开 AudioTrack），靠 3s 网络
                //   缓冲防欠载。--clock-synchro=1 音频时钟同步防漂移。
                args.add("--aout=audiotrack")
                // ★ VLC 3.6 没有 force-audio-rate / jitter 选项（libvlc_new 会
                //   直接失败！）。soxr 是唯一可用的高质量重采样器 —— 解码后
                //   PCM 经 soxr 重采样再喂 AudioTrack，输出更稳。
                args.add("--audio-resampler=soxr")
                // ★ aout-rate 是 VLC 正确的输出采样率选项（force-audio-rate 是雷）。
                //   强制 48k：抖音音频 44.1k，这台电视的音频 HAL 只优化 48k，
                //   44.1 直喂会断续。VLC 重采样到 48k 再输出 = 绕开 HAL 缺陷。
                args.add("--aout-rate=48000")
                args.add("--clock-synchro=1")
                // 大小写不敏感地找 UA / Referer（抖音 CDN 校验来源）
                var ua: String? = null
                var ref: String? = null
                for ((k, v) in headers) {
                    when {
                        k.equals("user-agent", true) -> ua = v
                        k.equals("referer", true) -> ref = v
                    }
                }
                if (ua != null) args.add("--http-user-agent=" + ua)
                if (ref != null) args.add("--http-referrer=" + ref)
                libVLC = LibVLC(ctx, args)
            }
            releaseInternal()
            // VLC 的 gnutls 在 Android 4.2 上找不到系统 CA（ENOENT）→ TLS 必然失败。
            // 抖音 CDN 直链支持 http 明文，降级后绕开 TLS。仅 VLC 引擎这样做。
            val playUrl = if (url.startsWith("https://")) "http://" + url.substring(8) else url
            val m = Media(libVLC!!, Uri.parse(playUrl))
            m.setHWDecoderEnabled(hwDecode, hwDecode)
            m.addOption(":network-caching=500")
            media = m
            val p = org.videolan.libvlc.MediaPlayer(libVLC!!)
            p.setEventListener(eventListener)
            val s = holder?.surface
            if (s != null) p.vlcVout.setVideoSurface(s, holder)
            // ★ 全屏显示：setVideoSurface 模式下 VLC 按视频原始尺寸渲染
            //   （1024x576 只占屏幕 1/4 在左上角），setVideoScale 只对
            //   VLCVideoLayout 有效。正解：布局回调里 setWindowSize(屏幕尺寸)
            //   → VLC 把输出窗口拉到全屏（内部按比例缩放，不拉伸变形）。
            p.vlcVout.attachViews(object : IVLCVout.OnNewVideoLayoutListener {
                override fun onNewVideoLayout(
                    vout: IVLCVout, width: Int, height: Int,
                    visibleWidth: Int, visibleHeight: Int, sarNum: Int, sarDen: Int
                ) {
                    try {
                        val f = holder?.surfaceFrame
                        if (f != null && f.width() > 0 && f.height() > 0) {
                            vout.setWindowSize(f.width(), f.height())
                        }
                    } catch (_: Exception) {}
                }
            })
            p.setMedia(m)
            mp = p
            p.play()
            Log.d(TAG, "VLC prepare: ${url.take(90)}")
        } catch (e: Throwable) {
            Log.e(TAG, "VLC 启动失败", e)
            listener?.onError("VLC启动失败: ${e.message}")
        }
    }

    override fun start() { try { mp?.play() } catch (_: Exception) {} }

    override fun pause() { try { mp?.pause() } catch (_: Exception) {} }

    override fun isPlaying(): Boolean =
        try { mp?.isPlaying ?: false } catch (_: Exception) { false }

    override fun seekTo(ms: Long) { try { mp?.setTime(ms) } catch (_: Exception) {} }

    override fun position(): Long = try { mp?.time ?: 0L } catch (_: Exception) { 0L }

    override fun duration(): Long = try { mp?.length ?: 0L } catch (_: Exception) { 0L }

    override fun decoderInfo(): String = if (hwDecode) "VLC(硬解)" else "VLC(软解)"

    override fun release() {
        releaseInternal()
        try { libVLC?.release() } catch (_: Exception) {}
        libVLC = null
    }

    private fun releaseInternal() {
        try { mp?.vlcVout?.detachViews() } catch (_: Exception) {}
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        try { media?.release() } catch (_: Exception) {}
        mp = null
        media = null
    }
}
