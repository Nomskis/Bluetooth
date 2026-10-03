package io.github.nomskis.earshot.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.concurrent.thread

/**
 * Plays back a snippet of the other person's voice ("what did you say?").
 * Streams from a small buffer on its own thread; static buffers this size
 * are refused on some phones.
 */
class ReplayPlayer(private val attributes: AudioAttributes) {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var playing = false

    val isPlaying: Boolean get() = playing

    fun play(pcm: ShortArray, sampleRate: Int, onDone: () -> Unit) {
        stop()
        if (pcm.isEmpty()) {
            onDone()
            return
        }
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer * 4)
            .build()
        track = t
        playing = true
        thread(name = "EarshotReplay") {
            try {
                t.play()
                var offset = 0
                val chunk = sampleRate / 50
                while (playing && offset < pcm.size) {
                    val n = t.write(pcm, offset, minOf(chunk, pcm.size - offset))
                    if (n < 0) break
                    offset += n
                }
                // Let what's buffered finish playing.
                while (playing && t.playbackHeadPosition < pcm.size) Thread.sleep(20)
            } catch (e: Exception) {
                Log.w("EarshotReplay", "Replay failed", e)
            } finally {
                val wasPlaying = playing
                playing = false
                runCatching { t.stop() }
                t.release()
                if (track === t) track = null
                if (wasPlaying) onDone()
            }
        }
    }

    fun stop() {
        playing = false
    }
}
