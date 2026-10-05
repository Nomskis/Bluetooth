package io.github.nomskis.earshot.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.nomskis.earshot.ui.theme.HangUpRed
import io.github.nomskis.earshot.ui.theme.Tones
import java.io.File

/** A person: the first letter of their name on their own colour, the same everywhere. */
@Composable
internal fun Avatar(name: String, modifier: Modifier = Modifier, seed: String = name, size: Dp = 40.dp) {
    val (background, letter) = Tones.person(seed)
    // In dp, not sp: a large font setting mustn't push the letter out of its circle.
    val fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() }
    // Their profile picture, once it's here and decoded; their initial until then.
    val picture = LocalAvatars.current(seed)
    val image = picture?.let { rememberPhoto(it.file, with(LocalDensity.current) { size.roundToPx() }, it.version) }
    Box(modifier.size(size).clip(CircleShape).background(background, CircleShape), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Text(initial(name), color = letter, fontSize = fontSize, fontWeight = FontWeight.Medium)
        }
    }
}

/** A profile picture's file, and a version that changes with it. */
data class AvatarPicture(val file: File, val version: Long)

/**
 * Profile pictures by the seed avatars use (an address, or [io.github.nomskis.earshot.messages.Profiles.ME]);
 * none unless the app provides them.
 */
val LocalAvatars = staticCompositionLocalOf<(String) -> AvatarPicture?> { { null } }

/** The first letter, whole even when it's an emoji or an accented letter made of two chars. */
internal fun initial(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    return String(Character.toChars(trimmed.codePointAt(0))).uppercase()
}

private val GroupOuter = 20.dp
private val GroupInner = 4.dp

/** Gap between the rows of a group. */
internal val GroupGap = 2.dp

/**
 * Rows of one group share a container: rounded where it starts and ends, nearly square
 * between, so they read as one thing, as in Android's own settings.
 */
internal fun groupShape(index: Int, count: Int): Shape {
    val top = if (index == 0) GroupOuter else GroupInner
    val bottom = if (index == count - 1) GroupOuter else GroupInner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** A row's place in its group: its corners and its colour. */
internal fun Modifier.groupRow(index: Int, count: Int, color: Color): Modifier =
    fillMaxWidth().clip(groupShape(index, count)).background(color)

/** Rows built one by one, some only sometimes, shown as one group under an optional heading. */
@Composable
internal fun Group(title: String?, rows: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    val color = Tones.row
    Column(modifier.fillMaxWidth()) {
        title?.let { SectionHeader(it) }
        Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
            rows.forEachIndexed { i, row ->
                Box(Modifier.groupRow(i, rows.size, color)) { row() }
            }
        }
    }
}

/** A heading over a group. */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

/**
 * Something that needs you for a moment (an update, a setup step, a call that was cut off),
 * at the top of the list until it's dealt with.
 */
@Composable
internal fun Notice(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    /** Something's wrong (the server can't be reached), not only waiting for you. */
    warning: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val background = if (warning) colors.errorContainer else colors.secondaryContainer
    val text = if (warning) colors.onErrorContainer else colors.onSecondaryContainer
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GroupOuter))
            .background(background)
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = text)
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = text) }
        CompositionLocalProvider(LocalContentColor provides text) { content() }
    }
}

/** One thing to switch on, with the button to where it's switched on. */
@Composable
internal fun NoticeStep(title: String, detail: String, action: String?, onAction: () -> Unit, done: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = if (done) "Done" else null,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        action?.let { FilledTonalButton(onClick = onAction) { Text(it) } }
    }
}

/** A notice's buttons, after a little space. */
@Composable
internal fun NoticeActions(content: @Composable () -> Unit) {
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

/** How big the call buttons are: big enough to find without looking twice. */
internal val CallButtonSize = 64.dp

/**
 * A call button. Round when off, a rounded square when on, and it gives a little under your
 * finger: the shape says the state, not only the colour.
 */
@Composable
internal fun CallToggleButton(icon: ImageVector, label: String, checked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = when {
            pressed -> 14.dp
            checked -> 20.dp
            else -> CallButtonSize / 2
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "call button shape",
    )
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (checked) colors.inverseSurface else colors.surfaceContainerHighest, label = "call button colour")
    val content by animateColorAsState(if (checked) colors.inverseOnSurface else colors.onSurface, label = "call button icon")
    Box(
        modifier
            .size(CallButtonSize)
            .clip(RoundedCornerShape(corner))
            .background(container)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = content)
    }
}

/** Hang up: red and wider than the rest, so it can't be mistaken for anything else. */
@Composable
internal fun HangUpButton(onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 112.dp) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) 18.dp else CallButtonSize / 2,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "hang up shape",
    )
    Box(
        modifier
            .size(width = width, height = CallButtonSize)
            .clip(RoundedCornerShape(corner))
            .background(HangUpRed)
            .clickable(interactionSource = interaction, indication = ripple(color = Color.White), role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.CallEnd, contentDescription = "Hang up", tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

/** A wide answer or decline button with its word on it. */
@Composable
internal fun AnswerPill(icon: ImageVector, label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) 20.dp else 36.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "answer shape",
    )
    Row(
        modifier
            .height(72.dp)
            .clip(RoundedCornerShape(corner))
            .background(color)
            .clickable(interactionSource = interaction, indication = ripple(color = Color.White), role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Light status bar icons (and navigation bar ones, unless [navigation] is false) while this is
 * on screen: for the call's dark screen and the green call bar when the rest of the app is
 * light. Put back as they were after.
 */
@Composable
internal fun DarkSystemBars(navigation: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(view, navigation) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = controller?.isAppearanceLightStatusBars
        val lightNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        if (navigation) controller?.isAppearanceLightNavigationBars = false
        onDispose {
            lightStatus?.let { controller.isAppearanceLightStatusBars = it }
            if (navigation) lightNavigation?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * "Remove Sam?": asked before anything that can't be undone. The action that does it is
 * named ("Delete", not "OK") and red; Cancel, or a tap outside, leaves everything as it was.
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
