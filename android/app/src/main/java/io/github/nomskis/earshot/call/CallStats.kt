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

    /** Cumulative (concealedSamples, totalSamplesReceived) for her audio. */
    fun audioConcealment(report: Map<String, Entry>): Pair<Double, Double>? {
        val inbound = report.values.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "audio" } ?: return null
        val concealed = number(inbound.members["concealedSamples"]) ?: return null
        val total = number(inbound.members["totalSamplesReceived"]) ?: return null
        return concealed to total
    }

    /**
     * Share of our packets lost on the way to her, 0..1, from her side's last receiver
     * report (RTCRemoteInboundRtpStreamStats.fractionLost): audio, or video when there's
     * no report on the audio yet.
     */
    fun sendLossFraction(report: Map<String, Entry>): Double? {
        val remote = report.values.filter { it.type == "remote-inbound-rtp" }
        return listOf("audio", "video").firstNotNullOfOrNull { kind ->
            remote.firstOrNull { it.members["kind"] == kind }?.let { number(it.members["fractionLost"]) }
        }
    }

    /** Cumulative counters for her audio, for [PacketTime]; null until all are reported. */
    fun inboundAudioCounters(report: Map<String, Entry>): PacketTime.Counters? {
        val inbound = report.values.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "audio" } ?: return null
        return PacketTime.Counters(
            packetsReceived = number(inbound.members["packetsReceived"]) ?: return null,
            packetsLost = number(inbound.members["packetsLost"]) ?: return null,
            concealedSamples = number(inbound.members["concealedSamples"]) ?: return null,
            totalSamples = number(inbound.members["totalSamplesReceived"]) ?: return null,
        )
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

    /** Cumulative packets we've sent of [kind] ("audio" or "video"), resends included (RTCOutboundRtpStreamStats). */
    fun outboundPackets(report: Map<String, Entry>, kind: String): Double? {
        val streams = report.values.filter { it.type == "outbound-rtp" && it.members["kind"] == kind }
        if (streams.isEmpty()) return null
        return streams.sumOf { number(it.members["packetsSent"]) ?: 0.0 }
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

    /**
     * The quickest round trip, in seconds, of a working path relayed on both ends: what the
     * call would get with "Route through relay". WebRTC keeps checking such standby paths
     * now and then (every 25 s by default), so a direct call learns it too.
     */
    fun relayedRoundTripSeconds(report: Map<String, Entry>): Double? = report.values.mapNotNull { entry ->
        if (entry.type != "candidate-pair" || entry.members["state"] != "succeeded") return@mapNotNull null
        val local = report[entry.members["localCandidateId"] as? String]?.members ?: return@mapNotNull null
        val remote = report[entry.members["remoteCandidateId"] as? String]?.members ?: return@mapNotNull null
        if (local["candidateType"] != "relay" || remote["candidateType"] != "relay") return@mapNotNull null
        number(entry.members["currentRoundTripTime"])?.takeIf { it > 0 }
    }.minOrNull()

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
