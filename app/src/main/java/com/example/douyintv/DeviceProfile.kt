package com.example.douyintv

import android.app.ActivityManager
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/**
 * 机型能力检测 —— 让「老设备特调」参数不再写死，按设备能力自动分流。
 *
 * 背景：项目已发布到网上，用户设备五花八门（从 647MB 的 Android 4.2 电视
 * 到 2GB+ 的新盒子都有）。在单台 MStar 电视上调出的软解/低清/保守阈值
 * 不能硬编码 —— 否则正常设备会被无谓降级（软解浪费性能、540p 浪费画质）。
 *
 * 判定原则（全部缓存一次，运行期零开销）：
 *   · legacy = 低内存（堆上限 ≤ 96MB，典型 1GB 以下机型）或 API ≤ 17
 *     —— 这类设备上 IJK 硬解有已知 native 崩溃路径（MediaCodec/OMX/Mali），
 *       软解 + 低清档最稳；
 *   · 正常设备 = 有足够内存且系统报告 H.264 解码器 → 硬解 + 高清档。
 *
 * 所有结论都可以被 Prefs 里的手动开关覆盖（未来设置页提供）。
 */
object DeviceProfile {

    private const val TAG = "DeviceProfile"

    @Volatile
    private var computed = false

    @Volatile
    var memoryClassMB: Int = 0
        private set

    @Volatile
    var hasH264Decoder: Boolean = false
        private set

    /** 是否老弱设备（软解 + 低清档 + 保守阈值） */
    @Volatile
    var isLegacy: Boolean = false
        private set

    /** 供诊断面板显示的一句话 */
    @Volatile
    var summary: String = "未检测"
        private set

    /** 计算一次，之后直接读字段 */
    fun compute(ctx: android.content.Context) {
        if (computed) return
        synchronized(this) {
            if (computed) return
            try {
                val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
                memoryClassMB = am.memoryClass
            } catch (t: Throwable) {
                memoryClassMB = 128   // 拿不到就按正常设备处理
            }
            try {
                hasH264Decoder = probeH264()
            } catch (t: Throwable) {
                hasH264Decoder = true
            }
            val api = Build.VERSION.SDK_INT
            // ★ 老弱判定：内存小（≤96MB 堆 ≈ 1GB 以下机型）或 API ≤ 17。
            //   这类设备 IJK 硬解有 tombstone 实锤的 3 条 native 崩溃路径。
            //   注意：hasH264Decoder 不能单独作为"用硬解"依据 —— 有些电视
            //   有硬解但 IJK 调用方式触发驱动缺陷（创维 8S52 实测）。
            isLegacy = memoryClassMB <= 96 || api <= 17
            summary = "内存${memoryClassMB}MB/API$api/H264硬解=${if (hasH264Decoder) "有" else "无"}/legacy=$isLegacy"
            Log.i(TAG, "✓ 机型检测: $summary")
            computed = true
        }
    }

    private fun probeH264(): Boolean {
        return try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                if (info.supportedTypes.any { it.contains("video/avc", true) || it.contains("video/h264", true) }) {
                    return true
                }
            }
            false
        } catch (t: Throwable) {
            true
        }
    }
}
