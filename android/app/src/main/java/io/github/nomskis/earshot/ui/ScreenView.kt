package io.github.nomskis.earshot.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import io.github.nomskis.earshot.call.ProxyVideoSink
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import kotlin.math.max
import kotlin.math.min

/**
 * Their shared screen, fitted to the space, with pinch and double-tap zoom that shows the
 * screen's own pixels: frames are drawn into a texture the size of the shared screen, and the
 * zoom is a transform on that texture. Text stays as sharp as the sharer's capture, and a zoom
 * never waits for the next frame (a still screen sends few).
 */
@Composable
fun ScreenView(
    sink: ProxyVideoSink,
    eglContext: EglBase.Context,
    modifier: Modifier = Modifier,
    /** A single tap, for showing and hiding the call's buttons. */
    onTap: () -> Unit = {},
    description: String = "Shared screen: pinch or double-tap to zoom",
) {
    var zoom by remember(sink) { mutableStateOf(ScreenZoom()) }
    var view by remember(sink) { mutableStateOf<ScreenTextureView?>(null) }
    key(sink) {
        AndroidView(
            modifier = modifier
                .semantics { contentDescription = description }
                .pointerInput(sink) {
                    detectTransformGestures { centroid, pan, gesture, _ ->
                        val v = view ?: return@detectTransformGestures
                        val (cw, ch) = v.fittedSize()
                        zoom = zoom.zoomed(
                            factor = gesture,
                            focusX = centroid.x - size.width / 2f,
                            focusY = centroid.y - size.height / 2f,
                            panX = pan.x,
                            panY = pan.y,
                            contentW = cw,
                            contentH = ch,
                            viewW = size.width.toFloat(),
                            viewH = size.height.toFloat(),
                        )
                        v.zoom = zoom
                    }
                }
                .pointerInput(sink) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { point ->
                            val v = view ?: return@detectTapGestures
                            val (cw, ch) = v.fittedSize()
                            zoom = zoom.doubleTapped(point.x - size.width / 2f, point.y - size.height / 2f, cw, ch, size.width.toFloat(), size.height.toFloat())
                            v.zoom = zoom
                        },
                    )
                },
            factory = { context ->
                ScreenTextureView(context, eglContext).also {
                    view = it
                    sink.setTarget(it)
                }
            },
            update = { it.zoom = zoom },
            onRelease = { v ->
                sink.clearTarget(v)
                v.release()
                view = null
            },
        )
    }
}

/**
 * Zoom on a shared screen as plain numbers, so it can be unit tested: [scale] from 1 (the
 * whole screen fitted) up to [MAX_SCALE], and [x], [y] how far the zoomed picture is moved
 * from the centre, in view pixels.
 */
internal data class ScreenZoom(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    /**
     * Zoomed by [factor] keeping the point under ([focusX], [focusY]) (from the view's centre)
     * where it is, then moved by ([panX], [panY]); never past the picture's edges.
     */
    fun zoomed(
        factor: Float,
        focusX: Float,
        focusY: Float,
        panX: Float,
        panY: Float,
        contentW: Float,
        contentH: Float,
        viewW: Float,
        viewH: Float,
    ): ScreenZoom {
        val next = (scale * factor).coerceIn(1f, MAX_SCALE)
        val ratio = next / scale
        return ScreenZoom(
            scale = next,
            x = focusX - ratio * (focusX - x) + panX,
            y = focusY - ratio * (focusY - y) + panY,
        ).clamped(contentW, contentH, viewW, viewH)
    }

    /** In to [DOUBLE_TAP_SCALE] at the tapped point, or back out to the whole screen. */
    fun doubleTapped(focusX: Float, focusY: Float, contentW: Float, contentH: Float, viewW: Float, viewH: Float): ScreenZoom =
        if (scale > 1.05f) ScreenZoom() else zoomed(DOUBLE_TAP_SCALE / scale, focusX, focusY, 0f, 0f, contentW, contentH, viewW, viewH)

    /** Moved back so the picture covers the view wherever it's bigger than it, and centred where it isn't. */
    fun clamped(contentW: Float, contentH: Float, viewW: Float, viewH: Float): ScreenZoom {
        fun limit(offset: Float, content: Float, view: Float): Float {
            val spare = (content * scale - view) / 2f
            return if (spare <= 0f) 0f else offset.coerceIn(-spare, spare)
        }
        return copy(x = limit(x, contentW, viewW), y = limit(y, contentH, viewH))
    }

    companion object {
        const val MAX_SCALE = 6f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}

/**
 * A TextureView that draws frames at their own size and fits them with a transform, which
 * [zoom] then scales and moves. SurfaceView can't be transformed like this. Made in code only.
 */
@SuppressLint("ViewConstructor")
internal class ScreenTextureView(context: Context, eglContext: EglBase.Context) :
    TextureView(context), TextureView.SurfaceTextureListener, VideoSink {

    private val renderer = EglRenderer("EarshotScreenView")
    /** The frames' size, written on the render thread, read on the main one. */
    @Volatile
    private var frameW = 0
    @Volatile
    private var frameH = 0

    var zoom: ScreenZoom = ScreenZoom()
        set(value) {
            field = value
            applyTransform()
        }

    init {
        renderer.init(eglContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        surfaceTextureListener = this
    }

    override fun onFrame(frame: VideoFrame) {
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (w != frameW || h != frameH) {
            frameW = w
            frameH = h
            renderer.setLayoutAspectRatio(w.toFloat() / h)
            post { sizeBufferToFrames() }
        }
        renderer.onFrame(frame)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        sizeBufferToFrames()
        renderer.createEglSurface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // TextureView sizes its buffer to the view on layout; put the frames' size back.
        sizeBufferToFrames()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        renderer.releaseEglSurface { done.countDown() }
        done.await()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTransform()
    }

    /** The whole screen fitted into the view, before zoom: its width and height on screen. */
    fun fittedSize(): Pair<Float, Float> {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (frameW <= 0 || frameH <= 0 || vw <= 0f || vh <= 0f) return vw to vh
        val fit = min(vw / frameW, vh / frameH)
        return frameW * fit to frameH * fit
    }

    fun release() {
        renderer.release()
    }

    private fun sizeBufferToFrames() {
        val texture = surfaceTexture ?: return
        if (frameW > 0 && frameH > 0) texture.setDefaultBufferSize(frameW, frameH)
        applyTransform()
    }

    private fun applyTransform() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val (dw, dh) = fittedSize()
        val cx = vw / 2f
        val cy = vh / 2f
        val z = zoom
        val matrix = Matrix().apply {
            // The buffer is stretched over the view; shrink it to the fitted size, centred...
            setScale(dw / vw, dh / vh)
            postTranslate((vw - dw) / 2f, (vh - dh) / 2f)
            // ...then zoom about the centre and move.
            postScale(max(1f, z.scale), max(1f, z.scale), cx, cy)
            postTranslate(z.x, z.y)
        }
        setTransform(matrix)
    }
}
