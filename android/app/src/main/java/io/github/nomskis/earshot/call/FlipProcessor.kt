package io.github.nomskis.earshot.call

import android.graphics.Matrix
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink
import java.nio.ByteBuffer

/**
 * Flip: mirrors your video left to right before it's encoded, so the other
 * person sees exactly what your own preview shows.
 *
 * Camera frames are flipped on the GPU by changing the texture's transform
 * matrix (no pixel copy). Frames usually arrive sideways with a rotation to
 * apply on screen, so a left-right flip on screen is an up-down flip of a
 * frame rotated by 90 or 270 degrees.
 */
class FlipProcessor : VideoProcessor {
    @Volatile
    var flip: Boolean = false

    @Volatile
    private var sink: VideoSink? = null

    override fun setSink(sink: VideoSink?) {
        this.sink = sink
    }

    override fun onCapturerStarted(success: Boolean) = Unit
    override fun onCapturerStopped() = Unit

    override fun onFrameCaptured(frame: VideoFrame) {
        val out = sink ?: return
        if (!flip) {
            out.onFrame(frame)
            return
        }
        val flipped = flipped(frame)
        out.onFrame(flipped)
        flipped.release()
    }

    companion object {
        /** A new frame, flipped left to right as it will be shown. The caller releases it. */
        fun flipped(frame: VideoFrame): VideoFrame {
            val sideways = frame.rotation % 180 != 0
            val buffer = when (val source = frame.buffer) {
                is VideoFrame.TextureBuffer -> source.applyTransformMatrix(flipMatrix(sideways), source.width, source.height)
                else -> {
                    val i420 = source.toI420() ?: return frame.also { it.retain() }
                    flipI420(i420, sideways).also { i420.release() }
                }
            }
            return VideoFrame(buffer, frame.rotation, frame.timestampNs)
        }

        /** Mirrors texture coordinates in [0, 1]: across x, or across y for a sideways frame. */
        fun flipMatrix(sideways: Boolean): Matrix = Matrix().apply {
            preTranslate(0.5f, 0.5f)
            if (sideways) preScale(1f, -1f) else preScale(-1f, 1f)
            preTranslate(-0.5f, -0.5f)
        }

        /** The same for a frame in memory (rare: cameras normally hand over textures). */
        fun flipI420(src: VideoFrame.I420Buffer, sideways: Boolean): VideoFrame.I420Buffer {
            val width = src.width
            val height = src.height
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            val y = ByteBuffer.allocateDirect(width * height)
            val u = ByteBuffer.allocateDirect(chromaWidth * chromaHeight)
            val v = ByteBuffer.allocateDirect(chromaWidth * chromaHeight)
            flipPlane(src.dataY, src.strideY, y, width, height, sideways)
            flipPlane(src.dataU, src.strideU, u, chromaWidth, chromaHeight, sideways)
            flipPlane(src.dataV, src.strideV, v, chromaWidth, chromaHeight, sideways)
            return JavaI420Buffer.wrap(width, height, y, width, u, chromaWidth, v, chromaWidth, null)
        }

        private fun flipPlane(source: ByteBuffer, stride: Int, dst: ByteBuffer, width: Int, height: Int, upsideDown: Boolean) {
            val src = source.duplicate() // our own position, not the frame's
            val row = ByteArray(width)
            for (r in 0 until height) {
                val from = if (upsideDown) height - 1 - r else r
                src.position(from * stride)
                src.get(row, 0, width)
                if (!upsideDown) row.reverse()
                dst.put(row, 0, width) // rows go out in order; relative puts work back to Android 8
            }
            dst.rewind()
        }
    }
}
