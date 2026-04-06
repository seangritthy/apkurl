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
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isCapturing = false
    private var isKhmer = false

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

        // Restore language preference
        val prefs = getSharedPreferences("apkurl_prefs", MODE_PRIVATE)
        isKhmer = prefs.getBoolean("overlay_lang_kh", false)

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

        // ── Populate version info ──
        val versionText = overlayView?.findViewById<TextView>(R.id.overlayVersionText)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = pInfo.versionName ?: "?"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                pInfo.longVersionCode.toInt() else @Suppress("DEPRECATION") pInfo.versionCode
            versionText?.text = getString(R.string.overlay_version_format, versionName, versionCode)
        } catch (_: Exception) {
            versionText?.text = getString(R.string.overlay_version_placeholder)
        }

        // ── Populate device info ──
        val deviceText = overlayView?.findViewById<TextView>(R.id.overlayDeviceText)
        deviceText?.text = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL}"

        val androidText = overlayView?.findViewById<TextView>(R.id.overlayAndroidText)
        androidText?.text = getString(R.string.overlay_android_format, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)

        // ── Wire buttons ──
        val logsText = overlayView?.findViewById<TextView>(R.id.overlayLogsText)
        val startBtn = overlayView?.findViewById<LinearLayout>(R.id.overlayStartBtn)
        val stopBtn = overlayView?.findViewById<LinearLayout>(R.id.overlayStopBtn)
        val closeBtn = overlayView?.findViewById<TextView>(R.id.overlayCloseBtn)
        val statusText = overlayView?.findViewById<TextView>(R.id.overlayStatusText)
        val statusDot = overlayView?.findViewById<View>(R.id.overlayStatusDot)
        val langBtn = overlayView?.findViewById<TextView>(R.id.overlayLangBtn)
        val clearBtn = overlayView?.findViewById<TextView>(R.id.overlayClearBtn)
        val logLabel = overlayView?.findViewById<TextView>(R.id.overlayLogLabel)

        // Enable scrolling inside the logs TextView
        logsText?.movementMethod = ScrollingMovementMethod.getInstance()

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
                updateStatusUI()
            }
        }

        stopBtn?.setOnClickListener {
            startService(Intent(this, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_STOP))
            isCapturing = false
            updateStatusUI()
        }

        closeBtn?.setOnClickListener {
            stopSelf()
        }

        // ── Language toggle ──
        langBtn?.setOnClickListener {
            isKhmer = !isKhmer
            getSharedPreferences("apkurl_prefs", MODE_PRIVATE)
                .edit().putBoolean("overlay_lang_kh", isKhmer).apply()
            applyLanguage()
        }

        // ── Clear logs ──
        clearBtn?.setOnClickListener {
            UrlLogStore.clear(this)
            updateLogs()
        }

        applyLanguage()
        updateStatusUI()
        updateLogs()

        try {
            windowManager?.addView(overlayView, params)
        } catch (_: Exception) {}
    }

    private fun applyLanguage() {
        val langBtn = overlayView?.findViewById<TextView>(R.id.overlayLangBtn)
        val startLabel = overlayView?.findViewById<TextView>(R.id.overlayStartLabel)
        val stopLabel = overlayView?.findViewById<TextView>(R.id.overlayStopLabel)
        val logLabel = overlayView?.findViewById<TextView>(R.id.overlayLogLabel)
        val clearBtn = overlayView?.findViewById<TextView>(R.id.overlayClearBtn)
        val deviceLabel = overlayView?.findViewById<TextView>(R.id.overlayDeviceLabel)
        val androidLabel = overlayView?.findViewById<TextView>(R.id.overlayAndroidLabel)

        if (isKhmer) {
            langBtn?.text = getString(R.string.overlay_lang_kh)
            startLabel?.text = getString(R.string.overlay_start_kh)
            stopLabel?.text = getString(R.string.overlay_stop_kh)
            logLabel?.text = getString(R.string.overlay_log_label_kh)
            clearBtn?.text = getString(R.string.clear_logs_kh)
            deviceLabel?.text = getString(R.string.overlay_device_label_kh)
            androidLabel?.text = getString(R.string.overlay_android_label_kh)
        } else {
            langBtn?.text = getString(R.string.overlay_lang_en)
            startLabel?.text = getString(R.string.overlay_start)
            stopLabel?.text = getString(R.string.overlay_stop)
            logLabel?.text = getString(R.string.overlay_log_label)
            clearBtn?.text = getString(R.string.clear_logs)
            deviceLabel?.text = getString(R.string.overlay_device_label)
            androidLabel?.text = getString(R.string.overlay_android_label)
        }
        updateStatusUI()
        updateLogs()
    }

    private fun updateStatusUI() {
        val statusText = overlayView?.findViewById<TextView>(R.id.overlayStatusText) ?: return
        val statusDot = overlayView?.findViewById<View>(R.id.overlayStatusDot)

        if (isCapturing) {
            statusText.text = if (isKhmer) getString(R.string.overlay_status_capturing_kh)
                              else getString(R.string.overlay_status_capturing)
            statusText.setTextColor(0xCC4CAF50.toInt())
            // Green dot
            val dot = GradientDrawable()
            dot.shape = GradientDrawable.OVAL
            dot.setColor(0xFF4CAF50.toInt())
            dot.setSize(dpToPx(6), dpToPx(6))
            statusDot?.background = dot
        } else {
            statusText.text = if (isKhmer) getString(R.string.overlay_status_idle_kh)
                              else getString(R.string.overlay_status_idle)
            statusText.setTextColor(0x66BBC7DB)
            // Grey dot
            val dot = GradientDrawable()
            dot.shape = GradientDrawable.OVAL
            dot.setColor(0x66BBC7DB)
            dot.setSize(dpToPx(6), dpToPx(6))
            statusDot?.background = dot
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun removeOverlay() {
        try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
        overlayView = null
    }

    private fun updateLogs() {
        val logsText = overlayView?.findViewById<TextView>(R.id.overlayLogsText) ?: return
        val rows = UrlLogStore.readAll(this)
        if (rows.isEmpty()) {
            logsText.text = if (isKhmer) getString(R.string.no_logs_kh) else getString(R.string.no_logs)
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
