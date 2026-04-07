package com.bongbee.apkurl

import android.content.Context
import android.util.Log
import android.webkit.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class EnhancedVidSrcExtractor(private val context: Context) {
    
    private val urlExtractor = UnifiedUrlExtractor(context)
    
    init {
        // Set up callbacks to intercept URLs from packet capture
        urlExtractor.onCloudnestraUrlFound = { cloudnestraUrl ->
            Log.d("EnhancedExtractor", "Captured Cloudnestra URL from network: $cloudnestraUrl")
            // Decode and play immediately
            GlobalScope.launch(Dispatchers.Main) {
                decodeAndPlay(cloudnestraUrl)
            }
        }
        
        urlExtractor.onStreamingUrlFound = { streamingUrl ->
            Log.d("EnhancedExtractor", "Captured streaming URL from network: $streamingUrl")
            onStreamingUrlCaptured?.invoke(streamingUrl)
        }
    }
    
    var onStreamingUrlCaptured: ((String) -> Unit)? = null
    var onVideoUrlReady: ((String) -> Unit)? = null
    
    private suspend fun decodeAndPlay(cloudnestraUrl: String) {
        val decoded = decodeCloudnestraUrl(cloudnestraUrl)
        if (decoded != null) {
            onVideoUrlReady?.invoke(decoded)
        }
    }
    
    private fun decodeCloudnestraUrl(url: String): String? {
        return try {
            val encoded = url.substringAfter("/rcp/")
            val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
            
            val patterns = listOf(
                Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)"""),
                Regex("""(https?://[^\s"']+\.mp4[^\s"']*)""")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(decoded)
                if (match != null) return match.value
            }
            null
        } catch (e: Exception) {
            Log.e("EnhancedExtractor", "Failed to decode Cloudnestra URL", e)
            null
        }
    }
    
    suspend fun extractVideoUrl(embedUrl: String): String? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String?>()
        
        val webView = WebView(context)
        
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                
                // Check for Cloudnestra RCP
                if (url.contains("cloudnestra.com/rcp/")) {
                    if (!deferred.isCompleted) {
                        val decoded = decodeCloudnestraUrl(url)
                        deferred.complete(decoded ?: url)
                    }
                    return null
                }
                
                // Check for direct video URLs
                if (url.contains(".m3u8") || url.contains(".mp4")) {
                    if (!deferred.isCompleted) {
                        deferred.complete(url)
                    }
                }
                
                return null
            }
        }
        
        webView.loadUrl(embedUrl)
        
        val result = withTimeoutOrNull(30000) {
            deferred.await()
        }
        
        webView.destroy()
        result
    }
    
    fun processCapturedPayload(payload: ByteArray, srcPort: Int, dstIp: ByteArray, isTls: Boolean, host: String?) {
        GlobalScope.launch {
            urlExtractor.processTcpPayload(payload, srcPort, dstIp, isTls, host)
        }
    }
}
