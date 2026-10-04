package io.github.nomskis.earshot.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.nomskis.earshot.call.ProxyVideoSink
import org.webrtc.EglBase
import kotlin.math.roundToInt

private val WIDTH = 108.dp
private val HEIGHT = 156.dp

/**
 * The small video in a call: drag it anywhere on the screen, tap it to swap it
 * with the big one. It starts in the bottom corner, above the call buttons.
 * [area] is the size of the screen it moves on; [key] resets its place (a new call).
 */
@Composable
internal fun FloatingVideo(
    sink: ProxyVideoSink,
    eglContext: EglBase.Context,
    area: IntSize,
    key: Any,
    onTap: () -> Unit,
    description: String,
) {
    val density = LocalDensity.current
    val size = with(density) { IntSize(WIDTH.roundToPx(), HEIGHT.roundToPx()) }
    val margin = with(density) { 16.dp.roundToPx() }
    val aboveButtons = with(density) { 120.dp.roundToPx() }
    val top = WindowInsets.statusBars.getTop(density)
    val bottom = WindowInsets.navigationBars.getBottom(density)
    val bounds = FloatingVideoBounds(size, area, top = top + margin, bottom = bottom + margin, sides = margin)
    var moved by remember(key) { mutableStateOf<Offset?>(null) }
    val start = Offset((area.width - size.width - margin).toFloat(), (area.height - size.height - bottom - aboveButtons).toFloat())
    val position = bounds.clamp(moved ?: start)

    Box(
        Modifier
            .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
            .size(WIDTH, HEIGHT)
            .clip(RoundedCornerShape(14.dp)),
    ) {
        // The frames as sent, Flip included: what you see is what they see.
        VideoRenderer(sink = sink, eglContext = eglContext, mirror = false, overlay = true, modifier = Modifier.fillMaxSize())
        // On top of the video's own surface, which doesn't take touches itself.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(bounds) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        moved = bounds.clamp((moved ?: start) + drag)
                    }
                }
                .pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) }
                .semantics { contentDescription = description },
        )
    }
}

/** Where the small video may go: inside [area], clear of the status and navigation bars. */
internal data class FloatingVideoBounds(val size: IntSize, val area: IntSize, val top: Int, val bottom: Int, val sides: Int) {
    fun clamp(position: Offset): Offset {
        val maxX = (area.width - size.width - sides).coerceAtLeast(sides)
        val maxY = (area.height - size.height - bottom).coerceAtLeast(top)
        return Offset(position.x.coerceIn(sides.toFloat(), maxX.toFloat()), position.y.coerceIn(top.toFloat(), maxY.toFloat()))
    }
}
