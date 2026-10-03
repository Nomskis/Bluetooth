package io.github.nomskis.earshot.call

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.ArrayDeque

/**
 * Passes video frames on [delayMs] later, for [LipSync]. Frames from a
 * hardware decoder live in a texture the decoder needs back before it can
 * output the next one, so held frames are copied to CPU memory (I420)
 * first; at 0 delay frames pass straight through untouched.
 */
class DelayedVideoSink(private val target: VideoSink) : VideoSink {
    @Volatile
    var delayMs: Int = 0

    private val thread = HandlerThread("EarshotLipSync").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private val pending = ArrayDeque<Pair<Long, VideoFrame>>()
    private var lastDue = 0L
    private var released = false

    override fun onFrame(frame: VideoFrame) {
        val delay = delayMs
        // Pass straight through unless something is already waiting (keeps order when the delay drops to 0).
        if (delay <= 0 && synchronized(lock) { pending.isEmpty() }) {
            target.onFrame(frame)
            return
        }
        val held = hold(frame) ?: return
        synchronized(lock) {
            if (released) {
                held.release()
                return
            }
            // Keep order when the delay shrinks: never schedule before the previous frame.
            val due = maxOf(SystemClock.uptimeMillis() + delay, lastDue)
            lastDue = due
            pending.addLast(due to held)
            handler.postAtTime(::deliverDue, due)
        }
    }

    private fun hold(frame: VideoFrame): VideoFrame? {
        val buffer = frame.buffer
        return if (buffer is VideoFrame.TextureBuffer) {
            val copy = buffer.toI420() ?: return null
            VideoFrame(copy, frame.rotation, frame.timestampNs)
        } else {
            frame.retain()
            frame
        }
    }

    private fun deliverDue() {
        val now = SystemClock.uptimeMillis()
        while (true) {
            val next = synchronized(lock) {
                val head = pending.peekFirst()
                if (head == null || head.first > now) null else pending.pollFirst()
            } ?: return
            try {
                target.onFrame(next.second)
            } finally {
                next.second.release()
            }
        }
    }

    fun release() {
        synchronized(lock) {
            released = true
            handler.removeCallbacksAndMessages(null)
            while (pending.isNotEmpty()) pending.pollFirst()?.second?.release()
        }
        thread.quitSafely()
    }
}
