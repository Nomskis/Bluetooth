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
