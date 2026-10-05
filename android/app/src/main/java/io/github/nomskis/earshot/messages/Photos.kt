// Android's own EXIF reader: its known bugs are in versions before the app's oldest (8.0).
@file:SuppressLint("ExifInterface")

package io.github.nomskis.earshot.messages

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.core.graphics.scale
import io.github.nomskis.earshot.call.Ids
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pictures in chats, kept as files in the app's own storage (`photos/`), not in the
 * conversations' JSON. Ours are made ready to send by [prepare]: upright, at most
 * [PhotoSizing.MAX_EDGE] pixels on the long side and about [PhotoSizing.TARGET_BYTES], a
 * second or two on a slow link; theirs are saved as they come.
 */
class Photos(context: Context) : Messenger.PhotoFiles {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "photos").apply { mkdirs() }

    fun file(name: String): File = File(dir, name)

    override fun read(name: String): ByteArray? = runCatching { file(name).readBytes() }.getOrNull()

    override fun save(bytes: ByteArray): String? {
        val name = Ids.random(12) + ".jpg"
        val temp = File(dir, "$name.tmp")
        return runCatching {
            temp.writeBytes(bytes)
            check(temp.renameTo(file(name)))
            name
        }.onFailure {
            temp.delete()
            Log.w(TAG, "Couldn't keep a picture", it)
        }.getOrNull()
    }

    override fun delete(names: Collection<String>) {
        for (name in names) file(name).delete()
    }

    /**
     * The picture at [uri] (from the photo picker or the camera) ready to send and kept here:
     * turned upright, at most [maxEdge] pixels on its long side, a JPEG of about
     * [PhotoSizing.TARGET_BYTES]. Null if it can't be read. Slow: not on the main thread.
     */
    fun prepare(uri: Uri, maxEdge: Int = PhotoSizing.MAX_EDGE): Photo? = runCatching {
        val base = decodeUpright(uri, maxEdge) ?: return null
        var edge = maxEdge
        var bytes: ByteArray
        var shaped: Bitmap
        // Smaller until it's light enough: a little less quality first, then fewer pixels.
        while (true) {
            shaped = scaled(base, edge)
            bytes = PhotoSizing.QUALITIES.firstNotNullOfOrNull { quality ->
                jpeg(shaped, quality).takeIf { it.size <= PhotoSizing.TARGET_BYTES }
            } ?: jpeg(shaped, PhotoSizing.QUALITIES.last())
            if (bytes.size <= PhotoSizing.TARGET_BYTES || edge <= PhotoSizing.MIN_EDGE) break
            if (shaped !== base) shaped.recycle()
            edge = (edge * 0.8f).roundToInt()
        }
        val name = save(bytes) ?: return null
        Photo(name, shaped.width, shaped.height).also {
            if (shaped !== base) shaped.recycle()
            base.recycle()
        }
    }.onFailure { Log.w(TAG, "Couldn't prepare a picture", it) }.getOrNull()

    /** The picture at [uri] decoded upright (from its EXIF orientation), at most [maxEdge] px on its long side. */
    fun decodeUpright(uri: Uri, maxEdge: Int): Bitmap? = runCatching {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sampled = BitmapFactory.Options().apply { inSampleSize = PhotoSizing.sampleSize(bounds.outWidth, bounds.outHeight, maxEdge) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, sampled) } ?: return null
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use {
                PhotoSizing.rotation(ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
            }
        }.getOrNull() ?: 0
        val (w, h) = PhotoSizing.fit(decoded.width, decoded.height, maxEdge)
        if (rotation == 0 && w == decoded.width && h == decoded.height) return decoded
        val matrix = Matrix().apply {
            postScale(w.toFloat() / decoded.width, h.toFloat() / decoded.height)
            postRotate(rotation.toFloat())
        }
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    }.onFailure { Log.w(TAG, "Couldn't read a picture", it) }.getOrNull()

    private fun scaled(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val (w, h) = PhotoSizing.fit(bitmap.width, bitmap.height, maxEdge)
        return if (w == bitmap.width && h == bitmap.height) bitmap else bitmap.scale(w, h)
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    private companion object {
        const val TAG = "EarshotPhotos"
    }
}

/** The sums behind [Photos.prepare], free of Android so they can be unit tested. */
object PhotoSizing {
    /** Sharp on any phone screen, zoomed in a little too. */
    const val MAX_EDGE = 1600

    /** With Less data on. */
    const val LESS_DATA_EDGE = 1280

    /** Never smaller than this on the long side, however heavy. */
    const val MIN_EDGE = 640

    /** About two seconds at a slow mobile upload; the server takes a little over twice this. */
    const val TARGET_BYTES = 450_000

    /** JPEG qualities tried in turn, before giving up pixels. */
    val QUALITIES = listOf(82, 74, 66)

    /** The largest power of two to decode at that still leaves the long side at least [maxEdge]. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        val long = max(width, height)
        var sample = 1
        while (long / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /** [width] by [height] scaled down so the long side is at most [maxEdge]; never up. */
    fun fit(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= maxEdge) return width to height
        val scale = maxEdge.toFloat() / long
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** Degrees to turn a picture by for its EXIF orientation (mirrored ones turned only). */
    fun rotation(exifOrientation: Int): Int = when (exifOrientation) {
        ExifOrientation.ROTATE_90, ExifOrientation.TRANSPOSE -> 90
        ExifOrientation.ROTATE_180, ExifOrientation.FLIP_VERTICAL -> 180
        ExifOrientation.ROTATE_270, ExifOrientation.TRANSVERSE -> 270
        else -> 0
    }

    /** ExifInterface's orientation values, here so the sums don't need Android. */
    object ExifOrientation {
        const val FLIP_VERTICAL = 4
        const val TRANSPOSE = 5
        const val ROTATE_90 = 6
        const val TRANSVERSE = 7
        const val ROTATE_270 = 8
        const val ROTATE_180 = 3
    }
}
