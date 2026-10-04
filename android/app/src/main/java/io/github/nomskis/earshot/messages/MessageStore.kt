package io.github.nomskis.earshot.messages

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Conversations on the phone: one small JSON file per contact, written whole (to a
 * temporary file, then renamed into place, so a crash mid-write never loses the old one).
 */
class MessageStore(context: Context) : Messenger.Store {
    private val dir = File(context.applicationContext.filesDir, "conversations").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    override fun loadAll(): Map<String, Conversation> = dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { file ->
        runCatching { json.decodeFromString(Conversation.serializer(), file.readText()) }
            .onFailure { Log.w(TAG, "Couldn't read ${file.name}", it) }
            .getOrNull()
    }.associateBy { it.address }

    override fun save(conversation: Conversation) {
        val target = File(dir, "${conversation.address}.json")
        val temp = File(dir, "${conversation.address}.json.tmp")
        runCatching {
            temp.writeText(json.encodeToString(Conversation.serializer(), conversation))
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }.onFailure { Log.w(TAG, "Couldn't save the conversation", it) }
    }

    fun delete(address: String) {
        File(dir, "$address.json").delete()
    }

    private companion object {
        const val TAG = "EarshotMessages"
    }
}
