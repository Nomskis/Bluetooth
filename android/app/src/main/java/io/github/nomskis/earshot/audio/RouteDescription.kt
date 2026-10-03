package io.github.nomskis.earshot.audio

import io.github.nomskis.earshot.settings.AudioMode

enum class Tone { GOOD, NEUTRAL, WARNING }

/** Plain-language explanation of an [AudioRoute], shown on the home and call screens. */
data class RouteDescription(val title: String, val detail: String, val tone: Tone)

fun describeRoute(route: AudioRoute, mode: AudioMode, inCall: Boolean = false): RouteDescription {
    val name = route.mediaOutput?.name

    if (route.callModeActive) {
        return if (mode == AudioMode.HEADSET && inCall) {
            RouteDescription(
                title = route.communicationDevice?.name ?: "Headset mic mode",
                detail = "Using your earbuds' microphone, so Bluetooth runs at call quality like any normal call.",
                tone = Tone.NEUTRAL,
            )
        } else {
            RouteDescription(
                title = "Phone is in call mode",
                detail = "Another app or a phone call has switched the phone into call mode, which pulls Bluetooth " +
                    "down to call quality. End that call to get full quality back.",
                tone = Tone.WARNING,
            )
        }
    }

    return when (route.quality) {
        RouteQuality.BLUETOOTH_HIFI -> RouteDescription(
            title = name ?: "Bluetooth",
            detail = if (mode == AudioMode.HIFI) {
                "High-quality music link (A2DP). Your music and the call both play at full quality."
            } else {
                "High-quality music link right now. A headset-mic call will switch it to call quality."
            },
            tone = if (mode == AudioMode.HIFI) Tone.GOOD else Tone.NEUTRAL,
        )
        RouteQuality.BLUETOOTH_LE_AUDIO -> RouteDescription(
            title = "${name ?: "Bluetooth"} · LE Audio",
            detail = "LE Audio carries music and a microphone together in good quality.",
            tone = Tone.GOOD,
        )
        RouteQuality.BLUETOOTH_CALL_QUALITY -> RouteDescription(
            title = "${name ?: "Bluetooth"} · call quality",
            detail = "Bluetooth is on the narrow two-way call link (HFP/SCO).",
            tone = Tone.WARNING,
        )
        RouteQuality.WIRED -> RouteDescription(
            title = name ?: "Headphones",
            detail = "Wired audio, full quality.",
            tone = Tone.GOOD,
        )
        RouteQuality.PHONE_SPEAKER -> RouteDescription(
            title = "Phone speaker",
            detail = if (route.bluetoothMusicAvailable) {
                "Audio is playing from the phone."
            } else {
                "No earbuds connected. Connect them to hear the call privately."
            },
            tone = Tone.NEUTRAL,
        )
        RouteQuality.UNKNOWN -> RouteDescription(
            title = name ?: "Audio output",
            detail = "Couldn't tell which link this device uses.",
            tone = Tone.NEUTRAL,
        )
    }
}
