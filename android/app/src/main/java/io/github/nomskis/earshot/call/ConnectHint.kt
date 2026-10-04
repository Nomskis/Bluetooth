package io.github.nomskis.earshot.call

import io.github.nomskis.earshot.signaling.IceServerConfig

/**
 * What to tell someone whose call is stuck connecting. Same wording as the
 * web client (web/js/app.js).
 */
object ConnectHint {
    const val AFTER_MS = 15_000L

    fun forStuck(iceServers: List<IceServerConfig>): String =
        if (hasRelay(iceServers)) {
            "Taking a while. Check you're both online."
        } else {
            "Taking a while. This network may block calls, and the server has no relay."
        }

    fun hasRelay(iceServers: List<IceServerConfig>): Boolean =
        iceServers.any { server -> server.urls.any { it.startsWith("turn:") || it.startsWith("turns:") } }
}
