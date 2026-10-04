package io.github.nomskis.earshot.call

/**
 * How WebRTC is set up to ride out bad Wi-Fi: lost packets, bursts of them,
 * and delay spikes. Each value is checked against the WebRTC source this app
 * ships with (branch-heads/6367); docs/how-it-works.md explains the choices.
 * The audio packet length isn't fixed here: it starts at 20 ms and follows
 * the link ([PacketTime]).
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
     * encoders ignore it (see [SOFTWARE_VIDEO_MAX_PIXELS]).
     */
    const val VIDEO_SCALABILITY_MODE = "L1T3"

    /**
     * Video at or below this many pixels is encoded in software (libvpx), above
     * it by the phone's hardware encoder, whatever the codec. Android's WebRTC
     * prefers a hardware encoder whenever the phone has one and doesn't switch
     * to software for temporal layers (sdk/android video_encoder_fallback.cc
     * passes prefer_temporal_support=false), so on most phones
     * [VIDEO_SCALABILITY_MODE] was simply ignored, and hardware encoders' rate
     * control is at its worst at low bitrates. WebRTC only scales the camera
     * down this far when bandwidth is short, and a resolution change
     * re-initialises the encoder, where the fallback wrapper checks this
     * threshold: so on a weak link the call gets temporal layers (a lost packet
     * costs a frame, not a freeze) and libvpx's rate control, while a good link
     * keeps the cheaper hardware encoder. 640x360: software VP9 or VP8 at that
     * size is light work for any phone.
     *
     * Set with WebRTC-Video-EncoderFallbackSettings, which applies to every codec
     * (video_encoder_software_fallback_wrapper.cc); the older
     * WebRTC-VP8-Forced-Fallback-Encoder-v2 only switches VP8. The wrapper then
     * keeps WebRTC's floor of 320x180 while in software.
     */
    const val SOFTWARE_VIDEO_MAX_PIXELS = 640 * 360

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
        append("WebRTC-Audio-Red-For-Opus/Enabled-$RED_REDUNDANCY/")
        append("WebRTC-Audio-NetEqDelayManagerConfig/quantile:$JITTER_QUANTILE/")
        // FieldTrialOptional<int> "resolution_threshold_px", parsed as key:value.
        append("WebRTC-Video-EncoderFallbackSettings/resolution_threshold_px:$SOFTWARE_VIDEO_MAX_PIXELS/")
        append("$OPUS_CONCEALMENT/Enabled/")
        append("$KEYFRAME_FLUSHING/Enabled/")
    }
}
