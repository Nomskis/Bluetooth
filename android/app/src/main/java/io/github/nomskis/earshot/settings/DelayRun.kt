package io.github.nomskis.earshot.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One delay measurement, tagged with what was being tried at the time. */
@Serializable
data class DelayRun(
    /** Name of the earbuds, as Android reports it. */
    val device: String,
    /** What the user had set up, e.g. "Game mode on" or "Codec: AAC". */
    val label: String,
    /** Delay from handing audio to Android until it reaches the ear, ms. */
    val delayMs: Double,
    /** Android's own estimate for the same path, ms. */
    val reportedMs: Double? = null,
    val calibrated: Boolean = true,
    val gameAudio: Boolean = true,
    /** The Bluetooth codec Android reported at the time, when known. */
    val codec: String? = null,
    val atMillis: Long,
)

object DelayRuns {
    const val MAX_STORED = 40
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(DelayRun.serializer())

    fun decode(text: String?): List<DelayRun> =
        text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    fun encode(runs: List<DelayRun>): String = json.encodeToString(serializer, runs.takeLast(MAX_STORED))

    /** The most recent measurement for a device, which best describes it right now. */
    fun latestFor(runs: List<DelayRun>, device: String?): DelayRun? =
        device?.let { name -> runs.lastOrNull { it.device == name } }

    /** The fastest setup measured for a device, if there are at least two to compare. */
    fun fastestFor(runs: List<DelayRun>, device: String?): DelayRun? {
        val forDevice = runs.filter { it.device == device }
        return if (forDevice.size >= 2) forDevice.minBy { it.delayMs } else null
    }
}
