package io.github.nomskis.earshot.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGovernorTest {
    private val governor = VideoGovernor(ceilingKbps = 1_500)
    private var now = 0L
    private var qpSum = 0.0
    private var frames = 0.0
    private var bytes = 0.0

    /** One 2 s interval at 30 fps, coded at [qp], sending [kbps]. */
    private fun interval(qp: Double, kbps: Int = governor.capKbps, codec: String = "VP9"): Int? {
        now += 2_000
        frames += 60
        qpSum += qp * 60
        bytes += kbps * 1000.0 / 8 * 2
        return governor.update(codec, qpSum, frames, bytes, now)
    }

    @Test
    fun aStillPictureCodedFinerThanVisibleCostsLess() {
        interval(qp = 40.0) // baseline
        assertNull(interval(qp = 40.0)) // one calm interval isn't enough
        assertEquals(1_275, interval(qp = 40.0))
        repeat(40) { interval(qp = 40.0) }
        // Down to the floor at most.
        assertEquals(VideoGovernor.FLOOR_KBPS, governor.capKbps)
    }

    @Test
    fun motionGetsTheBitsBackStraightAway() {
        interval(qp = 40.0)
        repeat(20) { interval(qp = 40.0) }
        val low = governor.capKbps
        val raised = interval(qp = 120.0)!!
        assertTrue(raised > low)
        repeat(10) { interval(qp = 120.0) }
        assertEquals(1_500, governor.capKbps) // never above the ceiling
    }

    @Test
    fun inTheGoodBandNothingChanges() {
        interval(qp = 75.0)
        repeat(20) { assertNull(interval(qp = 75.0)) }
        assertEquals(1_500, governor.capKbps)
    }

    @Test
    fun whenSomethingElseHoldsTheBitrateDownTheAllowanceStaysPut() {
        interval(qp = 40.0, kbps = 400)
        // Sending far under the allowance: the bandwidth estimate is the limit, not us.
        repeat(10) { assertNull(interval(qp = 40.0, kbps = 400)) }
        assertEquals(1_500, governor.capKbps)
    }

    @Test
    fun eachCodecHasItsOwnScale() {
        assertNull(VideoGovernor.bandFor("AV1"))
        assertNull(interval(qp = 5.0, codec = "AV1"))
        assertEquals(VideoGovernor.Band(10.0, 18.0), VideoGovernor.bandFor("vp8"))
    }

    @Test
    fun anEncoderThatReportsNoQuantizerIsLeftAlone() {
        // qpSum stuck at 0 would read as flawless quality; never starve the picture on that.
        interval(qp = 0.0)
        repeat(20) { assertNull(interval(qp = 0.0)) }
        assertEquals(1_500, governor.capKbps)
    }

    @Test
    fun aPausedCameraTellsNothing() {
        interval(qp = 40.0)
        now += 2_000
        frames += 2 // two frames in two seconds
        qpSum += 2 * 40.0
        assertNull(governor.update("VP9", qpSum, frames, bytes, now))
    }
}
