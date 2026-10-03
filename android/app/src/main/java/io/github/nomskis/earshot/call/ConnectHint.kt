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
            "This is taking a while. Check that both of you are online; switching one side between Wi-Fi and mobile data can help."
        } else {
            "This is taking a while. Some networks (mobile data, gym or office Wi-Fi) block direct calls, " +
                "and this server has no TURN relay to get around that. Try both on home Wi-Fi, or add TURN to the server."
        }

    fun hasRelay(iceServers: List<IceServerConfig>): Boolean =
        iceServers.any { server -> server.urls.any { it.startsWith("turn:") || it.startsWith("turns:") } }
}
