package io.github.nomskis.earshot.audio

import org.webrtc.AudioTrackSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Listens to the other person's decoded audio at the moment WebRTC hands it to
 * Android for playback, which is everything except the Android and Bluetooth
 * delay, so 100-250 ms before you actually hear it.
 *
 * Feeds a voice detector (for the "talking" cue and the music dip) and keeps
 * the last seconds for instant replay. Runs on WebRTC's audio thread, so it
 * does as little as possible.
 */
class RemoteVoiceTap(private val onSpeakingChanged: (Boolean) -> Unit) : AudioTrackSink {
    private val vad = VoiceActivityDetector()
    @Volatile var ring = PcmRingBuffer(DEFAULT_RATE, REPLAY_SECONDS)
        private set
    private var mono = ShortArray(DEFAULT_RATE / 50)

    override fun onData(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
        absoluteCaptureTimestampMs: Long,
    ) {
        if (bitsPerSample != 16 || numberOfChannels <= 0 || numberOfFrames <= 0) return
        if (sampleRate != ring.sampleRate) ring = PcmRingBuffer(sampleRate, REPLAY_SECONDS)
        if (mono.size < numberOfFrames) mono = ShortArray(numberOfFrames)

        val data = audioData.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = data.position()
        var sum = 0.0
        for (frame in 0 until numberOfFrames) {
            var mixed = 0
            for (ch in 0 until numberOfChannels) {
                mixed += data.getShort(base + 2 * (frame * numberOfChannels + ch)).toInt()
            }
            val sample = mixed / numberOfChannels
            mono[frame] = sample.toShort()
            val v = sample / 32768.0
            sum += v * v
        }
        ring.write(mono, numberOfFrames)
        vad.process(kotlin.math.sqrt(sum / numberOfFrames))?.let(onSpeakingChanged)
    }

    fun lastSeconds(seconds: Double): Pair<ShortArray, Int> = ring.let { it.last(seconds) to it.sampleRate }

    companion object {
        const val DEFAULT_RATE = 48_000
        const val REPLAY_SECONDS = 12
    }
}
