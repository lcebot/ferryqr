package io.github.lcebot.wxcode

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

private const val ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

private const val MODE_NUMERIC = 0b0001
private const val MODE_ALPHANUMERIC = 0b0010
private const val MODE_BYTE = 0b0100

/**
 * The data stream is QR's, with the character counts of small QR versions. Codes WeChat issues
 * hold one alphanumeric segment.
 */
internal object Segments {

    /** The text of the segments in [data], or null if the stream is malformed. */
    fun parse(data: IntArray): String? {
        val reader = BitReader(data)
        val text = StringBuilder()
        while (reader.remaining >= 4) {
            when (reader.read(4)) {
                0 -> break
                MODE_NUMERIC -> numeric(reader, text) ?: return null
                MODE_ALPHANUMERIC -> alphanumeric(reader, text) ?: return null
                MODE_BYTE -> bytes(reader, text) ?: return null
                else -> break
            }
        }
        return if (text.isEmpty()) null else text.toString()
    }

    private fun numeric(reader: BitReader, out: StringBuilder): Unit? {
        var count = reader.readOrNull(10) ?: return null
        while (count > 0) {
            val digits = minOf(count, 3)
            val value = reader.readOrNull(intArrayOf(0, 4, 7, 10)[digits]) ?: return null
            out.append(value.toString().padStart(digits, '0'))
            count -= digits
        }
        return Unit
    }

    private fun alphanumeric(reader: BitReader, out: StringBuilder): Unit? {
        var count = reader.readOrNull(9) ?: return null
        while (count >= 2) {
            val pair = reader.readOrNull(11) ?: return null
            if (pair >= 45 * 45) return null
            out.append(ALPHANUMERIC[pair / 45]).append(ALPHANUMERIC[pair % 45])
            count -= 2
        }
        if (count == 1) {
            val single = reader.readOrNull(6) ?: return null
            if (single >= 45) return null
            out.append(ALPHANUMERIC[single])
        }
        return Unit
    }

    private fun bytes(reader: BitReader, out: StringBuilder): Unit? {
        val count = reader.readOrNull(8) ?: return null
        if (count * 8 > reader.remaining) return null
        val raw = ByteArray(count)
        for (i in 0 until count) raw[i] = reader.read(8).toByte()
        out.append(
            try {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString()
            } catch (e: CharacterCodingException) {
                String(raw, Charsets.ISO_8859_1)
            }
        )
        return Unit
    }

    /**
     * [text] as a data stream of [capacity] codewords: one alphanumeric segment when every
     * character allows it, otherwise one UTF-8 byte segment, then the terminator and QR's
     * alternating pad codewords. Null when it does not fit.
     */
    fun encode(text: String, capacity: Int): IntArray? {
        val writer = BitWriter(capacity * 8)
        if (text.all { it in ALPHANUMERIC }) {
            if (text.length >= 1 shl 9) return null
            writer.write(MODE_ALPHANUMERIC, 4)
            writer.write(text.length, 9)
            var i = 0
            while (i + 1 < text.length) {
                writer.write(ALPHANUMERIC.indexOf(text[i]) * 45 + ALPHANUMERIC.indexOf(text[i + 1]), 11)
                i += 2
            }
            if (i < text.length) writer.write(ALPHANUMERIC.indexOf(text[i]), 6)
        } else {
            val raw = text.toByteArray(Charsets.UTF_8)
            if (raw.size >= 1 shl 8) return null
            writer.write(MODE_BYTE, 4)
            writer.write(raw.size, 8)
            for (b in raw) writer.write(b.toInt() and 0xFF, 8)
        }
        if (writer.overflowed) return null
        writer.write(0, minOf(4, writer.free))
        writer.write(0, writer.free % 8)
        var pad = 0
        while (writer.free > 0) writer.write(if (pad++ % 2 == 0) 0xEC else 0x11, 8)
        return writer.codewords()
    }
}

private class BitReader(private val bytes: IntArray) {
    private var position = 0

    val remaining: Int get() = bytes.size * 8 - position

    fun read(count: Int): Int {
        var value = 0
        repeat(count) {
            val bit = (bytes[position ushr 3] ushr (7 - (position and 7))) and 1
            value = (value shl 1) or bit
            position++
        }
        return value
    }

    fun readOrNull(count: Int): Int? = if (count <= remaining) read(count) else null
}

private class BitWriter(private val capacity: Int) {
    private val bits = BooleanArray(capacity)
    private var size = 0

    var overflowed = false
        private set

    val free: Int get() = capacity - size

    fun write(value: Int, count: Int) {
        if (count > free) {
            overflowed = true
            return
        }
        for (i in count - 1 downTo 0) bits[size++] = (value ushr i) and 1 == 1
    }

    fun codewords(): IntArray = IntArray(capacity / 8) { i ->
        var b = 0
        for (j in 0 until 8) b = (b shl 1) or (if (bits[i * 8 + j]) 1 else 0)
        b
    }
}
