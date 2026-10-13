package com.example.douyintv

import android.util.Base64
import java.nio.charset.Charset
import java.security.MessageDigest

/**
 * X-Bogus 签名算法 (移植自 f2 项目 johnserf-seed/f2，已用 Python 验证可用)
 */
object XBogus {

    private const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    // 十六进制字符 → 数值 查找表
    private val HEX_TABLE = IntArray(128) { -1 }.also { arr ->
        val hex = "0123456789abcdef"
        for (i in hex.indices) arr[hex[i].code] = i
    }

    private const val CHARACTER = "Dkdpgh4ZKsQB80/Mfvw36XI1R25-WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe="
    private val UA_KEY = byteArrayOf(0x00, 0x01, 0x0c)
    // API 16 兼容：不能用 Charsets.ISO_8859_1 (StandardCharsets 需 API 19+)
    private val LATIN1: Charset = Charset.forName("ISO-8859-1")

    fun generate(urlParams: String, userAgent: String = DEFAULT_UA): String {
        val ua = if (userAgent.isBlank()) DEFAULT_UA else userAgent

        // array1 = md5_str_to_array(md5(base64(rc4(ua_key, ua))))
        val rc4Ua = rc4Encrypt(UA_KEY, ua.toByteArray(LATIN1))
        val b64Ua = Base64.encodeToString(rc4Ua, Base64.NO_WRAP)
        val array1 = md5StrToArray(md5Hex(b64Ua))

        // array2 = md5_str_to_array(md5(md5_str_to_array("d41d8cd98f00b204e9800998ecf8427e")))
        val array2 = md5StrToArray(md5Hex(md5StrToArray("d41d8cd98f00b204e9800998ecf8427e")))

        // url_params_array = md5_encrypt(urlParams)
        val urlParamsArray = md5Encrypt(urlParams)

        val timer = (System.currentTimeMillis() / 1000).toInt()
        val ct = 536919696

        // 注意: Python 里第二个元素是 0.00390625，int() 后为 0
        val newArray = intArrayOf(
            64, 0, 1, 12,
            urlParamsArray[14], urlParamsArray[15],
            array2[14], array2[15],
            array1[14], array1[15],
            timer shr 24 and 255, timer shr 16 and 255, timer shr 8 and 255, timer and 255,
            ct shr 24 and 255, ct shr 16 and 255, ct shr 8 and 255, ct and 255
        )

        var xorResult = newArray[0]
        for (i in 1 until newArray.size) {
            xorResult = xorResult xor newArray[i]
        }

        // y 数组 = newArray + xorResult （等价于 Python 的 array3+array4 重组）
        val y = IntArray(newArray.size + 1)
        System.arraycopy(newArray, 0, y, 0, newArray.size)
        y[y.size - 1] = xorResult

        val encoded = String(y.map { it.toByte() }.toByteArray(), LATIN1)

        // rc4(0xFF, encoded)
        val rc4Result = rc4Encrypt(byteArrayOf(0xFF.toByte()), encoded.toByteArray(LATIN1))

        // chr(2) + chr(255) + rc4Result
        val garbled = byteArrayOf(2.toByte(), 0xFF.toByte()) + rc4Result
        val garbledStr = String(garbled, LATIN1)

        // 每3字节一组计算
        val sb = StringBuilder()
        val chars = garbledStr.toCharArray()
        var ci = 0
        while (ci + 2 < chars.size) {
            sb.append(calculation(chars[ci].code and 255, chars[ci + 1].code and 255, chars[ci + 2].code and 255))
            ci += 3
        }
        return sb.toString()
    }

    private fun md5StrToArray(md5Str: String): IntArray {
        if (md5Str.length > 32) return md5Str.map { it.code }.toIntArray()
        val out = IntArray(md5Str.length / 2)
        var idx = 0; var oi = 0
        while (idx + 1 < md5Str.length) {
            val hi = HEX_TABLE[md5Str[idx].code]
            val lo = HEX_TABLE[md5Str[idx + 1].code]
            out[oi++] = ((hi shl 4) or lo) and 0xFF
            idx += 2
        }
        return out
    }

    private fun md5Encrypt(urlParams: String): IntArray {
        val md5_1 = md5Hex(urlParams)              // hex str
        val arr = md5StrToArray(md5_1)             // bytes
        val md5_2 = md5Hex(arr)                    // hex str
        return md5StrToArray(md5_2)                // bytes
    }

    private fun md5Hex(input: String): String {
        val d = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun md5Hex(input: IntArray): String {
        val bytes = ByteArray(input.size) { input[it].toByte() }
        val d = MessageDigest.getInstance("MD5").digest(bytes)
        return d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun rc4Encrypt(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) % 256
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = ByteArray(data.size)
        var x = 0; var y = 0
        for (k in data.indices) {
            x = (x + 1) % 256
            y = (y + s[x]) % 256
            val t = s[x]; s[x] = s[y]; s[y] = t
            out[k] = ((data[k].toInt() and 0xFF) xor s[(s[x] + s[y]) % 256]).toByte()
        }
        return out
    }

    private fun calculation(a1: Int, a2: Int, a3: Int): String {
        val x3 = ((a1 and 255) shl 16) or ((a2 and 255) shl 8) or (a3 and 255)
        return "" + CHARACTER[(x3 and 16515072) shr 18] +
                CHARACTER[(x3 and 258048) shr 12] +
                CHARACTER[(x3 and 4032) shr 6] +
                CHARACTER[x3 and 63]
    }
}
