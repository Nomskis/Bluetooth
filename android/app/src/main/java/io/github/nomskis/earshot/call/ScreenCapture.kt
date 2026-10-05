package io.github.nomskis.earshot.call

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils
import org.webrtc.VideoFrame
import kotlin.math.max

/**
 * The phone's screen (or the one app the person chose to share) as video frames, for a
 * screen-share source. Three things WebRTC's own ScreenCapturerAndroid doesn't do:
 *
 * - **It makes its virtual display once and resizes it.** Android 14 allows one
 *   createVirtualDisplay per consent, and ScreenCapturerAndroid recreates its display on
 *   every size change, so turning the phone would end the share.
 * - **It sharpens a still screen.** A virtual display only makes a frame when something
 *   changes, so after a scroll the picture would stay at whatever quality that last frame got.
 *   The last frame goes again a few times a second for a couple of seconds, letting the
 *   encoder spend its bits refining it, then slowly, to keep the stream alive
 *   ([ScreenTuning.repeatEveryMs]).
 * - **Every frame gets a fresh timestamp**, so the encoder doesn't drop a repeat as old.
 *
 * Runs on its own thread (the texture helper's); [start] and [stop] can be called from any.
 */
class ScreenCapture(
    context: Context,
    private val projection: MediaProjection,
    eglContext: EglBase.Context,
    private val observer: CapturerObserver,
    /** The capture's short side is at most this ([ScreenTuning.captureSize]). */
    private val maxShortSide: Int,
    /** Sharing ended outside the app: the status bar chip, the phone locking, another app projecting. */
    private val onStopped: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val helper: SurfaceTextureHelper = SurfaceTextureHelper.create("EarshotScreen", eglContext)
    private val handler: Handler = helper.handler
    private var display: VirtualDisplay? = null
    private var size = 0 to 0
    private var running = false
    /** Given back, for good: after this nothing here runs again. */
    private var stopped = false

    /** The texture timestamp of the newest content, and when it arrived. */
    private var contentTimestampNs = Long.MIN_VALUE
    private var contentAt = 0L
    private var repeatedAt = 0L
    private var lastSentNs = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            handler.post {
                if (!running) return@post
                Log.i(TAG, "Screen sharing stopped by the system")
                stopHere()
                onStopped()
            }
        }

        // Android 14+: the shared app's window, or the whole screen when the phone turns.
        override fun onCapturedContentResize(width: Int, height: Int) {
            handler.post { resizeTo(width, height) }
        }
    }

    /** Before Android 14 nothing says the screen turned; the display does. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val (w, h) = screenSize()
            resizeTo(w, h)
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val every = if (contentAt > 0) ScreenTuning.repeatEveryMs(now - contentAt) else null
            if (every != null && now - repeatedAt >= every) {
                repeatedAt = now
                // Sends the last frame again if nothing new came in meanwhile.
                helper.forceFrame()
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        ThreadUtils.invokeAtFrontUninterruptibly(handler) {
            check(!stopped) { "Screen capture already stopped" }
            // Android 14 refuses to make the display without a callback registered first.
            projection.registerCallback(projectionCallback, handler)
            val (screenW, screenH) = screenSize()
            val (w, h) = ScreenTuning.captureSize(screenW, screenH, maxShortSide)
            size = w to h
            helper.setTextureSize(w, h)
            helper.startListening(::onFrame)
            display = projection.createVirtualDisplay(
                "EarshotScreen",
                w,
                h,
                densityDpi(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                Surface(helper.surfaceTexture),
                null,
                handler,
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                appContext.getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, handler)
            }
            running = true
            observer.onCapturerStarted(true)
            handler.postDelayed(tick, TICK_MS)
            Log.i(TAG, "Sharing the screen at ${w}x$h")
        }
    }

    /** Stops capturing and gives the screen back; safe to call more than once. */
    fun stop() {
        ThreadUtils.invokeAtFrontUninterruptibly(handler) { stopHere() }
        helper.dispose()
    }

    /** Everything [start] set up, also after a start that failed half way. */
    private fun stopHere() {
        if (stopped) return
        stopped = true
        val wasRunning = running
        running = false
        handler.removeCallbacks(tick)
        appContext.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        helper.stopListening()
        display?.release()
        display = null
        projection.unregisterCallback(projectionCallback)
        // Gives the screen back: the status bar chip goes, and the call's service stops saying it captures.
        projection.stop()
        if (wasRunning) observer.onCapturerStopped()
    }

    private fun onFrame(frame: VideoFrame) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (frame.timestampNs != contentTimestampNs) {
            contentTimestampNs = frame.timestampNs
            contentAt = now
        }
        // Strictly increasing, so a repeat isn't dropped as an old frame. The buffer stays the
        // helper's: the observer takes its own reference where it needs one.
        val timestamp = max(System.nanoTime(), lastSentNs + MIN_FRAME_GAP_NS)
        lastSentNs = timestamp
        observer.onFrameCaptured(VideoFrame(frame.buffer, frame.rotation, timestamp))
    }

    private fun resizeTo(width: Int, height: Int) {
        val target = ScreenTuning.captureSize(width, height, maxShortSide)
        val current = display ?: return
        if (target == size || target.first <= 0) return
        size = target
        helper.setTextureSize(target.first, target.second)
        current.resize(target.first, target.second, densityDpi())
        Log.i(TAG, "Screen now ${target.first}x${target.second}")
    }

    /**
     * The whole screen as it's turned now. From the display itself: a window manager from
     * the app's context (there's no window here) can be stale after the phone turns.
     */
    private fun screenSize(): Pair<Int, Int> {
        val display = appContext.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0 to 0
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun densityDpi(): Int = appContext.resources.configuration.densityDpi

    private companion object {
        const val TAG = "EarshotScreen"
        const val TICK_MS = 125L
        /** One millisecond. */
        const val MIN_FRAME_GAP_NS = 1_000_000L
    }
}
