package com.example.douyintv

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 按视频比例约束【内部视频 View】尺寸并强制居中的容器。
 *
 * ★ 为什么这样设计（而不是让容器自己缩小）★
 *   上一版让容器自己 setMeasuredDimension 变小，靠 layout_gravity=center 居中。
 *   实测在这台电视的 ROM 上 gravity 没生效 → 画面整体偏右、左边一大块黑边。
 *   现在改成 ExoPlayer 官方 AspectRatioFrameLayout 的做法：
 *     · 容器【自己始终占满父容器】（全屏）→ 不存在被摆放错位的问题；
 *     · 只把【子 View】按视频比例测量；
 *     · 在 onLayout 里用 (父尺寸 - 子尺寸) / 2 【显式居中】，
 *       完全不走 gravity，老 ROM 上也不会偏。
 *
 * ★ 防抖 ★
 *   比例相同（容差 1e-3）时不 requestLayout，避免 Surface 反复重建。
 */
class AspectFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    /** 视频宽高比；<= 0 表示不约束，子 View 直接铺满 */
    var aspectRatio: Float = 0f
        set(value) {
            val v = if (value.isNaN() || value <= 0f) 0f else value
            val same = field > 0f && v > 0f && Math.abs(field - v) < 1e-3f
            if (!same) {
                field = v
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pw = MeasureSpec.getSize(widthMeasureSpec)
        val ph = MeasureSpec.getSize(heightMeasureSpec)
        val ratio = aspectRatio

        // 计算子 View 的目标尺寸
        var cw = pw
        var ch = ph
        if (ratio > 0f && pw > 0 && ph > 0) {
            if (ratio > pw.toFloat() / ph) {
                // 视频比容器更“宽” → 宽度占满，高度压扁（上下留黑边）
                ch = (pw / ratio).toInt()
            } else {
                // 视频比容器更“高” → 高度占满，宽度收窄（左右留黑边）
                cw = (ph * ratio).toInt()
            }
            if (cw < 1) cw = 1
            if (ch < 1) ch = 1
        }

        val cwSpec = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY)
        val chSpec = MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility != GONE) c.measure(cwSpec, chSpec)
        }

        // ★ 自己始终占满父容器（全屏）★ —— 定位问题在 onLayout 里解决
        setMeasuredDimension(pw, ph)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pw = r - l
        val ph = b - t
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val cw = c.measuredWidth
            val ch = c.measuredHeight
            // ★ 显式居中：不依赖 layout_gravity ★
            //   （老 ROM 上 gravity 偶发失效，正是上一版“画面偏右”的原因）
            val left = (pw - cw) / 2
            val top = (ph - ch) / 2
            c.layout(left, top, left + cw, top + ch)
        }
    }
}
