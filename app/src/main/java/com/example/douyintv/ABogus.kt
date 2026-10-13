package com.example.douyintv

import java.util.Random

/**
 * 抖音 a_bogus 签名（web 端）
 * ===========================
 * 移植自开源实现 https://github.com/amxsa/douyin_abogus_python （abogus.py，逐行对照）。
 *
 * ★ 为什么必须用 a_bogus ★
 *   抖音搜索类接口（general/search/single、search/item）对 X-Bogus 一律返回
 *   status_code=2483（风控），只有 a_bogus 能过。同类项目 myDV 也是靠
 *   generateABogusParam 实现的（从其 dex 中确认）。
 *
 * ★ 与 X-Bogus 的区别 ★
 *   · 哈希用 SM3（国密），不是 MD5           → 见 Sm3.kt
 *   · 5 张自定义字符表（s0~s4），X-Bogus 只有 1 张
 *   · 需要浏览器指纹段（innerWidth/innerHeight/screenY 等）
 *   · 时间戳用毫秒且随机抖动（startTime / endTime）
 *
 * ★ 实现约定 ★
 *   所有中间产物都用 ByteArray 表示（对应 Python 里恒为 0~255 的字符码），
 *   避免 Kotlin String 与 Python str 的编码差异。
 */
class ABogus(private val userAgent: String) {

    private companion object {
        const val END_STRING = "cus"

        val S0 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
        val S1 = "Dkdpgh4ZKsQB80/Mfvw36XI1R25+WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe="
        val S2 = "Dkdpgh4ZKsQB80/Mfvw36XI1R25-WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe="
        val S3 = "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe"
        val S4 = "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe"

        val rnd = Random()
    }

    private val uaCode: IntArray
    private val browser: String
    private val browserLen: Int
    private val browserCode: IntArray

    init {
        uaCode = generateUaCode(userAgent)
        browser = generateBrowserInfo("Win32")
        browserLen = browser.length
        browserCode = charCodeAt(browser)
    }

    /**
     * 生成 a_bogus。
     * @param params 与请求 URL 中完全一致的查询串（顺序敏感）
     */
    fun generate(params: String): String {
        val s1 = generateString1()
        val s2 = generateString2(params)
        val all = ByteArray(s1.size + s2.size)
        System.arraycopy(s1, 0, all, 0, s1.size)
        System.arraycopy(s2, 0, all, s1.size, s2.size)
        return generateResult(all, S4)
    }

    // ================================================================
    // UA 编码
    // ================================================================
    private fun generateUaCode(ua: String): IntArray {
        // Python: key = ''.join(chr(int(n)) for n in [0.00390625, 1, 14])
        //         → "\x00\x01\x0e"
        val key = byteArrayOf(0, 1, 14)
        val rc4ed = rc4Encrypt(ua.toByteArray(Charsets.ISO_8859_1), key)
        val b64 = generateResult(rc4ed, S3)
        return bytesToIntArray(Sm3.hash(b64.toByteArray(Charsets.UTF_8)))
    }

    // ================================================================
    // 随机段（对应 Python list_1 / list_2 / list_3）
    // ================================================================
    private fun randomList(b: Int, c: Int, d: Int, e: Int, f: Int, g: Int): IntArray {
        val r = rnd.nextDouble() * 10000
        val ir = r.toInt()
        val v1 = ir and 255
        val v2 = ir shr 8
        return intArrayOf(
            (v1 and b) or d,
            (v1 and c) or e,
            (v2 and b) or f,
            (v2 and c) or g
        )
    }

    private fun list1() = randomList(170, 85, 1, 2, 5, 45 and 170)
    private fun list2() = randomList(170, 85, 1, 0, 0, 0)
    private fun list3() = randomList(170, 85, 1, 0, 5, 0)

    private fun generateString1(): ByteArray {
        val out = ByteArray(12)
        var p = 0
        for (arr in arrayOf(list1(), list2(), list3())) {
            for (x in arr) {
                out[p++] = (x and 0xFF).toByte()
            }
        }
        return out
    }

    // ================================================================
    // 主体段（对应 Python generate_string_2）
    // ================================================================
    private fun generateString2(params: String): ByteArray {
        val a = generateString2List(params, "GET")
        val e = endCheckNum(a)
        val all = ByteArray(a.size + browserCode.size + 1)
        var p = 0
        for (x in a) all[p++] = (x and 0xFF).toByte()
        for (x in browserCode) all[p++] = (x and 0xFF).toByte()
        all[p] = (e and 0xFF).toByte()
        return rc4Encrypt(all, byteArrayOf('y'.code.toByte()))
    }

