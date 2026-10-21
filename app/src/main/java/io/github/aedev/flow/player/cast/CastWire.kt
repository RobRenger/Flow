package io.github.aedev.flow.player.cast

import java.io.ByteArrayOutputStream

/** One Cast v2 message. Flow only sends and reads the JSON (string) payload type. */
internal data class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payload: String,
)

/**
 * Encodes and decodes the `CastMessage` protobuf by hand, so casting needs no protobuf or Google
 * Play Services dependency. The message has six fields: protocol version (1), source (2),
 * destination (3), namespace (4), payload type (5) and the UTF-8 payload (6).
 */
internal object CastWire {
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH = 2
    private const val WIRE_FIXED32 = 5

    fun encode(message: CastMessage): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeVarintField(1, 0) // CASTV2_1_0
        out.writeStringField(2, message.sourceId)
        out.writeStringField(3, message.destinationId)
        out.writeStringField(4, message.namespace)
        out.writeVarintField(5, 0) // STRING
        out.writeStringField(6, message.payload)
        return out.toByteArray()
    }

    /** Returns null for binary payloads and malformed input. */
    fun decode(bytes: ByteArray): CastMessage? {
        var source = ""
        var destination = ""
        var namespace = ""
        var payload: String? = null
        val reader = Reader(bytes)
        while (reader.hasMore()) {
            val tag = reader.varint() ?: return null
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                WIRE_VARINT -> reader.varint() ?: return null
                WIRE_FIXED64 -> if (!reader.skip(8)) return null
                WIRE_FIXED32 -> if (!reader.skip(4)) return null
                WIRE_LENGTH -> {
                    val value = reader.lengthDelimited() ?: return null
                    when (field) {
                        2 -> source = value.decodeToString()
                        3 -> destination = value.decodeToString()
                        4 -> namespace = value.decodeToString()
                        6 -> payload = value.decodeToString()
                    }
                }
                else -> return null
            }
        }
        return payload?.let { CastMessage(source, destination, namespace, it) }
    }

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        write(v.toInt())
    }

    private fun ByteArrayOutputStream.writeVarintField(
        field: Int,
        value: Long,
    ) {
        writeVarint((field shl 3 or WIRE_VARINT).toLong())
        writeVarint(value)
    }

    private fun ByteArrayOutputStream.writeStringField(
        field: Int,
        value: String,
    ) {
        val bytes = value.encodeToByteArray()
        writeVarint((field shl 3 or WIRE_LENGTH).toLong())
        writeVarint(bytes.size.toLong())
        write(bytes)
    }

    private class Reader(
        private val bytes: ByteArray,
    ) {
        private var pos = 0

        fun hasMore() = pos < bytes.size

        fun varint(): Long? {
            var result = 0L
            var shift = 0
            while (pos < bytes.size && shift < 64) {
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return null
        }

        fun skip(count: Int): Boolean {
            if (pos + count > bytes.size) return false
            pos += count
            return true
        }

        fun lengthDelimited(): ByteArray? {
            val length = varint()?.takeIf { it in 0..(bytes.size - pos).toLong() }?.toInt() ?: return null
            return bytes.copyOfRange(pos, pos + length).also { pos += length }
        }
    }
}
