package com.bongbee.apkurl

import android.content.Context
import android.webkit.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class VidSrcExtractor(private val context: Context) {

    /**
     * Attempts to find a direct video source URL (.m3u8, .mp4, or CloudNestra RCP) from an embed page.
     * Uses a hidden WebView with persistent cookies/cache to bypass Cloudflare.
     */
    suspend fun extractVideoUrl(embedUrl: String): String? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String?>()

        val webView = WebView(context)

        // 1. Enable Global Cookies (Crucial for Cloudflare Clearance)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true

            // 2. Browser Identification
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

            // 3. Cache Strategy
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                val lowUrl = url.lowercase()

                if (AdBlocker.isAd(url)) return AdBlocker.createEmptyResource()

                // Specific check for CloudNestra RCP URL
                if (lowUrl.contains("cloudnestra.com/rcp/")) {
                    if (!deferred.isCompleted) {
                        deferred.complete(url)
                        return null
                    }
                }

                // Intercept video stream manifests
                if ((lowUrl.contains(".m3u8") || lowUrl.contains(".mp4") || lowUrl.contains("/playlist") || lowUrl.contains("/manifest")) &&
                    !lowUrl.contains("ads") && !lowUrl.contains("telemetry")) {

                    if (!lowUrl.contains("segment") && !lowUrl.contains(".ts")) {
                        if (!deferred.isCompleted) {
                            deferred.complete(url)
                        }
                    }
                }

                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Sync cookies to persistent storage
                cookieManager.flush()

                // 4. Advanced JavaScript Spoofing (Bypass Bot Checks)
                val js = """
                    (function() {
                        try {
                            // Mask the fact that we are a WebView
                            Object.defineProperty(navigator, 'webdriver', { get: () => false });
                            Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
                            Object.defineProperty(navigator, 'deviceMemory', { get: () => 8 });
                            Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 8 });
                            
                            // Mock plugins
                            Object.defineProperty(navigator, 'plugins', {
                                get: () => [
                                    { name: 'Chrome PDF Viewer', filename: 'internal-pdf-viewer' },
                                    { name: 'Chrome PDF Plugin', filename: 'chrome-pdf-plugin' }
                                ]
                            });
                        } catch(e) {}

                        function autoAction() {
                            // Cleanup UI blocking elements
                            var adSelectors = ['.ad', '.ads', '.modal', '.popup', '.overlay', '[id*="google_ads"]'];
                            adSelectors.forEach(function(sel) {
                                document.querySelectorAll(sel).forEach(function(el) { el.remove(); });
                            });

                            // Simulate clicks on play buttons
                            var playButtons = [
                                '.vjs-big-play-button', '.jw-display-icon-container', '.play-button', 
                                '#play-button', 'button[class*="play"]', '.vjs-poster'
                            ];
                            playButtons.forEach(function(s) {
                                document.querySelectorAll(s).forEach(function(btn) { 
                                    try { 
                                        var clickEvent = new MouseEvent('click', { bubbles: true, cancelable: true, view: window });
                                        btn.dispatchEvent(clickEvent);
                                    } catch(e) {} 
                                });
                            });
                            
                            document.querySelectorAll('video').forEach(function(v) {
                                v.muted = false;
                                v.play().catch(function(e) {});
                            });
                        }
                        
                        autoAction();
                        var intervalId = setInterval(autoAction, 1500);
                        setTimeout(function() { clearInterval(intervalId); }, 25000);

                        // Redirect nested sources
                        var iframes = document.querySelectorAll('iframe');
                        for (var i = 0; i < iframes.length; i++) {
                            try {
                                var src = iframes[i].src;
                                if (src.includes('cloudnestra') || src.includes('playm4u') || src.includes('vidplay') || src.includes('filemoon')) {
                                    if (!window.hasRedirectedToSource) {
                                        window.hasRedirectedToSource = true;
                                        window.location.href = src;
                                    }
                                }
                            } catch(e) {}
                        }
                    })();
                """.trimIndent()
                view?.evaluateJavascript(js, null)
            }
        }

        // 5. Spoof Desktop Client Hints Headers
        val headers = mapOf(
            "Referer" to "https://vidsrc.xyz/",
            "Origin" to "https://vidsrc.xyz/",
            "Sec-Ch-Ua" to "\"Chromium\";v=\"134\", \"Not:A-Brand\";v=\"24\", \"Google Chrome\";v=\"134\"",
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Upgrade-Insecure-Requests" to "1"
        )

        webView.loadUrl(embedUrl, headers)

        val result = withTimeoutOrNull(40000) {
            deferred.await()
        }

        webView.stopLoading()
        webView.destroy()
        result
    }

    suspend fun extractCleanPlayerUrl(embedUrl: String): String? = extractVideoUrl(embedUrl)
}
