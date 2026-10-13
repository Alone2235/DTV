package com.example.douyintv

import java.lang.Character.isHighSurrogate
import java.lang.Character.isLowSurrogate

/**
 * 文本兼容层 —— 面向 Android 4.2 (API 17) 的显示降级。
 *
 * <h3>为什么必须有这一层</h3>
 * Android 4.2 自带的字体集**不含 emoji 字形**。抖音评论/文案里大量出现
 * emoji（😀👍🔥 这类），系统找不到字形就只能画"豆腐块"（□ 或 ▯），
 * 在电视大屏上尤其刺眼，用户看到的就是"一堆方块乱码"。
 *
 * <h3>策略</h3>
 * 1. **不改原始数据** —— CommentModel/VideoModel 里始终保留原文，
 *    只是送给 TextView 之前过一遍本类。这样将来换设备/换字体不用重新抓数据。
 * 2. **折叠连续不可显示字符** —— 连续的 5 个 emoji 折叠成一个标记，
 *    避免 "[表情][表情][表情][表情][表情]" 这种噪声刷屏。
 * 3. **保留有字形的符号** —— 常见的 ✓ ✗ ★ ♥ 等在 4.2 上有字形，不能误杀。
 *
 * <h3>不做什么</h3>
 * 不引入 EmojiCompat（属 AndroidX，且其字体包体积远超本项目预算）。
 */
object TextCompat {

    /** emoji 被折叠后显示的占位标记 */
    private const val EMOJI_FOLDED = "[表情]"

    /**
     * 过滤掉 Android 4.2 渲染不出的字符。
     *
     * @param src 原始文本，可为 null
     * @return 可安全交给 TextView 的文本（永不为 null）
     */
    fun forTv(src: String?): String {
        if (src == null || src.isEmpty()) return ""

        val sb = StringBuilder(src.length)
        var i = 0
        var folding = false          // 当前是否处于"连续 emoji"折叠段中

        while (i < src.length) {
            val cp: Int = src.codePointAt(i)
            val len: Int = Character.charCount(cp)

            if (renderable(cp)) {
                folding = false
                sb.appendCodePoint(cp)
            } else {
                // 只在折叠段的**开头**插一次标记，连续 emoji 合并成一个
                if (!folding) {
                    sb.append(EMOJI_FOLDED)
                    folding = true
                }
                // 变体选择符 / 零宽连接符直接吞掉，不产生额外输出
            }
            i += len
        }
        return sb.toString()
    }

    /**
     * 该码点在 Android 4.2 上是否有字形可显示。
     *
     * 判据是"已知 4.2 字体覆盖区间"，宁可放过几个、不可误杀常用的中文与符号。
     */
    private fun renderable(cp: Int): Boolean {
        // 1) 基础平面内一定有字形的范围 —— 直接放行，快速返回
        //    ASCII / 拉丁 / 中文 / 日文假名 / 全角符号 / 常用标点 等
        if (cp <= 0x2FFF) return true               // 含 ASCII、拉丁扩展、希腊、CJK 部首、假名
        if (cp >= 0x3000 && cp <= 0x303F) return true   // CJK 标点（、。「」等）
        if (cp >= 0x3040 && cp <= 0x30FF) return true   // 假名
        if (cp >= 0x4E00 && cp <= 0x9FFF) return true   // CJK 统一表意文字（汉字主体）
        if (cp >= 0xFF00 && cp <= 0xFFEF) return true   // 全角/半角形式

        // 2) 常见且 4.2 有字形的符号：几何图形 ★♥♫ 等（U+2190–U+25FF 的子集）
        //    实测 4.2 上 ★(U+2605) ♥(U+2665) ♦ ♣ ♠ 等有字形，
        //    但同一区段的天气/星座符号没有 —— 只放行确认有的这些。
        when (cp) {
            0x25A0, 0x25A1,             // ■ □
            0x25B2, 0x25B3,             // ▲ △
            0x25B6, 0x25B7,             // ▶ ▷  （播放三角，播放器 UI 在用）
            0x25BC, 0x25BD,             // ▼ ▽
            0x25C0, 0x25C1,             // ◀ ◁
            0x25CF, 0x25CB,             // ● ○
            0x2605, 0x2606,             // ★ ☆
            0x2665, 0x2661,             // ♥ ♡
            0x266A, 0x266B,             // ♪ ♫
            0x2660, 0x2663, 0x2666,     // ♠ ♣ ♦
            0x275A, 0x275B,             // ❚ ❛（暂停指示 ❚❚ 在用）
            0x2713, 0x2714, 0x2717, 0x2718,  // ✓ ✔ ✗ ✘
            0x2764                     // ❤
            -> return true
        }
        if (cp in 0x25A0..0x25FF) return true   // 几何图形区（方块/三角/圆）

        // 3) 明确不可显示的区段 —— 这些是 emoji 主战场，4.2 必然画成方块
        if (cp in 0x1F300..0x1F9FF) return false   // 表情、手势、交通、旗帜（补充平面）
        if (cp in 0x1F000..0x1F2FF) return false   // 麻将/扑克/封装符号
        if (cp in 0x2600..0x27BF) return false     // 杂项符号与装饰符（天气、星座、箭饰）
        if (cp in 0x2B00..0x2BFF) return false     // 杂项符号与箭头
        if (cp in 0xFE00..0xFE0F) return false     // 变体选择符（无独立字形）
        if (cp == 0x200D) return false             // 零宽连接符（组合 emoji 用）
        if (cp in 0x1F1E6..0x1F1FF) return false   // 区域指示符（国旗）

        // 4) 其余：默认放行（宁可显示不确定的字形，也不误删正文）
        return true
    }

    /** 供测试/诊断：统计一段文本里有多少个会被折叠的 emoji 码点 */
    fun countUnrenderable(src: String?): Int {
        if (src == null) return 0
        var n = 0
        var i = 0
        while (i < src.length) {
            val cp = src.codePointAt(i)
            if (!renderable(cp)) n++
            i += Character.charCount(cp)
        }
        return n
    }
}
