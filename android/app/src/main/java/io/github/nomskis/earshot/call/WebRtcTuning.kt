package io.github.nomskis.earshot.call

/**
 * How WebRTC can be set up to ride out bad Wi-Fi: lost packets, bursts of them,
 * and delay spikes. Each value is checked against the WebRTC source this app
 * ships with (branch-heads/6367); docs/how-it-works.md explains the choices.
 * Which of them a call uses is [CallTuning]'s to say.
 */
object WebRtcTuning {
    /**
     * Earlier audio packets repeated in each packet (RED, RFC 2198). WebRTC's
     * default is 1, which only repairs a single lost packet, while Wi-Fi
     * tends to lose them in bursts. 3 repairs up to 60 ms of consecutive loss
     * at 20 ms packets (30 at 10 ms, 120 at 40 ms). It adds bytes, not
     * packets, and on Wi-Fi the packet count is what costs airtime: about
     * 100 kbps more at HD voice. Longer bursts are resent when there's time
     * ([SdpTuning.requestAudioResends]).
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
     * Most audio packets the jitter buffer holds: 2 s at 20 ms packets (1 s at
     * 10 ms, 4 s at 40 ms). It also caps how far the buffer may grow (three
     * quarters of this). Too small, and a long stall (a crowded home router's
     * queue, say) overflows it and the audio skips.
     */
    const val JITTER_BUFFER_MAX_PACKETS = 100

    /**
     * Voice that RED, Opus FEC and resends all missed has to be made up. NetEq's default is its
     * own generic Expand; with this trial it asks the Opus decoder for Opus's own concealment
     * (NetEqImpl::DoCodecPlc, AudioDecoderOpusImpl::GeneratePlc), which knows the voice it was
     * just decoding and stays in step with it for the next packet. Measured on 6367's own NetEq
     * and Opus behind 3 RED copies (docs/research/concealment.md): better in 31 of 32 loss and
     * jitter conditions, +0.11 wideband PESQ on average and up to +0.22 on the worst links, and
     * as good or better through 0.2-1 s Wi-Fi stalls.
     */
    const val OPUS_CONCEALMENT = "WebRTC-Audio-OpusGeneratePlc"

    /**
     * A frozen picture comes back with a keyframe, which the sender's pacer would queue behind
     * the older frames' packets still waiting to go out: on a congested uplink, hundreds of
     * milliseconds of video the receiver can no longer use. With this trial the first packet of
     * a keyframe drops that stream's queued packets (and their resends), so the keyframe leaves
     * at once (PacingController::EnqueuePacket). Off by default in 6367; upstream WebRTC launched
     * it and has since made it the only behaviour ("Clean up WebRTC-Pacer-KeyframeFlushing trial").
     */
    const val KEYFRAME_FLUSHING = "WebRTC-Pacer-KeyframeFlushing"

    /** WebRTC field trials: "Name/Value/" pairs, set once when WebRTC starts. */
    val fieldTrials: String = buildString {
        if (CallTuning.REDUNDANT_AUDIO) append("WebRTC-Audio-Red-For-Opus/Enabled-$RED_REDUNDANCY/")
        if (CallTuning.DEEP_JITTER_BUFFER) append("WebRTC-Audio-NetEqDelayManagerConfig/quantile:$JITTER_QUANTILE/")
        append("$OPUS_CONCEALMENT/Enabled/")
        append("$KEYFRAME_FLUSHING/Enabled/")
    }
}
