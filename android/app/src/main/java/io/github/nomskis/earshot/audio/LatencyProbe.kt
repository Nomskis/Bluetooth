package io.github.nomskis.earshot.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
import io.github.nomskis.earshot.dsp.TimeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asks Android how long newly written audio takes to play, by playing half a
 * second of silence through the call's audio path and reading the playback
 * timestamps. Over A2DP those include the earbuds' own delay report, so this
 * is Android's best belief of app-to-ear latency (the sonar meter measures
 * the truth; this needs no setup and makes no sound).
 */
object LatencyProbe {
    private const val RATE = 48_000
    private const val CHUNK = 480 // 10 ms
    private const val DURATION_MS = 700
    private const val SKIP_MS = 250 // let the stream start before trusting timestamps

    suspend fun estimateMs(attributes: AudioAttributes): Double? = withContext(Dispatchers.IO) {
        runCatching { probe(attributes) }.onFailure { Log.w("EarshotProbe", "Latency probe failed", it) }.getOrNull()
    }

    private fun probe(attributes: AudioAttributes): Double? {
        val minBuffer = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(minBuffer)
            .build()
        try {
            val silence = ShortArray(CHUNK)
            val timestamp = AudioTimestamp()
            val frames = mutableListOf<Long>()
            val nanos = mutableListOf<Long>()
            val writes = mutableListOf<Pair<Long, Long>>() // (frames written so far, wall time)
            track.play()
            var written = 0L
            val total = RATE * DURATION_MS / 1000
            while (written < total) {
                var n = 0
                while (n < CHUNK) {
                    val r = track.write(silence, n, CHUNK - n)
                    if (r < 0) return null
                    n += r
                }
                written += CHUNK
                val now = System.nanoTime()
                if (written > RATE * SKIP_MS / 1000) {
                    writes += written to now
                    if (track.getTimestamp(timestamp) && timestamp.framePosition > 0) {
                        frames += timestamp.framePosition
                        nanos += timestamp.nanoTime
                    }
                }
            }
            track.stop()
            if (frames.size < 3) return null
            val map = TimeMap.fit(frames, nanos, RATE) ?: return null
            return latencyFromWrites(map, writes)
        } finally {
            track.release()
        }
    }

    /** Median of (when the last written frame plays - when it was written). */
    internal fun latencyFromWrites(map: TimeMap, writes: List<Pair<Long, Long>>): Double? {
        val latencies = writes.map { (frame, at) -> (map.toNanos(frame.toDouble()) - at) / 1e6 }.filter { it in 0.0..1000.0 }
        if (latencies.isEmpty()) return null
        val sorted = latencies.sorted()
        return sorted[sorted.size / 2]
    }
}
