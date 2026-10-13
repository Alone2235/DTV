package com.example.douyintv

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * 极简 WebSocket 控制服务器（端口 8090，RFC6455 文本帧）
 *
 * · 手机客户端以 ws://电视IP:8090/?uuid=<client_uuid> 连接；
 *   仅接受白名单内 client_uuid（配对成功后 TV 已授权）。
 * · 命令协议：JSON {"cmd":"next|prev|digg|collect|toggle|pause|play|seek|line|
 *                     engine|danmaku|loop|landscape|speed|search|push|volumeup|
 *                     volumedown|mute|home", "arg":"..."}
 * · 服务端回一帧 {"ok":true}；非法命令回 {"ok":false,"msg":...}
 */
class WSServer(private val appCtx: Context) : Thread("DTV-WS") {

    private val running = true

    override fun run() {
        try {
            val ss = ServerSocket(8090)
            Log.i("WS", "✓ WebSocket 控制通道 8090 就绪")
            while (running) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                Thread { handle(s) }.start()
            }
            try { ss.close() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e("WS", "启动失败: ${e.message}")
        }
    }

    private fun handle(s: Socket) {
        try {
            val ins = s.getInputStream()
            val outs = s.getOutputStream()
            val head = readHandshake(ins) ?: run { s.close(); return }
            val key = head["sec-websocket-key"] ?: run { s.close(); return }
            // 白名单鉴权：ws://ip:8090/?uuid=xxx
            val line = head["get-line"] ?: ""
            val uuid = line.substringAfter("?", "").split("&")
                .firstOrNull { it.startsWith("uuid=") }?.substringAfter("=") ?: ""
            if (!Prefs.isAllowed(uuid)) {
                Log.w("WS", "拒绝未授权连接 uuid=$uuid")
                s.close(); return
            }
            val accept = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                Base64.NO_WRAP)
            outs.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.UTF_8))
            outs.flush()
            Log.i("WS", "已连接客户端 uuid=${uuid.take(8)}…")
            while (running) {
                val frame = readFrame(ins) ?: break
                when (frame.first) {
                    0x8 -> break                       // close
                    0x1 -> {                          // text
                        val resp = dispatch(frame.second)
                        sendText(outs, resp)
                    }
                }
            }
            try { s.close() } catch (_: Exception) {}
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
        }
    }

    /** 命令分发（与 ConfigServer /cmd 同语义） */
    private fun dispatch(payload: String): String {
        return try {
            val o = JSONObject(payload)
            val cmd = o.optString("cmd", "")
            val arg = o.optString("arg", "")
            when (cmd) {
                "next", "prev", "digg", "collect", "toggle", "pause", "play",
                "line", "engine", "danmaku", "loop", "landscape", "speed", "seek" -> {
                    PlayerBus.send(cmd, arg)
                    """{"ok":true}"""
                }
                "search" -> {
                    if (arg.isNotBlank()) {
                        SearchBus.push(arg)
                        try {
                            val i = android.content.Intent(appCtx, SearchActivity::class.java)
                            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            i.putExtra("kw", arg)
                            appCtx.startActivity(i)
                        } catch (_: Exception) {}
                    }
                    """{"ok":true}"""
                }
                "push" -> {
                    Thread { ConfigServer.pushVideoStatic(appCtx, arg) }.start()
                    """{"ok":true}"""
                }
                "volumeup" -> { vol(5); """{"ok":true}""" }
                "volumedown" -> { vol(-5); """{"ok":true}""" }
                "mute" -> { mute(); """{"ok":true}""" }
                "home" -> {
                    try {
                        val i = android.content.Intent(appCtx, MainActivity::class.java)
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        appCtx.startActivity(i)
                    } catch (_: Exception) {}
                    """{"ok":true}"""
                }
                else -> """{"ok":false,"msg":"未知命令 $cmd"}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"msg":"${e.javaClass.simpleName}: ${e.message}"}"""
        }
    }

    private fun vol(delta: Int) {
        try {
            val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (cur + delta).coerceIn(0, max), 0)
        } catch (_: Throwable) {}
    }

    private fun mute() {
        try {
            val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, if (cur > 0) 0 else 8, 0)
        } catch (_: Throwable) {}
    }

    // ---------------- RFC6455 帧 ----------------
    private fun readHandshake(ins: InputStream): Map<String, String>? {
        val sb = StringBuilder()
        val buf = ByteArray(1)
        var blank = 0
        while (true) {
            val n = ins.read(buf)
            if (n < 0) return null
            val ch = buf[0].toInt().toChar()
            if (ch == '\r' || ch == '\n') { blank++; if (blank >= 2) break } else blank = 0
            sb.append(ch)
        }
        val lines = sb.toString().split("\r\n")
        val map = HashMap<String, String>()
        lines.firstOrNull()?.let { map["get-line"] = it }
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) map[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        return map
    }

    /** 读取一帧：返回 Pair(opcode, payload)；空/断连返回 null */
    private fun readFrame(ins: InputStream): Pair<Int, String>? {
        val h = ByteArray(2)
        if (ins.read(h) != 2) return null
        val opcode = h[0].toInt() and 0x0F
        var len = h[1].toInt() and 0x7F
        if (len == 126) {
            val e = ByteArray(2); if (ins.read(e) != 2) return null
            len = ((e[0].toInt() and 0xFF) shl 8) or (e[1].toInt() and 0xFF)
        } else if (len == 127) {
            val e = ByteArray(8); if (ins.read(e) != 8) return null
            var l = 0L
            for (b in e) l = (l shl 8) or (b.toLong() and 0xFF)
            len = l.toInt()
        }
        if (len > 4 * 1024 * 1024) return null
        val masked = (h[1].toInt() and 0x80) != 0
        val mask = ByteArray(4)
        if (masked) { if (ins.read(mask) != 4) return null }
        val data = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = ins.read(data, got, len - got)
            if (n < 0) return null
            got += n
        }
        if (masked) for (i in 0 until len) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        return opcode to String(data, Charsets.UTF_8)
    }

    private fun sendText(outs: OutputStream, text: String) {
        val data = text.toByteArray(Charsets.UTF_8)
        val head = ByteArray(2 + if (data.size >= 126) 2 else 0)
        head[0] = 0x81.toByte()
        when {
            data.size < 126 -> head[1] = data.size.toByte()
            data.size < 65536 -> { head[1] = 126.toByte(); head[2] = (data.size ushr 8).toByte(); head[3] = (data.size and 0xFF).toByte() }
            else -> { /* 忽略超大帧 */ return }
        }
        outs.write(head)
        outs.write(data)
        outs.flush()
    }
}
