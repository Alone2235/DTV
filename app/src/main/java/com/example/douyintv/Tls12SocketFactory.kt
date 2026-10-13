package com.example.douyintv

import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.*

/**
 * 强制 TLS 1.2 —— Android 4.1~4.4 默认只启用 TLS 1.0/1.1，而抖音要求 1.2+
 *
 * ⚠️ 关键: 只能启用设备**实际支持**的协议。
 *    直接 `s.enabledProtocols = arrayOf("TLSv1.2", ...)` 在老 ROM 上会抛
 *    IllegalArgumentException（不支持的名字不能进 enabledProtocols）。
 */
class Tls12SocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

    override fun getDefaultCipherSuites() = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites() = delegate.supportedCipherSuites

    override fun createSocket(s: Socket, h: String, p: Int, a: Boolean) = delegate.createSocket(s, h, p, a).also { tls(it) }
    override fun createSocket(h: String, p: Int) = delegate.createSocket(h, p).also { tls(it) }
    override fun createSocket(h: String, p: Int, lh: InetAddress, lp: Int) = delegate.createSocket(h, p, lh, lp).also { tls(it) }
    override fun createSocket(h: InetAddress, p: Int) = delegate.createSocket(h, p).also { tls(it) }
    override fun createSocket(a: InetAddress, p: Int, la: InetAddress, lp: Int) = delegate.createSocket(a, p, la, lp).also { tls(it) }

    private fun tls(s: Socket) {
        if (s !is SSLSocket) return
        try {
            val supported = s.supportedProtocols?.toSet() ?: emptySet()
            // 优先 1.2，逐级降级，且只挑设备支持的
            val want = listOf("TLSv1.2", "TLSv1.1", "TLSv1").filter { it in supported }
            if (want.isNotEmpty()) s.enabledProtocols = want.toTypedArray()
        } catch (e: Exception) {
            // 老 ROM 异常就保持默认协议
        }
    }

    companion object {
        /** 当前设备是否支持 TLS 1.2（诊断用） */
        fun deviceSupportsTls12(): Boolean = try {
            val ctx = SSLContext.getInstance("TLSv1.2")
            ctx.init(null, null, SecureRandom())
            "TLSv1.2" in (ctx.socketFactory.createSocket() as SSLSocket).supportedProtocols
        } catch (e: Exception) {
            false
        }

        fun create(tm: X509TrustManager): Tls12SocketFactory? = try {
            val ctx = try {
                SSLContext.getInstance("TLSv1.2")
            } catch (_: Exception) {
                SSLContext.getInstance("TLS")
            }
            ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
            Tls12SocketFactory(ctx.socketFactory as SSLSocketFactory)
        } catch (e: Exception) {
            null
        }
    }
}
