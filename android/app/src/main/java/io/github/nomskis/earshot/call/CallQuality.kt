package io.github.nomskis.earshot.call

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * How a call really went, from WebRTC's stats: the route, the delay, what was
 * lost and repaired, video freezes. Kept with the call in the history, and
 * copyable as text, so calls over a difficult connection can be understood and
 * tuned from what actually happened.
 */
@Serializable
data class CallQuality(
    /** "direct", or "relay (udp)" / "relay (tcp)" / "relay (tls)". */
    val path: String? = null,
    /** This phone's network: "wifi", "cellular"... */
    val network: String? = null,
    val rttMsAvg: Int? = null,
    val rttMsMax: Int? = null,
    /** Their audio packets lost on the way, before redundancy and resends repaired them. */
    val audioLossPercent: Double? = null,
    /** Share of their audio that had to be made up because it never arrived in time. */
    val concealedPercent: Double? = null,
    /** Resend requests for their audio. */
    val audioNacks: Int? = null,
    val jitterBufferMsAvg: Int? = null,
    val jitterBufferMsMax: Int? = null,
    val videoFreezes: Int? = null,
    val videoFreezeSeconds: Double? = null,
    /** Their video as it arrived: frames per second and height, averaged over the call. */
    val receivedFps: Double? = null,
    val receivedHeight: Int? = null,
    val videoCodec: String? = null,
    /** Share of the time our video was held back by the connection or by the phone's CPU. */
    val sendLimitedByBandwidthPercent: Int? = null,
    val sendLimitedByCpuPercent: Int? = null,
    /** WebRTC's estimate of what we could send, kbps. */
    val availableKbpsMin: Int? = null,
    val availableKbpsAvg: Int? = null,
    /** Share of the call our voice spent stepped down by [MediaBudget]. */
    val voiceReducedPercent: Int? = null,
    val reconnects: Int = 0,
) {
    enum class Verdict { GOOD, OKAY, POOR }

    /** A rough reading, mostly by what you'd have heard. */
    val verdict: Verdict
        get() {
            val concealed = concealedPercent ?: 0.0
            val freezes = videoFreezeSeconds ?: 0.0
            return when {
                concealed >= 3.0 || reconnects >= 3 || (rttMsAvg ?: 0) >= 400 -> Verdict.POOR
                concealed >= 1.0 || freezes >= 10.0 || reconnects >= 1 || (rttMsAvg ?: 0) >= 250 -> Verdict.OKAY
                else -> Verdict.GOOD
            }
        }

    /** Plain text to copy and send. */
    fun report(durationSeconds: Long? = null): String = buildString {
        appendLine("Earshot call report")
        durationSeconds?.let { appendLine("Length: ${it / 60} min ${it % 60} s") }
        appendLine("Quality: ${verdict.name.lowercase()}")
        appendLine("Route: ${path ?: "?"}, this phone on ${network ?: "?"}")
        appendLine("Round trip: ${rttMsAvg ?: "?"} ms average, ${rttMsMax ?: "?"} ms worst")
        appendLine(
            "Their audio: ${audioLossPercent.pct()} lost on the way, ${concealedPercent.pct()} made up" +
                (audioNacks?.let { ", $it resend requests" } ?: ""),
        )
        appendLine("Smoothing buffer: ${jitterBufferMsAvg ?: "?"} ms average, ${jitterBufferMsMax ?: "?"} ms most")
        if (receivedFps != null || videoFreezes != null) {
            appendLine(
                "Their video: ${receivedHeight?.let { "${it}p" } ?: "?"} at ${receivedFps?.let { "%.0f".format(it) } ?: "?"} fps, " +
                    "${videoFreezes ?: 0} freezes (${"%.1f".format(videoFreezeSeconds ?: 0.0)} s)",
            )
        }
        if (videoCodec != null || sendLimitedByBandwidthPercent != null) {
            appendLine(
                "Our video: ${videoCodec ?: "?"}, held back by the connection ${sendLimitedByBandwidthPercent ?: 0}% " +
                    "and by the phone ${sendLimitedByCpuPercent ?: 0}% of the time",
            )
        }
        appendLine("Could send: ${availableKbpsMin ?: "?"} kbps at worst, ${availableKbpsAvg ?: "?"} kbps average")
        voiceReducedPercent?.takeIf { it > 0 }?.let { appendLine("Our voice was lighter to fit the connection $it% of the time") }
        append("Reconnects: $reconnects")
    }

    private fun Double?.pct(): String = this?.let { "%.1f%%".format(it) } ?: "?"
}

