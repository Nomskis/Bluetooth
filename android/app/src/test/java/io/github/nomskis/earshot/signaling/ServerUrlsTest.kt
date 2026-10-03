package io.github.nomskis.earshot.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlsTest {
    @Test
    fun normalizesWhatPeopleType() {
        assertEquals("https://calls.example.com", ServerUrls.normalizeBase("calls.example.com"))
        assertEquals("https://calls.example.com", ServerUrls.normalizeBase(" https://calls.example.com/ "))
        assertEquals("https://calls.example.com", ServerUrls.normalizeBase("wss://calls.example.com/ws"))
        assertEquals("https://example.com/earshot", ServerUrls.normalizeBase("https://example.com/earshot/"))
        assertEquals("http://192.168.1.20:8080", ServerUrls.normalizeBase("192.168.1.20:8080"))
        assertEquals("http://localhost:8080", ServerUrls.normalizeBase("localhost:8080"))
        assertEquals("https://calls.example.com:8443", ServerUrls.normalizeBase("calls.example.com:8443"))
    }

    @Test
    fun rejectsNonsense() {
        assertNull(ServerUrls.normalizeBase(""))
        assertNull(ServerUrls.normalizeBase("   "))
        assertNull(ServerUrls.normalizeBase("ftp://example.com"))
        assertNull(ServerUrls.normalizeBase("https://"))
    }

    @Test
    fun buildsWebSocketAndInviteUrls() {
        assertEquals("wss://calls.example.com/ws", ServerUrls.webSocketUrl("https://calls.example.com"))
        assertEquals("ws://192.168.1.20:8080/ws", ServerUrls.webSocketUrl("http://192.168.1.20:8080"))
        assertEquals("https://calls.example.com/r/calm-otter-4821", ServerUrls.inviteLink("https://calls.example.com", "calm-otter-4821"))
    }
}
