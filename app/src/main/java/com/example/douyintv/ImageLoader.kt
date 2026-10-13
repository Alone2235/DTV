package com.example.douyintv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import okhttp3.Request
import java.util.concurrent.Executors

/**
 * 极简图片加载器（列表封面用）
 *
 * 为什么不用 Glide/Coil：本项目刻意不引 AndroidX，而 Glide 4 / Coil
 * 都强依赖 AndroidX，且 minSdk 16 上跑不稳。这里只要「下载 + 解码 + LRU + 主线程回填」，
 * 一百行内够用。LruCache 用 android.util.LruCache（API 12+，非 AndroidX）。
 */
object ImageLoader {

    private const val TAG = "ImageLoader"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private const val REFERER = "https://www.douyin.com/"

    /** 按字节数限制：可用堆的 1/8，封顶 4MB（抖音封面都是小图） */
    private val cache = object : LruCache<String, Bitmap>(
        // 1/8 可用堆，但【封顶 4MB】：老盒子堆本身小，不封顶的话播放时
        // 图片缓存会和播放器抢内存，直接推高 OOM 概率。
        (Runtime.getRuntime().maxMemory() / 8).toInt()
            .coerceIn(2 * 1024 * 1024, 4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /** 已在加载中的 url，避免同一张图重复排队 */
    private val inflight = HashSet<String>()

    fun load(url: String?, into: ImageView, tag: String? = null) {
        // ★ 无 URL → 直接失败兜底图
        //   规则要求「所有图片必须配置占位图、加载失败兜底图，防止空白黑屏」。
        if (url.isNullOrBlank()) {
            into.setImageResource(R.drawable.cover_error)
            return
        }
        // 列表复用：用 tag 标记这张图属于哪一行，防止错位
        into.tag = tag ?: url

        cache.get(url)?.let {
            into.setImageBitmap(it)
            return
        }
        // ★ 加载中占位图（原先这里是 setImageBitmap(null) → 一片黑，
        //   用户分不清"还没加载"和"加载失败"）
        into.setImageResource(R.drawable.cover_placeholder)

        synchronized(inflight) {
            if (!inflight.add(url)) return
        }

        pool.execute {
            val bmp = fetch(url)
            synchronized(inflight) { inflight.remove(url) }
            if (bmp == null) {
                Log.w(TAG, "封面加载失败: ${url.take(60)}")
                // ★ 失败兜底图：与占位图视觉不同，现场一眼能区分
                main.post {
                    if (into.tag == (tag ?: url)) {
                        into.setImageResource(R.drawable.cover_error)
                    }
                }
                return@execute
            }
            cache.put(url, bmp)
            main.post {
                // 回填前校验：这一行现在要的仍是这张图
                if (into.tag == (tag ?: url)) into.setImageBitmap(bmp)
            }
        }
    }

    private fun fetch(url: String): Bitmap? = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .build()
        // ★ 82.x 修复「老设备封面不加载」：封面原本只走【严格 TLS】client，
        //   老电视 CA 库过时 → 握手失败 → 封面全挂（视频能播是因为播放路径降级了）。
        //   改为与 DouyinApi.exec 同策略：严格优先，TLS/证书失败时宽松重试。
        val resp = try {
            DouyinApi.httpClient().newCall(req).execute()
        } catch (e: Exception) {
            if (!isTlsProblem(e)) throw e
            Log.w(TAG, "封面严格 TLS 失败 → 宽松重试: ${e.message}")
            DouyinApi.relaxedHttpClient().newCall(req).execute()
        }
        val code = resp.code()
        val bytes = resp.body()?.bytes()
        resp.close()
        if (code != 200 || bytes == null || bytes.isEmpty()) {
            Log.w(TAG, "封面 HTTP $code")
            null
        } else {
            // 先探尺寸再按需采样，避免大图撑爆 1GB 电视的内存
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / sample > 480 || opts.outHeight / sample > 480) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565   // 省一半内存
                })
        }
    } catch (e: Exception) {
        Log.w(TAG, "封面异常: ${e.message}")
        null
    }

    /** TLS/证书层问题判断（与 DouyinApi.isTlsProblem 同规则） */
    private fun isTlsProblem(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth++ < 10) {
            if (e is javax.net.ssl.SSLException || e is java.security.cert.CertificateException) return true
            val m = e.message ?: ""
            if (m.contains("Trust anchor") || m.contains("CertPathValidator")) return true
            e = e.cause
        }
        return false
    }

    fun clear() {
        cache.evictAll()
    }

    /**
     * 把图片缓存收缩到指定大小。
     *
     * 为什么需要：这台电视总内存只有 647MB、空闲常年在 30MB 上下。
     * 播放页期间首页那些封面图完全用不到，却一直占着十几 MB，
     * 直接挤压播放器的解码/缓冲开销 → 系统杀进程 → 退回首页。
     * 进播放页时压一次缓存，能立刻把这块让出来。
     */
    fun trimTo(maxBytes: Int) {
        try {
            cache.trimToSize(maxBytes)
        } catch (_: Throwable) {
        }
    }

    /** 当前缓存占用（诊断用） */
    fun cacheSize(): Int = try { cache.size() } catch (_: Throwable) { 0 }
}
