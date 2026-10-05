package io.github.nomskis.earshot.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import io.github.nomskis.earshot.ui.POINT_COLOR
import io.github.nomskis.earshot.ui.POINT_RING_MS
import androidx.compose.ui.graphics.toArgb

/**
 * While we share our screen: a ring where the other person points ([show]), over whatever
 * app is on screen, so both see the same spot. Needs Android's "Display over other apps"
 * for Earshot (the call screen's sharing banner offers it); without it, nothing shows.
 *
 * The ring's window takes no touches and is see-through enough (Android 12+ lets touches
 * pass only under windows at most 80% opaque), and it's only there while a ring is.
 */
class ScreenPointer(context: Context) {
    private val appContext = context.applicationContext
    private val windows = appContext.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: RingView? = null
    private val removeLater = Runnable { detach() }

    /** A ring at ([x], [y]), 0 to 1 across and down the shared screen. Main thread. */
    fun show(x: Float, y: Float) {
        if (!Settings.canDrawOverlays(appContext)) return
        val (w, h) = screenSize()
        if (w <= 0 || h <= 0) return
        val ring = view ?: attach() ?: return
        ring.ring(x * w, y * h)
        main.removeCallbacks(removeLater)
        main.postDelayed(removeLater, POINT_RING_MS + 300L)
    }

    /** Sharing ended. */
    fun release() {
        main.removeCallbacks(removeLater)
        detach()
    }

    private fun attach(): RingView? {
        val ring = RingView(appContext)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            alpha = MAX_PASS_THROUGH_ALPHA
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
            title = "Earshot pointer"
        }
        return runCatching { windows.addView(ring, params) }
            .onFailure { Log.w(TAG, "Couldn't show where they point", it) }
            .map { ring.also { view = it } }
            .getOrNull()
    }

    private fun detach() {
        val ring = view ?: return
        view = null
        runCatching { windows.removeView(ring) }
    }

    /** The whole screen as it's turned now, as the share captures it. */
    private fun screenSize(): Pair<Int, Int> {
        val display = appContext.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0 to 0
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    /** Rings at points on the screen, each growing and fading like the viewer's own. */
    private class RingView(context: Context) : View(context) {
        private val rings = ArrayDeque<Triple<Float, Float, Long>>()
        private val density = context.resources.displayMetrics.density
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6 * density
            color = Color.BLACK
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            color = POINT_COLOR.toArgb()
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = POINT_COLOR.toArgb() }
        private val where = IntArray(2)

        /** A ring at ([screenX], [screenY]) on the display. */
        fun ring(screenX: Float, screenY: Float) {
            rings.addLast(Triple(screenX, screenY, SystemClock.uptimeMillis()))
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            val now = SystemClock.uptimeMillis()
            while (rings.isNotEmpty() && now - rings.first().third >= POINT_RING_MS) rings.removeFirst()
            // The window may not start at the display's corner (a cutout, a bar): draw relative to it.
            getLocationOnScreen(where)
            for ((x, y, at) in rings) {
                val progress = (now - at).toFloat() / POINT_RING_MS
                val radius = (18 + 30 * progress) * density
                val alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
                val cx = x - where[0]
                val cy = y - where[1]
                shadow.alpha = (alpha * 0.45f).toInt()
                stroke.alpha = alpha
                dot.alpha = alpha
                canvas.drawCircle(cx, cy, radius, shadow)
                canvas.drawCircle(cx, cy, radius, stroke)
                canvas.drawCircle(cx, cy, 5 * density, dot)
            }
            if (rings.isNotEmpty()) postInvalidateOnAnimation()
        }
    }

    private companion object {
        const val TAG = "EarshotScreen"
        /** Android 12+ passes touches through a non-touchable window from another app only up to this opacity. */
        const val MAX_PASS_THROUGH_ALPHA = 0.8f
    }
}
