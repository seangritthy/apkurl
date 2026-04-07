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
import android.annotation.SuppressLint
import android.text.method.ScrollingMovementMethod
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.content.pm.PackageManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    private var attachedAppName: String? = null
    private var attachedAppIcon: android.graphics.drawable.Drawable? = null
    private var attachedPackage: String? = null

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isCapturing = false
    private var isKhmer = false

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CaptureVpnService.ACTION_NEW_RECORD -> {
                    updateLogs()
                    // Send to MainActivity via broadcast
                    val forwardIntent = Intent(CaptureVpnService.ACTION_NEW_RECORD)
                    forwardIntent.setPackage(packageName)
                    val row = intent.getStringExtra(CaptureVpnService.EXTRA_LOG_ROW)
                    if (row != null) {
                        forwardIntent.putExtra(CaptureVpnService.EXTRA_LOG_ROW, row)
                        sendBroadcast(forwardIntent)
                    }
                }
                CaptureVpnService.ACTION_STATUS_CHANGED -> {
                    isCapturing = CaptureVpnService.isServiceRunning
                    updateStatusUI()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Restore language preference
        val prefs = getSharedPreferences("apkurl_prefs", MODE_PRIVATE)
        isKhmer = prefs.getBoolean("overlay_lang_kh", false)

        // Sync capturing state with VPN service
        isCapturing = CaptureVpnService.isServiceRunning

        // Get attached app info
        attachedPackage = prefs.getString("selected_package", null)
        attachedAppName = attachedPackage?.let { pkg ->
            try {
                if (pkg == "*ALL*") {
                    attachedAppIcon = null
                    getString(R.string.all_apps)
                } else {
                    val pm = packageManager
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    attachedAppIcon = pm.getApplicationIcon(appInfo)
                    pm.getApplicationLabel(appInfo).toString()
                }
            } catch (_: Exception) { pkg }
        }

        createOverlay()

        val filter = IntentFilter().apply {
            addAction(CaptureVpnService.ACTION_NEW_RECORD)
            addAction(CaptureVpnService.ACTION_STATUS_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView == null) createOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(logReceiver) } catch (_: Exception) {}
        removeOverlay()
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Wrap the context with the app theme to resolve ?attr/ attributes in layout inflation
        val themeContext = ContextThemeWrapper(this, R.style.Theme_ApkUrl)
        val dummyRoot = android.widget.FrameLayout(themeContext)
        overlayView = LayoutInflater.from(themeContext).inflate(R.layout.overlay_capture, dummyRoot, false)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0
            y = 0
        }

        // Show attached app name and icon
        val appNameText = overlayView?.findViewById<TextView>(R.id.overlayAppNameText)
        val appIconView = overlayView?.findViewById<android.widget.ImageView>(R.id.overlayAppIcon)
        if (appNameText != null) appNameText.text = attachedAppName ?: "-"
        if (appIconView != null && attachedAppIcon != null) appIconView.setImageDrawable(attachedAppIcon)

        // Make app info clickable to return to MainActivity
        val appClickAction = View.OnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
        appIconView?.setOnClickListener(appClickAction)
        appNameText?.setOnClickListener(appClickAction)

        // Drag support (drag header to move)
        val header = overlayView?.findViewById<LinearLayout>(R.id.overlayHeader)
        var lastX = 0
        var lastY = 0
        var lastTouchX = 0f
        var lastTouchY = 0f
        
        header?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = params.x
                    lastY = params.y
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = lastX + (event.rawX - lastTouchX).toInt()
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
        deviceText?.text = getString(R.string.overlay_device_format, Build.MANUFACTURER.uppercase(), Build.MODEL)

        val androidText = overlayView?.findViewById<TextView>(R.id.overlayAndroidText)
        androidText?.text = getString(R.string.overlay_android_format, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)

        // ── Wire buttons ──
        val logsText = overlayView?.findViewById<TextView>(R.id.overlayLogsText)
        val startBtn = overlayView?.findViewById<LinearLayout>(R.id.overlayStartBtn)
        val stopBtn = overlayView?.findViewById<LinearLayout>(R.id.overlayStopBtn)
        val closeBtn = overlayView?.findViewById<TextView>(R.id.overlayCloseBtn)
        val langBtn = overlayView?.findViewById<TextView>(R.id.overlayLangBtn)
        val clearBtn = overlayView?.findViewById<TextView>(R.id.overlayClearBtn)

        logsText?.movementMethod = ScrollingMovementMethod.getInstance()

        startBtn?.setOnClickListener {
            val prefs = getSharedPreferences("apkurl_prefs", MODE_PRIVATE)
            val pkg = attachedPackage ?: prefs.getString("selected_package", null)
            
            if (pkg.isNullOrBlank()) {
                val msg = if (isKhmer) "សូមជ្រើសរើសកម្មវិធីជាមុនសិន" else "Please select an app first"
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_START)
            if (pkg != "*ALL*") {
                intent.putExtra(CaptureVpnService.EXTRA_TARGET_PACKAGE, pkg)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        stopBtn?.setOnClickListener {
            startService(Intent(this, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_STOP))
        }

        closeBtn?.setOnClickListener {
            stopSelf()
        }

        langBtn?.setOnClickListener {
            isKhmer = !isKhmer
            getSharedPreferences("apkurl_prefs", MODE_PRIVATE)
                .edit().putBoolean("overlay_lang_kh", isKhmer).apply()
            applyLanguage()
        }

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
        val appNameText = overlayView?.findViewById<TextView>(R.id.overlayAppNameText)

        if (isKhmer) {
            langBtn?.text = getString(R.string.overlay_lang_kh)
            startLabel?.text = getString(R.string.overlay_start_kh)
            stopLabel?.text = getString(R.string.overlay_stop_kh)
            logLabel?.text = getString(R.string.overlay_log_label_kh)
            clearBtn?.text = getString(R.string.clear_logs_kh)
            deviceLabel?.text = getString(R.string.overlay_device_label_kh)
            androidLabel?.text = getString(R.string.overlay_android_label_kh)
            if (attachedPackage == "*ALL*") appNameText?.text = "កម្មវិធីទាំងអស់"
        } else {
            langBtn?.text = getString(R.string.overlay_lang_en)
            startLabel?.text = getString(R.string.overlay_start)
            stopLabel?.text = getString(R.string.overlay_stop)
            logLabel?.text = getString(R.string.overlay_log_label)
            clearBtn?.text = getString(R.string.clear_logs)
            deviceLabel?.text = getString(R.string.overlay_device_label)
            androidLabel?.text = getString(R.string.overlay_android_label)
            if (attachedPackage == "*ALL*") appNameText?.text = "All Apps"
        }
        updateStatusUI()
        updateLogs()
    }

    private fun updateStatusUI() {
        isCapturing = CaptureVpnService.isServiceRunning

        val statusText = overlayView?.findViewById<TextView>(R.id.overlayStatusText) ?: return
        val statusDot = overlayView?.findViewById<View>(R.id.overlayStatusDot)
        val startBtn = overlayView?.findViewById<LinearLayout>(R.id.overlayStartBtn)
        val stopBtn = overlayView?.findViewById<LinearLayout>(R.id.overlayStopBtn)

        if (isCapturing) {
            statusText.text = if (isKhmer) getString(R.string.overlay_status_capturing_kh)
                              else getString(R.string.overlay_status_capturing)
            statusText.setTextColor(0xFF00E676.toInt())
            statusDot?.setBackgroundResource(R.drawable.dot_active)
            startBtn?.isEnabled = false
            stopBtn?.isEnabled = true
            startBtn?.alpha = 0.5f
            stopBtn?.alpha = 1.0f
        } else {
            statusText.text = if (isKhmer) getString(R.string.overlay_status_idle_kh)
                              else getString(R.string.overlay_status_idle)
            statusText.setTextColor(0x99ADC8FF.toInt())
            statusDot?.setBackgroundResource(R.drawable.dot_idle)
            startBtn?.isEnabled = true
            stopBtn?.isEnabled = false
            startBtn?.alpha = 1.0f
            stopBtn?.alpha = 0.5f
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
        val pkg = attachedPackage ?: "*ALL*"
        val rows = UrlLogStore.readByPackage(this, pkg)
        if (rows.isEmpty()) {
            logsText.text = if (isKhmer) getString(R.string.no_logs_kh) else getString(R.string.no_logs)
            return
        }
        logsText.text = rows.takeLast(15).joinToString("\n") { raw ->
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
        private const val CHANNEL_ID = "apkurl_overlay"
        private const val NOTIFICATION_ID = 10002
    }
}
