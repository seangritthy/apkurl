package com.bongbee.apkurl

import kotlin.math.min

object PacketParsers {

    fun extractTcpPayload(packet: ByteArray, packetLength: Int): ByteArray? {
        if (packetLength < 20) return null
        val ipVersion = (packet[0].toInt() ushr 4) and 0x0F
        if (ipVersion != 4) return null
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        if (ipHeaderLength < 20 || packetLength < ipHeaderLength + 20) return null
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 6) return null
        val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val tcpHeaderStart = ipHeaderLength
        val tcpHeaderLength = ((packet[tcpHeaderStart + 12].toInt() ushr 4) and 0x0F) * 4
        if (tcpHeaderLength < 20) return null
        val payloadStart = ipHeaderLength + tcpHeaderLength
        val payloadEnd = min(packetLength, totalLength)
        if (payloadStart >= payloadEnd || payloadStart < 0 || payloadEnd > packetLength) return null
        return packet.copyOfRange(payloadStart, payloadEnd)
    }

    /**
     * Extract DNS query name from a UDP payload (standard DNS over port 53).
     * Returns the queried hostname or null.
     */
    fun extractDnsQueryName(udpPayload: ByteArray): String? {
        if (udpPayload.size < 12) return null
        val flags = ((udpPayload[2].toInt() and 0xFF) shl 8) or (udpPayload[3].toInt() and 0xFF)
        if (flags and 0x8000 != 0) return null // response, not query
        val qdCount = ((udpPayload[4].toInt() and 0xFF) shl 8) or (udpPayload[5].toInt() and 0xFF)
        if (qdCount < 1) return null

        var idx = 12
        val sb = StringBuilder()
        while (idx < udpPayload.size) {
            val labelLen = udpPayload[idx].toInt() and 0xFF
            if (labelLen == 0) break
            if (idx + 1 + labelLen > udpPayload.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(udpPayload, idx + 1, labelLen, Charsets.US_ASCII))
            idx += 1 + labelLen
        }
        val name = sb.toString()
        return if (name.isNotBlank() && name.contains('.')) name else null
    }

    /**
     * Parse DNS response to extract hostname → IP address mappings (A records).
     * Returns (queriedHostname, list-of-IPs), or null on failure.
     */
    fun parseDnsResponse(udpPayload: ByteArray): Pair<String, List<String>>? {
        if (udpPayload.size < 12) return null
        val flags = ((udpPayload[2].toInt() and 0xFF) shl 8) or (udpPayload[3].toInt() and 0xFF)
        if (flags and 0x8000 == 0) return null // not a response
        val qdCount = ((udpPayload[4].toInt() and 0xFF) shl 8) or (udpPayload[5].toInt() and 0xFF)
        val anCount = ((udpPayload[6].toInt() and 0xFF) shl 8) or (udpPayload[7].toInt() and 0xFF)
        if (qdCount < 1 || anCount < 1) return null

        // Parse question section to get queried hostname
        var idx = 12
        val hostname = StringBuilder()
        while (idx < udpPayload.size) {
            val labelLen = udpPayload[idx].toInt() and 0xFF
            if (labelLen == 0) { idx++; break }
            if (labelLen and 0xC0 == 0xC0) { idx += 2; break } // compression pointer
            if (idx + 1 + labelLen > udpPayload.size) return null
            if (hostname.isNotEmpty()) hostname.append('.')
            hostname.append(String(udpPayload, idx + 1, labelLen, Charsets.US_ASCII))
            idx += 1 + labelLen
        }
        idx += 4 // skip QTYPE + QCLASS

        val ips = mutableListOf<String>()
        for (i in 0 until anCount) {
            if (idx >= udpPayload.size) break
            // Skip name field (may be pointer or labels)
            if (idx + 1 < udpPayload.size && udpPayload[idx].toInt() and 0xC0 == 0xC0) {
                idx += 2
            } else {
                while (idx < udpPayload.size) {
                    val l = udpPayload[idx].toInt() and 0xFF
                    if (l == 0) { idx++; break }
                    if (l and 0xC0 == 0xC0) { idx += 2; break }
                    idx += 1 + l
                }
            }
            if (idx + 10 > udpPayload.size) break
            val rType = ((udpPayload[idx].toInt() and 0xFF) shl 8) or (udpPayload[idx + 1].toInt() and 0xFF)
            val rdLength = ((udpPayload[idx + 8].toInt() and 0xFF) shl 8) or (udpPayload[idx + 9].toInt() and 0xFF)
            idx += 10
            if (idx + rdLength > udpPayload.size) break
            if (rType == 1 && rdLength == 4) { // A record
                ips.add("${udpPayload[idx].toInt() and 0xFF}.${udpPayload[idx + 1].toInt() and 0xFF}.${udpPayload[idx + 2].toInt() and 0xFF}.${udpPayload[idx + 3].toInt() and 0xFF}")
            }
            idx += rdLength
        }

        val host = hostname.toString()
        return if (host.isNotBlank() && host.contains('.') && ips.isNotEmpty()) Pair(host, ips) else null
    }
}

