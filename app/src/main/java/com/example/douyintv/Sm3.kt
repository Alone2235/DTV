package com.example.douyintv

/**
 * SM3 国密哈希（GB/T 32905-2016）
 * ================================
 * 抖音 a_bogus 签名用的是 SM3（不是 MD5/X-Bogus 那套），所以必须自带一份实现。
 * 纯计算、无依赖，minSdk 16 可用。
 *
 * 自检：SM3("abc") = 66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0
 * （标准测试向量，见 selfTest()）
 */
object Sm3 {

    private val IV = intArrayOf(
        0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600.toInt(),
        0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt()
    )

    /** 返回 32 字节摘要 */
    fun hash(data: ByteArray): ByteArray {
        val bitLen = data.size.toLong() * 8

        // 填充：0x80 + 0x00... + 8字节长度（大端）
        var padLen = 64 - ((data.size + 9) % 64)
        if (padLen == 64) padLen = 0
        val buf = ByteArray(data.size + 1 + padLen + 8)
        System.arraycopy(data, 0, buf, 0, data.size)
        buf[data.size] = 0x80.toByte()
        for (i in 0 until 8) {
            buf[buf.size - 8 + i] = ((bitLen ushr (8 * (7 - i))) and 0xFF).toByte()
        }

        val v = IV.copyOf()
        val w = IntArray(68)
        val w1 = IntArray(64)

        var off = 0
        while (off < buf.size) {
            for (i in 0 until 16) {
                val p = off + i * 4
                w[i] = ((buf[p].toInt() and 0xFF) shl 24) or
                        ((buf[p + 1].toInt() and 0xFF) shl 16) or
                        ((buf[p + 2].toInt() and 0xFF) shl 8) or
                        (buf[p + 3].toInt() and 0xFF)
            }
            for (i in 16 until 68) {
                val x = w[i - 16] xor w[i - 9] xor rotl(w[i - 3], 15)
                w[i] = x xor rotl(x, 15) xor rotl(x, 23) xor rotl(w[i - 13], 7) xor w[i - 6]
            }
            for (i in 0 until 64) {
                w1[i] = w[i] xor w[i + 4]
            }

            var a = v[0]; var b = v[1]; var c = v[2]; var d = v[3]
            var e = v[4]; var f = v[5]; var g = v[6]; var h = v[7]

            for (j in 0 until 64) {
                val a12 = rotl(a, 12)
                val ss1 = rotl(a12 + e + rotl(T(j), j % 32), 7)
                val ss2 = ss1 xor a12
                val tt1 = ff(j, a, b, c) + d + ss2 + w1[j]
                val tt2 = gg(j, e, f, g) + h + ss1 + w[j]
                d = c
                c = rotl(b, 9)
                b = a
                a = tt1
                h = g
                g = rotl(f, 19)
                f = e
                e = p0(tt2)
            }

            v[0] = v[0] xor a; v[1] = v[1] xor b
            v[2] = v[2] xor c; v[3] = v[3] xor d
            v[4] = v[4] xor e; v[5] = v[5] xor f
            v[6] = v[6] xor g; v[7] = v[7] xor h
            off += 64
        }

        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = ((v[i] ushr 24) and 0xFF).toByte()
            out[i * 4 + 1] = ((v[i] ushr 16) and 0xFF).toByte()
            out[i * 4 + 2] = ((v[i] ushr 8) and 0xFF).toByte()
            out[i * 4 + 3] = (v[i] and 0xFF).toByte()
        }
        return out
    }

    /** 十六进制小写摘要 */
    fun hex(data: ByteArray): String {
        val sb = StringBuilder(64)
        for (b in hash(data)) {
            val x = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[x ushr 4])
            sb.append("0123456789abcdef"[x and 0x0F])
        }
        return sb.toString()
    }

    fun hex(s: String): String = hex(s.toByteArray(Charsets.UTF_8))

    /** 标准测试向量自检；返回 null 表示通过，否则返回错误说明 */
    fun selfTest(): String? {
        val got = hex("abc")
        val want = "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0"
        return if (got == want) null else "SM3 self-test FAILED: got=$got want=$want"
    }

    private fun rotl(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    private fun T(j: Int): Int = if (j < 16) 0x79cc4519 else 0x7a879d8a

    private fun ff(j: Int, x: Int, y: Int, z: Int): Int =
        if (j < 16) (x xor y xor z) else ((x and y) or (x and z) or (y and z))

    private fun gg(j: Int, x: Int, y: Int, z: Int): Int =
        if (j < 16) (x xor y xor z) else ((x and y) or (x.inv() and z))

    private fun p0(x: Int): Int = x xor rotl(x, 9) xor rotl(x, 17)

    private fun p1(x: Int): Int = x xor rotl(x, 15) xor rotl(x, 23)

    /** 供 a_bogus 用：SM3(数据) 的摘要字节（再按需转 int 列表） */
    fun digestToBytes(data: ByteArray): ByteArray = hash(data)
}
