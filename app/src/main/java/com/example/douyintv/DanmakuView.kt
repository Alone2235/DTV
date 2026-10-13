package com.example.douyintv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import java.util.Random

/**
 * 轻量弹幕层（纯 Canvas，无第三方库，API 17 兼容）。
 *
 * 文案由播放器喂入（默认取当前视频的评论区文案），
 * 像 B 站弹幕一样从左向右飞过屏幕。
 * 透明叠加在视频之上：不拦截触摸/焦点，纯被动显示。
 */
class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private class Item(
        val text: String,
        val color: Int,
        var x: Float,          // 当前 x（px）
        val y: Float,          // 纵向位置（0..1 比例）
        val speed: Float,      // 每帧移动 px
        val paint: Paint
    )

    private val items = ArrayList<Item>()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var pool = ArrayList<String>()       // 待发射文案池
    private var poolIdx = 0
    private val rnd = Random()
    private var spawnCounter = 0

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f * (resources.displayMetrics.scaledDensity.coerceAtLeast(1f))
        setShadowLayer(6f, 0f, 2f, 0xFF000000.toInt())   // 黑描边保证视频上可读
    }

    private val COLORS = intArrayOf(
        0xFFFFFFFF.toInt(),  // 白（主力）
        0xFFFFE066.toInt(),  // 黄
        0xFFFE2C55.toInt(),  // 品牌红
        0xFF81C784.toInt(),  // 绿
        0xFF64B5F6.toInt(),  // 蓝
        0xFFFFB74D.toInt()   // 橙
    )

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val w = width
            if (w > 0) {
                // 移动 + 出屏回收
                var i = 0
                while (i < items.size) {
                    val it = items[i]
                    it.x -= it.speed
                    val pw = it.paint.measureText(it.text)
                    if (it.x + pw < -20f) items.removeAt(i) else i++
                }
                maybeSpawn(w)
                invalidate()
            }
            handler.postDelayed(this, 33)
        }
    }

    /** 按节奏发射新弹幕（5 条横道随机入道） */
    private fun maybeSpawn(w: Int) {
        if (pool.isEmpty()) return
        if (items.size >= 22) return
        if (rnd.nextInt(3) != 0) return          // ≈每 100ms 平均一条
        if (++spawnCounter > 100000) spawnCounter = 0
        val text = pool[poolIdx % pool.size]
        poolIdx++
        val lane = rnd.nextInt(5)
        val y = 0.10f + 0.15f * lane             // 上 10% ~ 70% 区域
        val speed = 4.5f + rnd.nextFloat() * 2.5f
        val paint = Paint(basePaint).apply { this.color = COLORS[rnd.nextInt(COLORS.size)] }
        val item = Item(text, paint.color, w.toFloat() + rnd.nextFloat() * 150f, height * y, speed, paint)
        items.add(item)
    }

    /** 播放器喂入弹幕文案（切视频时先 setContent 新的） */
    fun setContent(texts: List<String>) {
        pool = ArrayList(texts.filter { it.isNotBlank() })
        poolIdx = 0
        items.clear()
    }

    /** 开/关：开启启动动画循环；关闭停帧并清屏 */
    fun setRunning(on: Boolean) {
        if (running == on) return
        running = on
        if (on) {
            handler.removeCallbacks(tick)
            handler.post(tick)
        } else {
            handler.removeCallbacks(tick)
            items.clear()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
}