/**
 * Builds a [CallQuality] from the stats reports of a call, across reconnects
 * (each new connection starts its counters from zero).
 */
class CallQualityTracker {
    /** Cumulative counters of one connection's streams. */
    private data class Counters(
        val audioReceived: Double = 0.0,
        val audioLost: Double = 0.0,
        val samplesReceived: Double = 0.0,
        val samplesConcealed: Double = 0.0,
        val audioNacks: Double = 0.0,
        val freezes: Double = 0.0,
        val freezeSeconds: Double = 0.0,
        val limitBandwidth: Double = 0.0,
        val limitCpu: Double = 0.0,
        val limitTotal: Double = 0.0,
    ) {
        operator fun plus(o: Counters) = Counters(
            audioReceived + o.audioReceived, audioLost + o.audioLost, samplesReceived + o.samplesReceived,
            samplesConcealed + o.samplesConcealed, audioNacks + o.audioNacks, freezes + o.freezes,
            freezeSeconds + o.freezeSeconds, limitBandwidth + o.limitBandwidth, limitCpu + o.limitCpu, limitTotal + o.limitTotal,
        )
    }

    private var finished = Counters()
    private var current = Counters()
    private var sawNack = false

    private var rttSum = 0.0
    private var rttCount = 0
    private var rttMax = 0.0
    private var jitterSum = 0.0
    private var jitterCount = 0
    private var jitterMax = 0.0
    private var lastJitter: Pair<Double, Double>? = null
    private var availableSum = 0.0
    private var availableCount = 0
    private var availableMin = Double.MAX_VALUE
    private var fpsSum = 0.0
    private var fpsCount = 0
    private var heightSum = 0.0
    private var path: String? = null
    private var network: String? = null
    private var codec: String? = null
    private var samples = 0
    private var reducedSamples = 0
    private var reconnects = 0

    /** A new connection (after a reconnect): its counters start again from zero. */
    fun newConnection() {
        finished += current
        current = Counters()
        lastJitter = null
    }

    fun reconnected() {
        reconnects++
    }

