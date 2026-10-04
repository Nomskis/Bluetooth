package io.github.nomskis.earshot.settings

/**
 * "Use less data" for calls, as WhatsApp, Signal and Telegram offer: video at most
 * [VIDEO_KBPS] and [VIDEO_FPS] in both directions (ours capped at the encoder, theirs asked
 * for in the offer), the camera at 360p, the voice untouched. About a third of a gigabyte an hour for a video call
 * instead of one or more, and less for a thin mobile link to queue up in front of the voice.
 */
object LessData {
    const val VIDEO_KBPS = 300
    const val VIDEO_FPS = 15

    /** The tighter of an existing cap (null: none) and less data's, when it's on. */
    fun videoKbps(on: Boolean, cap: Int?): Int? = listOfNotNull(cap, VIDEO_KBPS.takeIf { on }).minOrNull()

    fun videoFps(on: Boolean, cap: Int?): Int? = listOfNotNull(cap, VIDEO_FPS.takeIf { on }).minOrNull()

    /** The camera at 360p when that's all that's sent: more would only cost battery and heat. */
    fun capture(on: Boolean, chosen: VideoQuality): VideoQuality = if (on) minOf(chosen, VideoQuality.LOW) else chosen
}
