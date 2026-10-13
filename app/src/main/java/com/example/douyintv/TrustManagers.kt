package com.example.douyintv

import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * 宽松信任管理器 —— 只在【系统默认验证失败】后作为兜底使用，不做默认。
 *
 * 为什么必须要有：
 *   Android 4.x 电视（酷开）的 CA 信任库停留在出厂年份，且没有 GMS 无法更新。
 *   抖音证书链的根 CA（如 Let's Encrypt ISRG Root X1，2015 年才有）不在该库里，
 *   握手直接报:
 *     SSLHandshakeException: CertPathValidatorException:
 *     Trust anchor for certification path not found
 *
 *   注意: 换 TLS 协议栈（Conscrypt）**解决不了**这个问题 ——
 *   它替换的是协议实现，信任库依旧读系统 CA 存储。
 *
 * 安全性: 仅在该应用（只访问抖音）且仅在严格校验失败时启用；
 *         正常设备（手机）永远走严格路径。
 */
class RelaxedTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
