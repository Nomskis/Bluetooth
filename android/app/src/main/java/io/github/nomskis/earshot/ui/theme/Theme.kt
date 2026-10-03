package io.github.nomskis.earshot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF3CCF91)
val Warning = Color(0xFFF5B83D)
val Danger = Color(0xFFE5484D)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF062418),
    primaryContainer = Color(0xFF1B5E4B),
    onPrimaryContainer = Color(0xFFE8FFF6),
    secondary = Color(0xFF9AA3B2),
    background = Color(0xFF0E1014),
    onBackground = Color(0xFFEEF1F6),
    surface = Color(0xFF0E1014),
    onSurface = Color(0xFFEEF1F6),
    surfaceVariant = Color(0xFF171A21),
    onSurfaceVariant = Color(0xFF9AA3B2),
    surfaceContainer = Color(0xFF171A21),
    surfaceContainerHigh = Color(0xFF222631),
    error = Danger,
)

/** Always dark: it is mostly used next to a video feed, often in a bright gym. */
@Composable
fun EarshotTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
