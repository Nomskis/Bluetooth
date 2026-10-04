package io.github.nomskis.earshot.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickRepliesTest {
    @Test
    fun neverSetMeansTheDefaults() {
        assertEquals(QuickReplies.DEFAULT, QuickReplies.decode(null))
        assertEquals(QuickReplies.DEFAULT, QuickReplies.decode("not json"))
    }

    @Test
    fun yoursAreKeptCleanedUp() {
        val mine = listOf("  Running late  ", "", "Running late", "x".repeat(100))
        val stored = QuickReplies.decode(QuickReplies.encode(mine))
        assertEquals(listOf("Running late", "x".repeat(QuickReplies.MAX_LENGTH)), stored)
        // Removing them all is a choice too: no quick replies.
        assertEquals(emptyList<String>(), QuickReplies.decode(QuickReplies.encode(emptyList())))
        assertEquals(QuickReplies.MAX, QuickReplies.clean((1..20).map { "Reply $it" }).size)
    }
}
