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
        // QR bit = 0 means query
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
}

object UrlExtractor {

    private val directUrlRegex = Regex("https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]+")

    // Known noise patterns to exclude (analytics, ads, telemetry, OS services)
    private val noisePatterns = listOf(
        "google-analytics", "googleads", "doubleclick", "facebook.com/tr",
        "app-measurement", "crashlytics", "firebaseinstallations",
        "firebaselogging", "googleapis.com/generate_204",
        "connectivitycheck", "gstatic.com/generate_204",
        "clients1.google.com", "clients2.google.com", "clients3.google.com",
        "play.googleapis.com", "android.clients.google.com"
    )

    /**
     * Extract ALL URLs from TCP payload — captures every http/https URL found.
     */
    fun extractUrls(payload: ByteArray): List<String> {
        if (payload.isEmpty()) return emptyList()

        val text = payload.toString(Charsets.ISO_8859_1)
        val results = LinkedHashSet<String>()

        // 1. All direct http/https URLs in payload
        directUrlRegex.findAll(text).forEach {
            val url = it.value.trim()
            if (!isNoise(url)) results.add(url)
        }

        // 2. Reconstruct full URL from HTTP request line + Host header
        val requestUrl = buildHttpRequestUrl(text)
        if (requestUrl != null && !isNoise(requestUrl)) {
            results.add(requestUrl)
        }

        // 3. TLS SNI — capture ALL HTTPS hostnames (not just "stream" ones)
        val tlsSniHost = parseTlsClientHelloSni(payload)
        if (!tlsSniHost.isNullOrBlank() && !isNoiseHost(tlsSniHost)) {
            results.add("https://$tlsSniHost")
        }

        return results.toList()
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
