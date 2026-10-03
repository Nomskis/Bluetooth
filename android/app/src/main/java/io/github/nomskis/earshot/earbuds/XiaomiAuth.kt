package io.github.nomskis.earshot.earbuds

/**
 * The challenge-response Xiaomi and Redmi earbuds require before they take
 * settings. It is the Bluetooth Core Specification's SAFER+ based function
 * Ar' (Vol 2 Part H, the core of E1), keyed with the 16-byte challenge (last
 * byte XOR 6) over a fixed 16-byte block. Written here from the spec's
 * definition: exponent/log boxes, key schedule with bias vectors, and the
 * pseudo-Hadamard transform with the "Armenian shuffle".
 */
object XiaomiAuth {
    private const val ROUNDS = 8

    /** Positions where SAFER+ XORs (and uses the exponent box); the others add (and use the log box). */
    private val XOR_POSITION = BooleanArray(16) { it % 4 == 0 || it % 4 == 3 }

    /** e(x) = 45^x mod 257, with 256 written as 0. */
    private val EXP = IntArray(256)
    /** l(x), the inverse of [EXP]. */
    private val LOG = IntArray(256)

    init {
        var v = 1
        for (x in 0 until 256) {
            EXP[x] = v and 0xFF // 45^128 = 256 -> 0
            LOG[v and 0xFF] = x
            v = v * 45 % 257
        }
    }

    /** Bias vector for subkey p (2..17), byte i (1..16): e(e(17p + i)) in the spec's terms. */
    private fun bias(p: Int, i: Int): Int {
        var inner = 1
        repeat((17 * p + i) % 256) { inner = inner * 45 % 257 }
        var outer = 1
        repeat(inner) { outer = outer * 45 % 257 }
        return outer and 0xFF
    }

    private val BIAS: Array<IntArray> = Array(16) { k -> IntArray(16) { i -> bias(k + 2, i + 1) } }

    /** The block Xiaomi encrypts. */
    private val BLOCK = intArrayOf(0x11, 0x22, 0x33, 0x33, 0x22, 0x11, 0x11, 0x22, 0x33, 0x33, 0x22, 0x11, 0x11, 0x22, 0x33, 0x33)

    /** "Armenian shuffle" between the transform's layers. */
    private val SHUFFLE = intArrayOf(8, 11, 12, 15, 2, 1, 6, 5, 10, 9, 14, 13, 0, 7, 4, 3)

    fun respond(challenge: ByteArray): ByteArray {
        require(challenge.size == 16) { "challenge must be 16 bytes" }
        val key = IntArray(16) { challenge[it].toInt() and 0xFF }
        key[15] = key[15] xor 6
        val out = arPrime(key, BLOCK)
        return ByteArray(16) { out[it].toByte() }
    }

    private fun keySchedule(key: IntArray): Array<IntArray> {
        val register = IntArray(17)
        for (i in 0 until 16) register[i] = key[i]
        register[16] = key.fold(0) { acc, b -> acc xor b }
        val keys = Array(17) { IntArray(16) }
        keys[0] = key.copyOf()
        for (p in 1 until 17) {
            for (i in 0 until 17) register[i] = ((register[i] shl 3) or (register[i] ushr 5)) and 0xFF
            for (i in 0 until 16) keys[p][i] = (register[(p + i) % 17] + BIAS[p - 1][i]) and 0xFF
        }
        return keys
    }

    private fun pht(state: IntArray) {
        for (i in 0 until 16 step 2) {
            val a = state[i]
            val b = state[i + 1]
            state[i] = (2 * a + b) and 0xFF
            state[i + 1] = (a + b) and 0xFF
        }
    }

    private fun mix(state: IntArray) {
        val t = IntArray(16)
        for (layer in 0 until 4) {
            pht(state)
            if (layer < 3) {
                for (i in 0 until 16) t[i] = state[SHUFFLE[i]]
                t.copyInto(state)
            }
        }
    }

    /** Ar': SAFER+ with the plaintext fed back into round 3. */
    private fun arPrime(key: IntArray, plaintext: IntArray): IntArray {
        val keys = keySchedule(key)
        val state = plaintext.copyOf()
        for (round in 0 until ROUNDS) {
            if (round == 2) {
                for (i in 0 until 16) state[i] = if (XOR_POSITION[i]) state[i] xor plaintext[i] else (state[i] + plaintext[i]) and 0xFF
            }
            val k1 = keys[2 * round]
            val k2 = keys[2 * round + 1]
            for (i in 0 until 16) {
                state[i] = if (XOR_POSITION[i]) EXP[state[i] xor k1[i]] else LOG[(state[i] + k1[i]) and 0xFF]
                state[i] = if (XOR_POSITION[i]) (state[i] + k2[i]) and 0xFF else state[i] xor k2[i]
            }
            mix(state)
        }
        val last = keys[16]
        for (i in 0 until 16) state[i] = if (XOR_POSITION[i]) state[i] xor last[i] else (state[i] + last[i]) and 0xFF
        return state
    }
}
