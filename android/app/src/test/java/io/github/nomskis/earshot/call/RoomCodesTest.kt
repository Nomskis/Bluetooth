package io.github.nomskis.earshot.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RoomCodesTest {
    @Test
    fun generatedCodesAreValid() {
        repeat(200) {
            val code = RoomCodes.generate()
            assertNotNull(code, RoomCodes.normalize(code))
            assertEquals(code, RoomCodes.normalize(code))
        }
    }

    @Test
    fun normalizeMatchesServerRules() {
        assertEquals("blue-otter-42", RoomCodes.normalize("  Blue-Otter-42 "))
        assertEquals("gym-time", RoomCodes.normalize("gym time"))
        for (bad in listOf("", "ab", "-abc", "abc-", "ä-room", "x".repeat(65))) {
            assertNull(bad, RoomCodes.normalize(bad))
        }
    }

    @Test
    fun idsAreUrlSafe() {
        repeat(100) {
            val id = Ids.random(12)
            assert(Regex("^[A-Za-z0-9_-]{16}$").matches(id)) { id }
        }
        assert(Ids.random(9, "s").startsWith("s-"))
    }
}
