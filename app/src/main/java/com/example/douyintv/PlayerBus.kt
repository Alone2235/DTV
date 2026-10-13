package com.example.douyintv

import android.os.Handler
import android.os.Looper

/**
 * 手机遥控总线：ConfigServer（LAN HTTP）把命令投给播放页，播放页执行。
 *
 * - attach/detach：PlayerActivity 在 onCreate/onDestroy 时挂接/摘除（单实例）。
 * - send()：任意线程可调，内部 post 回主线程再执行（播放器 API 要求主线程）。
 * - snapshot：播放页每 500ms 更新一次播放状态，ConfigServer /status 直接读，
 *   供手机客户端展示「正在播什么/进度/播放中/已赞/已藏」。
 */
object PlayerBus {

    interface Listener {
        /** cmd: next/prev/digg/collect/toggle/pause/play/seek/line/engine/danmaku/loop/landscape/search/push */
        fun onCommand(cmd: String, arg: String?)
    }

    @Volatile
    private var listener: Listener? = null

    @Volatile
    var snapshot = Snapshot()
        private set

    class Snapshot {
        @Volatile var inPlayer = false
        @Volatile var title = ""
        @Volatile var author = ""
        @Volatile var positionMs = 0L
        @Volatile var durationMs = 0L
        @Volatile var playing = false
        @Volatile var digged = false
        @Volatile var collected = false
        @Volatile var engine = ""
        @Volatile var danmaku = false
        @Volatile var speed = 1.0f
    }

    private val main = Handler(Looper.getMainLooper())

    fun attach(l: Listener) { listener = l }
    fun detach(l: Listener) { if (listener === l) listener = null }

    fun send(cmd: String, arg: String? = null) {
        val l = listener ?: return
        main.post { try { l.onCommand(cmd, arg) } catch (_: Throwable) {} }
    }
}
