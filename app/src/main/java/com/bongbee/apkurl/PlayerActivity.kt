package com.bongbee.apkurl

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class PlayerActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val toolbar = findViewById<MaterialToolbar>(R.id.playerToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val urlText = findViewById<TextView>(R.id.playerUrlText)
        webView = findViewById(R.id.playerWebView)

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val urlType = intent.getStringExtra(EXTRA_URL_TYPE) ?: "URL"

        toolbar.title = "$urlType Player"
        urlText.text = url

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

        val lowerUrl = url.lowercase()
        if (lowerUrl.contains(".m3u8")) {
            // Use HLS.js player for M3U8 streams
            loadHlsPlayer(url)
        } else if (isDirectMediaUrl(lowerUrl)) {
            // Use HTML5 video element for direct media
            loadVideoPlayer(url)
        } else {
            // Load URL directly in WebView
            webView.loadUrl(url)
        }
    }

    private fun loadHlsPlayer(url: String) {
        val escapedUrl = url.replace("'", "\\'").replace("\\", "\\\\")
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
            <style>
            *{margin:0;padding:0;box-sizing:border-box}
            body{background:#000;display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh;font-family:sans-serif}
            video{width:100%;max-height:90vh;background:#000}
            #status{color:#aaa;font-size:13px;padding:8px;text-align:center}
            #error{color:#f44;font-size:14px;padding:12px;text-align:center;display:none}
            </style>
            </head>
            <body>
            <video id="v" controls autoplay playsinline></video>
            <div id="status">Loading HLS stream...</div>
            <div id="error"></div>
            <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
            <script>
            var url='$escapedUrl';
            var v=document.getElementById('v');
            var status=document.getElementById('status');
            var err=document.getElementById('error');
            try{
              if(typeof Hls!=='undefined'&&Hls.isSupported()){
                var h=new Hls({enableWorker:true,lowLatencyMode:true});
                h.loadSource(url);
                h.attachMedia(v);
                h.on(Hls.Events.MANIFEST_PARSED,function(){
                  status.textContent='Stream loaded. Playing...';
                  v.play();
                });
                h.on(Hls.Events.ERROR,function(e,d){
                  if(d.fatal){
                    err.style.display='block';
                    err.textContent='HLS Error: '+d.type+' - '+d.details;
                    status.textContent='Playback failed';
                  }
                });
              }else if(v.canPlayType('application/vnd.apple.mpegurl')){
                v.src=url;
                v.addEventListener('loadedmetadata',function(){status.textContent='Playing (native HLS)';});
              }else{
                err.style.display='block';
                err.textContent='HLS not supported in this browser';
              }
            }catch(e){
              err.style.display='block';
              err.textContent='Error: '+e.message;
            }
            </script>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL("https://player.local", html, "text/html", "utf-8", null)
    }

    private fun loadVideoPlayer(url: String) {
        val escapedUrl = url.replace("'", "\\'").replace("\\", "\\\\")
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
            <style>
            *{margin:0;padding:0;box-sizing:border-box}
            body{background:#000;display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh;font-family:sans-serif}
            video{width:100%;max-height:90vh;background:#000}
            #status{color:#aaa;font-size:13px;padding:8px;text-align:center}
            #error{color:#f44;font-size:14px;padding:12px;text-align:center;display:none}
            </style>
            </head>
            <body>
            <video id="v" controls autoplay playsinline src="$escapedUrl"></video>
            <div id="status">Loading media...</div>
            <div id="error"></div>
            <script>
            var v=document.getElementById('v');
            var status=document.getElementById('status');
            var err=document.getElementById('error');
            v.addEventListener('loadedmetadata',function(){status.textContent='Playing';});
            v.addEventListener('error',function(e){
              err.style.display='block';
              err.textContent='Media Error: '+(v.error?v.error.message:'Unknown error');
              status.textContent='Playback failed';
            });
            </script>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL("https://player.local", html, "text/html", "utf-8", null)
    }

    private fun isDirectMediaUrl(lower: String): Boolean {
        val exts = listOf(".mp4", ".mp3", ".webm", ".m4a", ".m4v", ".ogg", ".wav",
            ".aac", ".flv", ".mov", ".avi", ".mkv", ".ts", ".m4s")
        return exts.any { lower.contains(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_player_url"
        const val EXTRA_URL_TYPE = "extra_url_type"
    }
}

