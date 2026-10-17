package com.example.douyintv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 自绘 Toast —— 替代系统 Toast
 * ============================
 * 为什么不用系统 Toast：
 *   系统 Toast 是白底黑字方块，在深色 TV 界面上极其突兀，是"界面露馅"的主要来源。
 *
 * 设计（对应 docs/DESIGN.md）：
 *   · 深色胶囊（scrim_panel 底）+ 细描边（divider）→ 与玻璃面板同语言
 *   · 底部居中，距底 96dp（避开暂停菜单）
 *   · 淡入 160ms / 停留 1.6s / 淡出 240ms
 *
 * 实现：直接挂到当前 Activity 的 android.R.id.content 上（不依赖 WindowManager / 悬浮窗权限）
 */
object CustomToast {

    private val main = Handler(Looper.getMainLooper())
    private var current: View? = null

    fun show(ctx: Context?, msg: CharSequence?) {
        val text = msg?.toString()?.trim().orEmpty()
        if (text.isEmpty() || ctx == null) return
        val activity = findActivity(ctx) ?: return
        main.post {
            try {
                val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@post
                // 同一时刻只保留一条
                current?.let { old -> (old.parent as? ViewGroup)?.removeView(old) }

                val dm = activity.resources.displayMetrics
                val tv = TextView(activity)
                tv.text = text
                tv.setTextColor(activity.resources.getColor(R.color.text_primary))
                tv.textSize = 16f
                tv.gravity = Gravity.CENTER
                tv.setPadding((dm.density * 20).toInt(), (dm.density * 12).toInt(),
                    (dm.density * 20).toInt(), (dm.density * 12).toInt())
                tv.background = GradientDrawable().apply {
                    cornerRadius = dm.density * 22f
                    setColor(activity.resources.getColor(R.color.scrim_panel))
                    setStroke((dm.density * 1.5f).toInt(), activity.resources.getColor(R.color.divider))
                }
                tv.alpha = 0f

                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                )
                lp.bottomMargin = (dm.density * 96).toInt()
                root.addView(tv, lp)
                current = tv

                tv.animate().alpha(1f).setDuration(160).start()
                main.postDelayed({
                    tv.animate().alpha(0f).setDuration(240).withEndAction {
                        try { root.removeView(tv) } catch (_: Throwable) {}
                        if (current === tv) current = null
                    }.start()
                }, 1600)
            } catch (_: Throwable) {
                // 兜底：极端情况下退回系统 Toast，保证有反馈
                try { android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
            }
        }
    }

    /** 从任意 Context 向上找到 Activity（兼容 ContextWrapper 链） */
    private fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
