package io.github.nomskis.earshot.messages

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Profile pictures: ours, set in Settings, and the ones contacts sent us, as small square
 * JPEGs in the app's storage (`avatars/`). Ours goes to each contact through the inbox like a
 * message, and again until their phone confirms it ([Messenger.shareProfile]), so a phone
 * that was offline, or a new contact, gets it too.
 */
class Profiles(context: Context) : Messenger.ProfileFiles {
    private val dir = File(context.applicationContext.filesDir, "avatars").apply { mkdirs() }
    private val stateFile = File(dir, "state.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** Our picture's version (when it was set; 0 = never), and which version each contact's phone has. */
    @Serializable
    private data class State(val version: Long = 0, val hasPhoto: Boolean = false, val sentTo: Map<String, Long> = emptyMap())

    private var state: State = runCatching { json.decodeFromString(State.serializer(), stateFile.readText()) }.getOrDefault(State())

    private val _versions = MutableStateFlow(scan())

    /**
     * Who has a picture here ([ME] for ours), and a version that changes with it, so a screen
     * shows the new one rather than one it kept from before.
     */
    val versions: StateFlow<Map<String, Long>> = _versions.asStateFlow()

    /** Ours, or theirs by address. */
    fun file(who: String): File = File(dir, "$who.jpg")

    override val myVersion: Long @Synchronized get() = state.version

    override val hasMyPhoto: Boolean @Synchronized get() = state.hasPhoto

    override fun myPhoto(): ByteArray? = runCatching { file(ME).readBytes() }.getOrNull()

    @Synchronized
    override fun sentVersion(address: String): Long = state.sentTo[address] ?: 0

    @Synchronized
    override fun markSent(address: String, version: Long) {
        if (version != state.version) return
        save(state.copy(sentTo = state.sentTo + (address to version)))
    }

    override fun saveTheirs(address: String, jpeg: ByteArray?) {
        val target = file(address)
        if (jpeg == null) {
            target.delete()
        } else if (!write(target, jpeg)) {
            return
        }
        changed(address)
    }

    /** Ours, from [square] (cropped already): kept at [SIDE] px, and on its way to every contact. */
    @Synchronized
    fun setMine(square: Bitmap): Boolean {
        val scaled = if (square.width == SIDE && square.height == SIDE) square else square.scale(SIDE, SIDE)
        val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }.toByteArray()
        if (scaled !== square) scaled.recycle()
        if (!write(file(ME), bytes)) return false
        save(State(version = System.currentTimeMillis(), hasPhoto = true))
        changed(ME)
        return true
    }

    /** Ours taken away: contacts are told, and show your initial again. */
    @Synchronized
    fun removeMine() {
        file(ME).delete()
        save(State(version = System.currentTimeMillis(), hasPhoto = false))
        changed(ME)
    }

    private fun changed(who: String) {
        _versions.update { all -> if (file(who).exists()) all + (who to System.nanoTime()) else all - who }
    }

    private fun scan(): Map<String, Long> =
        dir.listFiles { f -> f.extension == "jpg" }.orEmpty().associate { it.nameWithoutExtension to it.lastModified() }

    private fun save(next: State) {
        state = next
        runCatching { stateFile.writeText(json.encodeToString(State.serializer(), next)) }
            .onFailure { Log.w(TAG, "Couldn't save profile state", it) }
    }

    private fun write(target: File, bytes: ByteArray): Boolean {
        val temp = File(dir, "${target.name}.tmp")
        return runCatching {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.delete()
                check(temp.renameTo(target))
            }
        }.onFailure {
            temp.delete()
            Log.w(TAG, "Couldn't keep a profile picture", it)
        }.isSuccess
    }

    companion object {
        /** Our own picture's key in [versions] and [file]. */
        const val ME = "me"

        /** Sharp at any size it's shown, a few tens of KB. */
        const val SIDE = 256
        private const val QUALITY = 85
        private const val TAG = "EarshotProfiles"
    }
}
