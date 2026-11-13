package com.example.dtvremote.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * DtvRemote 设计令牌（第一版）
 * ==========================
 * 设计思路：
 *   · **身份统一**：与电视端（TVDouyinNative 的 colors.xml）同一套配色 —— 近黑底 + 抖音红，
 *     手机遥控和电视界面是同一产品，不能像两家公司。
 *   · **工程严谨**：借鉴本人其它项目（SaveUp / AI-Gateway）的设计体系 ——
 *     4pt 间距网格、三档圆角、七档字阶、四级文字透明度。
 *   · **场景适配**：遥控器是单手竖屏、随时按两下的工具，
 *     所以触控目标 ≥48dp，主操作要拇指够得着（底部区域）。
 *
 * 使用规则（写死在这里，别在页面里写魔法数字）：
 *   ✅ Space.md / Radius.card / Fonts.body / DtvColors.textSecondary
 *   ❌ 16.dp / 14.sp / Color(0xFF9A9AA8)
 */

// ==================== 间距（4pt 网格） ====================
object Space {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp

    /** 页面左右边距 */
    val page = 20.dp

    /** 卡片内边距 */
    val card = 16.dp

    /** 卡片之间 */
    val gap = 12.dp

    /** 分组之间 */
    val section = 28.dp
}

// ==================== 圆角（三档 + 胶囊） ====================
object Radius {
    /** 小控件：标签、小按钮、数字键 */
    val small = 10.dp

    /** 卡片、列表项、输入框 */
    val medium = 16.dp

    /** 大面板、底部弹层 */
    val large = 24.dp

    /** 胶囊（状态条、Chip） */
    val pill = 999.dp
}

// ==================== 字阶（七档） ====================
object Fonts {
    /** 页面大标题（如「我的电视」） */
    val display = 28.sp

    /** 区块标题 */
    val title1 = 22.sp

    /** 卡片标题 / 主要按钮 */
    val title2 = 18.sp

    /** 强调正文 */
    val bodyStrong = 16.sp

    /** 正文 */
    val body = 15.sp

    /** 辅助说明 */
    val caption = 13.sp

    /** 角标 / 极小字 */
    val micro = 11.sp

    /** 遥控器上的大数字（方向键、OK） */
    val keyGlyph = 22.sp
}

// ==================== 文字透明度（四级） ====================
object TextAlpha {
    const val primary = 1f
    const val secondary = 0.72f
    const val tertiary = 0.48f
    const val disabled = 0.30f
}

// ==================== 触控尺寸 ====================
object Touch {
    /** 最小可点区域（无障碍下限） */
    val min = 48.dp

    /** 普通按钮高度 */
    val btn = 52.dp

    /** 主按钮高度（拇指好按） */
    val btnPrimary = 56.dp

    /** 圆形遥控按键直径 */
    val key = 64.dp

    /** 大圆（OK 键） */
    val keyPrimary = 76.dp
}

// ==================== 动效时长 ====================
object Motion {
    /** 按压反馈 */
    const val press = 90

    /** 状态切换（连接/断开） */
    const val state = 180

    /** 页面切换 */
    const val page = 260
}

// ==================== 配色（与电视端 colors.xml 同源） ====================
object DtvColors {
    // 背景层次
    val bgRoot = Color(0xFF0B0B0F)
    val bgNav = Color(0xFF101015)
    val surface = Color(0xFF16161C)
    val surfaceHi = Color(0xFF1F1F28)
    val chip = Color(0xFF23232C)

    // 文字（带透明度，可叠任意底）
    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xB8FFFFFF)
    val textTertiary = Color(0x7AFFFFFF)

    // 品牌
    val brand = Color(0xFFFE2C55)
    val brandDim = Color(0x66FE2C55)
    val brandTint = Color(0x14FE2C55)
    val onBrand = Color(0xFFFFFFFF)

    // 焦点 / 描边
    val stroke = Color(0x33FFFFFF)
    val divider = Color(0x1AFFFFFF)

    // 状态色（与电视端一致）
    val ok = Color(0xFF81C784)
    val warn = Color(0xFFFFD54F)
    val error = Color(0xFFF06292)
}
