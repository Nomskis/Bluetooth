package io.github.nomskis.earshot.signaling

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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Runs the real client against a scripted WebSocket server. */
class SignalingClientTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient()

    private val allSockets = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()

    /** Server side of one accepted socket. */
    private inner class ServerSocket : WebSocketListener() {
        val received = LinkedBlockingQueue<String>()
        val opened = LinkedBlockingQueue<WebSocket>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            allSockets += webSocket
            opened.add(webSocket)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
        }

        fun next(): String = checkNotNull(received.poll(5, TimeUnit.SECONDS)) { "no message from client" }
        fun socket(): WebSocket = checkNotNull(opened.poll(5, TimeUnit.SECONDS)) { "client never connected" }
    }

    private val join = ClientMessage.Join(room = "gym", peerId = "peer-aaaa", name = "Test")

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        allSockets.forEach { runCatching { it.close(1001, null) } }
        server.close()
    }

    private fun acceptOne(): ServerSocket {
        val listener = ServerSocket()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build())
        return listener
    }

    private fun client() = SignalingClient(
        http = http,
        url = server.url("/ws").toString(),
        join = join,
        scope = scope,
        backoff = Backoff(baseMs = 50, maxMs = 100, jitter = 0.0),
    )

    @Test
    fun joinsOnConnectAndDeliversMessages() = runBlocking {
        val first = acceptOne()
        val client = client()
        client.connect()

        assertEquals(encodeClientMessage(join), first.next())
        withTimeout(5_000) { client.state.first { it == SignalingClient.State.Open } }

        first.socket().send("""{"type":"peer-left","peerId":"peer-bbbb","reason":"left"}""")
        val message = withTimeout(5_000) { client.incoming.receive() }
        assertEquals(ServerMessage.PeerLeft("peer-bbbb", "left"), message)
        client.close()
    }

    @Test
    fun reconnectsAndRejoinsWithTheSamePeerId() = runBlocking {
        val first = acceptOne()
        val second = acceptOne()
        val client = client()
        client.connect()
        assertEquals(encodeClientMessage(join), first.next())

        // Server drops us (think: phone switched from Wi-Fi to mobile data).
        first.socket().close(1001, "going away")

        assertEquals(encodeClientMessage(join), second.next())
        withTimeout(5_000) { client.state.first { it == SignalingClient.State.Open } }
        client.close()
    }

    @Test
    fun queuesSignalsWhileDisconnected() = runBlocking {
        val first = acceptOne()
        val client = client()
        val signal = ClientMessage.Signal("peer-bbbb", SignalData.RequestOffer("s1"))
        client.send(signal) // Not connected yet: must be queued, not lost.
        client.connect()
        assertEquals(encodeClientMessage(join), first.next())
        assertEquals(encodeClientMessage(signal), first.next())
        client.close()
    }

    @Test
    fun hangingUpSaysGoodbye() = runBlocking {
        val first = acceptOne()
        val client = client()
        client.connect()
        first.next()
        withTimeout(5_000) { client.state.first { it == SignalingClient.State.Open } }
        client.close()
        assertEquals(encodeClientMessage(ClientMessage.Leave), first.next())
        assertTrue(client.state.value is SignalingClient.State.Closed)
    }

    @Test
    fun stopsWhenReplacedByAnotherDevice() = runBlocking {
        val first = acceptOne()
        val client = client()
        client.connect()
        first.next()
        first.socket().close(4000, "replaced by a newer connection")
        val closed = withTimeout(5_000) { client.state.first { it is SignalingClient.State.Closed } }
        assertEquals(SignalingClient.State.Closed("replaced"), closed)
    }
}
