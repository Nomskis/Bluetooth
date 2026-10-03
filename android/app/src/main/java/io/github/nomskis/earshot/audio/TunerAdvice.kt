package io.github.nomskis.earshot.audio

import io.github.nomskis.earshot.settings.DelayRun
import io.github.nomskis.earshot.settings.DelayRuns

/** Something the user can do to cut the delay, ranked by expected effect. */
data class Tip(val kind: Kind, val title: String, val body: String) {
    enum class Kind {
        MEASURE,
        GAME_MODE,
        CODEC,
        WIFI_BAND,
        GAME_AUDIO_LABEL,
        BEST_SETUP,
        PLACEMENT,
        ALREADY_FAST,
    }
}

data class AdviceInput(
    val device: String?,
    val runs: List<DelayRun>,
    val wifiBand: WifiBand?,
    val gameAudioLabel: Boolean,
)

/** Labels the tuner offers, so tips can tell what has been tried. */
object SetupLabels {
    const val NORMAL = "Normal"
    const val GAME_MODE = "Game mode on"
    const val CODEC_SBC = "Codec: SBC"
    const val CODEC_AAC = "Codec: AAC"
    const val CODEC_HIGH = "Codec: LDAC/LHDC/aptX"
    val ALL = listOf(NORMAL, GAME_MODE, CODEC_AAC, CODEC_SBC, CODEC_HIGH)
}

object TunerAdvice {
    /** Above this the delay is noticeable in conversation once the network is added. */
    const val SLOW_MS = 120.0
    const val FAST_MS = 90.0

    fun tips(input: AdviceInput): List<Tip> {
        val forDevice = input.runs.filter { it.device == input.device }
        val latest = DelayRuns.latestFor(input.runs, input.device)
        val tried = forDevice.map { it.label }.toSet()
        val tips = mutableListOf<Tip>()

        if (latest == null) {
            tips += Tip(
                Tip.Kind.MEASURE,
                "Measure your earbuds first",
                "It takes about 7 seconds and tells you exactly how much delay your earbuds add.",
            )
        }

        val fastest = DelayRuns.fastestFor(input.runs, input.device)
        if (latest != null && fastest != null && latest.delayMs - fastest.delayMs > 15) {
            tips += Tip(
                Tip.Kind.BEST_SETUP,
                "Your fastest setup was \"${fastest.label}\"",
                "It measured ${fastest.delayMs.toInt()} ms, ${(latest.delayMs - fastest.delayMs).toInt()} ms faster than your last run. Switch back to it.",
            )
        }

        val slow = latest == null || latest.delayMs > SLOW_MS
        if (slow && SetupLabels.GAME_MODE !in tried) {
            tips += Tip(
                Tip.Kind.GAME_MODE,
                "Turn on your earbuds' game mode",
                "Most earbuds have a low-latency or game mode in their app. In tests it typically halves " +
                    "the delay (around 200 ms down to 100 ms). Turn it on, then measure again labelled \"Game mode on\".",
            )
        }

        if (input.wifiBand == WifiBand.GHZ_2_4) {
            tips += Tip(
                Tip.Kind.WIFI_BAND,
                "Switch to 5 GHz Wi-Fi if you can",
                "Your Wi-Fi is on 2.4 GHz, the same band as Bluetooth, and the phone shares one radio chip " +
                    "between them. Video call traffic there can make Bluetooth audio stutter and lag. A 5 GHz network or mobile data avoids that.",
            )
        }

        val codecTried = tried.any { it.startsWith("Codec:") }
        if (slow && !codecTried) {
            tips += Tip(
                Tip.Kind.CODEC,
                "Try a different Bluetooth codec",
                "In Developer options, \"Bluetooth audio codec\" lets you pick the codec. High-resolution ones " +
                    "(LDAC, LHDC, aptX HD) often add delay; AAC or SBC can be faster. Measure each and keep the fastest.",
            )
        }

        if (!input.gameAudioLabel) {
            tips += Tip(
                Tip.Kind.GAME_AUDIO_LABEL,
                "Turn \"Game audio label\" back on",
                "Labelling the call as game audio lets phones and earbuds that support it switch to their low-latency mode automatically.",
            )
        }

        if (latest != null && latest.delayMs <= FAST_MS) {
            tips += Tip(
                Tip.Kind.ALREADY_FAST,
                "That's fast",
                "${latest.delayMs.toInt()} ms is close to what gaming headsets manage. The rest of the call's delay is mostly the network.",
            )
        }

        tips += Tip(
            Tip.Kind.PLACEMENT,
            "Keep the phone in sight of your earbuds",
            "Your body blocks Bluetooth well. When the signal has to go around you, earbuds retransmit more and grow their buffer. " +
                "A phone propped up in front of you works best.",
        )
        return tips
    }
}
