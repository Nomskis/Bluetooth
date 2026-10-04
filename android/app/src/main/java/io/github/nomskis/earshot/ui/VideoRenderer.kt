package io.github.nomskis.earshot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.nomskis.earshot.call.ProxyVideoSink
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Shows a WebRTC video feed. The renderer is plugged into [sink] while it is
 * on screen and unplugged before it is released, so frames never hit a dead
 * surface.
 */
@Composable
fun VideoRenderer(
    sink: ProxyVideoSink,
    eglContext: EglBase.Context,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    /** Set for a small preview drawn on top of another video. */
    overlay: Boolean = false,
    fit: Boolean = false,
) {
    key(sink) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    init(eglContext, null)
                    setEnableHardwareScaler(true)
                    if (overlay) setZOrderMediaOverlay(true)
                    sink.setTarget(this)
                }
            },
            update = { renderer ->
                renderer.setMirror(mirror)
                renderer.setScalingType(
                    if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT else RendererCommon.ScalingType.SCALE_ASPECT_FILL,
                )
            },
            onRelease = { renderer ->
                // Swapping the big and small video makes the new renderer before the old one goes.
                sink.clearTarget(renderer)
                renderer.release()
            },
        )
    }
}