    /** One stats interval. [voiceReduced]: our voice was stepped down during it. */
    fun update(report: Map<String, CallStats.Entry>, voiceReduced: Boolean = false) {
        samples++
        if (voiceReduced) reducedSamples++
        CallStats.roundTripSeconds(report)?.let { s ->
            val ms = s * 1000
            rttSum += ms
            rttCount++
            rttMax = maxOf(rttMax, ms)
        }
        CallStats.availableOutgoingBitrate(report)?.let { bps ->
            availableSum += bps
            availableCount++
            availableMin = minOf(availableMin, bps)
        }
        CallStats.selectedNetworkType(report)?.let { network = it }
        CallStats.pathDescription(report)?.let { path = it }

        val audio = report.values.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "audio" }
        val video = report.values.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" }
        val sending = report.values.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] == "video" }

        // Smoothing buffer over the last interval, as the delay readout does.
        val delay = num(audio, "jitterBufferDelay")
        val emitted = num(audio, "jitterBufferEmittedCount")
        if (delay != null && emitted != null) {
            lastJitter?.let { (d0, e0) ->
                if (emitted > e0 && delay >= d0) {
                    val ms = (delay - d0) / (emitted - e0) * 1000
                    jitterSum += ms
                    jitterCount++
                    jitterMax = maxOf(jitterMax, ms)
                }
            }
            lastJitter = delay to emitted
        }

        num(video, "framesPerSecond")?.let { fps ->
            fpsSum += fps
            fpsCount++
            heightSum += num(video, "frameHeight") ?: 0.0
        }
        sending?.members?.get("codecId")?.let { id -> (report[id as? String]?.members?.get("mimeType") as? String)?.let { codec = it.substringAfter('/') } }

        @Suppress("UNCHECKED_CAST")
        val limits = sending?.members?.get("qualityLimitationDurations") as? Map<String, Any?>
        if (audio?.members?.containsKey("nackCount") == true) sawNack = true
        current = Counters(
            audioReceived = num(audio, "packetsReceived") ?: current.audioReceived,
            audioLost = num(audio, "packetsLost") ?: current.audioLost,
            samplesReceived = num(audio, "totalSamplesReceived") ?: current.samplesReceived,
            samplesConcealed = num(audio, "concealedSamples") ?: current.samplesConcealed,
            audioNacks = num(audio, "nackCount") ?: current.audioNacks,
            freezes = num(video, "freezeCount") ?: current.freezes,
            freezeSeconds = num(video, "totalFreezesDuration") ?: current.freezeSeconds,
            limitBandwidth = limits?.let { (it["bandwidth"] as? Number)?.toDouble() } ?: current.limitBandwidth,
            limitCpu = limits?.let { (it["cpu"] as? Number)?.toDouble() } ?: current.limitCpu,
            limitTotal = limits?.values?.sumOf { (it as? Number)?.toDouble() ?: 0.0 } ?: current.limitTotal,
        )
    }

    fun summary(): CallQuality {
        val total = finished + current
        val audioPackets = total.audioReceived + total.audioLost
        return CallQuality(
            path = path,
            network = network,
            rttMsAvg = avg(rttSum, rttCount),
            rttMsMax = rttMax.takeIf { rttCount > 0 }?.roundToInt(),
            audioLossPercent = (total.audioLost / audioPackets * 100).takeIf { audioPackets > 0 }?.round1(),
            concealedPercent = (total.samplesConcealed / total.samplesReceived * 100).takeIf { total.samplesReceived > 0 }?.round1(),
            audioNacks = total.audioNacks.toInt().takeIf { sawNack },
            jitterBufferMsAvg = avg(jitterSum, jitterCount),
            jitterBufferMsMax = jitterMax.takeIf { jitterCount > 0 }?.roundToInt(),
            videoFreezes = total.freezes.toInt().takeIf { fpsCount > 0 },
            videoFreezeSeconds = total.freezeSeconds.takeIf { fpsCount > 0 }?.round1(),
            receivedFps = (fpsSum / fpsCount).takeIf { fpsCount > 0 }?.round1(),
            receivedHeight = (heightSum / fpsCount).takeIf { fpsCount > 0 }?.roundToInt(),
            videoCodec = codec,
            sendLimitedByBandwidthPercent = (total.limitBandwidth / total.limitTotal * 100).takeIf { total.limitTotal > 0 }?.roundToInt(),
            sendLimitedByCpuPercent = (total.limitCpu / total.limitTotal * 100).takeIf { total.limitTotal > 0 }?.roundToInt(),
            availableKbpsMin = (availableMin / 1000).takeIf { availableCount > 0 }?.roundToInt(),
            availableKbpsAvg = avg(availableSum / 1000, availableCount),
            voiceReducedPercent = if (samples > 0) reducedSamples * 100 / samples else null,
            reconnects = reconnects,
        )
    }

    private fun num(entry: CallStats.Entry?, key: String): Double? = (entry?.members?.get(key) as? Number)?.toDouble()
    private fun avg(sum: Double, count: Int): Int? = if (count > 0) (sum / count).roundToInt() else null
    private fun Double.round1(): Double = (this * 10).roundToInt() / 10.0
}
