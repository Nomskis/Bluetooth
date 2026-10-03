package io.github.nomskis.earshot.turbo

/**
 * What the system dumps say about the Bluetooth audio path. Formats come from
 * AOSP: btif_av.cc / a2dp_codec_config.cc (bluetooth_manager) and
 * AudioFlinger Threads.cpp (media.audio_flinger).
 */
data class BluetoothOutputDiagnostics(
    /** "Current Codec:" from the A2DP section. */
    val codec: String?,
    /** AVDTP delay report from the earbuds (what they claim), in ms. */
    val reportedDelayMs: Double?,
    /** The A2DP mixer thread's HAL buffer, in ms, if found. */
    val halBufferMs: Double?,
    val hasFastMixer: Boolean?,
    val latencyModesEnabled: Boolean?,
    val halSupportsLatencyModes: Boolean?,
    val supportedLatencyModes: List<String>,
) {
    /** Whether the game-audio label can switch this phone's Bluetooth to low latency. */
    val gameLabelCanLowerLatency: Boolean?
        get() = when {
            halSupportsLatencyModes == false -> false
            supportedLatencyModes.contains("LOW") && hasFastMixer == true && latencyModesEnabled == true -> true
            supportedLatencyModes.isEmpty() && halSupportsLatencyModes == null -> null
            else -> false
        }
}

object TurboDiagnostics {

    fun parse(bluetoothDump: String, audioFlingerDump: String): BluetoothOutputDiagnostics {
        val codec = Regex("^\\s*Current Codec: (.+)$", RegexOption.MULTILINE).findAll(bluetoothDump)
            .map { it.groupValues[1].trim() }.firstOrNull { it != "None" }
        // Reported in 1/10 ms; take the first non-zero (the connected peer).
        val delay = Regex("Delay Reporting: (\\d+) \\(in 1/10 milliseconds\\)").findAll(bluetoothDump)
            .map { it.groupValues[1].toInt() }.firstOrNull { it > 0 }?.let { it / 10.0 }

        val thread = a2dpThread(audioFlingerDump)
        val sampleRate = thread?.let { Regex("^\\s*Sample rate: (\\d+) Hz", RegexOption.MULTILINE).find(it)?.groupValues?.get(1)?.toDouble() }
        val frames = thread?.let { Regex("^\\s*HAL frame count: (\\d+)", RegexOption.MULTILINE).find(it)?.groupValues?.get(1)?.toDouble() }
        val modes = thread?.let { Regex("^Supported latency modes: \\{(.*)\\}", RegexOption.MULTILINE).find(it)?.groupValues?.get(1) }
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

        return BluetoothOutputDiagnostics(
            codec = codec,
            reportedDelayMs = delay,
            halBufferMs = if (sampleRate != null && frames != null && sampleRate > 0) frames * 1000 / sampleRate else null,
            hasFastMixer = thread?.let { !it.contains("No FastMixer") },
            latencyModesEnabled = thread?.let {
                when {
                    it.contains("Bluetooth latency modes are not enabled") -> false
                    it.contains("Bluetooth latency modes are enabled") -> true
                    else -> null
                }
            },
            halSupportsLatencyModes = thread?.let {
                when {
                    it.contains("HAL does not support Bluetooth latency modes") -> false
                    it.contains("HAL does support Bluetooth latency modes") -> true
                    else -> null
                }
            },
            supportedLatencyModes = modes,
        )
    }

    /** The output thread section currently routed to an A2DP device. */
    private fun a2dpThread(dump: String): String? {
        val sections = Regex("(?=^Output thread )", RegexOption.MULTILINE).split(dump)
        return sections.firstOrNull { section ->
            section.startsWith("Output thread") &&
                Regex("^\\s*Output devices: .*BLUETOOTH_A2DP", RegexOption.MULTILINE).containsMatchIn(section)
        }
    }

    /** Parses ITurboService.codecStatus(): "type:sampleRate:bits:specific1|sel1,sel2". */
    fun parseCodecStatus(text: String): CodecStatus? {
        if (text.isBlank()) return null
        val (current, selectable) = text.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val parts = current.split(':')
        if (parts.size < 4) return null
        return CodecStatus(
            codecType = parts[0].toIntOrNull() ?: return null,
            sampleRateFlags = parts[1].toIntOrNull() ?: 0,
            bitsFlags = parts[2].toIntOrNull() ?: 0,
            codecSpecific1 = parts[3].toLongOrNull() ?: 0,
            selectableTypes = selectable.split(',').mapNotNull { it.trim().toIntOrNull() }.distinct(),
        )
    }
}

data class CodecStatus(
    val codecType: Int,
    val sampleRateFlags: Int,
    val bitsFlags: Int,
    val codecSpecific1: Long,
    val selectableTypes: List<Int>,
)