object UrlExtractor {

    private val directUrlRegex = Regex("https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]+")

    // Streaming media file extensions
    private val streamingMediaRegex = Regex(
        """https?://[^\s"'<>\x00-\x1F]+\.(m3u8|m3u|mpd|ts|m4s|mp4|mp3|aac|flv|m4a|m4v|webm|f4v|f4m|mov|avi|mkv|ismv|isma|key|vtt|srt)(\?[^\s"'<>\x00-\x1F]*)?""",
        RegexOption.IGNORE_CASE
    )

    // Known noise patterns to exclude (analytics, ads, telemetry, OS services)
    private val noisePatterns = listOf(
        "google-analytics", "googleads", "doubleclick", "facebook.com/tr",
        "app-measurement", "crashlytics", "firebaseinstallations",
        "firebaselogging", "googleapis.com/generate_204",
        "connectivitycheck", "gstatic.com/generate_204",
        "clients1.google.com", "clients2.google.com", "clients3.google.com",
        "play.googleapis.com", "android.clients.google.com"
    )

    // ── TLS record detection ─────────────────────────────────────────────────

    /**
     * Returns true if the payload starts with a TLS record header.
     * TLS types: 0x14=ChangeCipherSpec, 0x15=Alert, 0x16=Handshake, 0x17=ApplicationData
     * Followed by version 0x03 0x00-0x04.
     * Running URL regex on encrypted TLS data produces garbage like "https://H".
     */
    private fun isTlsRecord(payload: ByteArray): Boolean {
        if (payload.size < 5) return false
        val contentType = payload[0].toInt() and 0xFF
        if (contentType !in 0x14..0x17) return false
        val major = payload[1].toInt() and 0xFF
        val minor = payload[2].toInt() and 0xFF
        return major == 0x03 && minor in 0x00..0x04
    }

    // ── URL domain validation ────────────────────────────────────────────────

