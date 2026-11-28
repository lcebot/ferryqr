package io.github.lcebot.ferryqr.content

/**
 * Reads the QR code a computer shows for cross-device authentication with a passkey, as CTAP 2.2
 * defines it: FIDO:/ followed by digits that encode a CBOR map. Every 7 bytes are written as 17
 * digits of their little-endian value, and a shorter last group takes as few digits as its bytes
 * need. Key 3 of the map is when the code was made, in seconds since 1970, which browsers include.
 */
internal object PasskeyQr {

    private const val PREFIX = "FIDO:/"
    private const val CHUNK_BYTES = 7
    private const val CHUNK_DIGITS = 17

    /** Digits that a final group of 1 to 6 bytes takes. */
    private val PARTIAL_DIGITS = intArrayOf(0, 3, 5, 8, 10, 13, 15)

    private const val KEY_CREATED = 3L
    private const val KEY_OPERATION = 5L

    /** When the code was made, in seconds since 1970, or null when the code does not say. */
    fun createdAt(raw: String): Long? = valueOf(raw, KEY_CREATED) as? Long

    /**
     * What the other device asks for, from the hint CTAP 2.2 codes carry: `ga` to sign in with a
     * passkey, `mc` to create one, and `dcp` and `dci` to present or add a digital credential. Null
     * when the code gives no hint, as older codes do, or one FerryQR does not know, which the
     * specification has authenticators take as signing in but a name should not presume.
     */
    fun operation(raw: String): PasskeyOperation? = when (valueOf(raw, KEY_OPERATION)) {
        "ga" -> PasskeyOperation.SIGN_IN
        "mc" -> PasskeyOperation.CREATE
        "dcp" -> PasskeyOperation.PRESENT_CREDENTIAL
        "dci" -> PasskeyOperation.ADD_CREDENTIAL
        else -> null
    }

    private fun valueOf(raw: String, key: Long): Any? {
        if (!raw.startsWith(PREFIX, ignoreCase = true)) return null
        val bytes = bytesOf(raw.substring(PREFIX.length)) ?: return null
        return try {
            Cbor(bytes).valueInMap(key)
        } catch (e: IndexOutOfBoundsException) {
            null
        } catch (e: IllegalStateException) {
            null
        }
    }

    private fun bytesOf(digits: String): ByteArray? {
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
        val out = ArrayList<Byte>()
        var index = 0
        while (index < digits.length) {
            val remaining = digits.length - index
            val (take, bytes) = if (remaining >= CHUNK_DIGITS) {
                CHUNK_DIGITS to CHUNK_BYTES
            } else {
                val partial = PARTIAL_DIGITS.indexOf(remaining)
                if (partial <= 0) return null
                remaining to partial
            }
            var value = digits.substring(index, index + take).toLong()
            repeat(bytes) {
                out += (value and 0xFF).toByte()
                value = value ushr 8
            }
            if (value != 0L) return null
            index += take
        }
        return out.toByteArray()
    }

    /**
     * Just enough CBOR to find a value under an unsigned key of the top map: an unsigned number as
     * a Long, a string as a String, anything else as null. Byte strings count as strings too: the
     * specification's own example writes the hint as one, where Chromium writes text.
     */
    private class Cbor(private val data: ByteArray) {
        private var position = 0

        fun valueInMap(key: Long): Any? {
            val (major, pairs) = head()
            check(major == MAP)
            var found: Any? = null
            repeat(pairs.toInt()) {
                val (keyMajor, keyValue) = head()
                if (keyMajor == UINT && keyValue == key) {
                    val (valueMajor, value) = head()
                    found = when (valueMajor) {
                        UINT -> value
                        TEXT, BYTES -> text(value)
                        else -> null.also { skipBody(valueMajor, value, depth = 1) }
                    }
                } else {
                    skipBody(keyMajor, keyValue, depth = 1)
                    skip(depth = 1)
                }
            }
            return found
        }

        private fun next(): Int = data[position++].toInt() and 0xFF

        private fun text(length: Long): String {
            check(length in 0..(data.size - position).toLong())
            val start = position
            position += length.toInt()
            return String(data, start, length.toInt(), Charsets.UTF_8)
        }

        /** The major type of the next item and its argument: a value, a length or a count. */
        private fun head(): Pair<Int, Long> {
            val initial = next()
            val major = initial ushr 5
            val info = initial and 0x1F
            val argument = when {
                info < 24 -> info.toLong()
                info == 24 -> next().toLong()
                info == 25 -> (next().toLong() shl 8) or next().toLong()
                info == 26 -> (0 until 4).fold(0L) { acc, _ -> (acc shl 8) or next().toLong() }
                info == 27 -> (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or next().toLong() }
                else -> error("indefinite lengths are not used here")
            }
            return major to argument
        }

        private fun skip(depth: Int) {
            val (major, argument) = head()
            skipBody(major, argument, depth)
        }

        /**
         * Skips an item [depth] levels into the top map. Each level is a call deeper, and scanned
         * data can nest thousands of levels in a code, so nesting deeper than [MAX_DEPTH] is taken
         * as malformed before it can exhaust the stack.
         */
        private fun skipBody(major: Int, argument: Long, depth: Int) {
            check(depth <= MAX_DEPTH)
            when (major) {
                UINT, NEGATIVE, SIMPLE -> Unit
                BYTES, TEXT -> {
                    check(argument in 0..(data.size - position).toLong())
                    position += argument.toInt()
                }
                ARRAY -> repeat(argument.toInt()) { skip(depth + 1) }
                MAP -> repeat(argument.toInt()) {
                    skip(depth + 1)
                    skip(depth + 1)
                }
                TAG -> skip(depth + 1)
            }
        }

        companion object {
            const val UINT = 0
            const val NEGATIVE = 1
            const val BYTES = 2
            const val TEXT = 3
            const val ARRAY = 4
            const val MAP = 5
            const val TAG = 6
            const val SIMPLE = 7

            /** Far beyond the level or two that real codes nest. */
            const val MAX_DEPTH = 16
        }
    }
}
