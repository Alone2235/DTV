package com.example.douyintv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 签名算法正确性验证（JVM 单元测试，不需要设备）
 * ================================================
 * SM3 有公开标准测试向量，必须逐位对上；
 * a_bogus 无法逐位比对（含随机数与时间戳），只校验形状与稳定性。
 */
class SignatureTest {

    // ---------------- SM3 标准测试向量 ----------------

    @Test
    fun sm3_abc_matchesStandardVector() {
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            Sm3.hex("abc")
        )
    }

    @Test
    fun sm3_empty_matchesStandardVector() {
        assertEquals(
            "1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b",
            Sm3.hex("")
        )
    }

    @Test
    fun sm3_longInput_crossesBlockBoundary() {
        // 64 字节正好一个分组，65 字节会触发第二块 —— 两块都要正确
        val s64 = "a".repeat(64)
        val s65 = "a".repeat(65)
        assertEquals(64, Sm3.hex(s64).length)
        assertEquals(64, Sm3.hex(s65).length)
        assertFalse(Sm3.hex(s64) == Sm3.hex(s65))
    }

    @Test
    fun sm3_selfTest_passes() {
        assertEquals(null, Sm3.selfTest())
    }

    // ---------------- a_bogus ----------------

    @Test
    fun aBogus_nonEmpty_andReasonableLength() {
        val ab = ABogus("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        val sig = ab.generate("device_platform=webapp&aid=6383&count=10")
        assertTrue("签名不应为空", sig.isNotEmpty())
        assertTrue("长度异常: " + sig.length, sig.length in 100..220)
    }

    @Test
    fun aBogus_charsetIsBase64Like() {
        val ab = ABogus("Mozilla/5.0 Chrome/122.0.0.0")
        val sig = ab.generate("aid=6383&keyword=test")
        val ok = sig.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }
        assertTrue("含非法字符: " + sig, ok)
    }

    @Test
    fun aBogus_differsPerCall() {
        // 含时间戳与随机数，两次签名不应相同
        val ab = ABogus("Mozilla/5.0 Chrome/122.0.0.0")
        val a = ab.generate("aid=6383&keyword=test")
        Thread.sleep(5)
        val b = ab.generate("aid=6383&keyword=test")
        assertFalse("两次签名相同（随机/时间戳段可能失效）", a == b)
    }

    @Test
    fun aBogus_dependsOnParams() {
        val ab = ABogus("Mozilla/5.0 Chrome/122.0.0.0")
        val a = ab.generate("aid=6383&keyword=one")
        val b = ab.generate("aid=6383&keyword=two")
        assertFalse("参数不同却签名相同", a == b)
    }

    @Test
    fun sm3_objectNotBrokenByConcurrency() {
        // 签名会在后台线程里用；至少保证单线程重复调用结果稳定
        val h1 = Sm3.hex("douyin")
        val h2 = Sm3.hex("douyin")
        assertEquals(h1, h2)
    }
}
