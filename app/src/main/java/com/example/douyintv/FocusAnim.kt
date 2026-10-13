package com.example.douyintv

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 统一动效系统（10 英尺 UI / 焦点反馈）
 * ===================================
 * 设计原则：
 *   · 焦点放大 = 1.06x → 目标 1.10x，**ease-out**（DecelerateInterpolator）
 *   · 时长 140ms：太快没反馈感，太慢显得拖
 *   · 失焦同样 ease-out，同长，保持对称
 *   · 所有调用方用同一入口 → 全应用焦点手感一致，改一处全改
 *
 * 用法：view.onFocusChangeListener 里调 FocusAnim.scale(view, hasFocus)
 */
object FocusAnim {

    private const val DURATION = 140L
    private const val FOCUSED = 1.10f
    private const val NORMAL = 1.0f

    /** 焦点缩放（ease-out，140ms） */
    fun scale(v: View, hasFocus: Boolean) = scaleTo(v, hasFocus, FOCUSED)

    /** 焦点缩放（自定义幅度：大卡片用 1.04，按钮用 1.10） */
    fun scaleTo(v: View, hasFocus: Boolean, target: Float) {
        try {
            val t = if (hasFocus) target else NORMAL
            val s = v.scaleX
            if (Math.abs(s - t) < 0.01f) return   // 已是目标值，跳过
            v.animate()
                .scaleX(t).scaleY(t)
                .setDuration(DURATION)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } catch (_: Throwable) {}
    }

    /** 淡入（页面/面板进入） */
    fun fadeIn(v: View) {
        try {
            v.alpha = 0f
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        } catch (_: Throwable) {}
    }

    /** 按下回弹（瞬时按压缩放，用于遥控按钮/触屏） */
    fun press(v: View) {
        try {
            val set = AnimatorSet()
            set.playTogether(
                ObjectAnimator.ofFloat(v, "scaleX", 1f, 0.94f, 1f),
                ObjectAnimator.ofFloat(v, "scaleY", 1f, 0.94f, 1f)
            )
            set.duration = 160L
            set.interpolator = DecelerateInterpolator()
            set.start()
        } catch (_: Throwable) {}
    }
}
