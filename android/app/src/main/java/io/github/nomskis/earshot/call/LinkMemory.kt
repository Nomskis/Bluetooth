package io.github.nomskis.earshot.call

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What a call with one person, from one kind of network, learned about the
 * connection, so the next call starts there instead of from scratch.
 *
 * Every call otherwise starts blind: WebRTC guesses 300 kbps and finds the
 * real figure over the first seconds, the voice starts as HD voice in 10 ms
 * packets, and on a weak international route it takes the first half
 * minute to settle on what works ([MediaBudget], [PacketTime]): squeezed
 * video and broken-up voice at the start of every call. Two people who call
 * each other keep calling over much the same route, so the call remembers,
 * per contact and our network type: a cautious figure for what we could
 * send, the audio packet length that held, and the voice level that fit.
 * The next call starts there and still adapts as usual, so a route that has
 * improved is found again within a minute. Kept two weeks.
 */
@Serializable
data class LinkMemory(
    /** The contact's inbox address. */
    val address: String,
    /** Our network then: "wifi" or "cellular" (the half of the route that's ours). */
    val network: String,
    /** A cautious figure for what we could send: the lower quartile of the estimate over the call's last minutes. */
    val sendEstimateBps: Int? = null,
    val packetMs: Int = 10,
    /** [MediaBudget.Level] name. */
    val voiceLevel: String = MediaBudget.Level.FULL.name,
    val atMillis: Long,
) {
    /** WebRTC's starting estimate for the next call: a little under what held, within sane bounds. */
    val startBitrateBps: Int?
        get() = sendEstimateBps?.let(::startBitrateFor)

    val packetStep: PacketTime.Step
        get() = PacketTime.Step.entries.firstOrNull { it.ms == packetMs } ?: PacketTime.Step.SHORT

    val level: MediaBudget.Level
        get() = MediaBudget.Level.entries.firstOrNull { it.name == voiceLevel } ?: MediaBudget.Level.FULL

    companion object {
        const val START_SHARE = 0.8
        const val MIN_START_BPS = 150_000
        /** WebRTC probes up from here in a second or two; starting higher would only risk a burst if the route got worse. */
        const val MAX_START_BPS = 1_000_000

        fun startBitrateFor(sendEstimateBps: Int): Int = (sendEstimateBps * START_SHARE).toInt().coerceIn(MIN_START_BPS, MAX_START_BPS)
    }
}

object LinkMemories {
    const val MAX = 50
    const val KEEP_MS = 14 * 24 * 60 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(LinkMemory.serializer())

    fun decode(text: String?): List<LinkMemory> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())

    fun encode(memories: List<LinkMemory>): String = json.encodeToString(serializer, memories)

    /** Replaces the memory for the same contact and network; newest first, stale ones dropped. */
    fun upsert(memories: List<LinkMemory>, memory: LinkMemory, nowMs: Long): List<LinkMemory> =
        (listOf(memory) + memories.filter { !(it.address == memory.address && it.network == memory.network) })
            .filter { nowMs - it.atMillis < KEEP_MS }
            .take(MAX)

    /** The last call's lessons for this contact from this kind of network, if recent. */
    fun find(memories: List<LinkMemory>, address: String?, network: String?, nowMs: Long): LinkMemory? {
        if (address == null || network == null) return null
        return memories.firstOrNull { it.address == address && it.network == network && nowMs - it.atMillis in 0 until KEEP_MS }
    }
}

/**
 * Collects a call's send estimates while it's connected and turns them into a
 * [LinkMemory] at the end. The lower quartile, over the last few minutes:
 * what the connection reliably carried, not its best moment.
 */
class LinkLearner(private val windowSamples: Int = 90) {
    private val estimates = ArrayDeque<Double>()

    fun addEstimate(bps: Double) {
        estimates.addLast(bps)
        while (estimates.size > windowSamples) estimates.removeFirst()
    }

    /** A cautious figure for what we could send, once there's enough to judge (half a minute of stats). */
    val sendEstimateBps: Int?
        get() {
            if (estimates.size < MIN_SAMPLES) return null
            val sorted = estimates.sorted()
            return sorted[(sorted.size - 1) / 4].toInt()
        }

    companion object {
        const val MIN_SAMPLES = 15
    }
}
