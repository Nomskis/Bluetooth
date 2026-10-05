package io.github.nomskis.earshot.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.math.max

/**
 * The sharer's own WebRTC, next to the call's: a factory with an audio device of its own, so
 * a share can carry the shared app's sound (a video, a game) in stereo while the call's
 * device keeps the microphone for the voice.
 *
 * WebRTC on Android only records from a microphone. This device's recorder starts muted,
 * and the moment it starts its recording is swapped for Android's playback capture
 * (Android 10+: the sound of apps that allow it, never Earshot's own, so the call's voice
 * can't echo back). Only then is it unmuted: if the swap fails, the share goes without
 * sound, and the microphone is never heard through it.
 */
internal class ScreenMedia(context: Context, eglContext: EglBase.Context, projection: MediaProjection) {
    private val appContext = context.applicationContext
    private val sound: PlaybackSound? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canRecord(appContext)) PlaybackSound(projection) else null

    private val audioDeviceModule: JavaAudioDeviceModule = JavaAudioDeviceModule.builder(appContext)
        .setUseStereoInput(true)
        .setInputSampleRate(SAMPLE_RATE)
        // WebRTC opens a microphone first, for the moment before the swap: a plain one, so the
        // call's voice processing (its own recording) isn't disturbed.
        .setAudioSource(MediaRecorder.AudioSource.MIC)
        // Sound for listening, not a voice: nothing may filter it.
        .setUseHardwareAcousticEchoCanceler(false)
        .setUseHardwareNoiseSuppressor(false)
        .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
            // On the recording thread, before its first read.
            override fun onWebRtcAudioRecordStart() {
                val swapped = runCatching { swapInSound() }
                    .onFailure { Log.w(TAG, "Couldn't take the shared app's sound; sharing without it", it) }
                    .getOrDefault(false)
                audioDeviceModule.setMicrophoneMute(!swapped)
            }

            override fun onWebRtcAudioRecordStop() {
                audioDeviceModule.setMicrophoneMute(true)
            }
        })
        .createAudioDeviceModule()
        .also { it.setMicrophoneMute(true) }

    val factory: PeerConnectionFactory = PeerConnectionFactory.builder()
        .setAudioDeviceModule(audioDeviceModule)
        .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
        .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
        .createPeerConnectionFactory()

    private val soundSource: AudioSource? = sound?.let { factory.createAudioSource(untouched()) }

    /** Whether the share can carry sound at all (Android 10+, with the microphone permission). */
    val hasSound: Boolean get() = soundSource != null

    /** A video source marked as a screen: WebRTC keeps its resolution and lets the frame rate give way. */
    fun createScreenSource(): VideoSource = factory.createVideoSource(true)

    fun createScreenTrack(source: VideoSource): VideoTrack = factory.createVideoTrack(Ids.random(6, "s"), source)

    /** The shared app's sound, for a new connection; null without [hasSound]. */
    fun createSoundTrack(): AudioTrack? = soundSource?.let { factory.createAudioTrack(Ids.random(6, "m"), it) }

    /** After every connection, track and source made here is disposed. */
    fun release() {
        soundSource?.dispose()
        factory.dispose()
        audioDeviceModule.release()
    }

    /**
     * Puts the playback capture where WebRTC's recorder reads from, and stops the microphone
     * it had just started. WebRTC (stream-webrtc-android 1.3.10) keeps the recorder in
     * `JavaAudioDeviceModule.audioInput` and its AudioRecord in that object's `audioRecord`
     * field, which its recording thread reads before every buffer; both names are kept in
     * minified builds (the library's own keep rules).
     */
    private fun swapInSound(): Boolean {
        val sound = sound ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val input = JavaAudioDeviceModule::class.java.getField("audioInput").get(audioDeviceModule) ?: return false
        val field = input.javaClass.getDeclaredField("audioRecord").apply { isAccessible = true }
        val mic = field.get(input) as? AudioRecord ?: return false
        val capture = sound.recorderLike(mic, appContext) ?: return false
        field.set(input, capture)
        mic.stop()
        mic.release()
        Log.i(TAG, "Sharing the shared app's sound (${capture.sampleRate} Hz, ${capture.channelCount} channels)")
        return true
    }

    /** The shared app's sound (Android 10+), as an AudioRecord shaped like WebRTC's. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private class PlaybackSound(projection: MediaProjection) {
        private val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            // Earshot's own: in Hi-Fi mode the call plays as media, and their voice mustn't go back to them.
            .excludeUid(Process.myUid())
            .build()

        /** Recording now, in [like]'s format; null if Android won't. */
        fun recorderLike(like: AudioRecord, context: Context): AudioRecord? {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
            val mask = if (like.channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val min = AudioRecord.getMinBufferSize(like.sampleRate, mask, like.audioFormat)
            if (min <= 0) return null
            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(like.audioFormat)
                        .setSampleRate(like.sampleRate)
                        .setChannelMask(mask)
                        .build(),
                )
                .setBufferSizeInBytes(max(min, like.bufferSizeInFrames * 2 * like.channelCount) * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                return null
            }
            return record
        }
    }

    private companion object {
        const val TAG = "EarshotScreen"
        const val SAMPLE_RATE = 48_000

        fun canRecord(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** No echo cancelling, noise suppression, gain or high-pass: it's music, not a voice. */
        fun untouched() = MediaConstraints().apply {
            for (key in listOf("googEchoCancellation", "googNoiseSuppression", "googAutoGainControl", "googHighpassFilter")) {
                mandatory += MediaConstraints.KeyValuePair(key, "false")
            }
        }
    }
}
