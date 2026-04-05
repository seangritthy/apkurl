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
import kotlin.concurrent.thread

class CaptureVpnService : VpnService() {

    @Volatile
    private var isRunning = false

    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null

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
        if (isRunning) {
            return
        }

        val builder = Builder()
            .setSession("APK URL Capture")
            .addAddress("10.10.10.2", 32)
            .addRoute("0.0.0.0", 0)

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
        workerThread = thread(name = "CaptureVpnReader", isDaemon = true) {
            runCaptureLoop(targetPackage)
        }
    }

    private fun stopCapture() {
        isRunning = false
        workerThread?.interrupt()
        workerThread = null

        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun runCaptureLoop(targetPackage: String) {
        val fileDescriptor = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(fileDescriptor)
        val packetBuffer = ByteArray(MAX_PACKET_SIZE)

        try {
            while (isRunning) {
                val packetLength = inputStream.read(packetBuffer)
                if (packetLength <= 0) {
                    continue
                }

                val payload = PacketParsers.extractTcpPayload(packetBuffer, packetLength) ?: continue
                val urls = UrlExtractor.extractUrls(payload)

                for (url in urls) {
                    val row = logRow(targetPackage, url)
                    UrlLogStore.append(this, row)

                    val updateIntent = Intent(ACTION_NEW_RECORD)
                        .setPackage(packageName)
                        .putExtra(EXTRA_LOG_ROW, row)
                    sendBroadcast(updateIntent)
                }
            }
        } catch (_: Exception) {
            UrlLogStore.append(this, logRow(targetPackage, "Capture loop stopped"))
        } finally {
            try {
                inputStream.close()
            } catch (_: Exception) {
            }
            stopCapture()
        }
    }

    private fun buildNotification(targetPackage: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            manager?.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, targetPackage))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun logRow(targetPackage: String, value: String): String {
        return "${System.currentTimeMillis()}|$targetPackage|$value"
    }

    companion object {
        const val ACTION_START = "com.bongbee.apkurl.ACTION_START_CAPTURE"
        const val ACTION_STOP = "com.bongbee.apkurl.ACTION_STOP_CAPTURE"
        const val ACTION_NEW_RECORD = "com.bongbee.apkurl.ACTION_NEW_RECORD"

        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val EXTRA_LOG_ROW = "extra_log_row"

        private const val CHANNEL_ID = "apkurl_capture"
        private const val CHANNEL_NAME = "APK URL Capture"
        private const val NOTIFICATION_ID = 10001
        private const val MAX_PACKET_SIZE = 32767
    }
}

