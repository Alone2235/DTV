package com.example.douyintv

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * WebProtect / bd_ticket_guard 签名实现（抖音 web 写操作的安全层）
 * ============================================================
 * 依据公开资料（zcx2077 系列 + GitHub 同类仓库）：
 *   · 浏览器 web_protect.js 生成 EC P-256 密钥对，私钥存 localStorage（导入到 Prefs.wp_ec_private）
 *   · 请求头携带 bd_ticket_guard 数据：
 *       part1（静态）: {"bd-ticket-guard-version":2,"bd-ticket-guard-iteration-version":1,
 *                       "bd-ticket-guard-ree-public-key":"<客户端公钥>","bd-ticket-guard-web-version":2}
 *       part2（动态）: {"ts_sign":"<ts_sign>","req_content":"ticket,path,timestamp",
 *                       "req_sign":"<ECDSA签名>","timestamp":<秒>}
 *       req_sign = ECDSA-SHA256(ec_private, "ticket=<ticket>&path=<path>&timestamp=<ts>")
 *   · 完整数据 = base64url(part1) + "." + base64url(part2)
 *
 * ★ 请求头名称待用户真机抓包确认后微调（x-bd-ticket-guard-* 前缀是公开资料里的记法）。
 */
object WebProtectSigner {

    private fun secp256r1Params(): ECParameterSpec {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return (kpg.generateKeyPair().public as ECPublicKey).params
    }

    /**
     * 导入 EC 私钥，支持三种格式：
     *   · PKCS8 PEM（-----BEGIN PRIVATE KEY-----）
     *   · SEC1 PEM（-----BEGIN EC PRIVATE KEY-----，手动解 DER）
     *   · JWK JSON（{"kty":"EC","crv":"P-256","d":"<base64url>",...}）
     * 返回 PrivateKey，失败返回 null。
     */
    fun importPrivateKey(material: String): PrivateKey? {
        return try {
            val m = material.trim()
            when {
                m.startsWith("{") -> fromJwk(m)
                m.contains("BEGIN EC PRIVATE KEY") -> fromSec1(m)
                m.contains("BEGIN PRIVATE KEY") -> fromPkcs8(m)
                else -> fromSec1("-----BEGIN EC PRIVATE KEY-----\n$m\n-----END EC PRIVATE KEY-----")
            }
        } catch (e: Exception) {
            android.util.Log.e("WP", "EC 私钥导入失败: ${e.message}")
            null
        }
    }

    private fun fromPkcs8(pem: String): PrivateKey {
        val body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s"), "")
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(body, Base64.DEFAULT)))
    }

    /** SEC1：手解 DER，取 OCTET STRING 里的私钥标量 */
    private fun fromSec1(pem: String): PrivateKey {
        val body = pem.replace(Regex("-----BEGIN [^-]+-----"), "")
            .replace(Regex("-----END [^-]+-----"), "")
            .replace(Regex("\\s"), "")
        val der = Base64.decode(body, Base64.DEFAULT)
        // 粗解：找第一个 OCTET STRING (0x04 len)
        var i = 0
        while (i + 1 < der.size) {
            val tag = der[i].toInt() and 0xFF
            val len = readLen(der, i + 1)
            val head = if (len[1] < 0x80) 2 else 2 + len[1] - 0x80 + 1
            when (tag) {
                0x30, 0x31, 0xA0, 0xA1 -> { i += head; continue } // 容器，跳过
                0x04 -> {
                    val scalar = der.copyOfRange(i + head, i + head + len[0])
                    return KeyFactory.getInstance("EC")
                        .generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), secp256r1Params()))
                }
                else -> i += head
            }
        }
        throw IllegalArgumentException("SEC1 解析失败")
    }

    private fun readLen(der: ByteArray, off: Int): IntArray {
        val b = der[off].toInt() and 0xFF
        return if (b < 0x80) intArrayOf(b, 0, off + 1)
        else {
            val n = b - 0x80
            var l = 0
            for (k in 1..n) l = (l shl 8) or (der[off + k].toInt() and 0xFF)
            intArrayOf(l, n, off + 1)
        }
    }

    private fun fromJwk(jwk: String): PrivateKey {
        val o = JSONObject(jwk)
        val d = o.optString("d", "")
            .replace('-', '+').replace('_', '/')
        val pad = d.length % 4
        val b = Base64.decode(if (pad == 0) d else d + "====".take(4 - pad), Base64.DEFAULT)
        return KeyFactory.getInstance("EC")
            .generatePrivate(ECPrivateKeySpec(BigInteger(1, b), secp256r1Params()))
    }

    /**
     * 计算 req_sign = ECDSA-SHA256(priv, "ticket=<t>&path=<path>&timestamp=<ts>")
     * 返回标准 base64 的 DER 签名（与公开样本 MEUCI… 同型）。
     */
    fun signReq(priv: PrivateKey, ticket: String, path: String, ts: Long): String {
        val data = "ticket=$ticket&path=$path&timestamp=$ts".toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(priv)
        sig.update(data)
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }

    /** part1 静态配置（客户端公钥那份，同 Cookie bd_ticket_guard_client_data） */
    fun part1Json(clientPub: String): String {
        val o = JSONObject()
        o.put("bd-ticket-guard-version", 2)
        o.put("bd-ticket-guard-iteration-version", 1)
        o.put("bd-ticket-guard-ree-public-key", clientPub)
        o.put("bd-ticket-guard-web-version", 2)
        return o.toString()
    }

    /** part2 动态数据（每次请求刷新 timestamp 与 req_sign） */
    fun part2Json(tsSign: String, ticket: String, path: String, ts: Long, priv: PrivateKey): String {
        val o = JSONObject()
        o.put("ts_sign", tsSign)
        o.put("req_content", "ticket,path,timestamp")
        o.put("req_sign", signReq(priv, ticket, path, ts))
        o.put("timestamp", ts)
        return o.toString()
    }

    private fun b64url(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

    /**
     * 组装 bd_ticket_guard_client_data 完整串（base64url(part1).base64url(part2)）。
     * 缺凭据返回 null。
     */
    fun buildClientData(path: String): String? {
        val priv = Prefs.getEcPrivateKey()
        val cert = Prefs.getServerCert()
        val ticket = Prefs.getTicket()
        val tsSign = Prefs.getTsSign()
        if (priv.isBlank() || cert.isBlank() || ticket.isBlank() || tsSign.isBlank()) return null
        return try {
            val pk = importPrivateKey(priv) ?: return null
            val ts = System.currentTimeMillis() / 1000
            b64url(part1Json(cert)) + "." + b64url(part2Json(tsSign, ticket, path, ts, pk))
        } catch (e: Exception) {
            android.util.Log.e("WP", "buildClientData 失败: ${e.message}")
            null
        }
    }
}