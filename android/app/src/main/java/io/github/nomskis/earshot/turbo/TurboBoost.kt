package io.github.nomskis.earshot.turbo

import android.util.Log
import io.github.nomskis.earshot.audio.Codecs
import io.github.nomskis.earshot.settings.DelayRun
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Turbo's privileged switches, applied for the length of a call and undone
 * after: Bluetooth low-latency mode on, the codec the delay tuner measured
 * fastest for these earbuds (music goes back to its usual codec after the
 * call), and the shortest phone-side Bluetooth buffer. Does nothing unless
 * Shizuku is set up.
 */
class TurboBoost(turboProvider: () -> TurboClient) {
    private val turbo by lazy(turboProvider)

    data class Status(val text: String)

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    private var restoreCodec: CodecStatus? = null
    private var restoreBufferFor: Int? = null

    suspend fun begin(runs: List<DelayRun>, device: String?) {
        if (!turbo.awaitReady(READY_TIMEOUT_MS)) return
        val changes = mutableListOf<String>()
        turbo.enableVariableLatency()
        val current = turbo.codecStatus()
        var codecType = current?.codecType
        val faster = current?.let { callCodec(runs, device, it.codecType, it.selectableTypes) }
        if (current != null && faster != null && turbo.setCodec(faster)) {
            restoreCodec = current
            codecType = faster
            changes += "${Codecs.name(faster)} for the call"
            delay(CODEC_SETTLE_MS) // the stream restarts with the new codec
        }
        if (codecType != null) {
            turbo.shortestBuffer(codecType)?.let { ms ->
                restoreBufferFor = codecType
                changes += "$ms ms Bluetooth buffer"
            }
        }
        Log.i(TAG, "Turbo for the call: $changes")
        _status.value = Status(if (changes.isEmpty()) "Turbo: low-latency Bluetooth on" else "Turbo: " + changes.joinToString(", "))
    }

    suspend fun end() {
        _status.value = null
        restoreBufferFor?.let { turbo.defaultBuffer(it) }
        restoreBufferFor = null
        restoreCodec?.let { turbo.setCodec(it.codecType, it.codecSpecific1) }
        restoreCodec = null
    }

    companion object {
        private const val TAG = "EarshotTurbo"
        private const val READY_TIMEOUT_MS = 3_000L
        private const val CODEC_SETTLE_MS = 2_500L

        /** A codec has to beat the current one by this much to be worth the switch. */
        const val WORTH_SWITCHING_MS = 20.0

        /**
         * The codec to use for a call: the fastest one measured for these
         * earbuds, if the phone can select it and it beats the current codec's
         * best measurement (or the current one was never measured) clearly.
         */
        fun callCodec(runs: List<DelayRun>, device: String?, currentType: Int, selectable: List<Int>): Int? {
            if (device == null) return null
            val byCodec = runs.filter { it.device == device }
                .mapNotNull { run -> codecOfLabel(run.label)?.let { it to run.delayMs } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, delays) -> delays.min() }
            val (best, bestMs) = byCodec.minByOrNull { it.value } ?: return null
            if (best == currentType) return null
            if (selectable.isNotEmpty() && best !in selectable) return null
            val currentMs = byCodec[currentType]
            return if (currentMs == null || bestMs < currentMs - WORTH_SWITCHING_MS) best else null
        }

        /** "Codec: AAC (auto)" or "Codec: SBC" -> the codec type; the tuner's grouped label is ambiguous. */
        fun codecOfLabel(label: String): Int? {
            if (!label.startsWith("Codec: ")) return null
            val name = label.removePrefix("Codec: ").removeSuffix(" (auto)")
            if ('/' in name) return null
            return Codecs.typeOf(name)
        }
    }
}
