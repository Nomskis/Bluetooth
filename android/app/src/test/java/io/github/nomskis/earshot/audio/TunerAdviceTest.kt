package io.github.nomskis.earshot.audio

import io.github.nomskis.earshot.settings.DelayRun
import io.github.nomskis.earshot.settings.DelayRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunerAdviceTest {
    private fun run(label: String, ms: Double, at: Long, device: String = "Buds") =
        DelayRun(device = device, label = label, delayMs = ms, atMillis = at)

    private fun kinds(input: AdviceInput) = TunerAdvice.tips(input).map { it.kind }

    @Test
    fun firstTimeAsksToMeasureAndSuggestsTheBigWins() {
        val k = kinds(AdviceInput("Buds", emptyList(), wifiBand = null, gameAudioLabel = true))
        assertEquals(Tip.Kind.MEASURE, k.first())
        assertTrue(Tip.Kind.GAME_MODE in k)
        assertTrue(Tip.Kind.CODEC in k)
    }

    @Test
    fun slowEarbudsGetGameModeAndCodecTipsUntilTried() {
        val slow = listOf(run(SetupLabels.NORMAL, 210.0, 1))
        val k1 = kinds(AdviceInput("Buds", slow, null, true))
        assertTrue(Tip.Kind.GAME_MODE in k1)
        assertTrue(Tip.Kind.CODEC in k1)
        assertFalse(Tip.Kind.MEASURE in k1)

        val triedGameMode = slow + run(SetupLabels.GAME_MODE, 160.0, 2)
        assertFalse(Tip.Kind.GAME_MODE in kinds(AdviceInput("Buds", triedGameMode, null, true)))
    }

    @Test
    fun pointsBackToTheFastestSetup() {
        val runs = listOf(run(SetupLabels.GAME_MODE, 95.0, 1), run(SetupLabels.NORMAL, 200.0, 2))
        val tips = TunerAdvice.tips(AdviceInput("Buds", runs, null, true))
        val best = tips.first { it.kind == Tip.Kind.BEST_SETUP }
        assertTrue(best.title.contains(SetupLabels.GAME_MODE))
        assertEquals(Tip.Kind.BEST_SETUP, tips.first().kind)
    }

    @Test
    fun warnsAboutTwoPointFourGigahertzWifi() {
        val runs = listOf(run(SetupLabels.NORMAL, 80.0, 1))
        assertTrue(Tip.Kind.WIFI_BAND in kinds(AdviceInput("Buds", runs, WifiBand.GHZ_2_4, true)))
        assertFalse(Tip.Kind.WIFI_BAND in kinds(AdviceInput("Buds", runs, WifiBand.GHZ_5, true)))
    }

    @Test
    fun fastEarbudsAreToldSo() {
        val k = kinds(AdviceInput("Buds", listOf(run(SetupLabels.GAME_MODE, 70.0, 1)), null, true))
        assertTrue(Tip.Kind.ALREADY_FAST in k)
        assertFalse(Tip.Kind.GAME_MODE in k)
    }

    @Test
    fun runsForOtherEarbudsDontCount() {
        val runs = listOf(run(SetupLabels.GAME_MODE, 70.0, 1, device = "Other"))
        assertEquals(Tip.Kind.MEASURE, kinds(AdviceInput("Buds", runs, null, true)).first())
    }

    @Test
    fun delayRunsRoundTripAndKeepTheNewest() {
        val runs = (1..50).map { run(SetupLabels.NORMAL, it.toDouble(), it.toLong()) }
        val decoded = DelayRuns.decode(DelayRuns.encode(runs))
        assertEquals(DelayRuns.MAX_STORED, decoded.size)
        assertEquals(50.0, decoded.last().delayMs, 0.0)
        assertEquals(emptyList<DelayRun>(), DelayRuns.decode("not json"))
        assertEquals(emptyList<DelayRun>(), DelayRuns.decode(null))
    }

    @Test
    fun wifiBands() {
        assertEquals(WifiBand.GHZ_2_4, LinkConditions.bandFor(2_437))
        assertEquals(WifiBand.GHZ_5, LinkConditions.bandFor(5_180))
        assertEquals(WifiBand.GHZ_6, LinkConditions.bandFor(5_955))
        assertEquals(null, LinkConditions.bandFor(60_000))
    }
}
