package io.github.nomskis.earshot.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Earshot's green, for phones before Android 12, which have no wallpaper colours to use. */
private val EarshotLight = lightColorScheme(
    primary = Color(0xFF006C4D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF8BF8C3),
    onPrimaryContainer = Color(0xFF002115),
    inversePrimary = Color(0xFF6FDBA8),
    secondary = Color(0xFF4C6357),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE9D9),
    onSecondaryContainer = Color(0xFF092016),
    tertiary = Color(0xFF3D6373),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC1E8FB),
    onTertiaryContainer = Color(0xFF001F29),
    background = Color(0xFFF5FBF5),
    onBackground = Color(0xFF171D19),
    surface = Color(0xFFF5FBF5),
    onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFDBE5DD),
    onSurfaceVariant = Color(0xFF404943),
    inverseSurface = Color(0xFF2C322E),
    inverseOnSurface = Color(0xFFECF2EC),
    outline = Color(0xFF707973),
    outlineVariant = Color(0xFFBFC9C1),
    surfaceBright = Color(0xFFF5FBF5),
    surfaceDim = Color(0xFFD5DBD6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5EF),
    surfaceContainer = Color(0xFFE9EFE9),
    surfaceContainerHigh = Color(0xFFE4EAE4),
    surfaceContainerHighest = Color(0xFFDEE4DE),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val EarshotDark = darkColorScheme(
    primary = Color(0xFF6FDBA8),
    onPrimary = Color(0xFF003825),
    primaryContainer = Color(0xFF00513A),
    onPrimaryContainer = Color(0xFF8BF8C3),
    inversePrimary = Color(0xFF006C4D),
    secondary = Color(0xFFB3CCBE),
    onSecondary = Color(0xFF1F352A),
    secondaryContainer = Color(0xFF354B40),
    onSecondaryContainer = Color(0xFFCFE9D9),
    tertiary = Color(0xFFA5CCDF),
    onTertiary = Color(0xFF073543),
    tertiaryContainer = Color(0xFF244C5B),
    onTertiaryContainer = Color(0xFFC1E8FB),
    background = Color(0xFF0F1511),
    onBackground = Color(0xFFDFE4DD),
    surface = Color(0xFF0F1511),
    onSurface = Color(0xFFDFE4DD),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFBFC9C1),
    inverseSurface = Color(0xFFDFE4DD),
    inverseOnSurface = Color(0xFF2C322E),
    outline = Color(0xFF89938C),
    outlineVariant = Color(0xFF404943),
    surfaceBright = Color(0xFF353B37),
    surfaceDim = Color(0xFF0F1511),
    surfaceContainerLowest = Color(0xFF0A0F0C),
    surfaceContainerLow = Color(0xFF171D19),
    surfaceContainer = Color(0xFF1B211D),
    surfaceContainerHigh = Color(0xFF252B27),
    surfaceContainerHighest = Color(0xFF303632),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Whether the theme in use is dark, for the few colours that aren't in the scheme. */
private val LocalDark = staticCompositionLocalOf { false }

/**
 * Your wallpaper's colours on Android 12 and later, Earshot's green before that; light or
 * dark as the phone is set. The phone's own font, so Earshot looks like it belongs there.
 */
@Composable
fun EarshotTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = remember(context, dark) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> EarshotDark
            else -> EarshotLight
        }
    }
    // Text and icons outside a Surface follow the theme too (a call's dark screen inside a light app).
    CompositionLocalProvider(LocalDark provides dark, LocalContentColor provides scheme.onSurface) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Calls are always dark, like the phone app's: they sit next to video, and a bright screen at night is unkind. */
@Composable
fun CallTheme(content: @Composable () -> Unit) = EarshotTheme(dark = true, content = content)

/** Ending or declining a call, and nothing else. White on it. */
val HangUpRed = Color(0xFFD93025)

/** Answering a call, and the bar back to one. White on it. */
val AnswerGreen = Color(0xFF1E8E3E)

/** The surfaces lists sit on, and a few colours that depend on light or dark. */
object Tones {
    /** Behind grouped lists: a shade off the rows, as in Android's own settings. */
    val page: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDark.current) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer

    /** A row in a group, a notice, a message of theirs. */
    val row: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDark.current) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLowest

    /** Their messages: a step off the page, light or dark. */
    val bubble: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDark.current) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest

    /** Something to look at but not alarming: a weak measurement, a tip. */
    val warning: Color
        @Composable @ReadOnlyComposable
        get() = if (LocalDark.current) Color(0xFFF5B83D) else Color(0xFF8A5100)

    /** A person's colour, the same every time for the same person: (background, initial). */
    @Composable @ReadOnlyComposable
    fun person(seed: String): Pair<Color, Color> {
        val (light, dark) = PEOPLE[Math.floorMod(seed.hashCode(), PEOPLE.size)]
        return if (LocalDark.current) dark else light
    }

    // Soft tones that read as a name's colour, not a status: no red (missed calls) and no
    // strong green (answering). Each as (light, dark), and each pair high in contrast.
    private val PEOPLE = listOf(
        (Color(0xFFD3E3FD) to Color(0xFF041E49)) to (Color(0xFF0842A0) to Color(0xFFD3E3FD)),
        (Color(0xFFBDEDE6) to Color(0xFF00201C)) to (Color(0xFF00504A) to Color(0xFFBDEDE6)),
        (Color(0xFFDDEBC4) to Color(0xFF141F02)) to (Color(0xFF3B4B20) to Color(0xFFDDEBC4)),
        (Color(0xFFFFE08A) to Color(0xFF241A00)) to (Color(0xFF574500) to Color(0xFFFFE08A)),
        (Color(0xFFFFDBC9) to Color(0xFF331200)) to (Color(0xFF733400) to Color(0xFFFFDBC9)),
        (Color(0xFFFFD8E7) to Color(0xFF3B0723)) to (Color(0xFF7A2950) to Color(0xFFFFD8E7)),
        (Color(0xFFE8DEF8) to Color(0xFF1D192B)) to (Color(0xFF4A4458) to Color(0xFFE8DEF8)),
        (Color(0xFFDBE4EA) to Color(0xFF111D24)) to (Color(0xFF3B4950) to Color(0xFFDBE4EA)),
    )
}
