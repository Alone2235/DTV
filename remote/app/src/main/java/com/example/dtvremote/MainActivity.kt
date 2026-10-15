package com.example.dtvremote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DTV 遥控器 v3.00 —— Jetpack Compose 重写
 *
 * 为什么重写：传统 View 手写 pager/手绘高光/近似弹簧，越做越怪；Compose 直接用成熟方案：
 *   · HorizontalPager  —— 原生连续翻页（跟手 / fling / 吸附 / 边界）
 *   · spring()          —— 真弹簧物理（点按回弹 / 指示条）
 *   · Material3         —— 组件与主题
 * 网络逻辑（发现 / 配对 / WebSocket / APK 升级）沿用原实现，UI 层整体替换。
 */
class MainActivity : ComponentActivity() {

    // ---------------- 可观察状态（Compose 直接订阅） ----------------
    private var ip by mutableStateOf("")
    private var uuid by mutableStateOf("")
    private var paired by mutableStateOf(false)
    private var tvVersion by mutableStateOf("?")
    private var connState by mutableStateOf(0)      // 0 未连接 / 1 连接中 / 2 已连接
    private var title by mutableStateOf("")
    private var positionMs by mutableStateOf(0L)
    private var durationMs by mutableStateOf(0L)
    private var playing by mutableStateOf(false)
    private var digged by mutableStateOf(false)
    private var collected by mutableStateOf(false)
    private var danmakuOn by mutableStateOf(false)
    private var vibOn by mutableStateOf(true)
    private var locked by mutableStateOf(false)
    private var logText by mutableStateOf("")
    private var codeInput by mutableStateOf("")
    private var ipInput by mutableStateOf("")
    private var kwInput by mutableStateOf("")
    private var pushInput by mutableStateOf("")
    private val devices = mutableStateListOf<Dev>()
    private var showCrash by mutableStateOf<String?>(null)

