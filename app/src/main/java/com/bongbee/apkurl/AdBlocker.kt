package com.bongbee.apkurl

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object AdBlocker {
    private val adDomains = setOf(
        "google-analytics.com",
        "googletagmanager.com",
        "doubleclick.net",
        "googleads.g.doubleclick.net",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "adnxs.com",
        "outbrain.com",
        "taboola.com",
        "popads.net",
        "propellerads.com"
    )

    fun isAd(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return adDomains.any { lowerUrl.contains(it) } || 
               lowerUrl.contains("/ads/") || 
               lowerUrl.contains("ads.js") ||
               lowerUrl.contains("telemetry")
    }

    fun createEmptyResource(): WebResourceResponse {
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
    }
}
