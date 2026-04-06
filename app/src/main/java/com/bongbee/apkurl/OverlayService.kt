package com.bongbee.apkurl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.android.material.button.MaterialButton

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isCapturing = false

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CaptureVpnService.ACTION_NEW_RECORD) {
                updateLogs()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        createOverlay()

        val filter = IntentFilter(CaptureVpnService.ACTION_NEW_RECORD)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_OVERLAY -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(logReceiver) } catch (_: Exception) {}
        removeOverlay()
        super.onDestroy()
    }

    private fun createOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_capture, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        // Drag support
        var lastY = 0
        var lastTouchY = 0f
        overlayView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastY = params.y
                    lastTouchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.y = lastY + (event.rawY - lastTouchY).toInt()
                    try { windowManager?.updateViewLayout(overlayView, params) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }

        // Wire buttons
        val logsText = overlayView?.findViewById<TextView>(R.id.overlayLogsText)
        val startBtn = overlayView?.findViewById<MaterialButton>(R.id.overlayStartBtn)
        val stopBtn = overlayView?.findViewById<MaterialButton>(R.id.overlayStopBtn)
        val closeBtn = overlayView?.findViewById<MaterialButton>(R.id.overlayCloseBtn)

        startBtn?.setOnClickListener {
            val prefs = getSharedPreferences("apkurl_prefs", MODE_PRIVATE)
            val pkg = prefs.getString("selected_package", null)
            if (!pkg.isNullOrBlank()) {
                val captureIntent = Intent(this, CaptureVpnService::class.java)
                    .setAction(CaptureVpnService.ACTION_START)
                    .putExtra(CaptureVpnService.EXTRA_TARGET_PACKAGE, pkg)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(captureIntent)
                } else {
                    startService(captureIntent)
                }
                isCapturing = true
            }
        }

        stopBtn?.setOnClickListener {
            startService(Intent(this, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_STOP))
            isCapturing = false
        }

        closeBtn?.setOnClickListener {
            stopSelf()
        }

        updateLogs()

        try {
            windowManager?.addView(overlayView, params)
        } catch (_: Exception) {}
    }

    private fun removeOverlay() {
        try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
        overlayView = null
    }

    private fun updateLogs() {
        val logsText = overlayView?.findViewById<TextView>(R.id.overlayLogsText) ?: return
        val rows = UrlLogStore.readAll(this)
        if (rows.isEmpty()) {
            logsText.text = getString(R.string.no_logs)
            return
        }
        logsText.text = rows.takeLast(10).joinToString("\n") { raw ->
            val parts = raw.split("|", limit = 3)
            if (parts.size >= 3) parts[2] else raw
        }
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Overlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("APK URL Overlay")
            .setContentText("Floating overlay is active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP_OVERLAY = "com.bongbee.apkurl.ACTION_STOP_OVERLAY"
        private const val CHANNEL_ID = "apkurl_overlay"
        private const val NOTIFICATION_ID = 10002
    }
}

