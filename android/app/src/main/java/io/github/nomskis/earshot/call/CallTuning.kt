package io.github.nomskis.earshot.call

/**
 * Which of Earshot's own call tunings are on, beyond what WebRTC does by itself.
 *
 * The first real calls between the two phones (Finland and Morocco, one on
 * mobile data) were unusable: an echo, voice that barely got through, a
 * 577 ms smoothing buffer with a fifth of the voice made up while nothing was
 * lost, and video in pieces, with 2.5 Mbps or more to spare. Many tunings had
 * gone in together without one real call to check them against, so they're all
 * off: a call is WebRTC's own defaults, plus the echo fix (the phone's call
 * audio without earbuds, [io.github.nomskis.earshot.audio.AudioProfile]). Each
 * comes back on its own, once a call report shows it helps.
 */
object CallTuning {
    /** Repeat earlier voice packets in each one (RED, [WebRtcTuning.RED_REDUNDANCY]). */
    const val REDUNDANT_AUDIO = false

    /** Ask for 10, 20 or 40 ms audio packets as the link changes ([PacketTime]); renegotiates mid-call. */
    const val ADAPTIVE_PACKET_TIME = false

    /** Opus at 48 kbps instead of WebRTC's 32 ([SdpTuning.preferHdVoice]). */
    const val HD_VOICE = false

    /** Resends of lost voice ([SdpTuning.requestAudioResends]). */
    const val AUDIO_RESENDS = false

    /** A deeper, slower-shrinking smoothing buffer ([WebRtcTuning.JITTER_QUANTILE], [WebRtcTuning.JITTER_BUFFER_MAX_PACKETS]). */
    const val DEEP_JITTER_BUFFER = false

    /** Voice first: leaner voice and paused video when the estimate is short ([MediaBudget]). */
    const val VOICE_FIRST = false

    /** Lighter video towards a starving Wi-Fi uplink on their side ([AirtimeShare]). */
    const val AIRTIME_SHARE = false

    /** Start the bandwidth estimate where the last call on this route got to ([LinkMemory]). */
    const val START_FROM_MEMORY = false

    /** Cap video on 2.4 GHz Wi-Fi next to Bluetooth earbuds ([RadioPlan.wifiVideoCapKbps]). */
    const val RADIO_VIDEO_CAP = false

    /**
     * Second-quick path failover ([RtcEngine.FAILOVER_RECEIVE_TIMEOUT_MS] and the rest) even
     * on a single network. Always on when mobile data stands by next to Wi-Fi.
     */
    const val FAST_FAILOVER_WITHOUT_STANDBY = false

    /**
     * Silence as tiny packets (Opus DTX, [SdpTuning.enableOpusDtx]): while nobody talks, one
     * small packet every 400 ms instead of 50 a second, with WebRTC's own fix that keeps the
     * background noise in the pauses steady ([WebRtcTuning.AVOID_NOISE_PUMPING]). On: it saves
     * data without changing how speech is coded, as other calling apps do. The call report's
     * "Data used" line shows what it saves; if a report shows clipped words or pumping noise,
     * this is the switch.
     */
    const val SILENCE_AS_TINY_PACKETS = true

    /**
     * Our video's allowance follows how finely the encoder is coding ([VideoGovernor]): a still
     * picture coded finer than anyone can see costs less, motion gets the bits back at once.
     * On: it only ever lowers the bitrate inside a quality band well above where WebRTC itself
     * starts lowering the resolution. The report's video quantizer and "Data used" lines show
     * its effect; if a report shows a softer picture, this is the switch.
     */
    const val VIDEO_BY_PICTURE_QUALITY = true

    /**
     * No direct TCP candidates (Signal's RingRTC sets the same,
     * `tcpCandidatePolicy = DISABLED`): a call never runs over a direct TCP connection, whose
     * resends stall the voice for seconds on a lossy link. On, unlike the rest: a WebRTC option
     * a big calling app ships, not a tuning of Earshot's own. The relay over TCP and TLS stays.
     */
    const val NO_TCP_CANDIDATES = true
}
