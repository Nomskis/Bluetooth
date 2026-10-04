package io.github.nomskis.earshot.settings

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The short messages one tap sends: in a call's chat, and when declining a call. Yours to edit. */
object QuickReplies {
    val DEFAULT = listOf("One sec", "Can't hear you", "Call you back", "Can't talk now", "On my way", "👍")
    const val MAX = 8
    const val MAX_LENGTH = 60

    private val serializer = ListSerializer(String.serializer())

    /** Trimmed, no blanks or repeats, at most [MAX] of at most [MAX_LENGTH] characters. */
    fun clean(replies: List<String>): List<String> =
        replies.map { it.trim().take(MAX_LENGTH) }.filter { it.isNotEmpty() }.distinct().take(MAX)

    fun encode(replies: List<String>): String = Json.encodeToString(serializer, clean(replies))

    /** Never set (or unreadable): the defaults. */
    fun decode(text: String?): List<String> =
        if (text == null) DEFAULT else runCatching { clean(Json.decodeFromString(serializer, text)) }.getOrDefault(DEFAULT)
}
