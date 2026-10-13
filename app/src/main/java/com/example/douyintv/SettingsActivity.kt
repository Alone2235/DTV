package com.example.douyintv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.media.MediaCodecList
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

/**
 * 设置页（只留诊断）
 * ==================
 * 登录相关的全部搬到 LoginActivity：
 *   · 扫码推送 Cookie、手动输入、退出登录  →  登录页
 *   · 这一页不再启动 ConfigServer（谁开谁关，避免和登录页抢 8088 端口）
 */
class SettingsActivity : Activity() {

    private companion object {
        private const val TAG = "Settings"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(R.layout.activity_settings)

            val btnDiag = findViewById(R.id.btnDiag) as TextView
            val btnProbe = findViewById(R.id.btnProbe) as TextView
            val btnAbout = findViewById(R.id.btnAbout) as TextView

            setupTvFocus(btnDiag, btnProbe, btnAbout)
            // 黑方块来源诊断页入口（判定方法见 WhiteTestActivity 注释）
            btnDiag.setOnClickListener {
                startActivity(Intent(this, WhiteTestActivity::class.java))
            }
            // 接口自检：热搜 / 搜索 / 联想的真实可用性，结果直接打在屏幕上
            btnProbe.setOnClickListener {
                startActivity(Intent(this, ApiProbeActivity::class.java))
            }
            // 关于：作者 / 主页二维码 / 版本与签名指纹
            btnAbout.setOnClickListener {
                startActivity(Intent(this, AboutActivity::class.java))
            }

            // 解码器探测：这台电视到底有没有可用的视频硬解，一看便知。
            // 创维酷开是定制系统（带自研「图强引擎」），第三方 APK 的标准
            // MediaCodec 接口有可能拿不到硬件解码器 —— 那就是卡顿的根因。
            (findViewById(R.id.tvCodecs) as? TextView)?.text = probeVideoDecoders()

            btnDiag.post { btnDiag.requestFocus() }
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate 失败", t)
        }
    }

    /**
     * 列出本机所有【视频解码器】，用来判断硬解到底能不能用。
     *
     * 为什么要看这个：本机是创维酷开定制系统（Cortex-A7 1.2GHz + Mali450，Android 4.2 兼容层），
     * 视频硬解很可能走厂商私有引擎（「图强引擎」）。如果这里列不出 H.264 解码器，
     * 就说明第三方 App 只能软解 —— 而软解 540p@30fps 对这颗 CPU 正好是极限，
     * 表现就是「卡 + 音画不同步」。有了这份清单就能定死方案。
     */
    private fun probeVideoDecoders(): String {
        val sb = StringBuilder()
        try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            val items = ArrayList<String>()
            var hasAvc = false
            var hasHevc = false
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                val vt = info.supportedTypes.filter { it.startsWith("video/") }
                if (vt.isEmpty()) continue
                val short = vt.joinToString(",") { it.removePrefix("video/") }
                items.add("${info.name}  [$short]")
                if (vt.any { it.contains("avc", true) || it.contains("h264", true) }) hasAvc = true
                if (vt.any { it.contains("hevc", true) || it.contains("h265", true) }) hasHevc = true
            }
            if (items.isEmpty()) {
                sb.append("⚠ 系统没有报告任何视频解码器\n")
                sb.append("→ 第三方 App 拿不到硬解，只能软解\n")
                sb.append("→ 这就是卡顿与音画不同步的根因")
            } else {
                sb.append("视频解码器 ${items.size} 个：\n")
                items.take(10).forEach { sb.append("· ").append(it).append("\n") }
                if (items.size > 10) sb.append("…（还有 ${items.size - 10} 个）\n")
                sb.append("\n")
                sb.append(if (hasAvc) "✓ 有 H.264 解码器（抖音主要格式）" else "⚠ 没找到 H.264 解码器")
                sb.append("\n")
                sb.append(if (hasHevc) "✓ 有 H.265 解码器" else "· 无 H.265")
            }
        } catch (t: Throwable) {
            sb.append("探测失败：").append(t.javaClass.simpleName).append(": ").append(t.message)
        }
        return sb.toString()
    }

    /** TV 焦点态：轻微放大 + 允许在 touch mode 下被方向键聚焦 */
    private fun setupTvFocus(vararg views: View) {
        views.forEach { v ->
            v.isFocusable = true
            v.isFocusableInTouchMode = true
            v.setOnFocusChangeListener { view, hasFocus -> FocusAnim.scaleTo(view, hasFocus, 1.03f) }
        }
    }

    /** 遥控器: dispatchKeyEvent 比 onKeyDown 可靠 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            finish(); return true
        }
        return super.dispatchKeyEvent(event)
    }
}
