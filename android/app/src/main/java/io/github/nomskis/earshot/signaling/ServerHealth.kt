package io.github.nomskis.earshot.signaling

/**
 * Reads the server's /healthz answer and says what it means for calls: how
 * far away the server is (every call setup and reconnect makes round trips to
 * it) and whether calls can fall back to a relay.
 */
object ServerHealth {
    /** Further than this and a server nearer both callers would make calls start noticeably faster. */
    const val FAR_MS = 120L

    data class Answer(val ok: Boolean, val relay: Boolean?)

    /** `{"status":"ok","relay":true}`; servers from before relays were reported leave [Answer.relay] null. */
    fun parse(body: String): Answer {
        val ok = Regex("\"status\"\\s*:\\s*\"ok\"").containsMatchIn(body)
        val relay = Regex("\"relay\"\\s*:\\s*(true|false)").find(body)?.groupValues?.get(1)?.toBooleanStrict()
        return Answer(ok, relay)
    }

    /** Plain notes for Settings, after "Server is reachable". */
    fun notes(roundTripMs: Long?, relay: Boolean?): List<String> = buildList {
        roundTripMs?.let { ms ->
            add(
                if (ms >= FAR_MS) {
                    "$ms ms away. A server nearer you both would be faster."
                } else {
                    "$ms ms away."
                },
            )
        }
        when (relay) {
            true -> add("Relay ready")
            false -> add("No relay: some networks won't connect")
            null -> Unit
        }
    }
}
