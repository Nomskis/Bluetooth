package io.github.nomskis.earshot.call

/**
 * SDP tweaks applied to descriptions before they're sent to the other peer.
 * Same rules as web/js/sdp.js.
 */
object SdpTuning {

    /**
     * Asks the other side to send 10 ms audio packets instead of the default
     * 20 ms. Each packet waits half as long to fill before it's sent, which
     * saves about 10 ms per direction for a little more packet overhead.
     */
    fun preferLowLatencyAudio(sdp: String): String {
        val out = StringBuilder(sdp.length + 16)
        var inAudio = false
        val lines = sdp.split("\r\n")
        lines.forEachIndexed { index, line ->
            val isLast = index == lines.lastIndex
            if (line.startsWith("m=")) {
                inAudio = line.startsWith("m=audio")
                out.append(line).append("\r\n")
                if (inAudio) out.append("a=ptime:10").append("\r\n")
            } else if (!(inAudio && line.startsWith("a=ptime:"))) {
                out.append(line)
                if (!isLast) out.append("\r\n")
            }
        }
        return out.toString()
    }

    /** The codec name of the first payload type on the audio line, e.g. "red" or "opus". */
    fun firstAudioCodec(sdp: String?): String? {
        if (sdp == null) return null
        val payload = Regex("^m=audio \\S+ \\S+ (\\d+)", RegexOption.MULTILINE).find(sdp)?.groupValues?.get(1)
            ?: return null
        return Regex("^a=rtpmap:$payload ([^/\\s]+)", RegexOption.MULTILINE).find(sdp)?.groupValues?.get(1)?.lowercase()
    }
}
