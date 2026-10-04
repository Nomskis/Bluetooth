package io.github.nomskis.earshot.settings

/**
 * A call that was still running when the app stopped without hanging up:
 * the system killed it in the background, or it crashed. The home screen
 * offers to rejoin while it's recent.
 */
data class InterruptedCall(val room: String, val withVideo: Boolean, val aliveAtMillis: Long) {
    fun isRecent(nowMillis: Long): Boolean = nowMillis - aliveAtMillis in 0..REJOIN_WINDOW_MS

    companion object {
        /** The running call refreshes its mark this often. */
        const val ALIVE_EVERY_MS = 60_000L
        const val REJOIN_WINDOW_MS = 15 * 60_000L
    }
}
