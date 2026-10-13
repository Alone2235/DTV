package com.example.dtvremote

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * 玻璃条容器：View 版的「跟手高光」（补上液态玻璃缺的光学反馈）。
 *
 * 真机液态玻璃 = 几何动画（已有 spring Q 弹）+ 光学动画（本类）：
 *   · 手指按下/拖动 → 在触点生成一层很淡的白色径向高光，跟着手指实时移动
 *   · 抬手 → 高光平滑淡出
 *   · 高光画在子 View【之上】（dispatchDraw 后绘制），但**不消费触摸事件**
 *     （dispatchTouchEvent 只旁路观察坐标，继续交给子 View 处理点击）
 *
 * 高光强度克制：最大 alpha 0.22、半径 60dp（按设计规范 0.2~0.3 / 50~70dp）。
 */
class GlassBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private var hx = 0f
    private var hy = 0f
    private var hlAlpha = 0f
    private val hlRadius = 62f * resources.displayMetrics.density
    private var cornerPx = 22f * resources.displayMetrics.density
    /** 高光吸附的单元数（底部 Tab=4：高光是一个「按钮」轮廓，而不是一团光晕） */
    var cellCount = 1

    /** 允许在玻璃条本体上横向拖拽（交给 ViewPager2 的 fakeDrag 实现连续翻页） */
    interface FakeDrag {
        fun begin()
        fun dragBy(dx: Float)
        fun end()
    }
    var fakeDrag: FakeDrag? = null
    private var lastX = 0f
    private var dragging = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * resources.displayMetrics.density
    }
    private val path = Path()
    private val rect = RectF()
    private val cellRect = RectF()
    private var anim: ValueAnimator? = null

    fun setCorner(px: Float) { cornerPx = px; invalidate() }

    private fun animateTo(target: Float, dur: Long) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(hlAlpha, target).apply {
            duration = dur
            addUpdateListener {
                hlAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hx = ev.x; hy = ev.y
                lastX = ev.x
                animateTo(0.22f, 130)
                // ★ 在玻璃条上按下 → 让 pager 进入 fakeDrag（Tab 栏本身可拖动翻页）
                try { fakeDrag?.begin(); dragging = true } catch (_: Throwable) { dragging = false }
            }
            MotionEvent.ACTION_MOVE -> {
                hx = ev.x; hy = ev.y
                if (dragging) {
                    val d = ev.x - lastX
                    lastX = ev.x
                    try { fakeDrag?.dragBy(-d) } catch (_: Throwable) {}
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    try { fakeDrag?.end() } catch (_: Throwable) {}
                }
                animateTo(0f, 280)
            }
        }
        return super.dispatchTouchEvent(ev)   // ★ 点击事件照常交给子 View
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (hlAlpha <= 0.01f || width == 0) return
        try {
            // ① 玻璃条整体圆角裁剪
            path.reset()
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            path.addRoundRect(rect, cornerPx, cornerPx, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(path)

            // ② 「按钮」轮廓高光：吸附到按下的那个单元（Tab），不是一团光晕
            val cw = width.toFloat() / cellCount
            val idx = (hx / cw).toInt().coerceIn(0, cellCount - 1)
            val inset = 5f * resources.displayMetrics.density
            cellRect.set(
                idx * cw + inset, inset,
                (idx + 1) * cw - inset, height - inset
            )
            val cellCorner = cellRect.height() / 2f

            // 内部：淡淡的径向光（液态感），中心跟随手指 x
            paint.shader = RadialGradient(
                hx.coerceIn(cellRect.left, cellRect.right), cellRect.centerY(),
                maxOf(hlRadius, cellRect.width() * 0.75f),
                intArrayOf(
                    Color.argb((hlAlpha * 200).toInt(), 255, 255, 255),
                    Color.argb((hlAlpha * 40).toInt(), 255, 255, 255),
                    Color.argb(0, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
            path.reset()
            path.addRoundRect(cellRect, cellCorner, cellCorner, Path.Direction.CW)
            canvas.drawPath(path, paint)
            paint.shader = null

            // 外框：1.2dp 白色高光描边 → 明确的「按钮」轮廓
            strokePaint.color = Color.argb((hlAlpha * 190).toInt(), 255, 255, 255)
            canvas.drawPath(path, strokePaint)
            canvas.restore()
        } catch (_: Throwable) {}
    }
}
