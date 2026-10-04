package io.github.nomskis.earshot.signaling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The same JSON examples are checked by the server tests, so the Android app,
 * the web client and the server can't drift apart without a test failing.
 */
class ProtocolFixturesTest {
    private val fixtures = File(checkNotNull(System.getProperty("earshot.fixtures")) { "fixtures dir not set" })

    private fun files(dir: String) = File(fixtures, dir).listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }

    @Test
    fun everyServerFixtureDecodes() {
        val files = files("server")
        assertTrue(files.size >= 5)
        for (file in files) {
            val message = decodeServerMessage(file.readText())
            if (file.name.contains("unknown")) {
                assertNull("${file.name} should be ignored, not crash", message)
            } else {
                assertNotNull("${file.name} failed to decode", message)
            }
        }
    }

    @Test
    fun clientFixturesRoundTrip() {
        for (file in files("client")) {
            val original: JsonElement = Json.parseToJsonElement(file.readText())
            val decoded = ProtocolJson.decodeFromString(ClientMessage.serializer(), file.readText())
            val reEncoded = Json.parseToJsonElement(encodeClientMessage(decoded))
            assertEquals("${file.name} does not round-trip", original, reEncoded)
        }
    }

    @Test
    fun joinedFixtureHasExpectedContent() {
        val joined = decodeServerMessage(File(fixtures, "server/joined.json").readText()) as ServerMessage.Joined
        assertEquals("blue-otter-42", joined.room)
        assertEquals(2, joined.seq)
        assertEquals(1, joined.peers.single().seq)
        assertEquals("Salma", joined.peers.single().name)
        val turn = joined.iceServers[1]
        assertEquals("1791234567:a1b2c3d4e5f6g7h8", turn.username)
        assertEquals(2, turn.urls.size)
    }

    @Test
    fun signalPayloadsDecodeToTheRightKinds() {
        val offer = decodeServerMessage(File(fixtures, "server/signal-offer.json").readText()) as ServerMessage.Signal
        assertTrue(offer.data is SignalData.Offer)
        val candidate = decodeServerMessage(File(fixtures, "server/signal-candidate.json").readText()) as ServerMessage.Signal
        val payload = (candidate.data as SignalData.Candidate).candidate
        assertEquals("1", payload.sdpMid)
        assertEquals(1, payload.sdpMLineIndex)
        val media = decodeServerMessage(File(fixtures, "server/signal-media-state.json").readText()) as ServerMessage.Signal
        assertEquals(SignalData.MediaState(micMuted = true, cameraOff = false, audioMode = null), media.data)
        val pocket = decodeServerMessage(File(fixtures, "server/signal-media-state-pocket.json").readText()) as ServerMessage.Signal
        assertEquals(SignalData.MediaState(micMuted = false, cameraOff = true, audioMode = "hifi", inPocket = true), pocket.data)
        val weak = decodeServerMessage(File(fixtures, "server/signal-media-state-weak.json").readText()) as ServerMessage.Signal
        assertEquals(SignalData.MediaState(micMuted = false, cameraOff = true, audioMode = "hifi", weakConnection = true), weak.data)
        val link = decodeServerMessage(File(fixtures, "server/signal-media-state-link.json").readText()) as ServerMessage.Signal
        assertEquals(
            SignalData.MediaState(micMuted = false, cameraOff = false, audioMode = "hifi", network = "wifi", uplink = "starved", radioShared = true),
            link.data,
        )
    }

    @Test
    fun ringMessagesDecodeAsTheServerSendsThem() {
        val incoming = decodeServerMessage(File(fixtures, "server/incoming.json").readText()) as ServerMessage.Incoming
        assertEquals("calm-otter-4821", incoming.room)
        assertEquals(Caller("Salma", "1D8ANuTJStR4AyHh0kwUw6"), incoming.from)
        assertTrue(incoming.video)
        assertTrue(incoming.preconnect)
        val status = decodeServerMessage(File(fixtures, "server/ring-status.json").readText()) as ServerMessage.RingStatus
        assertEquals("ringing", status.status)
        val cancelled = decodeServerMessage(File(fixtures, "server/ring-cancelled.json").readText()) as ServerMessage.RingCancelled
        assertEquals("timeout", cancelled.reason)
        val answered = decodeServerMessage(File(fixtures, "server/ring-answered.json").readText()) as ServerMessage.RingAnswered
        assertTrue(answered.accepted)
        assertNull(answered.reason)
    }

    @Test
    fun requestOfferWithNullSessionFromTheWebClientDecodes() {
        // JSON.stringify keeps nulls, so the web client sends "session": null.
        val text = """{"type":"signal","from":"abcdefgh","data":{"kind":"request-offer","session":null}}"""
        val message = decodeServerMessage(text) as ServerMessage.Signal
        assertEquals(SignalData.RequestOffer(null), message.data)
    }

    @Test
    fun aRequestToRenegotiateInPlaceSaysSo() {
        val text = File(fixtures, "client/signal-request-offer-renegotiate.json").readText()
        val request = ProtocolJson.decodeFromString(ClientMessage.serializer(), text) as ClientMessage.Signal
        assertEquals(SignalData.RequestOffer("s-3HkLtQnPb", iceRestart = false), request.data)
        // Without the field it's the old meaning: restart ICE.
        val plain = encodeClientMessage(ClientMessage.Signal("abcdefgh", SignalData.RequestOffer("s-1")))
        assertEquals("""{"type":"signal","to":"abcdefgh","data":{"kind":"request-offer","session":"s-1"}}""", plain)
    }

    @Test
    fun simpleMessagesEncodeAsTheServerExpects() {
        assertEquals("""{"type":"leave"}""", encodeClientMessage(ClientMessage.Leave))
        assertEquals("""{"type":"ping"}""", encodeClientMessage(ClientMessage.Ping))
        val signal = encodeClientMessage(ClientMessage.Signal("abcdefgh", SignalData.RequestOffer(null)))
        assertEquals("""{"type":"signal","to":"abcdefgh","data":{"kind":"request-offer"}}""", signal)
    }

    @Test
    fun garbageIsIgnored() {
        assertNull(decodeServerMessage("not json"))
        assertNull(decodeServerMessage("""{"type":"from-the-future"}"""))
        assertNull(decodeServerMessage("""{"no":"type"}"""))
    }
}
