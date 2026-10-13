package com.example.douyintv

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * UDP 发现服务（端口 8089）
 * 手机客户端向局域网广播 "DTV_DISCOVER"，本服务应答设备信息：
 *   {"app":"DTV","uuid":"<tv_uuid>","name":"DTV电视","version":"2.xx","port":8088}
 * 手机侧据此自动发现同一 WiFi 下的 DTV 电视（配合手动填 IP 兜底）。
 */
class DiscoveryServer(private val appCtx: Context) : Thread("DTV-Discovery") {

    private val running = true

    override fun run() {
        try {
            val sock = DatagramSocket(8089)
            sock.soTimeout = 1500
            val buf = ByteArray(1024)
            while (running) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    if (msg.contains("DTV_DISCOVER")) {
                        val version = try { BuildConfig.VERSION_NAME } catch (_: Throwable) { "2.x" }
                        val reply = ("{\"app\":\"DTV\",\"uuid\":\"${Prefs.getTvUuid()}\"," +
                            "\"name\":\"DTV电视\",\"version\":\"$version\",\"port\":${ConfigServer.PORT}}")
                            .toByteArray(Charsets.UTF_8)
                        sock.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
                        Log.d("Discovery", "已应答发现请求 from ${pkt.address}")
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // 正常轮询超时，继续等
                } catch (e: Exception) {
                    Log.e("Discovery", "接收失败: ${e.message}")
                }
            }
            sock.close()
        } catch (e: Exception) {
            Log.e("Discovery", "启动失败: ${e.message}")
        }
    }
}
