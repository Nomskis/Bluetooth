package io.github.nomskis.earshot.calls

import io.github.nomskis.earshot.signaling.Backoff
import io.github.nomskis.earshot.signaling.Caller
import io.github.nomskis.earshot.signaling.ClientMessage
import io.github.nomskis.earshot.signaling.ServerMessage
import io.github.nomskis.earshot.signaling.encodeClientMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Runs the real inbox client against a scripted WebSocket server. */
class InboxClientTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private val received = LinkedBlockingQueue<ServerMessage>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()

    private inner class ServerSocket : WebSocketListener() {
        val messages = LinkedBlockingQueue<String>()
        val opened = LinkedBlockingQueue<WebSocket>()
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets += webSocket
            opened.add(webSocket)
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            messages.add(text)
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
        fun next(): String = checkNotNull(messages.poll(5, TimeUnit.SECONDS)) { "no message from client" }
        fun socket(): WebSocket = checkNotNull(opened.poll(5, TimeUnit.SECONDS)) { "client never connected" }
    }

    private fun acceptOne() = ServerSocket().also { server.enqueue(MockResponse.Builder().webSocketUpgrade(it).build()) }

    private fun client(keepAwakeMs: Long = 60_000) = InboxClient(
        http = http,
        url = server.url("/ws").toString(),
        inboxKey = KEY,
        scope = scope,
        onMessage = { received.add(it) },
        keepAwakeMs = keepAwakeMs,
        backoff = Backoff(baseMs = 50, maxMs = 100, jitter = 0.0),
    )

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() {
        scope.cancel()
        sockets.forEach { runCatching { it.close(1001, null) } }
        server.close()
    }

    @Test
    fun listensOnConnectAndHandsOverRings() = runBlocking {
        val first = acceptOne()
        val client = client()
        client.start()
        assertEquals(encodeClientMessage(ClientMessage.Listen(KEY)), first.next())
        val ws = first.socket()
        ws.send("""{"type":"listening","address":"FpXE_Hse1mKgCltfE83ULb"}""")
        withTimeout(5_000) { client.state.first { it == InboxClient.State.LISTENING } }
        ws.send("""{"type":"incoming","ringId":"r-0001abcd","room":"calm-otter-4821","from":{"name":"Salma","address":null},"video":true}""")
        val incoming = generateSequence { received.poll(5, TimeUnit.SECONDS) }.first { it is ServerMessage.Incoming }
        assertEquals(ServerMessage.Incoming("r-0001abcd", "calm-otter-4821", Caller("Salma", null), video = true), incoming)

        assertTrue(client.send(ClientMessage.RingAnswer("r-0001abcd", accepted = true)))
        assertEquals(encodeClientMessage(ClientMessage.RingAnswer("r-0001abcd", accepted = true)), first.next())
        client.stop()
    }

    @Test
    fun listensAgainAfterTheConnectionDrops() = runBlocking {
        val first = acceptOne()
        val second = acceptOne()
        val client = client()
        client.start()
        assertEquals(encodeClientMessage(ClientMessage.Listen(KEY)), first.next())
        first.socket().close(1001, "going away")
        assertEquals(encodeClientMessage(ClientMessage.Listen(KEY)), second.next())
        client.stop()
    }

    @Test
    fun keepsTheServerAwake() = runBlocking {
        val first = acceptOne()
        val client = client(keepAwakeMs = 100)
        client.start()
        first.next() // listen
        assertEquals(encodeClientMessage(ClientMessage.Ping), first.next())
        client.stop()
    }

    private companion object {
        const val KEY = "sam-secret-inbox-key-0001"
    }
}
