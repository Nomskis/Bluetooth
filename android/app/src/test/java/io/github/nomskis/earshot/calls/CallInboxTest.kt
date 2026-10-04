package io.github.nomskis.earshot.calls

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.nomskis.earshot.settings.SettingsRepository
import io.github.nomskis.earshot.signaling.ClientMessage
import io.github.nomskis.earshot.signaling.encodeClientMessage
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
    private lateinit var inbox: CallInbox

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
        val settings = SettingsRepository(context)
        runBlocking {
            settings.update { it.copy(serverUrl = server.url("/").toString(), receiveCalls = true) }
            settings.saveContact(Contact("Salma ❤️", SALMA_ADDRESS))
        }
        inbox = CallInbox(context, settings, OkHttpClient(), isBusy = { busy }, startCall = { ring, video -> started += ring.room to video })
        inbox.follow()
        // The phone connects and listens.
        assertTrue(waitFor { fromPhone.peek() != null })
        assertTrue(fromPhone.poll()!!.contains("\"type\":\"listen\""))
    }

    @After
    fun tearDown() {
        inbox.close()
        runCatching { socket?.close(1000, null) }
        server.close()
    }

    /** Runs the main looper (where the inbox works) until [condition] holds or 5 s pass. */
    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun ring(video: Boolean = true) {
        checkNotNull(socket).send(
            """{"type":"incoming","ringId":"r-Zk3pQ81wLa","room":"calm-otter-4821","from":{"name":"Salma","address":"$SALMA_ADDRESS"},"video":$video}""",
        )
        waitFor { inbox.ringing.value != null || fromPhone.peek() != null }
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
    }

    private companion object {
        const val SALMA_ADDRESS = "1D8ANuTJStR4AyHh0kwUw6"
    }
}
