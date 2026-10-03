package io.github.nomskis.earshot.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import io.github.nomskis.earshot.dsp.Chirp
import io.github.nomskis.earshot.dsp.Emission
import io.github.nomskis.earshot.dsp.MatchedFilter
import io.github.nomskis.earshot.dsp.SonarAnalysis
import io.github.nomskis.earshot.dsp.SonarPhase
import io.github.nomskis.earshot.dsp.SonarSummary
import io.github.nomskis.earshot.dsp.TimeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * Measures the real delay of the earbuds by sound: chirps are played through
 * them while you hold one against the phone's microphone, and a matched filter
 * finds exactly when each chirp arrived.
 *
 * A first, short pass through the phone's own speaker calibrates the
 * microphone's timestamps (Android times its built-in speaker well), so the
 * result doesn't inherit the mic's error. The earbud pass uses the same audio
 * attributes as a call, so whatever low-latency mode a call would trigger is
 * included in the measurement.
 */
class SonarMeter(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    enum class Stage { CALIBRATING, MEASURING, ANALYZING }

    sealed interface Outcome {
        data class Success(val summary: SonarSummary, val deviceName: String, val warnings: List<Warning>) : Outcome
        data object NotHeard : Outcome
        data object NoEarbuds : Outcome
        data class Failed(val reason: String) : Outcome
    }

    enum class Warning { LOW_VOLUME, NOT_CALIBRATED, INCONSISTENT, FEW_HITS }

    /** Caller must hold RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    suspend fun measure(playback: AudioAttributes, onStage: (Stage) -> Unit = {}): Outcome = withContext(Dispatchers.IO) {
        val route = audioManager.getAudioDevicesForAttributesCompat(playback)
        if (route == null || !DeviceKind.fromType(route.type).isPersonal) return@withContext Outcome.NoEarbuds
        val deviceName = route.toOutputDevice().name

        val template = Chirp.generate(RATE, amplitude = CHIRP_AMPLITUDE)
        val pcm = Chirp.toPcm16(template)
        val focus = pauseOtherAudio()
        val recorder = Recorder(builtIn(AudioManager.GET_DEVICES_INPUTS, AudioDeviceInfo.TYPE_BUILTIN_MIC))
        try {
            recorder.start()
            onStage(Stage.CALIBRATING)
            val speakerPhase = runCatching {
                playPhase(
                    attributes = playback,
                    device = builtIn(AudioManager.GET_DEVICES_OUTPUTS, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
                    chirp = pcm,
                    warmupMs = 300,
                    count = CALIBRATION_CHIRPS,
                )
            }.onFailure { Log.w(TAG, "Calibration pass failed", it) }.getOrNull()

            onStage(Stage.MEASURING)
            val earbudPhase = playPhase(attributes = playback, device = null, chirp = pcm, warmupMs = 900, count = MEASURE_CHIRPS)
            Thread.sleep(SonarAnalysis.MAX_DELAY_MS.toLong())
            val recording = recorder.stop()

            onStage(Stage.ANALYZING)
            val recordMap = recording.timeMap() ?: return@withContext Outcome.Failed("The microphone gave no timing information.")
            val speakerHits = speakerPhase?.let { detectIn(recording, recordMap, it, template) }.orEmpty()
            val earbudHits = detectIn(recording, recordMap, earbudPhase, template)
            val summary = SonarAnalysis.summarize(speakerHits, earbudHits, MEASURE_CHIRPS)
                ?: return@withContext Outcome.NotHeard

            val warnings = buildList {
                val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() /
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (volume < 0.4) add(Warning.LOW_VOLUME)
                if (!summary.calibrated) add(Warning.NOT_CALIBRATED)
                if (summary.spreadMs > 20) add(Warning.INCONSISTENT)
                if (summary.hits < MEASURE_CHIRPS - 1) add(Warning.FEW_HITS)
            }
            Outcome.Success(summary, deviceName, warnings)
        } catch (e: Exception) {
            Log.e(TAG, "Measurement failed", e)
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            recorder.release()
            focus?.let { audioManager.abandonAudioFocusRequest(it) }
        }
    }

    /** Only the part of the recording that can contain this phase's chirps is searched. */
    private fun detectIn(recording: Recording, map: TimeMap, phase: SonarPhase, template: DoubleArray): List<io.github.nomskis.earshot.dsp.Hit> {
        if (phase.emissions.isEmpty()) return emptyList()
        val start = map.toFrame(phase.emissions.first().handedOverAtNanos - 50e6).toInt().coerceIn(0, recording.size)
        val end = map.toFrame(phase.emissions.last().handedOverAtNanos + (SonarAnalysis.MAX_DELAY_MS + 100) * 1e6)
            .toInt().coerceIn(start, recording.size)
        if (end - start <= template.size) return emptyList()
        val segment = DoubleArray(end - start) { recording.samples[start + it] / 32768.0 }
        return SonarAnalysis.detect(MatchedFilter(segment, template), map.relativeTo(start.toDouble()), phase)
    }

    /** Plays silence with chirps every [SPACING_FRAMES], recording when each was handed over. */
    private fun playPhase(attributes: AudioAttributes, device: AudioDeviceInfo?, chirp: ShortArray, warmupMs: Int, count: Int): SonarPhase {
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
            // Same fast path as a call, so a low-latency mode it triggers is measured too.
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(minBuffer)
            .build()
        try {
            device?.let { track.preferredDevice = it }
            val warmupFrames = (RATE * warmupMs / 1000) / CHUNK * CHUNK
            val emissionFrames = (0 until count).map { warmupFrames + it * SPACING_FRAMES }
            val totalFrames = emissionFrames.last() + SPACING_FRAMES
            val emissions = mutableListOf<Emission>()
            val tsFrames = mutableListOf<Long>()
            val tsNanos = mutableListOf<Long>()
            val timestamp = AudioTimestamp()
            val chunk = ShortArray(CHUNK)

            track.play()
            var frame = 0
            while (frame < totalFrames) {
                chunk.fill(0)
                for (start in emissionFrames) {
                    // Copy whatever part of a chirp overlaps this chunk.
                    val from = maxOf(frame, start)
                    val to = minOf(frame + CHUNK, start + chirp.size)
                    for (f in from until to) chunk[f - frame] = chirp[f - start]
                }
                var written = 0
                while (written < CHUNK) {
                    val n = track.write(chunk, written, CHUNK - written)
                    if (n < 0) error("AudioTrack write failed: $n")
                    written += n
                }
                val now = System.nanoTime()
                if (frame in emissionFrames) emissions += Emission(frame.toLong(), now)
                if (track.getTimestamp(timestamp) && timestamp.framePosition > 0 && frame > warmupFrames / 2) {
                    tsFrames += timestamp.framePosition
                    tsNanos += timestamp.nanoTime
                }
                frame += CHUNK
            }
            track.stop()
            return SonarPhase(emissions, TimeMap.fit(tsFrames, tsNanos, RATE))
        } finally {
            track.release()
        }
    }

    /** Asks music players to pause for the few seconds the measurement takes. */
    private fun pauseOtherAudio(): AudioFocusRequest? {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        return request.takeIf { audioManager.requestAudioFocus(it) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
    }

    private fun builtIn(flag: Int, type: Int): AudioDeviceInfo? =
        audioManager.getDevices(flag).firstOrNull { it.type == type }

    private class Recording(val samples: ShortArray, val size: Int, val tsFrames: List<Long>, val tsNanos: List<Long>, val readFrames: List<Long>, val readNanos: List<Long>) {
        /** Prefer the recorder's own timestamps; fall back to when reads returned (calibration absorbs the offset). */
        fun timeMap(): TimeMap? = TimeMap.fit(tsFrames, tsNanos, RATE).takeIf { tsFrames.size >= 3 }
            ?: TimeMap.fit(readFrames, readNanos, RATE)
    }

    /** Records the built-in mic on its own thread, collecting timestamps as it goes. */
    @SuppressLint("MissingPermission")
    private inner class Recorder(mic: AudioDeviceInfo?) {
        private val record: AudioRecord
        private val samples = ShortArray(RATE * MAX_RECORDING_SECONDS)
        @Volatile private var size = 0
        @Volatile private var running = false
        private val tsFrames = mutableListOf<Long>()
        private val tsNanos = mutableListOf<Long>()
        private val readFrames = mutableListOf<Long>()
        private val readNanos = mutableListOf<Long>()
        private var worker: Thread? = null

        init {
            val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord.Builder()
                // No noise suppression or gain riding: they smear the chirp.
                .setAudioSource(if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, RATE / 5 * 2))
                .build()
            mic?.let { record.preferredDevice = it }
        }

        fun start() {
            running = true
            record.startRecording()
            worker = thread(name = "EarshotSonarRecord", priority = Thread.MAX_PRIORITY) {
                val buffer = ShortArray(CHUNK)
                val ts = AudioTimestamp()
                while (running && size < samples.size - CHUNK) {
                    val n = record.read(buffer, 0, CHUNK)
                    val now = System.nanoTime()
                    if (n <= 0) continue
                    buffer.copyInto(samples, size, 0, n)
                    size += n
                    readFrames += size.toLong()
                    readNanos += now
                    if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS && ts.framePosition > 0) {
                        tsFrames += ts.framePosition
                        tsNanos += ts.nanoTime
                    }
                }
            }
        }

        fun stop(): Recording {
            running = false
            worker?.join(2_000)
            runCatching { record.stop() }
            return Recording(samples, size, tsFrames.toList(), tsNanos.toList(), readFrames.toList(), readNanos.toList())
        }

        fun release() {
            running = false
            worker?.join(2_000)
            record.release()
        }
    }

    private companion object {
        const val TAG = "EarshotSonar"
        const val RATE = 48_000
        const val CHUNK = 480 // 10 ms
        /** 600 ms between chirps, more than the longest delay we look for. */
        const val SPACING_FRAMES = 28_800
        const val CALIBRATION_CHIRPS = 3
        const val MEASURE_CHIRPS = 5
        const val CHIRP_AMPLITUDE = 0.7
        const val MAX_RECORDING_SECONDS = 15
    }
}

/** Where audio with these attributes would play. Android 13+ asks the system; older versions guess. */
@SuppressLint("InlinedApi")
internal fun AudioManager.getAudioDevicesForAttributesCompat(attributes: AudioAttributes): AudioDeviceInfo? {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        return getAudioDevicesForAttributes(attributes).firstOrNull()
    }
    val outputs = getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val priority = listOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    )
    return priority.firstNotNullOfOrNull { type -> outputs.firstOrNull { it.type == type } }
}
