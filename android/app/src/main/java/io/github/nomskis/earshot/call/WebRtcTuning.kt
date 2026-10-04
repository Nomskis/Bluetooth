package io.github.nomskis.earshot.call

/**
 * How WebRTC is set up to ride out bad Wi-Fi: lost packets, bursts of them,
 * and delay spikes. Each value is checked against the WebRTC source this app
 * ships with (branch-heads/6367); docs/how-it-works.md explains the choices.
 */
object WebRtcTuning {
    /**
     * Earlier audio packets repeated in each packet (RED, RFC 2198). WebRTC's
     * default is 1; at 10 ms packets that only repairs a single lost packet,
     * while Wi-Fi tends to lose them in bursts. 3 repairs up to 30 ms of
     * consecutive loss. It adds bytes, not packets, and on Wi-Fi the packet
     * count is what costs airtime: about 100 kbps more at HD voice.
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
     * Most audio packets the jitter buffer holds, 1 s at 10 ms packets. It also
     * caps how far the buffer may grow (three quarters of this). Too small,
     * and a long Wi-Fi stall overflows it and the audio skips.
     */
    const val JITTER_BUFFER_MAX_PACKETS = 100

    /**
     * Three temporal layers for VP8: half the frames are referenced by no
     * other frame and a quarter by just one. A lost packet then usually costs
     * a single frame instead of freezing the picture until it's resent or a
     * new keyframe arrives. Only the software VP8 encoder makes layers;
     * hardware encoders ignore it.
     */
    const val VIDEO_SCALABILITY_MODE = "L1T3"

    /** WebRTC field trials: "Name/Value/" pairs, set once when WebRTC starts. */
    val fieldTrials: String = buildString {
        append("WebRTC-Audio-Red-For-Opus/Enabled-$RED_REDUNDANCY/")
        append("WebRTC-Audio-NetEqDelayManagerConfig/quantile:$JITTER_QUANTILE/")
    }
}
