package io.github.nomskis.earshot.call

import java.security.SecureRandom
import java.util.Base64

object Ids {
    private val random = SecureRandom()

    /** URL-safe random id, matching what the web client generates. */
    fun random(bytes: Int = 9, prefix: String = ""): String {
        val raw = ByteArray(bytes).also(random::nextBytes)
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        return if (prefix.isEmpty()) id else "$prefix-$id"
    }
}
