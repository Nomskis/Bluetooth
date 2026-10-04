package io.github.nomskis.earshot.call

/**
 * Reads WebRTC's stats report (W3C webrtc-stats). Kept free of WebRTC types
 * so it can be unit tested: each entry is id -> (type, members).
 */
object CallStats {
    data class Entry(val type: String, val members: Map<String, Any?>)

    /** "wifi", "cellular", ... for the local side of the candidate pair in use. */
    fun selectedNetworkType(report: Map<String, Entry>): String? {
        val pair = selectedPair(report) ?: return null
        val localId = pair.members["localCandidateId"] as? String ?: return null
        return report[localId]?.members?.get("networkType") as? String
    }

    /** Round trip of the connection in use, seconds (RTCIceCandidatePairStats.currentRoundTripTime). */
    fun roundTripSeconds(report: Map<String, Entry>): Double? = number(selectedPair(report)?.members?.get("currentRoundTripTime"))

    /**
     * Cumulative jitter-buffer time and samples emitted for her audio
     * (RTCInboundRtpStreamStats.jitterBufferDelay / jitterBufferEmittedCount).
     */
    fun audioJitterBuffer(report: Map<String, Entry>): Pair<Double, Double>? {
        val inbound = report.values.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "audio" } ?: return null
        val delay = number(inbound.members["jitterBufferDelay"]) ?: return null
        val emitted = number(inbound.members["jitterBufferEmittedCount"]) ?: return null
        return delay to emitted
    }

    /** Cumulative (packetsReceived, packetsLost) for her audio. */
    fun audioPackets(report: Map<String, Entry>): Pair<Double, Double>? {
        val inbound = report.values.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "audio" } ?: return null
        val received = number(inbound.members["packetsReceived"]) ?: return null
        val lost = number(inbound.members["packetsLost"]) ?: return null
        return received to lost
    }

    /** WebRTC's estimate of what we can send, bits per second (RTCIceCandidatePairStats.availableOutgoingBitrate). */
    fun availableOutgoingBitrate(report: Map<String, Entry>): Double? =
        number(selectedPair(report)?.members?.get("availableOutgoingBitrate"))

    /** Cumulative bytes we've sent of [kind] ("audio" or "video"), payload and headers (RTCOutboundRtpStreamStats). */
    fun outboundBytes(report: Map<String, Entry>, kind: String): Double? {
        val streams = report.values.filter { it.type == "outbound-rtp" && it.members["kind"] == kind }
        if (streams.isEmpty()) return null
        return streams.sumOf { (number(it.members["bytesSent"]) ?: 0.0) + (number(it.members["headerBytesSent"]) ?: 0.0) }
    }

    /** True when the connection in use goes through a TURN relay (either end's candidate is "relay"). */
    fun relayed(report: Map<String, Entry>): Boolean? {
        val pair = selectedPair(report) ?: return null
        val types = listOf("localCandidateId", "remoteCandidateId")
            .mapNotNull { key -> report[pair.members[key] as? String]?.members?.get("candidateType") as? String }
        return if (types.isEmpty()) null else "relay" in types
    }

    /**
     * "direct", or "relay (udp)" / "relay (tcp)" / "relay (tls)" (how we reach our relay)
     * when the connection in use goes through one.
     */
    fun pathDescription(report: Map<String, Entry>): String? {
        val pair = selectedPair(report) ?: return null
        val local = report[pair.members["localCandidateId"] as? String]?.members ?: return null
        val remote = report[pair.members["remoteCandidateId"] as? String]?.members
        return when {
            local["candidateType"] == "relay" -> "relay (${local["relayProtocol"] as? String ?: "udp"})"
            remote?.get("candidateType") == "relay" -> "relay (theirs)"
            else -> "direct"
        }
    }

    /** Every working candidate pair with its network and ping counters, for [PathSteering]. */
    fun candidatePairs(report: Map<String, Entry>): List<PathSteering.CandidatePair> = report.mapNotNull { (id, entry) ->
        if (entry.type != "candidate-pair" || entry.members["state"] != "succeeded") return@mapNotNull null
        val local = report[entry.members["localCandidateId"] as? String] ?: return@mapNotNull null
        val network = local.members["networkType"] as? String ?: return@mapNotNull null
        val sent = number(entry.members["requestsSent"]) ?: return@mapNotNull null
        val received = number(entry.members["responsesReceived"]) ?: return@mapNotNull null
        PathSteering.CandidatePair(id, network, sent, received)
    }

    private fun number(value: Any?): Double? = (value as? Number)?.toDouble()

    internal fun selectedPair(report: Map<String, Entry>): Entry? {
        // Preferred: the transport names its selected pair.
        report.values.firstOrNull { it.type == "transport" }
            ?.members?.get("selectedCandidatePairId")?.let { id -> report[id as? String]?.let { return it } }
        // Fallback: the nominated, succeeded pair.
        return report.values.firstOrNull {
            it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true
        }
    }
}