    private class Dev(val uuid: String, val name: String, val ip: String, val version: String)

    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var lastCmdAt = 0L
    private val logBuf = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vibOn = savedBool("vib", true)
        locked = savedBool("locked", false)
        ipInput = savedStr("last_ip", "")
        ip = ipInput
        uuid = savedStr("last_uuid", "")
        showCrash = RemoteApp.lastCrash(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFFE2C55), background = Color(0xFF0A0B0F), surface = Color(0xFF14141A)
            )) {
                DtvApp()
            }
        }
        handler.postDelayed(pollTick, 1500)
        if (ip.isNotBlank()) connect()
    }

    // ================= Compose UI =================

    @Composable
    private fun DtvApp() {
        val pages = listOf("遥控", "设备", "工具", "设置", "方向")
        val pagerState = rememberPagerState(pageCount = { pages.size })
        val scope = rememberCoroutineScope()

        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF160D14), Color(0xFF0A0B0F), Color(0xFF140A12)))
        )) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                TopBar()
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    pageSpacing = 8.dp
                ) { page ->
                    when (page) {
                        0 -> RemotePage(scope)
                        1 -> DevicePage(scope)
                        2 -> ToolsPage()
                        3 -> SettingsPage()
                        else -> KeyPage()
                    }
                }
                BottomBar(pages, pagerState, scope)
            }
        }
        showCrash?.let { c ->
            AlertDialog(
                onDismissRequest = { showCrash = null },
                title = { Text("上次崩溃（截图发我）") },
                text = { Text(c.take(1500), fontSize = 11.sp) },
                confirmButton = {
                    TextButton(onClick = { RemoteApp.clearCrash(this); showCrash = null }) { Text("清除") }
                },
                dismissButton = { TextButton(onClick = { showCrash = null }) { Text("关闭") } }
            )
        }
    }

    /** 顶部：设备名 + 状态点 + ⋮ */
    @Composable
    private fun TopBar() {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val statusText = when {
                ip.isBlank() -> "未连接 → 去「设备」页配对"
                locked -> "遥控已锁定 → 去「设置」页解锁"
                else -> "$ip  v$tvVersion"
            }
            Text(
                statusText,
                color = if (ip.isBlank() || locked) Color(0xFFF06292) else Color(0xFF9A9AA8),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            val dotColor = when (connState) {
                2 -> Color(0xFF81C784); 1 -> Color(0xFFFFE066); else -> Color(0xFFF06292)
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(10.dp))
            Image(
                painter = painterResource(R.drawable.ic_more), contentDescription = "更多",
                colorFilter = ColorFilter.tint(Color(0xFF9A9AA8)),
                modifier = Modifier.size(20.dp)
            )
        }
    }

    /** 底部玻璃 Tab 栏：指示条由 pager 进度连续驱动 + 按压缩放（真 spring） */
    @Composable
    private fun BottomBar(pages: List<String>, pagerState: androidx.compose.foundation.pager.PagerState, scope: kotlinx.coroutines.CoroutineScope) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(Color(0x1FFFFFFF), Color(0x0AFFFFFF))))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
        ) {
            val tabW = maxWidth / pages.size
            val progress = pagerState.currentPage + pagerState.currentPageOffsetFraction
            Box(Modifier.fillMaxWidth().height(56.dp)) {
                // 指示条
                Box(
                    Modifier
                        .offset(x = tabW * progress + tabW * 0.18f)
                        .align(Alignment.BottomStart)
                        .padding(bottom = 6.dp)
                        .width(tabW * 0.64f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFFFE2C55))
                )
                Row(Modifier.fillMaxSize()) {
                    pages.forEachIndexed { i, name ->
                        val selected = pagerState.currentPage == i
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val scale by animateFloatAsState(
                            targetValue = if (pressed) 0.90f else 1f,
                            animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
                            label = "tabScale"
                        )
                        Box(
                            Modifier.weight(1f).fillMaxHeight()
                                .clickable(interactionSource = interaction, indication = null) {
                                    haptic(); scope.launch { pagerState.animateScrollToPage(i) }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                name, fontSize = 14.sp, modifier = Modifier.scale(scale),
                                color = if (selected) Color(0xFFFE2C55) else Color(0xFF8A8A99),
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }

    /** 遥控页：状态 + 全屏触控板 + 三个圆钮 */
    @Composable
    private fun RemotePage(scope: kotlinx.coroutines.CoroutineScope) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Text(
                buildString {
                    append(if (playing) "▶ " else "❚❚ ")
                    append(title.ifBlank { "电视未在播放" }.take(30))
                    if (durationMs > 0) append("   ${positionMs / 1000}/${durationMs / 1000}s")
                },
                color = Color.White, fontSize = 13.sp, maxLines = 1
            )
            Spacer(Modifier.height(10.dp))
            TouchPad(Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CircleBtn(R.drawable.ic_prev, "上一条", false) { send("prev") }
                CircleBtn(R.drawable.ic_play, "播放", true, 68.dp) { send("toggle") }
                CircleBtn(R.drawable.ic_next, "下一条", false) { send("next") }
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    /** 触控板：单击暂停 / 双击点赞 / 上下滑切视频；横向留给 HorizontalPager 翻页 */
    @Composable
    private fun TouchPad(modifier: Modifier) {
        var hint by remember { mutableStateOf(true) }
        Box(
            modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF0E0E13))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(20.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { hint = false; haptic(); gestureAct("tap") },
                        onDoubleTap = { hint = false; haptic(); gestureAct("dbl") }
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { hint = false },
                        onDragEnd = { },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            if (Math.abs(dragAmount) > 12f) {
                                haptic()
                                send(if (dragAmount < 0) "next" else "prev")
                            }
                        }
                    )
                }
        ) {
            if (hint) {
                Text(
                    "单击暂停 · 双击点赞 · 上下滑切视频 · 左右滑翻页",
                    color = Color(0x33FFFFFF), fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            // 四角标记（很淡）
            val corner = Color(0x33FFFFFF)
            listOf(
                Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd
            ).forEach { a ->
                Box(Modifier.align(a).padding(10.dp).size(14.dp)
                    .border(2.dp, corner, RoundedCornerShape(2.dp)))
            }
        }
    }

    /** 圆形按钮：矢量图标（不再用 emoji）+ 按下 spring 回弹 */
    @Composable
    private fun CircleBtn(icon: Int, title: String, primary: Boolean, size: androidx.compose.ui.unit.Dp = 62.dp, act: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = if (pressed) 0.88f else 1f,
                animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
                label = "btnScale"
            )
            Box(
                Modifier.size(size).scale(scale).clip(CircleShape)
                    .background(
                        if (primary) Brush.verticalGradient(listOf(Color(0xFFFE2C55), Color(0xFFC41E45)))
                        else Brush.verticalGradient(listOf(Color(0x1FFFFFFF), Color(0x0DFFFFFF)))
                    )
                    .border(1.dp, if (primary) Color(0x66FFFFFF) else Color(0x33FFFFFF), CircleShape)
                    .clickable(interactionSource = interaction, indication = null) { haptic(); act() },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(icon), contentDescription = title,
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(size * 0.42f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(title, color = Color(0xFF8A8A99), fontSize = 11.sp)
        }
    }

    /** 独立「方向」页：方向键 / OK / 返回 / 菜单（不再占用遥控页空间） */
    @Composable
    private fun KeyPage() {
        Column(
            Modifier.fillMaxSize().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("方向键 / 返回 / 菜单 —— 用于操作电视界面",
                color = Color(0xFF9A9AA8), fontSize = 13.sp)
            Spacer(Modifier.height(24.dp))
            DpadCluster()
        }
    }
    /** 方向键九宫格 + 返回/菜单（TV 端自 2.18 起已支持这些指令） */
    @Composable
    private fun DpadCluster() {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                KeyBtn(R.drawable.ic_back, "返回") { send("back") }
                Spacer(Modifier.width(8.dp))
                KeyBtn(R.drawable.ic_dpad_up, "上") { send("dpad_up") }
                Spacer(Modifier.width(8.dp))
                KeyBtn(R.drawable.ic_menu, "菜单") { send("menu") }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                KeyBtn(R.drawable.ic_dpad_left, "左") { send("dpad_left") }
                Spacer(Modifier.width(8.dp))
                KeyBtn(R.drawable.ic_ok, "OK", primary = true) { send("ok") }
                Spacer(Modifier.width(8.dp))
                KeyBtn(R.drawable.ic_dpad_right, "右") { send("dpad_right") }
            }
            Spacer(Modifier.height(10.dp))
            KeyBtn(R.drawable.ic_dpad_down, "下") { send("dpad_down") }
        }
    }

    /** 方形按键：矢量图标 + 按下弹簧回弹 */
    @Composable
    private fun KeyBtn(icon: Int, desc: String, primary: Boolean = false, act: () -> Unit) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.90f else 1f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
            label = "key"
        )
        Box(
            Modifier.size(54.dp).scale(scale).clip(RoundedCornerShape(14.dp))
                .background(if (primary) Color(0xFFFE2C55) else Color(0x1FFFFFFF))
                .border(1.dp, if (primary) Color(0x66FFFFFF) else Color(0x33FFFFFF), RoundedCornerShape(14.dp))
                .clickable(interactionSource = interaction, indication = null) { haptic(); act() },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(icon), contentDescription = desc,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.size(24.dp)
            )
        }
    }

    /** 设备页：扫描 / 手动 / 配对码 */
    @Composable
    private fun DevicePage(scope: kotlinx.coroutines.CoroutineScope) {
        Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState())) {
            Text("设备", color = Color(0xFF9A9AA8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = ipInput, onValueChange = { ipInput = it },
                label = { Text("电视 IP") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("扫描设备", Modifier.weight(1f)) { discover() }
                Btn("手动添加", Modifier.weight(1f)) { manualAdd() }
                Btn("重连", Modifier.weight(1f)) { connect() }
            }
            Spacer(Modifier.height(12.dp))
            if (devices.isEmpty()) {
                Text("未发现设备（需与电视同一 WiFi）", color = Color(0xFF5A5A6A), fontSize = 12.sp)
            } else {
                devices.forEach { d ->
                    Spacer(Modifier.height(6.dp))
                    Btn("${d.name}  v${d.version}   ${d.ip}", Modifier.fillMaxWidth()) {
                        ip = d.ip; uuid = d.uuid; ipInput = d.ip
                        saveStr("last_ip", d.ip); saveStr("last_uuid", d.uuid)
                        connect()
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("配对（首次需要，电视上有 6 位码）", color = Color(0xFF9A9AA8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = codeInput, onValueChange = { codeInput = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("6 位配对码") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Btn(if (paired) "已配对 ✓" else "配对", Modifier.fillMaxWidth()) { pair() }
        }
    }

    /** 工具页：搜索 / 推视频 / APK */
    @Composable
    private fun ToolsPage() {
        Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState())) {
            Text("搜索视频", color = Color(0xFF9A9AA8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = kwInput, onValueChange = { kwInput = it },
                label = { Text("关键词") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Btn("在电视上搜索", Modifier.fillMaxWidth()) { send("search", kwInput) }
            Spacer(Modifier.height(24.dp))
            Text("推送到电视", color = Color(0xFF9A9AA8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = pushInput, onValueChange = { pushInput = it },
                label = { Text("抖音分享链接 / aweme_id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Btn("推送到电视", Modifier.fillMaxWidth()) { send("push", pushInput) }
            Spacer(Modifier.height(24.dp))
            Text("APK 升级", color = Color(0xFF9A9AA8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("检查更新", Modifier.weight(1f)) { apkInfo() }
                Btn("上传APK", Modifier.weight(1f)) { pickApk() }
                Btn("安装", Modifier.weight(1f)) { apkInstall() }
            }
            Spacer(Modifier.height(24.dp))
            Text("日志", color = Color(0xFF9A9AA8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(logText, color = Color(0xFF64B5F6), fontSize = 10.sp)
        }
    }

    /** 设置页：开关 + 手势映射 */
    @Composable
    private fun SettingsPage() {
        Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState())) {
            Text("设置", color = Color(0xFF9A9AA8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            SwitchRow("触控反馈震动", vibOn) { vibOn = it; saveBool("vib", it) }
            SwitchRow("遥控锁定（防误触）", locked) { locked = it; saveBool("locked", it) }
            Spacer(Modifier.height(16.dp))
            Text("手势映射（点一下循环切换）", color = Color(0xFF9A9AA8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            for (g in listOf("tap", "dbl", "vert")) {
                var cur by remember { mutableStateOf(prefStr("g_" + g, DEFAULT_GESTURES[g] ?: "pause")) }
                Row(
                    Modifier.fillMaxWidth().clickable {
                        val i = GESTURE_OPTIONS.indexOf(cur)
                        cur = GESTURE_OPTIONS[(i + 1) % GESTURE_OPTIONS.size]
                        saveStr("g_" + g, cur)
                        haptic()
                    }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(GESTURE_NAMES[g] ?: g, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(ACTION_LABELS[cur] ?: cur, color = Color(0xFFFE2C55), fontSize = 13.sp)
                }
            }
        }
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun Btn(label: String, modifier: Modifier = Modifier, act: () -> Unit) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.94f else 1f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
            label = "btn"
        )
        Box(
            modifier.scale(scale).height(52.dp).clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF23232E))
                .clickable(interactionSource = interaction, indication = null) { haptic(); act() },
            contentAlignment = Alignment.Center
        ) { Text(label, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center) }
    }

    // ================= 网络逻辑（沿用原实现） =================

    private fun haptic() {
        if (!vibOn) return
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
            if (v.hasVibrator()) v.vibrate(16)
        } catch (_: Throwable) {}
    }

    private fun gestureAct(g: String, arg: String? = null) {
        val a = prefStr("g_" + g, DEFAULT_GESTURES[g] ?: "pause")
        when (a) {
            "pause" -> send("toggle")
            "digg" -> send("digg")
            "collect" -> send("collect")
            "next" -> send("next")
            "prev" -> send("prev")
            "seekf" -> send("seek", arg ?: "10000")
            "seekb" -> send("seek", arg ?: "-10000")
            "speed2" -> send("speed", if (arg == "1") "1.0" else "2.0")
            "mute" -> send("mute")
            "volup" -> send("volumeup")
            "volumedown" -> send("volumedown")
        }
    }

    private fun discover() {
        devices.clear()
        toast("正在扫描…")
        Thread {
            val seen = HashSet<String>()
            try {
                val sock = java.net.DatagramSocket()
                sock.setBroadcast(true)
                sock.soTimeout = 1600
                val msg = "DTV_DISCOVER".toByteArray()
                val targets = ArrayList<java.net.InetAddress>()
                try { targets.add(java.net.InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
                try {
                    val en = java.net.NetworkInterface.getNetworkInterfaces()
                    while (en.hasMoreElements()) {
                        val nif = en.nextElement()
                        if (!nif.isUp) continue
                        val addrs = nif.inetAddresses
                        while (addrs.hasMoreElements()) {
                            val a = addrs.nextElement()
                            if (a is java.net.Inet4Address && a.isSiteLocalAddress && !a.isLoopbackAddress) {
                                val p = a.hostAddress.split(".")
                                if (p.size == 4) {
                                    try { targets.add(java.net.InetAddress.getByName("${p[0]}.${p[1]}.${p[2]}.255")) } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
                for (round in 0 until 3) {
                    for (t in targets) { try { sock.send(java.net.DatagramPacket(msg, msg.size, t, 8089)) } catch (_: Exception) {} }
                    try {
                        val buf = ByteArray(1024)
                        while (true) {
                            val pkt = java.net.DatagramPacket(buf, buf.size)
                            sock.receive(pkt)
                            val text = String(pkt.data, 0, pkt.length).trim()
                            if (!text.contains("DTV")) continue
                            val o = JSONObject(text)
                            val devIp = pkt.address.hostAddress ?: ""
                            if (seen.add(devIp)) {
                                handler.post {
                                    devices.add(Dev(o.optString("uuid", ""), o.optString("name", "DTV电视"), devIp, o.optString("version", "?")))
                                }
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                    }
                }
                sock.close()
            } catch (e: Exception) { log("扫描失败: ${e.message}") }
            handler.post { log("扫描完成，发现 ${seen.size} 台") }
        }.start()
    }

    private fun manualAdd() {
        if (ipInput.isBlank()) { toast("先填电视 IP"); return }
        ip = ipInput; uuid = ""; paired = false
        saveStr("last_ip", ip)
        connect()
    }

    private fun connect() {
        if (ip.isBlank()) { toast("没有设备地址"); return }
        connState = 1
        Thread {
            try {
                val r = http.newCall(Request.Builder().url("http://$ip:8088/status?uuid=${myUuid()}").build()).execute()
                val o = JSONObject(r.body()?.string() ?: "")
                r.close()
                val tu = o.optString("uuid", "")
                if (tu.isNotBlank()) uuid = tu
                tvVersion = o.optString("version", "?")
                paired = o.optBoolean("isAllowed", false)
                saveStr("last_uuid", uuid)
                handler.post {
                    connState = 2
                    log(if (paired) "✓ 已连接 $ip v$tvVersion" else "已连接 $ip，未配对 → 输配对码")
                    openWs()
                }
            } catch (e: Exception) {
                handler.post { connState = 0; log("连接失败: ${e.message}") }
            }
        }.start()
    }

    private fun pair() {
        if (codeInput.length != 6) { toast("输入 6 位配对码"); return }
        Thread {
            try {
                val r = http.newCall(Request.Builder().url(
                    "http://$ip:8088/pair?code=$codeInput&client_uuid=${myUuid()}&name=${Uri.encode("DTV手机")}").build()).execute()
                val o = JSONObject(r.body()?.string() ?: "")
                r.close()
                if (o.optBoolean("ok")) {
                    uuid = o.optString("uuid", uuid); paired = true
                    saveStr("last_uuid", uuid)
                    handler.post { log("✓ 配对成功"); openWs() }
                } else handler.post { toast("配对失败（电视码 ${o.optString("code")}）") }
            } catch (e: Exception) { handler.post { toast("配对失败: ${e.message}") } }
        }.start()
    }

    private fun myUuid(): String {
        var u = savedStr("client_uuid", "")
        if (u.isBlank()) {
            val chars = "abcdef0123456789"; val sb = StringBuilder(); val r = java.util.Random()
            for (i in 0 until 32) sb.append(chars[r.nextInt(chars.length)])
            u = sb.toString(); saveStr("client_uuid", u)
        }
        return u
    }

    private fun openWs() {
        try { ws?.close(1000, "switch") } catch (_: Exception) {}
        try {
            ws = http.newWebSocket(Request.Builder().url("ws://$ip:8090/?uuid=${myUuid()}").build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { handler.post { log("✓ WS 通道已建立") } }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { handler.post { log("WS 不可用(HTTP 兜底): ${t.message}") } }
            })
        } catch (e: Exception) { log("WS 失败: ${e.message}") }
    }

    private fun send(cmd: String, arg: String = "") {
        if (ip.isBlank()) { toast("先连接设备"); return }
        if (locked) { toast("遥控已锁定，去设置解锁"); return }
        val now = System.currentTimeMillis()
        if (now - lastCmdAt < 120) return
        lastCmdAt = now
        val payload = JSONObject().put("cmd", cmd).apply { if (arg.isNotBlank()) put("arg", arg) }.toString()
        val w = ws
        if (w != null) { try { if (w.send(payload)) return } catch (_: Exception) {} }
        Thread {
            try {
                val url = "http://$ip:8088/cmd?uuid=${myUuid()}&action=$cmd" +
                    (if (arg.isNotBlank()) "&arg=${Uri.encode(arg)}" else "")
                http.newCall(Request.Builder().url(url).build()).execute().close()
            } catch (e: Exception) { handler.post { log("命令失败: ${e.message}") } }
        }.start()
    }

    private val pollTick = object : Runnable {
        override fun run() {
            if (ip.isNotBlank()) {
                Thread {
                    try {
                        val r = http.newCall(Request.Builder().url("http://$ip:8088/status?uuid=${myUuid()}").build()).execute()
                        val o = JSONObject(r.body()?.string() ?: "")
                        r.close()
                        val p = o.optJSONObject("player")
                        handler.post {
                            connState = 2
                            title = p?.optString("title", "") ?: ""
                            positionMs = p?.optLong("pos", 0) ?: 0
                            durationMs = p?.optLong("dur", 0) ?: 0
                            playing = p?.optBoolean("playing") ?: false
                            digged = p?.optBoolean("digged") ?: false
                            collected = p?.optBoolean("collected") ?: false
                            danmakuOn = p?.optBoolean("danmaku") ?: false
                        }
                    } catch (_: Exception) {}
                }.start()
            }
            handler.postDelayed(this, 1500)
        }
    }

    private fun apkInfo() {
        Thread {
            try {
                val r = http.newCall(Request.Builder().url("http://$ip:8088/apk/info?uuid=${myUuid()}").build()).execute()
                val o = JSONObject(r.body()?.string() ?: "")
                r.close()
                handler.post { log("TV v${o.optString("version")}，包 ${o.optLong("size") / 1024 / 1024}MB，空间 ${o.optLong("space") / 1024 / 1024}MB") }
            } catch (e: Exception) { handler.post { log("版本查询失败: ${e.message}") } }
        }.start()
    }

    private fun pickApk() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/vnd.android.package-archive"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 1001)
        } catch (e: Exception) { toast("打开文件失败: ${e.message}") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK && data?.data != null) uploadApk(data.data!!)
    }

    private fun uploadApk(uri: Uri) {
        Thread {
            try {
                val fis = contentResolver.openInputStream(uri) ?: return@Thread
                val total = fis.available().toLong()
                val buf = ByteArray(256 * 1024)
                var offset = 0L
                while (true) {
                    val n = fis.read(buf)
                    if (n < 0) break
                    offset = uploadChunk(buf, n, offset)
                    handler.post { log("上传 ${offset * 100 / maxOf(1L, total)}%") }
                }
                fis.close()
                handler.post { log("✓ 上传完成，点「安装」") }
            } catch (e: Exception) { handler.post { log("上传失败: ${e.message}") } }
        }.start()
    }

    private fun uploadChunk(data: ByteArray, len: Int, offset: Long): Long {
        return try {
            val body = RequestBody.create(null, data.copyOf(len))
            http.newCall(Request.Builder().url("http://$ip:8088/apk/upload?uuid=${myUuid()}&offset=$offset").post(body).build())
                .execute().close()
            offset + len
        } catch (_: Exception) { offset }
    }

    private fun apkInstall() {
        Thread {
            try {
                val r = http.newCall(Request.Builder().url("http://$ip:8088/apk/install?uuid=${myUuid()}").build()).execute()
                val msg = r.body()?.string() ?: ""
                r.close()
                handler.post { log("安装: $msg") }
            } catch (e: Exception) { handler.post { log("唤起安装器失败: ${e.message}") } }
        }.start()
    }

    private fun log(s: String) {
        logBuf.append(s).append("\n")
        if (logBuf.length > 3000) logBuf.delete(0, 1200)
        logText = logBuf.toString()
    }

    private fun toast(s: String) = runOnUiThread { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show() }

    private fun savedStr(k: String, def: String): String = getSharedPreferences("dtv_remote", MODE_PRIVATE).getString(k, def) ?: def
    private fun saveStr(k: String, v: String) = getSharedPreferences("dtv_remote", MODE_PRIVATE).edit().putString(k, v).apply()
    private fun savedBool(k: String, def: Boolean): Boolean = getSharedPreferences("dtv_remote", MODE_PRIVATE).getBoolean(k, def)
    private fun saveBool(k: String, v: Boolean) = getSharedPreferences("dtv_remote", MODE_PRIVATE).edit().putBoolean(k, v).apply()
    private fun prefStr(k: String, def: String): String = getSharedPreferences("dtv_remote", MODE_PRIVATE).getString(k, def) ?: def

    companion object {
        val DEFAULT_GESTURES = mapOf("tap" to "pause", "dbl" to "digg", "vert" to "next")
        val GESTURE_NAMES = mapOf("tap" to "单击", "dbl" to "双击", "vert" to "上下滑")
        val GESTURE_OPTIONS = listOf("pause", "digg", "collect", "next", "prev", "seekf", "seekb", "speed2", "mute", "volup", "volumedown")
        val ACTION_LABELS = mapOf(
            "pause" to "暂停/播放", "digg" to "点赞", "collect" to "收藏", "next" to "下一条",
            "prev" to "上一条", "seekf" to "快进", "seekb" to "快退", "speed2" to "2x快进",
            "mute" to "静音", "volup" to "音量+", "volumedown" to "音量−"
        )
    }
}
