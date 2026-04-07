package com.bongbee.apkurl

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress

class UnifiedUrlExtractor(private val context: Context) {
    
    companion object {
        private const val TAG = "UnifiedUrlExtractor"
    }
    
    // Observable state for extracted URLs
    private val _extractedUrls = MutableStateFlow<List<ExtractedUrl>>(emptyList())
    val extractedUrls: StateFlow<List<ExtractedUrl>> = _extractedUrls
    
    data class ExtractedUrl(
        val url: String,
        val type: UrlType,
        val timestamp: Long = System.currentTimeMillis(),
        val source: String? = null
    )
    
    enum class UrlType {
        STREAMING_MEDIA,    // .m3u8, .mp4, etc.
        CLOUDNESTRA_RCP,    // Cloudnestra RCP URL
        VIDSRC_EMBED,       // VidSrc embed URL
        TMDB_API,           // TMDB API endpoint
        DNS_QUERY,          // DNS lookup
        TLS_SNI,            // TLS Server Name Indication
        HTTP_REQUEST,       // Regular HTTP request
        OTHER
    }
    
    /**
     * Process a captured TCP packet and extract URLs
     */
    suspend fun processTcpPayload(payload: ByteArray, srcPort: Int, dstIp: ByteArray, isTls: Boolean, host: String?): List<ExtractedUrl> = 
        withContext(Dispatchers.IO) {
            val results = mutableListOf<ExtractedUrl>()
            
            try {
                // Extract URLs using existing logic
                val urls = if (isTls) {
                    UrlExtractor.extractUrls(payload)
                } else {
                    UrlExtractor.extractResponseUrls(payload, host)
                }
                
                val currentHost = host ?: InetAddress.getByAddress(dstIp).hostAddress ?: "Unknown"
                
                for (url in urls) {
                    val type = classifyUrl(url)
                    val extractedUrl = ExtractedUrl(
                        url = url,
                        type = type,
                        source = currentHost
                    )
                    results.add(extractedUrl)
                    
                    // Callbacks for specific types
                    when (type) {
                        UrlType.CLOUDNESTRA_RCP -> onCloudnestraUrlFound?.invoke(url)
                        UrlType.VIDSRC_EMBED -> onVidSrcUrlFound?.invoke(url)
                        UrlType.STREAMING_MEDIA -> onStreamingUrlFound?.invoke(url)
                        else -> {}
                    }
                }
                
                _extractedUrls.value = _extractedUrls.value + results
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing payload", e)
            }
            
            return@withContext results
        }
    
    // Callbacks for immediate action
    var onCloudnestraUrlFound: ((String) -> Unit)? = null
    var onVidSrcUrlFound: ((String) -> Unit)? = null
    var onStreamingUrlFound: ((String) -> Unit)? = null
    
    private fun classifyUrl(url: String): UrlType {
        val lowUrl = url.lowercase()
        return when {
            lowUrl.contains("cloudnestra.com/rcp/") -> UrlType.CLOUDNESTRA_RCP
            lowUrl.contains("vidsrc") && lowUrl.contains("/embed/") -> UrlType.VIDSRC_EMBED
            lowUrl.contains(".m3u8") || lowUrl.contains(".mp4") || lowUrl.contains(".ts") || 
            lowUrl.contains(".m4s") || lowUrl.contains(".mpd") -> UrlType.STREAMING_MEDIA
            lowUrl.contains("api.themoviedb.org") -> UrlType.TMDB_API
            lowUrl.startsWith("https://") || lowUrl.startsWith("http://") -> UrlType.HTTP_REQUEST
            else -> UrlType.OTHER
        }
    }
}
