package com.bongbee.apkurl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class CaptureVpnService : VpnService() {

    @Volatile
    private var isRunning = false

    private var vpnInterface: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private var executor: ExecutorService? = null

    private val tcpSessions = ConcurrentHashMap<Int, TcpSession>()
    private val udpSessions = ConcurrentHashMap<Int, UdpSession>()

    @Volatile
    private var tunOutputStream: FileOutputStream? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
                if (targetPackage.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification(targetPackage))
                startCapture(targetPackage)
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun startCapture(targetPackage: String) {
        if (isRunning) return

        val builder = Builder()
            .setSession("APK URL Capture")
            .addAddress("10.10.10.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
            .setMtu(MTU)

        try {
            builder.addAllowedApplication(targetPackage)
        } catch (_: PackageManager.NameNotFoundException) {
            UrlLogStore.append(this, logRow(targetPackage, "Failed: package not found"))
            stopSelf()
            return
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            UrlLogStore.append(this, logRow(targetPackage, "Failed: VPN establish returned null"))
            stopSelf()
            return
        }

        isRunning = true
        executor = Executors.newCachedThreadPool()
        val fd = vpnInterface!!.fileDescriptor
        tunOutputStream = FileOutputStream(fd)

        readerThread = thread(name = "VpnReader", isDaemon = true) {
            runReadLoop(targetPackage, FileInputStream(fd))
        }
    }

    private fun stopCapture() {
        isRunning = false
        readerThread?.interrupt()
        readerThread = null

        for (s in tcpSessions.values) { try { s.socket.close() } catch (_: Exception) {} }
        tcpSessions.clear()
        for (s in udpSessions.values) { try { s.socket.close() } catch (_: Exception) {} }
        udpSessions.clear()

        try { tunOutputStream?.close() } catch (_: Exception) {}
        tunOutputStream = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        executor?.shutdownNow()
        executor = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ── Read loop ──────────────────────────────────────────────────────────────

    private fun runReadLoop(targetPackage: String, inputStream: FileInputStream) {
        val buf = ByteArray(MTU)
        try {
            while (isRunning) {
                val len = inputStream.read(buf)
                if (len <= 0 || len < 20) continue
                val ipVer = (buf[0].toInt() ushr 4) and 0x0F
                if (ipVer != 4) continue
                val proto = buf[9].toInt() and 0xFF
                val pkt = buf.copyOf(len)

                // Extract URLs from TCP for logging
                if (proto == PROTO_TCP) {
                    val payload = PacketParsers.extractTcpPayload(pkt, len)
                    if (payload != null && payload.isNotEmpty()) {
                        for (url in UrlExtractor.extractUrls(payload)) {
                            val row = logRow(targetPackage, url)
                            UrlLogStore.append(this, row)
                            sendBroadcast(Intent(ACTION_NEW_RECORD).setPackage(packageName).putExtra(EXTRA_LOG_ROW, row))
                        }
                    }
                }

                when (proto) {
                    PROTO_TCP -> handleTcpOut(pkt, len)
                    PROTO_UDP -> handleUdpOut(pkt, len, targetPackage)
                }
            }
        } catch (_: Exception) {
        } finally {
            try { inputStream.close() } catch (_: Exception) {}
            stopCapture()
        }
    }

    // ── UDP ────────────────────────────────────────────────────────────────────

    private fun handleUdpOut(pkt: ByteArray, len: Int, targetPackage: String) {
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (len < ihl + 8) return
        val dstIp = InetAddress.getByAddress(pkt.copyOfRange(16, 20))
        val srcPort = rU16(pkt, ihl)
        val dstPort = rU16(pkt, ihl + 2)
        val udpLen = rU16(pkt, ihl + 4)
        val payOff = ihl + 8
        val payLen = udpLen - 8
        if (payOff + payLen > len || payLen < 0) return
        val data = pkt.copyOfRange(payOff, payOff + payLen)

        // Log DNS queries (port 53) to capture every hostname the app connects to
        if (dstPort == 53 && payLen > 12) {
            val host = PacketParsers.extractDnsQueryName(data)
            if (!host.isNullOrBlank()) {
                val url = "https://$host"
                val row = logRow(targetPackage, url)
                UrlLogStore.append(this, row)
                sendBroadcast(Intent(ACTION_NEW_RECORD).setPackage(packageName).putExtra(EXTRA_LOG_ROW, row))
            }
        }

        val session = udpSessions.getOrPut(srcPort) {
            val sock = DatagramSocket()
            protect(sock)
            val s = UdpSession(sock, srcPort, pkt.copyOfRange(12, 16))
            executor?.submit { udpRcv(s) }
            s
        }
        try {
            session.lastDstIp = dstIp.address
            session.lastDstPort = dstPort
            session.socket.send(DatagramPacket(data, payLen, dstIp, dstPort))
        } catch (_: Exception) {
            udpSessions.remove(srcPort)
            try { session.socket.close() } catch (_: Exception) {}
        }
    }

    private fun udpRcv(s: UdpSession) {
        val buf = ByteArray(MTU - 28)
        try {
            s.socket.soTimeout = 60_000
            while (isRunning) {
                val dg = DatagramPacket(buf, buf.size)
                s.socket.receive(dg)
                val total = 20 + 8 + dg.length
                val r = ByteArray(total)
                r[0] = 0x45.toByte()
                wU16(r, 2, total)
                r[8] = 64; r[9] = PROTO_UDP.toByte()
                System.arraycopy(s.lastDstIp ?: dg.address.address, 0, r, 12, 4)
                System.arraycopy(s.srcIp, 0, r, 16, 4)
                wU16(r, 20, dg.port)
                wU16(r, 22, s.srcPort)
                wU16(r, 24, 8 + dg.length)
                System.arraycopy(buf, 0, r, 28, dg.length)
                fixIpCksum(r)
                writeTun(r)
            }
        } catch (_: Exception) {} finally {
            udpSessions.remove(s.srcPort)
            try { s.socket.close() } catch (_: Exception) {}
        }
    }

    // ── TCP ────────────────────────────────────────────────────────────────────

    private fun handleTcpOut(pkt: ByteArray, len: Int) {
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (len < ihl + 20) return
        val srcIp = pkt.copyOfRange(12, 16)
        val dstIp = InetAddress.getByAddress(pkt.copyOfRange(16, 20))
        val srcPort = rU16(pkt, ihl)
        val dstPort = rU16(pkt, ihl + 2)
        val seq = rU32(pkt, ihl + 4)
        val ack = rU32(pkt, ihl + 8)
        val thl = ((pkt[ihl + 12].toInt() ushr 4) and 0x0F) * 4
        val flags = pkt[ihl + 13].toInt() and 0xFF
        val syn = flags and 0x02 != 0
        val ackF = flags and 0x10 != 0
        val fin = flags and 0x01 != 0
        val rst = flags and 0x04 != 0
        val payOff = ihl + thl
        val payLen = len - payOff

        if (syn && !ackF) {
            tcpSessions.remove(srcPort)?.let { try { it.socket.close() } catch (_: Exception) {} }
            executor?.submit {
                try {
                    val sock = Socket()
                    protect(sock)
                    sock.connect(InetSocketAddress(dstIp, dstPort), 10_000)
                    val s = TcpSession(sock, srcPort, dstPort, srcIp, dstIp.address, seq + 1, 0)
                    tcpSessions[srcPort] = s
                    writeTun(buildTcp(dstIp.address, srcIp, dstPort, srcPort, s.sSeq, s.cSeq, 0x12, ByteArray(0)))
                    s.sSeq++
                    executor?.submit { tcpRcv(s) }
                } catch (_: Exception) {
                    writeTun(buildTcp(dstIp.address, srcIp, dstPort, srcPort, 0, seq + 1, 0x14, ByteArray(0)))
                }
            }
        } else if (fin) {
            val s = tcpSessions.remove(srcPort) ?: return
            s.cSeq = seq + 1
            writeTun(buildTcp(s.dstIp, s.srcIp, s.dstPort, s.srcPort, s.sSeq, s.cSeq, 0x11, ByteArray(0)))
            s.sSeq++
            try { s.socket.close() } catch (_: Exception) {}
        } else if (rst) {
            tcpSessions.remove(srcPort)?.let { try { it.socket.close() } catch (_: Exception) {} }
        } else if (ackF && payLen > 0) {
            val s = tcpSessions[srcPort] ?: return
            s.cSeq = seq + payLen
            val data = pkt.copyOfRange(payOff, payOff + payLen)
            try {
                s.socket.getOutputStream().write(data)
                s.socket.getOutputStream().flush()
                writeTun(buildTcp(s.dstIp, s.srcIp, s.dstPort, s.srcPort, s.sSeq, s.cSeq, 0x10, ByteArray(0)))
            } catch (_: Exception) {
                tcpSessions.remove(srcPort)
                try { s.socket.close() } catch (_: Exception) {}
            }
        }
    }

    private fun tcpRcv(s: TcpSession) {
        val buf = ByteArray(MTU - 40)
        try {
            val inp = s.socket.getInputStream()
            while (isRunning && !s.socket.isClosed) {
                val n = inp.read(buf)
                if (n < 0) break
                writeTun(buildTcp(s.dstIp, s.srcIp, s.dstPort, s.srcPort, s.sSeq, s.cSeq, 0x18, buf.copyOf(n)))
                s.sSeq += n
            }
        } catch (_: Exception) {} finally {
            if (isRunning && tcpSessions.containsKey(s.srcPort)) {
                writeTun(buildTcp(s.dstIp, s.srcIp, s.dstPort, s.srcPort, s.sSeq, s.cSeq, 0x11, ByteArray(0)))
                s.sSeq++
            }
            tcpSessions.remove(s.srcPort)
            try { s.socket.close() } catch (_: Exception) {}
        }
    }

    // ── Packet builder ─────────────────────────────────────────────────────────

    private fun buildTcp(sIp: ByteArray, dIp: ByteArray, sP: Int, dP: Int, seq: Long, ack: Long, fl: Int, pay: ByteArray): ByteArray {
        val total = 40 + pay.size
        val p = ByteArray(total)
        p[0] = 0x45.toByte()
        wU16(p, 2, total)
        p[8] = 64; p[9] = PROTO_TCP.toByte()
        System.arraycopy(sIp, 0, p, 12, 4)
        System.arraycopy(dIp, 0, p, 16, 4)
        wU16(p, 20, sP); wU16(p, 22, dP)
        wU32(p, 24, seq); wU32(p, 28, ack)
        p[32] = (5 shl 4).toByte(); p[33] = fl.toByte()
        wU16(p, 34, 65535)
        if (pay.isNotEmpty()) System.arraycopy(pay, 0, p, 40, pay.size)
        fixTcpCksum(p, 20, total - 20, sIp, dIp)
        fixIpCksum(p)
        return p
    }

    private fun fixIpCksum(p: ByteArray) {
        p[10] = 0; p[11] = 0
        var s = 0L
        for (i in 0 until 20 step 2) s += rU16(p, i)
        while (s > 0xFFFF) s = (s and 0xFFFF) + (s shr 16)
        wU16(p, 10, s.toInt().inv() and 0xFFFF)
    }

    private fun fixTcpCksum(p: ByteArray, off: Int, segLen: Int, sIp: ByteArray, dIp: ByteArray) {
        p[off + 16] = 0; p[off + 17] = 0
        var s = 0L
        s += rU16(sIp, 0); s += rU16(sIp, 2)
        s += rU16(dIp, 0); s += rU16(dIp, 2)
        s += PROTO_TCP.toLong(); s += segLen.toLong()
        var i = off
        while (i + 1 < off + segLen) { s += rU16(p, i); i += 2 }
        if (i < off + segLen) s += (p[i].toInt() and 0xFF) shl 8
        while (s > 0xFFFF) s = (s and 0xFFFF) + (s shr 16)
        wU16(p, off + 16, s.toInt().inv() and 0xFFFF)
    }

    @Synchronized
    private fun writeTun(p: ByteArray) {
        try { tunOutputStream?.write(p) } catch (_: Exception) {}
    }

    private fun rU16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun wU16(b: ByteArray, o: Int, v: Int) { b[o] = ((v shr 8) and 0xFF).toByte(); b[o + 1] = (v and 0xFF).toByte() }
    private fun rU32(b: ByteArray, o: Int): Long = ((b[o].toLong() and 0xFF) shl 24) or ((b[o+1].toLong() and 0xFF) shl 16) or ((b[o+2].toLong() and 0xFF) shl 8) or (b[o+3].toLong() and 0xFF)
    private fun wU32(b: ByteArray, o: Int, v: Long) { b[o]=((v shr 24) and 0xFF).toByte(); b[o+1]=((v shr 16) and 0xFF).toByte(); b[o+2]=((v shr 8) and 0xFF).toByte(); b[o+3]=(v and 0xFF).toByte() }

    private fun buildNotification(targetPackage: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr?.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, targetPackage))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(pi).setOngoing(true).build()
    }

    private fun logRow(pkg: String, v: String) = "${System.currentTimeMillis()}|$pkg|$v"

    private data class TcpSession(val socket: Socket, val srcPort: Int, val dstPort: Int, val srcIp: ByteArray, val dstIp: ByteArray, var cSeq: Long, var sSeq: Long)
    private class UdpSession(val socket: DatagramSocket, val srcPort: Int, val srcIp: ByteArray, var lastDstIp: ByteArray? = null, var lastDstPort: Int = 0)

    companion object {
        const val ACTION_START = "com.bongbee.apkurl.ACTION_START_CAPTURE"
        const val ACTION_STOP = "com.bongbee.apkurl.ACTION_STOP_CAPTURE"
        const val ACTION_NEW_RECORD = "com.bongbee.apkurl.ACTION_NEW_RECORD"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val EXTRA_LOG_ROW = "extra_log_row"
        private const val CHANNEL_ID = "apkurl_capture"
        private const val CHANNEL_NAME = "APK URL Capture"
        private const val NOTIFICATION_ID = 10001
        private const val MTU = 1500
        private const val PROTO_TCP = 6
        private const val PROTO_UDP = 17
    }
}
