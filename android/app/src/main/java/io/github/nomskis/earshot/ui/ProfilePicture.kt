package io.github.nomskis.earshot.ui

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nomskis.earshot.messages.Profiles
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Every screen below sees profile pictures as they arrive ([LocalAvatars]). */
@Composable
fun ProvideAvatars(profiles: Profiles, content: @Composable () -> Unit) {
    val versions by profiles.versions.collectAsStateWithLifecycle()
    val lookup = remember(versions) {
        { who: String -> versions[who]?.let { AvatarPicture(profiles.file(who), it) } }
    }
    CompositionLocalProvider(LocalAvatars provides lookup, content = content)
}

/**
 * Your picture, chosen from a photo: pinch and drag it inside the circle, then Use. What's in
 * the circle (its square, really: other phones draw it round) becomes the picture.
 */
@Composable
internal fun ProfileCrop(source: Bitmap, onUse: (Bitmap) -> Unit, onDismiss: () -> Unit) {
    val image = remember(source) { source.asImageBitmap() }
    var scale by remember(source) { mutableFloatStateOf(1f) }
    var offset by remember(source) { mutableStateOf(Offset.Zero) }
    var side by remember { mutableFloatStateOf(0f) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Text(
                "Your picture",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(20.dp),
            )
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val square = if (maxWidth < maxHeight) maxWidth else maxHeight
                side = with(LocalDensity.current) { square.toPx() }
                Box(
                    Modifier
                        .size(square)
                        .pointerInput(source) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, ProfileCropping.MAX_SCALE)
                                offset = ProfileCropping.clamp(offset + pan, source.width, source.height, side, scale)
                            }
                        },
                ) {
                    Image(
                        image,
                        contentDescription = "Your picture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    )
                    // Dim everything but the circle.
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
                    ) {
                        drawRect(Color.Black.copy(alpha = 0.6f))
                        drawCircle(Color.Transparent, radius = size.minDimension / 2f, blendMode = BlendMode.Clear)
                        drawCircle(Color.White.copy(alpha = 0.8f), radius = size.minDimension / 2f, style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
                TextButton(onClick = {
                    val (left, top, size) = ProfileCropping.crop(source.width, source.height, side, scale, offset)
                    runCatching { Bitmap.createBitmap(source, left, top, size, size) }.getOrNull()?.let(onUse)
                }) { Text("Use", color = Color.White) }
            }
        }
    }
}

/** The sums behind [ProfileCrop], kept apart so they can be unit tested. */
internal object ProfileCropping {
    const val MAX_SCALE = 5f

    /** How far the picture may move at [scale] and still cover the whole [side] by [side] square. */
    fun clamp(offset: Offset, width: Int, height: Int, side: Float, scale: Float): Offset {
        if (side <= 0f || width <= 0 || height <= 0) return Offset.Zero
        val k = cover(width, height, side) * scale
        val spareX = max(0f, (width * k - side) / 2f)
        val spareY = max(0f, (height * k - side) / 2f)
        return Offset(offset.x.coerceIn(-spareX, spareX), offset.y.coerceIn(-spareY, spareY))
    }

    /** The square of the source picture showing in the [side] by [side] view: left, top and side, in its pixels. */
    fun crop(width: Int, height: Int, side: Float, scale: Float, offset: Offset): Triple<Int, Int, Int> {
        val short = min(width, height)
        if (side <= 0f) return Triple((width - short) / 2, (height - short) / 2, short)
        val k = cover(width, height, side) * scale
        val size = (side / k).roundToInt().coerceIn(1, short)
        val left = (width / 2f - (side / 2f + offset.x) / k).roundToInt().coerceIn(0, width - size)
        val top = (height / 2f - (side / 2f + offset.y) / k).roundToInt().coerceIn(0, height - size)
        return Triple(left, top, size)
    }

    /** The scale at which the picture just covers the square, as ContentScale.Crop draws it. */
    private fun cover(width: Int, height: Int, side: Float) = max(side / width, side / height)
}

/**
 * Your picture, at the top of Settings: tap it to choose a photo, take one, or take it away.
 * A photo opens for cropping ([ProfileCrop]) first.
 */
@Composable
internal fun ProfilePictureRow(
    name: String,
    hasPhoto: Boolean,
    onPicked: (Uri) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var shot by remember { mutableStateOf<Uri?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(onPicked) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken -> if (taken) shot?.let(onPicked) }
    fun takePicture() {
        val uri = runCatching { cameraUri(context) }.getOrNull() ?: return
        shot = uri
        runCatching { camera.launch(uri) }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) takePicture() }
    if (removing) {
        ConfirmDialog(
            title = "Remove your picture?",
            text = "Your contacts see your initial again.",
            confirm = "Remove",
            onConfirm = onRemove,
            onDismiss = { removing = false },
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (hasPhoto) "Change your picture" else "Add a picture") { menu = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box {
            Avatar(name.ifBlank { "?" }, seed = Profiles.ME, size = 64.dp)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Choose a photo") },
                    leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                    onClick = {
                        menu = false
                        runCatching { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Take a photo") },
                    leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                    onClick = {
                        menu = false
                        if (context.hasPermission(Manifest.permission.CAMERA)) takePicture() else cameraPermission.launch(Manifest.permission.CAMERA)
                    },
                )
                if (hasPhoto) {
                    DropdownMenuItem(
                        text = { Text("Remove picture") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menu = false
                            removing = true
                        },
                    )
                }
            }
        }
        Column {
            Text(if (hasPhoto) "Your picture" else "Add a picture", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your contacts see it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
