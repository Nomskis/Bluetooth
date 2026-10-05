package io.github.nomskis.earshot.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import io.github.nomskis.earshot.messages.Conversation
import io.github.nomskis.earshot.messages.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pictures decoded for the screen, kept a while so scrolling back doesn't decode them again.
 * Each is decoded at no more than the size it's shown at.
 */
private object PhotoBitmaps {
    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun cached(key: String): ImageBitmap? = cache.get(key)

    /** [open] read twice: once for the size, once for the pixels, at most [maxPx] on the long side. */
    fun decode(key: String, maxPx: Int, open: () -> java.io.InputStream?): ImageBitmap? {
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bitmap = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        return bitmap.asImageBitmap().also { cache.put(key, it) }
    }
}

/**
 * A picture file, decoded off the main thread at about [maxPx]; null until it's ready (or if
 * it can't be). [version] changes when the same file gets a new picture (a profile picture).
 */
@Composable
internal fun rememberPhoto(file: File?, maxPx: Int, version: Long = 0): ImageBitmap? {
    val key = file?.let { "${it.path}#$version@$maxPx" }
    val image by produceState(key?.let(PhotoBitmaps::cached), key) {
        if (file == null || key == null) return@produceState
        value = withContext(Dispatchers.IO) { runCatching { PhotoBitmaps.decode(key, maxPx) { file.inputStream() } }.getOrNull() }
    }
    return image
}

/** A picture you picked, before it's sent. */
@Composable
private fun rememberPicked(uri: Uri, maxPx: Int): ImageBitmap? {
    val context = LocalContext.current
    val key = "$uri@$maxPx"
    val image by produceState(PhotoBitmaps.cached(key), key) {
        value = withContext(Dispatchers.IO) {
            runCatching { PhotoBitmaps.decode(key, maxPx) { context.contentResolver.openInputStream(uri) } }.getOrNull()
        }
    }
    return image
}

/** A picture in a message: its own shape while it loads, a tap opens it whole. */
@Composable
internal fun PhotoInBubble(photo: Photo, file: File?, sending: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val maxPx = with(LocalDensity.current) { PHOTO_WIDTH.roundToPx() }
    val image = rememberPhoto(file, maxPx)
    val ratio = (photo.width.toFloat() / photo.height.coerceAtLeast(1)).coerceIn(0.5f, 2f)
    Box(
        modifier
            .widthIn(max = PHOTO_WIDTH)
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.08f))
            .clickable(onClickLabel = "Open picture", onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        image?.let { Image(it, contentDescription = "Picture", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (sending) CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp, color = Color.White)
    }
}

private val PHOTO_WIDTH = 260.dp

/**
 * A picture on its own, on black: pinch and double-tap zoom, drag around, Save to the phone's
 * pictures and Share.
 */
@Composable
internal fun PhotoViewer(file: File, caption: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val maxPx = with(LocalDensity.current) { 2400.dp.roundToPx() }.coerceAtMost(4096)
    val image = rememberPhoto(file, maxPx)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            image?.let {
                Image(
                    it,
                    contentDescription = "Picture",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                if (scale > 1.05f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            })
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            } ?: CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val onDark = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                IconButton(onClick = onDismiss, colors = onDark) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                Box(Modifier.weight(1f))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    IconButton(onClick = { saveToPictures(context, file) }, colors = onDark) {
                        Icon(Icons.Filled.Download, contentDescription = "Save to your pictures")
                    }
                }
                IconButton(onClick = { sharePhoto(context, file) }, colors = onDark) { Icon(Icons.Filled.Share, contentDescription = "Share") }
            }
            if (caption.isNotBlank()) {
                Text(
                    caption,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .navigationBarsPadding()
                        .padding(16.dp),
                )
            }
        }
    }
}

/** The picture you picked, with a box for its words and Send: what WhatsApp shows before a picture goes. */
@Composable
internal fun PhotoPreview(uri: Uri, theirName: String, onSend: (caption: String) -> Unit, onDismiss: () -> Unit) {
    val maxPx = with(LocalDensity.current) { 1200.dp.roundToPx() }
    val image = rememberPicked(uri, maxPx)
    var caption by remember(uri) { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .imePadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                    Icon(Icons.Filled.Close, contentDescription = "Don't send")
                }
                Text("To $theirName", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                image?.let { Image(it, contentDescription = "Picture to send", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    ?: CircularProgressIndicator(color = Color.White)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = caption,
                    onValueChange = { caption = it.take(Conversation.MAX_TEXT) },
                    placeholder = { Text("Add a caption") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    maxLines = 4,
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(onClick = { onSend(caption) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send picture")
                }
            }
        }
    }
}

/** Shares through the app's file provider ([filesAuthority]). */
private fun sharePhoto(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, filesAuthority(context), file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null))
    }
}

/** Into Pictures/Earshot, where the gallery shows it (Android 10+: no permission needed). */
private fun saveToPictures(context: Context, file: File) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val saved = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Earshot ${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Earshot")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("No place to save")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("Couldn't write")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }.isSuccess
    Toast.makeText(context, if (saved) "Saved to your pictures" else "Couldn't save it", Toast.LENGTH_SHORT).show()
}

/** The app's file provider, for the camera and for sharing pictures. */
internal fun filesAuthority(context: Context): String = "${context.packageName}.files"

/** Where the camera puts a new picture before it's made ready to send; earlier ones are let go. */
internal fun cameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, filesAuthority(context), file)
}
