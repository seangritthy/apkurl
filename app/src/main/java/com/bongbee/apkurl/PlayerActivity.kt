package com.bongbee.apkurl

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.appbar.MaterialToolbar

class PlayerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var exoView: PlayerView
    private lateinit var captureLogText: TextView
    private lateinit var startBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var backBtn: TextView
    private var isExoPlayerUsed = false

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CaptureVpnService.ACTION_NEW_RECORD -> updateCaptureLog()
                CaptureVpnService.ACTION_STATUS_CHANGED -> updateButtons()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val toolbar = findViewById<MaterialToolbar>(R.id.playerToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val urlText = findViewById<TextView>(R.id.playerUrlText)
        webView = findViewById(R.id.playerWebView)
        exoView = findViewById(R.id.playerExoView)
        captureLogText = findViewById(R.id.playerCapturedUrls)
        startBtn = findViewById(R.id.playerStartBtn)
        stopBtn = findViewById(R.id.playerStopBtn)
        backBtn = findViewById(R.id.playerBackBtn)

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val urlType = intent.getStringExtra(EXTRA_URL_TYPE) ?: "URL"

        toolbar.title = "$urlType Player"
        urlText.text = url

        val lowerUrl = url.lowercase()
        if (lowerUrl.contains(".m3u8") || lowerUrl.contains(".mpd") || isDirectMediaUrl(lowerUrl)) {
            // Use ExoPlayer for m3u8, ts, and direct media
            setupExoPlayer(url)
            isExoPlayerUsed = true
            exoView.visibility = android.view.View.VISIBLE
            webView.visibility = android.view.View.GONE
        } else {
            // Fallback: Load URL directly in WebView
            exoView.visibility = android.view.View.GONE
            webView.visibility = android.view.View.VISIBLE
            webView.settings.apply {
                javaScriptEnabled = true
                mediaPlaybackRequiresUserGesture = false
                domStorageEnabled = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = WebViewClient()
            webView.loadUrl(url)
        }

        startBtn.setOnClickListener {
            val prefs = getSharedPreferences("apkurl_prefs", Context.MODE_PRIVATE)
            val pkg = prefs.getString("selected_package", null)
            val intent = Intent(this, CaptureVpnService::class.java)
                .setAction(CaptureVpnService.ACTION_START)
            if (pkg == "*ALL*") {
                // No package filter: capture all apps
                intent.removeExtra(CaptureVpnService.EXTRA_TARGET_PACKAGE)
            } else if (!pkg.isNullOrBlank()) {
                intent.putExtra(CaptureVpnService.EXTRA_TARGET_PACKAGE, pkg)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Capture started", Toast.LENGTH_SHORT).show()
        }
        stopBtn.setOnClickListener {
            val intent = Intent(this, CaptureVpnService::class.java).setAction(CaptureVpnService.ACTION_STOP)
            startService(intent)
            Toast.makeText(this, "Capture stopped", Toast.LENGTH_SHORT).show()
        }
        backBtn.setOnClickListener { finish() }

        // Register log update receiver
        val filter = IntentFilter(CaptureVpnService.ACTION_NEW_RECORD)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(logReceiver, filter, null, android.os.Handler())
        }
        updateCaptureLog()
        updateButtons()
    }

    private fun updateButtons() {
        val isRunning = CaptureVpnService.isServiceRunning
        startBtn.isEnabled = !isRunning
        stopBtn.isEnabled = isRunning
        startBtn.alpha = if (isRunning) 0.5f else 1.0f
        stopBtn.alpha = if (isRunning) 1.0f else 0.5f
    }

    private fun setupExoPlayer(url: String) {
        exoPlayer = ExoPlayer.Builder(this).build()
        exoView.player = exoPlayer

        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        val lowerUrl = url.lowercase()
        if (lowerUrl.contains(".m3u8")) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        } else if (lowerUrl.contains(".mpd")) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        }

        exoPlayer.setMediaItem(mediaItemBuilder.build())
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(CaptureVpnService.ACTION_NEW_RECORD)
            addAction(CaptureVpnService.ACTION_STATUS_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(logReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        if (this::exoPlayer.isInitialized && isExoPlayerUsed) {
            exoPlayer.release()
        }
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private fun isDirectMediaUrl(lower: String): Boolean {
        val exts = listOf(".mp4", ".mp3", ".webm", ".m4a", ".m4v", ".ogg", ".wav",
            ".aac", ".flv", ".mov", ".avi", ".mkv", ".ts", ".m4s", ".m3u", ".vtt", ".srt")
        return exts.any { lower.contains(it) }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun updateCaptureLog() {
        val rows = UrlLogStore.readAll(this)
        if (rows.isEmpty()) {
            captureLogText.text = getString(R.string.no_logs)
            return
        }
        captureLogText.text = rows.takeLast(5).joinToString("\n") { raw ->
            val parts = raw.split("|", limit = 3)
            if (parts.size >= 3) parts[2] else raw
        }
    }

    companion object {
        const val EXTRA_URL = "extra_player_url"
        const val EXTRA_URL_TYPE = "extra_url_type"
    }
}
