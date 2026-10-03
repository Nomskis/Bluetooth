package io.github.nomskis.earshot.dsp

import kotlin.math.abs

/** One chirp handed to the audio system. */
data class Emission(
    /** Frame index of the chirp's first sample in the playback stream. */
    val frame: Long,
    /** System.nanoTime() when the write containing that frame returned. */
    val handedOverAtNanos: Long,
)

/** Where and when one emission was heard by the microphone. */
data class Hit(
    val emission: Emission,
    val heardAtNanos: Double,
    /** When Android said the chirp would be played (from AudioTrack timestamps), if known. */
    val reportedPlayNanos: Double?,
    val score: Double,
) {
    val handoffToHeardMs: Double get() = (heardAtNanos - emission.handedOverAtNanos) / 1e6
    val reportedLatencyMs: Double? get() = reportedPlayNanos?.let { (it - emission.handedOverAtNanos) / 1e6 }
    val reportErrorMs: Double? get() = reportedPlayNanos?.let { (heardAtNanos - it) / 1e6 }
}

/** Everything one playback phase (phone speaker or earbuds) produced. */
class SonarPhase(
    val emissions: List<Emission>,
    /** Playback frame → presentation time, as Android reports it. */
    val trackMap: TimeMap?,
)

data class SonarSummary(
    /** Delay from handing audio to Android until it is heard, in ms (calibrated when possible). */
    val delayMs: Double,
    /** What Android itself estimates for the same path, in ms. */
    val reportedMs: Double?,
    /** Spread of the individual measurements, in ms. */
    val spreadMs: Double,
    val hits: Int,
    val attempts: Int,
    /** True when the phone-speaker pass corrected the microphone's timestamp error. */
    val calibrated: Boolean,
    /** Microphone timestamp error found by the calibration pass, in ms. */
    val micErrorMs: Double,
)

/**
 * Turns a recording plus the chirps that were played into delay numbers.
 *
 * Two passes make it self-calibrating. Chirps from the phone's own speaker
 * are timed accurately by Android, so any gap between "Android says it played
 * now" and "the mic heard it now" in that pass is the microphone's own
 * timestamp error. That error is then removed from the earbud pass.
 */
object SonarAnalysis {
    /** Longest delay the meter looks for; emissions must be spaced further apart than this. */
    const val MAX_DELAY_MS = 550.0

    /**
     * Finds each emission in the recording. A chirp can't be heard before it
     * was handed over, and the next chirp can't be heard before *it* was, so
     * each search window ends just after the next emission's hand-over. Keep
     * emissions further apart than any delay worth measuring.
     */
    fun detect(
        filter: MatchedFilter,
        recordMap: TimeMap,
        phase: SonarPhase,
        searchWindowMs: Double = MAX_DELAY_MS,
        earlyMarginMs: Double = 20.0,
    ): List<Hit> = phase.emissions.mapIndexedNotNull { index, emission ->
        val next = phase.emissions.getOrNull(index + 1)
        val windowEnd = minOf(
            emission.handedOverAtNanos + searchWindowMs * 1e6,
            next?.let { it.handedOverAtNanos + earlyMarginMs * 1e6 } ?: Double.MAX_VALUE,
        )
        val from = recordMap.toFrame(emission.handedOverAtNanos - earlyMarginMs * 1e6)
        val until = recordMap.toFrame(windowEnd)
        val peak = filter.findPeak(from.toInt(), until.toInt()) ?: return@mapIndexedNotNull null
        Hit(
            emission = emission,
            heardAtNanos = recordMap.toNanos(peak.position),
            reportedPlayNanos = phase.trackMap?.toNanos(emission.frame.toDouble()),
            score = peak.score,
        )
    }

    fun summarize(speakerHits: List<Hit>, earbudHits: List<Hit>, earbudAttempts: Int): SonarSummary? {
        if (earbudHits.isEmpty()) return null
        val speakerErrors = speakerHits.mapNotNull { it.reportErrorMs }
        val calibrated = speakerErrors.size >= 2
        val micError = if (calibrated) median(speakerErrors) else 0.0

        val delays = robust(earbudHits.map { it.handoffToHeardMs - micError })
        val reported = earbudHits.mapNotNull { it.reportedLatencyMs }.takeIf { it.isNotEmpty() }?.let(::median)
        return SonarSummary(
            delayMs = median(delays),
            reportedMs = reported,
            spreadMs = delays.max() - delays.min(),
            hits = earbudHits.size,
            attempts = earbudAttempts,
            calibrated = calibrated,
            micErrorMs = micError,
        )
    }

    /** Drops measurements far from the median (a missed chirp locking onto an echo, say). */
    private fun robust(values: List<Double>): List<Double> {
        val m = median(values)
        val kept = values.filter { abs(it - m) <= 25.0 }
        return kept.ifEmpty { values }
    }

    fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
