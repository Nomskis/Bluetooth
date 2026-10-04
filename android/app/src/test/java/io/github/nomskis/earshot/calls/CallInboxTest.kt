package io.github.nomskis.earshot.calls

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.nomskis.earshot.settings.SettingsRepository
import io.github.nomskis.earshot.signaling.ClientMessage
import io.github.nomskis.earshot.signaling.encodeClientMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Incoming calls end to end: the real CallInbox, its connection and its
 * notifications, against a scripted server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CallInboxTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val fromPhone = LinkedBlockingQueue<String>()
    private var socket: WebSocket? = null
    private val started = mutableListOf<Pair<String, Boolean>>()
    private var busy = false
    /** What our own call is ringing, for calls that cross. */
    private var ringingOut: RingingOut? = null
    private val switched = mutableListOf<Pair<String, Boolean>>()
    /** Rings connected early, and those let go again. */
    private val preconnected = mutableListOf<String>()
    private val dropped = mutableListOf<String>()
    private val rekeyed = mutableListOf<Pair<String, String>>()
    /** The room of the call we're on, if any. */
    private var inCallRoom: String? = null
    private lateinit var myAddress: String
    private lateinit var inbox: CallInbox
    private val settings by lazy { SettingsRepository(context) }

    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Before
    fun setUp() {
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    socket = webSocket
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    fromPhone.add(text)
                }
            }).build(),
        )
        server.start()
        runBlocking {
            settings.update { it.copy(serverUrl = server.url("/").toString(), receiveCalls = true) }
            settings.saveContact(Contact("Salma ❤️", SALMA_ADDRESS))
        }
        myAddress = InboxKeys.address(runBlocking { settings.inboxKey() })
        inbox = CallInbox(
            context,
            settings,
            OkHttpClient(),
            isBusy = { busy },
            startCall = { ring, video -> started += ring.room to video },
            ringingOut = { ringingOut },
            switchTo = { ring, video -> switched += ring.room to video },
            preconnect = { ring -> preconnected += ring.ringId },
            dropPreconnect = { ringId -> dropped += ringId },
            rekeyPreconnect = { old, new -> rekeyed += old to new },
            inCall = { room -> room == inCallRoom },
        )
        inbox.follow()
        // The phone connects and listens.
        assertTrue(waitFor { fromPhone.peek() != null })
        assertTrue(fromPhone.poll()!!.contains("\"type\":\"listen\""))
    }

    @After
    fun tearDown() {
        // Let the history finish writing, so the next test's settings aren't queued behind it.
        waitFor(500) { false }
        inbox.close()
        runCatching { socket?.close(1000, null) }
        server.close()
    }

    /** Runs the main looper (where the inbox works) until [condition] holds or [timeoutMs] pass. */
    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun ring(
        video: Boolean = true,
        from: String = SALMA_ADDRESS,
        waitMs: Long = 5_000,
        preconnect: Boolean = false,
        ringId: String = "r-Zk3pQ81wLa",
    ) {
        checkNotNull(socket).send(
            """{"type":"incoming","ringId":"$ringId","room":"calm-otter-4821","from":{"name":"Salma","address":"$from"},""" +
                """"video":$video,"preconnect":$preconnect}""",
        )
        waitFor(waitMs) { inbox.ringing.value != null || fromPhone.peek() != null || switched.isNotEmpty() }
    }

    private fun nextFromPhone(): String {
        waitFor { fromPhone.peek() != null }
        return checkNotNull(fromPhone.poll(1, TimeUnit.SECONDS)) { "the phone sent nothing" }
    }

    @Test
    fun aRingShowsTheCallerAndAcceptingStartsTheCall() {
        ring(video = true)
        val ringing = checkNotNull(inbox.ringing.value) { "nothing is ringing" }
        assertEquals("Salma ❤️", ringing.callerName) // the saved contact's name
        assertTrue(ringing.video)
        assertNotNull(notifications.getNotification(CallNotifications.ID_INCOMING))

        assertTrue(inbox.answer(withVideo = true))
        assertEquals(encodeClientMessage(ClientMessage.RingAnswer("r-Zk3pQ81wLa", accepted = true)), nextFromPhone())
        assertEquals(listOf("calm-otter-4821" to true), started)
        assertNull(inbox.ringing.value)
        assertNull(notifications.getNotification(CallNotifications.ID_INCOMING))
    }

    @Test
    fun aContactsCallConnectsWhileItRingsAndAnsweringKeepsTheConnection() {
        ring(preconnect = true)
        assertEquals(listOf("r-Zk3pQ81wLa"), preconnected)
        assertTrue(inbox.answer(withVideo = true))
        assertEquals(listOf("calm-otter-4821" to true), started)
        assertTrue(dropped.isEmpty())
    }

    @Test
    fun decliningLetsTheEarlyConnectionGo() {
        ring(preconnect = true)
        inbox.decline()
        assertEquals(listOf("r-Zk3pQ81wLa"), dropped)
    }

    @Test
    fun theCallerHangingUpLetsTheEarlyConnectionGo() {
        ring(preconnect = true)
        checkNotNull(socket).send("""{"type":"ring-cancelled","ringId":"r-Zk3pQ81wLa","reason":"cancelled"}""")
        assertTrue(waitFor { dropped.isNotEmpty() })
        assertEquals(listOf("r-Zk3pQ81wLa"), dropped)
    }

    @Test
    fun theCallerRingingAgainForTheSameCallKeepsItRinging() {
        ring(preconnect = true)
        // Their app reconnected and rang again; the server will end the first ring with its old connection.
        ring(preconnect = true, ringId = "r-Second0001", waitMs = 0)
        waitFor { inbox.ringing.value?.ringId == "r-Second0001" || fromPhone.peek() != null }
        assertNull("not answered busy", fromPhone.peek())
        assertEquals("r-Second0001", inbox.ringing.value?.ringId)
        assertEquals(listOf("r-Zk3pQ81wLa" to "r-Second0001"), rekeyed)
        assertEquals(listOf("r-Zk3pQ81wLa"), preconnected) // the same early connection, not a second one
        // The first ring's cancel changes nothing...
        checkNotNull(socket).send("""{"type":"ring-cancelled","ringId":"r-Zk3pQ81wLa","reason":"cancelled"}""")
        waitFor(500) { false }
        assertNotNull(inbox.ringing.value)
        assertTrue(dropped.isEmpty())
        assertTrue(notifications.allNotifications.none { it.extras.getString("android.title")?.startsWith("Missed call") == true })
        // ...and answering answers the ring that's live.
        assertTrue(inbox.answer(withVideo = true))
        assertEquals(encodeClientMessage(ClientMessage.RingAnswer("r-Second0001", accepted = true)), nextFromPhone())
    }

    @Test
    fun aRingForTheCallWereAlreadyOnIsAnsweredYes() {
        busy = true
        inCallRoom = "calm-otter-4821"
        // Their app didn't hear our answer before it reconnected, so it rang again.
        ring()
        assertEquals(encodeClientMessage(ClientMessage.RingAnswer("r-Zk3pQ81wLa", accepted = true)), nextFromPhone())
        assertNull(inbox.ringing.value)
        assertTrue(notifications.allNotifications.none { it.extras.getString("android.title")?.contains("Salma") == true })
    }

    @Test
    fun onlySavedContactsWhoseAppAllowsItConnectEarly() {
        // A stranger would learn where this phone is on the internet before you answer.
        ring(preconnect = true, from = "z".repeat(22))
        assertNotNull(inbox.ringing.value)
        assertTrue(preconnected.isEmpty())
        inbox.decline()
        nextFromPhone()
        // An older app on their side wouldn't wait for the answer, so it isn't done for them either.
        ring(preconnect = false)
        assertNotNull(inbox.ringing.value)
        assertTrue(preconnected.isEmpty())
    }

    @Test
    fun decliningSaysSo() {
        ring()
        inbox.decline()
        assertEquals(encodeClientMessage(ClientMessage.RingAnswer("r-Zk3pQ81wLa", accepted = false, reason = "declined")), nextFromPhone())
        assertNull(inbox.ringing.value)
        assertTrue(started.isEmpty())
    }

    @Test
    fun onAnotherCallTheCallerHearsBusyAndYouGetANote() {
        busy = true
        ring()
        assertEquals(encodeClientMessage(ClientMessage.RingAnswer("r-Zk3pQ81wLa", accepted = false, reason = "busy")), nextFromPhone())
        assertNull(inbox.ringing.value)
        assertTrue(notifications.allNotifications.any { it.extras.getString("android.title")?.contains("Salma") == true })
    }

    @Test
    fun theCallerHangingUpStopsTheRingingAndLeavesAMissedCall() {
        ring()
        checkNotNull(socket).send("""{"type":"ring-cancelled","ringId":"r-Zk3pQ81wLa","reason":"cancelled"}""")
        assertTrue(waitFor { inbox.ringing.value == null })
        assertNull(notifications.getNotification(CallNotifications.ID_INCOMING))
        val missed = notifications.allNotifications.first { it.extras.getString("android.title") == "Missed call from Salma ❤️" }
        // They proved their address, so they can be rung back from the notification.
        assertEquals(listOf("Call back"), missed.actions.map { it.title.toString() })
        // And it's in the history, in red.
        assertTrue(waitFor { runBlocking { settings.callLog.first() }.any { it.missed && it.address == SALMA_ADDRESS } })
    }

    @Test
    fun callingEachOtherAtOnceTheirCallWinsWhenTheirAddressIsLower() {
        val lower = "-".repeat(22)
        assertTrue(lower < myAddress)
        busy = true // we're on our own call, ringing them
        ringingOut = RingingOut(lower, video = false)
        ring(from = lower)
        // Not "busy": we take their call and leave ours, keeping our choice of voice or video.
        assertEquals(encodeClientMessage(ClientMessage.RingAnswer("r-Zk3pQ81wLa", accepted = true)), nextFromPhone())
        assertEquals(listOf("calm-otter-4821" to false), switched)
        assertNull(inbox.ringing.value)
        assertTrue(notifications.allNotifications.none { it.extras.getString("android.title")?.startsWith("Missed call") == true })
    }

    @Test
    fun callingEachOtherAtOnceOursWinsWhenOurAddressIsLower() {
        val higher = "z".repeat(22)
        assertTrue(myAddress < higher)
        busy = true
        ringingOut = RingingOut(higher, video = true)
        // Their ring is left alone: they switch to ours when it reaches them.
        ring(from = higher, waitMs = 1_000)
        assertNull(fromPhone.peek())
        assertTrue(switched.isEmpty())
        assertNull(inbox.ringing.value)
        // And their giving up on it later leaves no missed call.
        checkNotNull(socket).send("""{"type":"ring-cancelled","ringId":"r-Zk3pQ81wLa","reason":"cancelled"}""")
        waitFor(500) { false }
        assertTrue(notifications.allNotifications.none { it.extras.getString("android.title")?.startsWith("Missed call") == true })
    }

    @Test
    fun ringingSomeoneElseStillMeansBusy() {
        busy = true
        ringingOut = RingingOut("z".repeat(22), video = true)
        ring() // from Salma, who isn't the one we're calling
        assertEquals(encodeClientMessage(ClientMessage.RingAnswer("r-Zk3pQ81wLa", accepted = false, reason = "busy")), nextFromPhone())
        assertTrue(switched.isEmpty())
    }

    private companion object {
        const val SALMA_ADDRESS = "1D8ANuTJStR4AyHh0kwUw6"
    }
}