    private fun generateString2List(params: String, method: String): IntArray {
        val startTime = System.currentTimeMillis()
        val endTime = startTime + 4 + rnd.nextInt(5)          // randint(4, 8)
        val paramsArray = generateParamsCode(params)
        val methodArray = generateMethodCode(method)
        return list4(
            ((endTime shr 24) and 255).toInt(), paramsArray[21], uaCode[23],
            ((endTime shr 16) and 255).toInt(), paramsArray[22], uaCode[24],
            ((endTime shr 8) and 255).toInt(), (endTime and 255).toInt(),
            ((startTime shr 24) and 255).toInt(), ((startTime shr 16) and 255).toInt(),
            ((startTime shr 8) and 255).toInt(), (startTime and 255).toInt(),
            methodArray[21], methodArray[22],
            (endTime / 4294967296L).toInt(),
            (startTime / 4294967296L).toInt(),
            browserLen
        )
    }

    private fun list4(
        a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int,
        i: Int, j: Int, k: Int, m: Int, n: Int, o: Int, p: Int, q: Int, r: Int
    ): IntArray = intArrayOf(
        44, a, 0, 0, 0, 0, 24, b, n, 0, c, d, 0, 0, 0, 1, 0, 239,
        e, o, f, g, 0, 0, 0, 0, h, 0, 0, 14, i, j, 0, k, m, 3, p, 1, q, 1, r, 0, 0, 0
    )

    private fun endCheckNum(a: IntArray): Int {
        var r = 0
        for (x in a) r = r xor x
        return r
    }

    // ================================================================
    // SM3 段（双重 SM3，对应 Python generate_params_code）
    // ================================================================
    private fun generateMethodCode(method: String): IntArray {
        val inner = Sm3.hash((method + END_STRING).toByteArray(Charsets.UTF_8))
        return bytesToIntArray(Sm3.hash(inner))
    }

    private fun generateParamsCode(params: String): IntArray {
        val inner = Sm3.hash((params + END_STRING).toByteArray(Charsets.UTF_8))
        return bytesToIntArray(Sm3.hash(inner))
    }

    // ================================================================
    // 浏览器指纹
    // ================================================================
    private fun generateBrowserInfo(platform: String): String {
        val innerWidth = 1280 + rnd.nextInt(1920 - 1280 + 1)
        val innerHeight = 720 + rnd.nextInt(1080 - 720 + 1)
        val outerWidth = innerWidth + rnd.nextInt(1920 - innerWidth + 1)
        val outerHeight = innerHeight + rnd.nextInt(1080 - innerHeight + 1)
        val screenX = 0
        val screenY = if (rnd.nextBoolean()) 0 else 30
        val values = intArrayOf(
            innerWidth, innerHeight, outerWidth, outerHeight,
            screenX, screenY, 0, 0,
            outerWidth, outerHeight, outerWidth, outerHeight,
            innerWidth, innerHeight, 24, 24
        )
        val sb = StringBuilder()
        for (v in values) sb.append(v).append('|')
        sb.append(platform)
        return sb.toString()
    }

    private fun charCodeAt(s: String): IntArray {
        val out = IntArray(s.length)
        for (i in s.indices) out[i] = s[i].code
        return out
    }

    // ================================================================
    // 自定义 base64 编码（对应 Python generate_result）
    // ================================================================
    private fun generateResult(data: ByteArray, table: String): String {
        val sb = StringBuilder()
        val masks = intArrayOf(0xFC0000, 0x03F000, 0x0FC0, 0x3F)
        val shifts = intArrayOf(18, 12, 6, 0)
        var i = 0
        while (i < data.size) {
            val rem = data.size - i
            var n = (data[i].toInt() and 0xFF) shl 16
            if (rem > 1) n = n or ((data[i + 1].toInt() and 0xFF) shl 8)
            if (rem > 2) n = n or (data[i + 2].toInt() and 0xFF)
            for (t in 0 until 4) {
                if (shifts[t] == 6 && rem <= 1) break
                if (shifts[t] == 0 && rem <= 2) break
                sb.append(table[(n and masks[t]) ushr shifts[t]])
            }
            i += 3
        }
        val pad = (4 - sb.length % 4) % 4
        for (t in 0 until pad) sb.append('=')
        return sb.toString()
    }

    // ================================================================
    // RC4（key 以字节给出；Python 里用 ord(key[i]) 取码）
    // ================================================================
    private fun rc4Encrypt(plain: ByteArray, key: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) % 256
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = ByteArray(plain.size)
        var i = 0
        j = 0
        for (k in plain.indices) {
            i = (i + 1) % 256
            j = (j + s[i]) % 256
            val t = s[i]; s[i] = s[j]; s[j] = t
            val t2 = (s[i] + s[j]) % 256
            out[k] = ((plain[k].toInt() and 0xFF) xor s[t2]).toByte()
        }
        return out
    }

    // ================================================================
    // 小工具
    // ================================================================
    private fun bytesToIntArray(b: ByteArray): IntArray {
        val out = IntArray(b.size)
        for (i in b.indices) out[i] = b[i].toInt() and 0xFF
        return out
    }

    /** 供诊断：SM3 实现是否通过标准测试向量 */
    fun sm3SelfTest(): String? = Sm3.selfTest()
}
