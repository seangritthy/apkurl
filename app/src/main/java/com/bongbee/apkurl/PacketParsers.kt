package com.bongbee.apkurl

import kotlin.math.min

object PacketParsers {

    fun extractTcpPayload(packet: ByteArray, packetLength: Int): ByteArray? {
        if (packetLength < 20) {
            return null
        }

        val ipVersion = (packet[0].toInt() ushr 4) and 0x0F
        if (ipVersion != 4) {
            return null
        }

        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        if (ipHeaderLength < 20 || packetLength < ipHeaderLength + 20) {
            return null
        }

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 6) {
            return null
        }

        val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val tcpHeaderStart = ipHeaderLength
        val tcpHeaderLength = ((packet[tcpHeaderStart + 12].toInt() ushr 4) and 0x0F) * 4
        if (tcpHeaderLength < 20) {
            return null
        }

        val payloadStart = ipHeaderLength + tcpHeaderLength
        val payloadEnd = min(packetLength, totalLength)
        if (payloadStart >= payloadEnd || payloadStart < 0 || payloadEnd > packetLength) {
            return null
        }

        return packet.copyOfRange(payloadStart, payloadEnd)
    }
}

object UrlExtractor {

    private val directUrlRegex = Regex("https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]+")
    private val mediaHintRegex = Regex("(?i)(\\.m3u8|\\.mpd|\\.mp4|\\.m4s)(\\?|$)")

    fun extractUrls(payload: ByteArray): List<String> {
        if (payload.isEmpty()) {
            return emptyList()
        }

        val text = payload.toString(Charsets.ISO_8859_1)
        val results = LinkedHashSet<String>()

        directUrlRegex.findAll(text).forEach {
            val url = it.value.trim()
            if (mediaHintRegex.containsMatchIn(url)) {
                results.add(url)
            }
        }

        val requestUrl = buildHttpRequestUrl(text)
        if (requestUrl != null && mediaHintRegex.containsMatchIn(requestUrl)) {
            results.add(requestUrl)
        }

        val tlsSniHost = parseTlsClientHelloSni(payload)
        if (!tlsSniHost.isNullOrBlank() && looksLikeStreamHost(tlsSniHost)) {
            results.add("https://$tlsSniHost")
        }

        return results.toList()
    }

    private fun buildHttpRequestUrl(text: String): String? {
        val requestLine = text.lineSequence().firstOrNull()?.trim() ?: return null
        val requestParts = requestLine.split(' ')
        if (requestParts.size < 2) {
            return null
        }

        val method = requestParts[0]
        if (method !in setOf("GET", "POST", "HEAD")) {
            return null
        }

        val target = requestParts[1]
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return target
        }

        val hostMatch = Regex("(?im)^Host:\\s*([^\\r\\n]+)").find(text) ?: return null
        val host = hostMatch.groupValues[1].trim()
        if (!target.startsWith("/")) {
            return null
        }

        return "http://$host$target"
    }

    private fun looksLikeStreamHost(host: String): Boolean {
        val lower = host.lowercase()
        return lower.contains("stream") || lower.contains("hls") || lower.contains("dash") || lower.contains("media")
    }

    private fun parseTlsClientHelloSni(payload: ByteArray): String? {
        if (payload.size < 43) {
            return null
        }

        if ((payload[0].toInt() and 0xFF) != 0x16) {
            return null
        }

        if ((payload[5].toInt() and 0xFF) != 0x01) {
            return null
        }

        var index = 9
        index += 2
        index += 32
        if (index >= payload.size) {
            return null
        }

        val sessionIdLength = payload[index].toInt() and 0xFF
        index += 1 + sessionIdLength
        if (index + 2 > payload.size) {
            return null
        }

        val cipherSuitesLength = readU16(payload, index)
        index += 2 + cipherSuitesLength
        if (index >= payload.size) {
            return null
        }

        val compressionMethodsLength = payload[index].toInt() and 0xFF
        index += 1 + compressionMethodsLength
        if (index + 2 > payload.size) {
            return null
        }

        val extensionsLength = readU16(payload, index)
        index += 2
        val extensionEnd = (index + extensionsLength).coerceAtMost(payload.size)

        while (index + 4 <= extensionEnd) {
            val extensionType = readU16(payload, index)
            val extensionSize = readU16(payload, index + 2)
            index += 4

            if (index + extensionSize > extensionEnd) {
                break
            }

            if (extensionType == 0x0000 && extensionSize >= 5) {
                var sniIndex = index
                val serverNameListLength = readU16(payload, sniIndex)
                sniIndex += 2
                val sniEnd = (sniIndex + serverNameListLength).coerceAtMost(index + extensionSize)

                while (sniIndex + 3 <= sniEnd) {
                    val nameType = payload[sniIndex].toInt() and 0xFF
                    val nameLen = readU16(payload, sniIndex + 1)
                    sniIndex += 3

                    if (sniIndex + nameLen > sniEnd) {
                        break
                    }

                    if (nameType == 0x00) {
                        return payload.copyOfRange(sniIndex, sniIndex + nameLen)
                            .toString(Charsets.US_ASCII)
                    }

                    sniIndex += nameLen
                }
            }

            index += extensionSize
        }

        return null
    }

    private fun readU16(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) {
            return 0
        }
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }
}

