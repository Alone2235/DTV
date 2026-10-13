package com.example.douyintv

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 关于 Dtv（设置 -> 关于）
 * ========================
 * 展示作者名称 + 主页二维码 + 版本号 + 签名指纹。
 * 签名指纹与 App 启动时的自校验联动：指纹不符（被重打包）时显示红条警告。
 */
class AboutActivity : Activity() {

    private companion object {
        const val TAG = "About"
        const val AUTHOR_NAME = "chunese2235"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(R.layout.activity_about)

            (findViewById(R.id.tvAuthor) as TextView).text = AUTHOR_NAME
            (findViewById(R.id.tvVersion) as TextView).text = versionName()

            val sig = findViewById(R.id.tvSig) as TextView
            sig.text = App.sigInfo()

            // 签名校验不符 -> 红色警告条（防篡改提醒，不阻断使用 ——
            // 避免指纹机制尚未成熟时误杀正常安装）
            val badge = findViewById(R.id.tvSigBadge) as TextView
            if (App.sigIsOfficial == false) {
                badge.visibility = View.VISIBLE
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate 失败", t)
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            finish(); return true
        }
        return super.dispatchKeyEvent(event)
    }
}
