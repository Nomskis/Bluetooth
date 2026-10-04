package io.github.nomskis.earshot.call

import android.app.Application
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.webrtc.JavaI420Buffer
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FlipProcessorTest {
    private fun map(sideways: Boolean, x: Float, y: Float): List<Float> {
        val point = floatArrayOf(x, y)
        FlipProcessor.flipMatrix(sideways).mapPoints(point)
        return point.map { Math.round(it * 100) / 100f }
    }

    @Test
    fun anUprightFrameIsMirroredAcrossItsWidth() {
        assertEquals(listOf(0.8f, 0.3f), map(sideways = false, 0.2f, 0.3f))
        assertEquals(listOf(0.5f, 0.5f), map(sideways = false, 0.5f, 0.5f))
    }

    @Test
    fun aSidewaysFrameIsMirroredAcrossWhatBecomesTheScreensWidth() {
        // Rotated 90 or 270 degrees for display, the frame's height runs across the screen.
        assertEquals(listOf(0.2f, 0.7f), map(sideways = true, 0.2f, 0.3f))
    }

    /** A 4x2 frame with distinct values, so any shuffle shows. */
    private fun frame(): JavaI420Buffer {
        val y = ByteBuffer.allocateDirect(8).put(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)).also { it.rewind() }
        val u = ByteBuffer.allocateDirect(2).put(byteArrayOf(10, 11)).also { it.rewind() }
        val v = ByteBuffer.allocateDirect(2).put(byteArrayOf(20, 21)).also { it.rewind() }
        return JavaI420Buffer.wrap(4, 2, y, 4, u, 2, v, 2, null)
    }

    private fun bytes(buffer: ByteBuffer) = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }

    @Test
    fun framesInMemoryAreFlippedTheSameWay() {
        val upright = FlipProcessor.flipI420(frame(), sideways = false)
        assertArrayEquals(byteArrayOf(4, 3, 2, 1, 8, 7, 6, 5), bytes(upright.dataY))
        assertArrayEquals(byteArrayOf(11, 10), bytes(upright.dataU))
        assertArrayEquals(byteArrayOf(21, 20), bytes(upright.dataV))

        val sideways = FlipProcessor.flipI420(frame(), sideways = true)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8, 1, 2, 3, 4), bytes(sideways.dataY))
        assertArrayEquals(byteArrayOf(10, 11), bytes(sideways.dataU))
    }
}
