package io.github.nomskis.earshot.signaling

import java.net.URI
import java.net.URLEncoder

/**
 * Turns what the user typed ("calls.example.com", "https://calls.example.com/")
 * into the URLs the app needs.
 */
object ServerUrls {

    /** Returns a normalized base URL like "https://calls.example.com", or null if unusable. */
    fun normalizeBase(input: String): String? {
        var text = input.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) {
            // Plain hosts default to https; LAN IPs and localhost default to http for development.
            val host = text.substringBefore('/').substringBefore(':')
            text = if (isLocalHost(host)) "http://$text" else "https://$text"
        }
        val uri = runCatching { URI(text.trimEnd('/')) }.getOrNull() ?: return null
        val scheme = when (uri.scheme?.lowercase()) {
            "https", "wss" -> "https"
            "http", "ws" -> "http"
            else -> return null
        }
        val host = uri.host ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/').removeSuffix("/ws")
        return "$scheme://$host$port$path"
    }

    fun webSocketUrl(base: String): String {
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return "$wsBase/ws"
    }

    fun inviteLink(base: String, room: String): String =
        "$base/r/${URLEncoder.encode(room, "UTF-8")}"

    fun isSecure(base: String): Boolean = base.startsWith("https://")

    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local")) return true
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 || parts[0] == 127 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31)
    }
}
