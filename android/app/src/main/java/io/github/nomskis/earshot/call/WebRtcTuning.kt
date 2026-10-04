package io.github.nomskis.earshot.call

/**
 * How WebRTC is set up to ride out bad Wi-Fi: lost packets, bursts of them,
 * and delay spikes. Each value is checked against the WebRTC source this app
 * ships with (branch-heads/6367); docs/how-it-works.md explains the choices.
 */
object WebRtcTuning {
    /**
     * Audio packet length, ms. WebRTC's usual 20 rather than 10: on weak Wi-Fi
     * across a long route, half the packets means less contention for airtime
     * and half the header overhead (about 23 kbps instead of 46), the same
     * redundant copies cover twice the loss, and Opus codes 20 ms frames more
     * efficiently. 10 ms only saves about 10 ms of delay.
     */
    const val AUDIO_PACKET_MS = 20

    /**
     * Earlier audio packets repeated in each packet (RED, RFC 2198). WebRTC's
     * default is 1, which only repairs a single lost packet, while Wi-Fi
     * tends to lose them in bursts. 3 repairs up to 60 ms of consecutive loss
     * at 20 ms packets. It adds bytes, not packets, and on Wi-Fi the packet
     * count is what costs airtime: about 100 kbps more at HD voice. Longer
     * bursts are resent (SdpTuning.enableAudioNack) when there's time.
     */
    const val RED_REDUNDANCY = 3

    /**
     * Share of delay spikes the audio jitter buffer is sized to absorb
     * (WebRTC's default 0.95). Higher means fewer dropouts and robotic
     * patches on jittery Wi-Fi, for a little more delay only while the
     * network is that bad; on a steady network the two are the same.
     */
    const val JITTER_QUANTILE = 0.97

    /**
     * Most audio packets the jitter buffer holds, 2 s at 20 ms packets. It also
     * caps how far the buffer may grow (three quarters of this). Too small,
     * and a long stall (a crowded home router's queue, say) overflows it and
     * the audio skips.
     */
    const val JITTER_BUFFER_MAX_PACKETS = 100

    /**
     * Preferred video codec. VP9 needs roughly a third fewer bits than VP8 for
     * the same picture, which is what a slow home upload needs most; VP8,
     * H.264 and the rest stay as fallbacks. Software VP9 costs more CPU; WebRTC
     * lowers the resolution by itself if the phone can't keep up.
     */
    const val PREFERRED_VIDEO_CODEC = "VP9"

    /** Codecs whose software encoders make temporal layers. */
    val TEMPORAL_LAYER_CODECS = listOf("VP8", "VP9")

    /**
     * Three temporal layers for VP8 and VP9: half the frames are referenced by no
     * other frame and a quarter by just one. A lost packet then usually costs
     * a single frame instead of freezing the picture until it's resent or a
     * new keyframe arrives. Only the software encoders make layers; hardware
     * encoders ignore it.
     */
    const val VIDEO_SCALABILITY_MODE = "L1T3"

    /** WebRTC field trials: "Name/Value/" pairs, set once when WebRTC starts. */
    val fieldTrials: String = buildString {
        append("WebRTC-Audio-Red-For-Opus/Enabled-$RED_REDUNDANCY/")
        append("WebRTC-Audio-NetEqDelayManagerConfig/quantile:$JITTER_QUANTILE/")
    }
}
