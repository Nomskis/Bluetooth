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

    /**
     * Asks the other side to keep the video it sends us under [kbps]
     * (b=AS, and b=TIAS in bits per second, in the video section; RFC 4566
     * and RFC 3890). Null removes any cap. The browser and WebRTC encoders
     * honour these as a ceiling for their bandwidth estimate.
     */
    fun capVideoBandwidth(sdp: String, kbps: Int?): String {
        val lines = sdp.split("\r\n")
        val out = ArrayList<String>(lines.size + 2)
        var inVideo = false
        var pendingCap = false
        for (line in lines) {
            if (line.startsWith("m=")) {
                if (pendingCap) out += capLines(kbps!!) // video section without a c= line
                inVideo = line.startsWith("m=video")
                pendingCap = inVideo && kbps != null
                out += line
                continue
            }
            if (inVideo && (line.startsWith("b=AS:") || line.startsWith("b=TIAS:"))) continue
            if (pendingCap && line.startsWith("c=")) {
                out += line
                out += capLines(kbps!!)
                pendingCap = false
                continue
            }
            if (pendingCap && !line.startsWith("i=")) {
                out += capLines(kbps!!)
                pendingCap = false
            }
            out += line
        }
        return out.joinToString("\r\n")
    }

    private fun capLines(kbps: Int) = listOf("b=AS:$kbps", "b=TIAS:${kbps * 1000L}")

    /** The codec name of the first payload type on the audio line, e.g. "red" or "opus". */
    fun firstAudioCodec(sdp: String?): String? {
        if (sdp == null) return null
        val payload = Regex("^m=audio \\S+ \\S+ (\\d+)", RegexOption.MULTILINE).find(sdp)?.groupValues?.get(1)
            ?: return null
        return Regex("^a=rtpmap:$payload ([^/\\s]+)", RegexOption.MULTILINE).find(sdp)?.groupValues?.get(1)?.lowercase()
    }
}
