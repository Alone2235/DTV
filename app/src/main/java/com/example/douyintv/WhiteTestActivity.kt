package com.example.douyintv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView

/**
 * 黑方块来源诊断页（纯 View 版）
 * ==============================
 *
 * ★ 本页【不允许出现任何 SurfaceView / SurfaceHolder】★
 *   纯普通 View 渲染 —— 这样白底上出现黑斑时，唯一可能来源就是系统本身
 *   （SurfaceFlinger / GPU 合成 / ROM 缺陷），与应用代码无关。
 *
 * 上一版带了个"测 SurfaceView"绿色方块，等于在检测里掺入了
 * "我们自己创建 Surface"的变量，检测就不纯粹了 —— 已按用户要求移除。
 *
 * 观察方法（文字始终与真实底色一致）：
 *   1) 纯白 #FFFFFF —— 有黑斑 ⇒ 系统层；干净 ⇒ 播放器层
 *   2) 中灰 #808080 —— 第二组对照，排除纯白过曝掩盖浅色瑕疵的可能
 */
class WhiteTestActivity : Activity() {

    private companion object {
        const val TAG = "WhiteTest"

        const val MODE_WHITE = 0
        const val MODE_GRAY = 1
    }

    private lateinit var tvBody: TextView
    private lateinit var btnWhite: TextView
    private lateinit var btnGray: TextView
    private lateinit var btnExit: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var mode = MODE_WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(R.layout.activity_white_test)

            tvBody = findViewById(R.id.tvBody) as TextView
            btnWhite = findViewById(R.id.btnWhite) as TextView
            btnGray = findViewById(R.id.btnGray) as TextView
            btnExit = findViewById(R.id.btnExit) as TextView

            // 根布局 + decorView 双保险铺纯白，连系统栏区域一起盖住，
            // 避免状态栏残留被误判成黑斑。
            window.decorView.setBackgroundColor(Color.WHITE)

            btnWhite.setOnClickListener { setMode(MODE_WHITE) }
            btnGray.setOnClickListener { setMode(MODE_GRAY) }
            btnExit.setOnClickListener { finish() }

            setMode(MODE_WHITE)

            // 规则④：显式默认焦点
            btnWhite.post { btnWhite.requestFocus() }
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate 失败", t)
        }
    }

    private fun setMode(m: Int) {
        mode = m
        val c = if (m == MODE_WHITE) Color.WHITE else Color.rgb(0x80, 0x80, 0x80)
        try {
            window.decorView.setBackgroundColor(c)
        } catch (e: Exception) {
            Log.w(TAG, "改背景失败: ${e.message}")
        }

        // ★ 文字描述必须与真实底色一致 ★（用户明确要求）
        val colorName = if (m == MODE_WHITE) "纯白 #FFFFFF" else "中灰 #808080"
        tvBody.text = buildString {
            append("当前底色：").append(colorName).append("\n\n")
            append("请观察整块底色上是否出现黑斑。\n\n")
            append("· 有黑斑 → 系统层问题（SurfaceFlinger / GPU / ROM 缺陷），\n")
            append("  与播放器代码无关，换内核也不会好。\n\n")
            append("· 干净   → 问题在播放器层，可以继续针对 Surface 处理。\n\n")
            append("本页是纯普通 View 渲染，没有 SurfaceView ——\n")
            append("如果这里都出现黑斑，那基本可以断定是系统本身。")
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            finish(); return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
