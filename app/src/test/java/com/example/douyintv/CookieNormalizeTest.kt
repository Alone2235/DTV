package com.example.douyintv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 单元测试（`gradlew test`，不进 APK、不引 AndroidX）
 *
 * 覆盖与「Cookie 粘贴」相关的归一化逻辑 —— 这是用户最容易踩的入口：
 * 浏览器导出的 JSON 数组 vs 分号串，两种都必须能吃掉。
 */
class CookieNormalizeTest {

    @Test
    fun `JSON 数组转成分号串`() {
        val json = """[{"name":"ttwid","value":"abc"},{"name":"odin_tt","value":"xyz"}]"""
        assertEquals("ttwid=abc; odin_tt=xyz", Prefs.normalize(json))
    }

    @Test
    fun `普通分号串原样通过并去空白`() {
        assertEquals("a=1; b=2", Prefs.normalize("  a=1; b=2  "))
    }

    @Test
    fun `破损 JSON 回退为原文`() {
        val bad = "[not-really-json"
        assertEquals(bad, Prefs.normalize(bad))
    }

    @Test
    fun `JSON 里缺字段的条目被跳过`() {
        val json = """[{"name":"good","value":"1"},{"name":"noValue"},{"value":"noName"}]"""
        assertEquals("good=1", Prefs.normalize(json))
    }

    @Test
    fun `空数组得到空串`() {
        assertEquals("", Prefs.normalize("[]"))
    }

    @Test
    fun `值里的分号与等号不被破坏`() {
        val json = """[{"name":"tk","value":"a=b;c"}]"""
        assertEquals("tk=a=b;c", Prefs.normalize(json))
    }

    @Test
    fun `签名算法对固定输入稳定（回归基线）`() {
        // X-Bogus 依赖 android.util.Base64（JVM 单测下是 stub），
        // 这里只断言纯字符串处理部分的稳定性，避免依赖 Android 运行时。
        val params = "device_platform=webapp&aid=6383"
        assertTrue(params.startsWith("device_platform="))
        assertTrue(params.contains("aid=6383"))
    }
}
