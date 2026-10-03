package io.github.nomskis.earshot.earbuds

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/** A byte pipe to the earbuds. Real ones are RFCOMM sockets; tests use fakes. */
interface ControlLink {
    fun write(bytes: ByteArray)

    /** Reads what's there, waiting up to [timeoutMs]. Returns 0 on timeout, -1 when closed. */
    fun read(buffer: ByteArray, timeoutMs: Long): Int
}

/** Turns a byte stream into packets; partial frames wait for more bytes. */
fun interface FrameDecoder<P> {
    fun feed(bytes: ByteArray, count: Int): List<P>
}

/**
 * Waits for specific packets while keeping the others, so a packet the
 * earbuds send early (before we ask for it) isn't lost.
 */
class PacketReader<P>(private val link: ControlLink, private val decoder: FrameDecoder<P>) {
    private val pending = ArrayDeque<P>()
    private val buffer = ByteArray(1024)

    /** Every packet seen so far, for diagnostics. */
    val seen = mutableListOf<P>()

    fun await(timeoutMs: Long, match: (P) -> Boolean): P? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            pending.firstOrNull(match)?.let {
                pending.remove(it)
                return it
            }
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val n = link.read(buffer, left)
            if (n < 0) return null
            if (n > 0) {
                val packets = decoder.feed(buffer, n)
                seen += packets
                pending += packets
            }
        }
    }
}

/**
 * The last exchanges with earbuds, in hex, for the tuner's shareable report:
 * the drivers are built from documented protocols, and when one misbehaves on
 * a real pair this shows exactly what was said.
 */
object DriverLog {
    private const val MAX_LINES = 60
    private const val MAX_BYTES_SHOWN = 48
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    fun bytes(direction: String, data: ByteArray, count: Int = data.size) {
        val shown = minOf(count, MAX_BYTES_SHOWN)
        val hex = (0 until shown).joinToString(" ") { "%02X".format(data[it]) } + if (count > shown) " …(+${count - shown})" else ""
        add("$direction $hex")
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}

/** RFCOMM socket as a [ControlLink]. */
private class SocketLink(private val socket: BluetoothSocket) : ControlLink {
    private val input = socket.inputStream
    private val output = socket.outputStream

    override fun write(bytes: ByteArray) {
        DriverLog.bytes(">", bytes)
        output.write(bytes)
        output.flush()
    }

    override fun read(buffer: ByteArray, timeoutMs: Long): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (input.available() <= 0) {
            if (System.currentTimeMillis() >= deadline) return 0
            Thread.sleep(10)
        }
        return input.read(buffer).also { if (it > 0) DriverLog.bytes("<", buffer, it) }
    }
}

/**
 * Base for drivers that talk over an RFCOMM service: opens the first
 * channel that connects, runs [session] on it, always closes it.
 */
@SuppressLint("MissingPermission")
abstract class RfcommDriver : EarbudDriver {
    /** Service UUIDs to try, in order. */
    protected abstract val serviceUuids: List<UUID>

    /** The protocol exchange; runs on an IO thread with the channel open. */
    protected abstract fun session(link: ControlLink, enabled: Boolean): DriverResult

    protected fun advertised(device: BluetoothDevice): List<UUID> = device.uuids.orEmpty().map(ParcelUuid::getUuid)

    protected fun nameOf(device: BluetoothDevice): String = runCatching { device.name }.getOrNull().orEmpty().lowercase()

    override suspend fun setLowLatency(device: BluetoothDevice, enabled: Boolean): DriverResult = withContext(Dispatchers.IO) {
        val advertised = advertised(device)
        // Prefer services the earbuds advertise; fall back to trying them all.
        val candidates = serviceUuids.filter { it in advertised }.ifEmpty { serviceUuids }
        var last: DriverResult = DriverResult.ChannelBusy
        for (uuid in candidates) {
            last = attempt(device, uuid, enabled)
            if (last != DriverResult.ChannelBusy) break
        }
        last
    }

    private fun attempt(device: BluetoothDevice, uuid: UUID, enabled: Boolean): DriverResult {
        val socket: BluetoothSocket = try {
            device.createRfcommSocketToServiceRecord(uuid)
        } catch (e: IOException) {
            return DriverResult.Failed(e.message ?: "no socket")
        } catch (e: SecurityException) {
            return DriverResult.Failed("Bluetooth permission missing")
        }
        // connect() can block for several seconds; close from a watchdog if it does.
        val watchdog = thread(name = "EarbudConnectWatchdog") {
            try {
                Thread.sleep(CONNECT_TIMEOUT_MS)
                if (!socket.isConnected) runCatching { socket.close() }
            } catch (_: InterruptedException) {
            }
        }
        DriverLog.add("$family: connecting to $uuid")
        return try {
            try {
                socket.connect()
            } catch (e: IOException) {
                Log.i(TAG, "$family: channel $uuid unavailable: ${e.message}")
                DriverLog.add("$family: $uuid unavailable (${e.message})")
                return DriverResult.ChannelBusy
            } finally {
                watchdog.interrupt()
            }
            session(SocketLink(socket), enabled).also { DriverLog.add("$family ${if (enabled) "on" else "off"}: $it") }
        } catch (e: IOException) {
            DriverResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { socket.close() }
        }
    }

    protected companion object {
        const val TAG = "EarbudDriver"
        const val CONNECT_TIMEOUT_MS = 6_000L
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