    /**
     * Validates that a URL has a plausible domain — rejects garbage like "https://H".
     * Domain must contain at least one dot, TLD ≥ 2 chars, labels ≤ 63 chars, no
     * control characters, and only standard hostname characters.
     */
    private fun hasValidDomain(url: String): Boolean {
        val withoutScheme = url.removePrefix("https://").removePrefix("http://")
        val authority = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPart = authority.substringBefore(':') // strip port
        if (!hostPart.contains('.')) return false
        val labels = hostPart.split('.')
        if (labels.size < 2) return false
        val tld = labels.last()
        if (tld.length < 2 || tld.isEmpty()) return false
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return false
            if (label.any { c ->
                    c !in 'a'..'z' && c !in 'A'..'Z' && c !in '0'..'9' && c != '-'
                }) return false
        }
        return true
    }

    /**
     * Extract ALL URLs from outbound TCP payload (app → server).
     * Handles HTTP request URLs, TLS SNI hostnames, and direct URL patterns.
     */
    fun extractUrls(payload: ByteArray): List<String> {
        if (payload.isEmpty()) return emptyList()

        val results = LinkedHashSet<String>()

        // ── TLS SNI — always try, even on TLS records (SNI is in plaintext handshake)
        val tlsSniHost = parseTlsClientHelloSni(payload)
        if (!tlsSniHost.isNullOrBlank()) {
            results.add("https://$tlsSniHost")
        }

        // 2. Skip regex-based scanning on TLS records (encrypted = random garbage matches)
        if (isTlsRecord(payload)) return results.toList()

        val text = payload.toString(Charsets.ISO_8859_1)

        // 3. All direct http/https URLs in payload
        directUrlRegex.findAll(text).forEach {
            val url = cleanUrl(it.value)
            if (hasValidDomain(url)) results.add(url)
        }

        // 4. Reconstruct full URL from HTTP request line + Host header
        val requestUrl = buildHttpRequestUrl(text)
        if (requestUrl != null && hasValidDomain(requestUrl)) {
            results.add(requestUrl)
        }

        return results.toList()
    }

    /**
     * Extract URLs from server response data (server → app) on non-TLS connections.
     * Parses HTTP response headers, M3U8/HLS playlists, JSON values, and direct URLs.
     * @param host optional hostname for resolving relative URLs
     */
    fun extractResponseUrls(payload: ByteArray, host: String?): List<String> {
        if (payload.isEmpty()) return emptyList()

        // Skip if this looks like TLS data (shouldn't happen since caller checks isTls,
        // but guard against edge cases / binary-heavy non-TLS responses)
        if (isTlsRecord(payload)) return emptyList()

        val text = payload.toString(Charsets.ISO_8859_1)
        val results = LinkedHashSet<String>()

        // 1. All direct http/https URLs in payload
        directUrlRegex.findAll(text).forEach {
            val url = cleanUrl(it.value)
            if (hasValidDomain(url)) results.add(url)
        }

        // 2. Streaming media URLs (with known extensions)
        streamingMediaRegex.findAll(text).forEach {
            val url = cleanUrl(it.value)
            if (hasValidDomain(url)) results.add(url)
        }

        // 3. HTTP redirect Location header
        extractHeader(text, "Location")?.let { loc ->
            val resolved = resolveUrl(loc.trim(), host)
            if (resolved != null && hasValidDomain(resolved)) results.add(resolved)
        }

        // 4. Content-Location header
        extractHeader(text, "Content-Location")?.let { loc ->
            val resolved = resolveUrl(loc.trim(), host)
            if (resolved != null && hasValidDomain(resolved)) results.add(resolved)
        }

        // 5. M3U8/HLS playlist lines
        if (text.contains("#EXTM3U") || text.contains("#EXTINF") || text.contains("#EXT-X-")) {
            extractM3u8Urls(text, host).forEach {
                if (hasValidDomain(it)) results.add(it)
            }
        }

        // 6. MPD/DASH manifest URLs
        if (text.contains("<MPD") || text.contains("<BaseURL>") || text.contains("<SegmentTemplate")) {
            extractMpdUrls(text, host).forEach {
                if (hasValidDomain(it)) results.add(it)
            }
        }

        // 7. JSON string values that look like URLs (for API responses)
        extractJsonUrls(text).forEach {
            if (hasValidDomain(it)) results.add(it)
        }

        return results.toList()
    }

    /**
     * Extract Host header value from an HTTP request payload.
     */
    fun extractHttpHost(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val text = payload.toString(Charsets.ISO_8859_1)
        return extractHeader(text, "Host")?.trim()
    }

    // ── M3U8/HLS playlist parsing ────────────────────────────────────────────

    private fun extractM3u8Urls(text: String, host: String?): List<String> {
        val urls = mutableListOf<String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // Extract URI= from EXT tags (e.g., #EXT-X-MAP:URI="init.mp4")
            if (trimmed.startsWith("#")) {
                val uriMatch = Regex("""URI="([^"]+)"""").find(trimmed)
                if (uriMatch != null) {
                    resolveUrl(uriMatch.groupValues[1], host)?.let { urls.add(it) }
                }
                // #EXT-X-STREAM-INF, #EXT-X-MEDIA can have URLs on the next line
                continue
            }

            // Non-comment, non-empty lines are media segment URLs
            resolveUrl(trimmed, host)?.let { urls.add(it) }
        }
        return urls
    }

    // ── MPD/DASH manifest parsing ────────────────────────────────────────────

    private fun extractMpdUrls(text: String, host: String?): List<String> {
        val urls = mutableListOf<String>()
        // Extract BaseURL content
        Regex("""<BaseURL[^>]*>([^<]+)</BaseURL>""", RegexOption.IGNORE_CASE).findAll(text).forEach {
            resolveUrl(it.groupValues[1].trim(), host)?.let { u -> urls.add(u) }
        }
        // Extract media/init attributes from SegmentTemplate
        Regex("""(?:media|initialization|init)\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(text).forEach {
            val v = it.groupValues[1].trim()
            if (v.contains("/") || v.contains(".")) {
                resolveUrl(v, host)?.let { u -> urls.add(u) }
            }
        }
        return urls
    }

    // ── JSON URL extraction ──────────────────────────────────────────────────

    private fun extractJsonUrls(text: String): List<String> {
        val urls = mutableListOf<String>()
        // Match JSON string values containing URLs: "key": "https://..."
        Regex(""""[^"]*":\s*"(https?://[^"]+)"""").findAll(text).forEach {
            val url = cleanUrl(it.groupValues[1])
            // Unescape JSON forward slashes
            val unescaped = url.replace("\\/", "/")
            if (looksLikeStreamingUrl(unescaped)) urls.add(unescaped)
        }
        // Also match URLs in JSON arrays: ["https://..."]
        Regex("""\[\s*"(https?://[^"]+)""").findAll(text).forEach {
            val url = cleanUrl(it.groupValues[1]).replace("\\/", "/")
            if (looksLikeStreamingUrl(url)) urls.add(url)
        }
        return urls
    }


    // ── Helper methods ───────────────────────────────────────────────────────

    private fun extractHeader(text: String, name: String): String? {
        val match = Regex("(?im)^${Regex.escape(name)}:\\s*([^\r\n]+)").find(text)
        return match?.groupValues?.get(1)
    }

    /**
     * Resolve a potentially relative URL to an absolute URL.
     */
    fun resolveUrl(relative: String, host: String?): String? {
        val trimmed = relative.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return cleanUrl(trimmed)
        if (host.isNullOrBlank()) return null
        val cleanHost = host.replace(Regex(":(80|443)$"), "")
        // Determine scheme: assume https unless host explicitly uses port 80
        val scheme = if (host.endsWith(":80")) "http" else "https"
        return if (trimmed.startsWith("/")) {
            cleanUrl("$scheme://$cleanHost$trimmed")
        } else {
            cleanUrl("$scheme://$cleanHost/$trimmed")
        }
    }

    /** Does the URL look like a streaming/media URL? */
    private fun looksLikeStreamingUrl(url: String): Boolean {
        val lower = url.lowercase()
        val mediaExts = listOf(
            ".m3u8", ".m3u", ".mpd", ".ts", ".m4s", ".mp4", ".mp3", ".aac",
            ".flv", ".m4a", ".m4v", ".webm", ".f4v", ".f4m", ".mov", ".avi",
            ".mkv", ".ismv", ".isma", ".key", ".vtt", ".srt", ".wav", ".ogg"
        )
        val mediaKeywords = listOf(
            "/video/", "/audio/", "/stream", "/live/", "/hls/", "/dash/",
            "/media/", "/content/", "/play/", "/vod/", "/manifest",
            "playlist", "master.m3u8", "index.m3u8", "chunklist",
            "/seg-", "/segment", "/chunk-", "/frag-", "/init-",
            "videoplayback", "googlevideo.com", ".akamaized.net",
            ".cloudfront.net", ".cdn.", "cdn-", "-cdn."
        )
        return mediaExts.any { lower.endsWith(it) || lower.contains("$it?") || lower.contains("$it&") }
                || mediaKeywords.any { lower.contains(it) }
    }

    private fun cleanUrl(url: String): String {
        return url.trim().trimEnd(')', ']', '}', '>', '"', '\'', ',', ';')
    }

    private fun isNoise(url: String): Boolean {
        val lower = url.lowercase()
        return noisePatterns.any { lower.contains(it) }
    }

    private fun isNoiseHost(host: String): Boolean {
        val lower = host.lowercase()
        return noisePatterns.any { lower.contains(it) }
    }

    private fun buildHttpRequestUrl(text: String): String? {
        val requestLine = text.lineSequence().firstOrNull()?.trim() ?: return null
        val requestParts = requestLine.split(' ')
        if (requestParts.size < 2) return null
        val method = requestParts[0]
        if (method !in setOf("GET", "POST", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS")) return null
        val target = requestParts[1]
        if (target.startsWith("http://") || target.startsWith("https://")) return target
        val hostMatch = Regex("(?im)^Host:\\s*([^\\r\\n]+)").find(text) ?: return null
        val host = hostMatch.groupValues[1].trim()
        if (!target.startsWith("/")) return null
        return "http://$host$target"
    }

    fun parseTlsClientHelloSni(payload: ByteArray): String? {
        if (payload.size < 43) return null
        if ((payload[0].toInt() and 0xFF) != 0x16) return null
        if ((payload[5].toInt() and 0xFF) != 0x01) return null

        var index = 43
        if (index >= payload.size) return null

        val sessionIdLength = payload[index].toInt() and 0xFF
        index += 1 + sessionIdLength
        if (index + 2 > payload.size) return null

        val cipherSuitesLength = readU16(payload, index)
        index += 2 + cipherSuitesLength
        if (index >= payload.size) return null

        val compressionMethodsLength = payload[index].toInt() and 0xFF
        index += 1 + compressionMethodsLength
        if (index + 2 > payload.size) return null

        val extensionsLength = readU16(payload, index)
        index += 2
        val extensionEnd = (index + extensionsLength).coerceAtMost(payload.size)

        while (index + 4 <= extensionEnd) {
            val extensionType = readU16(payload, index)
            val extensionSize = readU16(payload, index + 2)
            index += 4
            if (index + extensionSize > extensionEnd) break

            if (extensionType == 0x0000 && extensionSize >= 5) {
                var sniIndex = index
                val serverNameListLength = readU16(payload, sniIndex)
                sniIndex += 2
                val sniEnd = (sniIndex + serverNameListLength).coerceAtMost(index + extensionSize)
                while (sniIndex + 3 <= sniEnd) {
                    val nameType = payload[sniIndex].toInt() and 0xFF
                    val nameLen = readU16(payload, sniIndex + 1)
                    sniIndex += 3
                    if (sniIndex + nameLen > sniEnd) break
                    if (nameType == 0x00) {
                        return payload.copyOfRange(sniIndex, sniIndex + nameLen).toString(Charsets.US_ASCII)
                    }
                    sniIndex += nameLen
                }
            }
            index += extensionSize
        }
        return null
    }

    private fun readU16(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }
}
