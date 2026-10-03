package io.github.nomskis.earshot.dsp

/**
 * Maps audio frame positions to System.nanoTime() using the (frame, time)
 * pairs Android reports in AudioTimestamp. A least-squares line smooths out
 * the jitter of individual timestamps.
 */
class TimeMap private constructor(
    private val frame0: Double,
    private val time0: Double,
    /** Nanoseconds per frame. */
    private val slope: Double,
) {
    fun toNanos(frame: Double): Double = time0 + (frame - frame0) * slope

    fun toFrame(nanos: Double): Double = frame0 + (nanos - time0) / slope

    /** The same clock for a buffer whose index 0 is frame [startFrame] of this one. */
    fun relativeTo(startFrame: Double): TimeMap = TimeMap(frame0 - startFrame, time0, slope)

    /** The effective sample rate the stream really ran at. */
    val measuredSampleRate: Double get() = 1e9 / slope

    companion object {
        /**
         * Fits a line through the points. Falls back to the nominal rate when
         * there are too few points or they don't span enough time.
         */
        fun fit(frames: List<Long>, nanos: List<Long>, nominalSampleRate: Int): TimeMap? {
            require(frames.size == nanos.size)
            if (frames.isEmpty()) return null
            val nominalSlope = 1e9 / nominalSampleRate
            val f0 = frames.average()
            val t0 = nanos.map { it.toDouble() }.average()
            if (frames.size < 3 || frames.max() - frames.min() < nominalSampleRate / 10) {
                return TimeMap(f0, t0, nominalSlope)
            }
            var num = 0.0
            var den = 0.0
            for (i in frames.indices) {
                val df = frames[i] - f0
                num += df * (nanos[i] - t0)
                den += df * df
            }
            val slope = num / den
            // A clock running more than 1% off nominal means bad timestamps; trust the nominal rate.
            val safeSlope = if (slope in nominalSlope * 0.99..nominalSlope * 1.01) slope else nominalSlope
            return TimeMap(f0, t0, safeSlope)
        }
    }
}
