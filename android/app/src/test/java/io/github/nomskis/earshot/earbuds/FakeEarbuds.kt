package io.github.nomskis.earshot.earbuds

/**
 * A [ControlLink] whose other end is a simulated pair of earbuds: every
 * frame we write is handed to [respond], and what it returns is what we
 * read next.
 */
class FakeEarbuds(private val respond: (ByteArray) -> List<ByteArray>) : ControlLink {
    private val incoming = ArrayDeque<Byte>()
    val written = mutableListOf<ByteArray>()

    /** Bytes the earbuds send without being asked. */
    fun push(bytes: ByteArray) = bytes.forEach { incoming.addLast(it) }

    override fun write(bytes: ByteArray) {
        written += bytes
        respond(bytes).forEach(::push)
    }

    override fun read(buffer: ByteArray, timeoutMs: Long): Int {
        if (incoming.isEmpty()) return -1 // nothing more will come; don't make tests wait
        var n = 0
        // Deliver in small chunks to exercise reassembly.
        while (n < minOf(buffer.size, 5) && incoming.isNotEmpty()) buffer[n++] = incoming.removeFirst()
        return n
    }
}

fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
