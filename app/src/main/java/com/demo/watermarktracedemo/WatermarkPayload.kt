package com.demo.watermarktracedemo

import java.util.zip.CRC32

data class WatermarkPayload(
    val userId: String,
    val deviceId: String,
    val timestamp: Long
) {
    fun toBitArray(): BooleanArray {
        require('|' !in userId && '|' !in deviceId) { "ID cannot contain |" }
        val payload = "$userId|$deviceId|$timestamp".toByteArray(Charsets.UTF_8)
        require(payload.size <= 0xffff) { "Watermark payload is too long" }

        val frame = ByteArray(HEADER_BYTES + payload.size + CHECKSUM_BYTES)
        MAGIC.copyInto(frame)
        frame[4] = (payload.size ushr 8).toByte()
        frame[5] = payload.size.toByte()
        payload.copyInto(frame, HEADER_BYTES)
        val checksum = CRC32().apply { update(frame, 0, HEADER_BYTES + payload.size) }.value
        for (index in 0 until CHECKSUM_BYTES) {
            frame[HEADER_BYTES + payload.size + index] =
                (checksum ushr (24 - index * 8)).toByte()
        }
        return frame.toBits()
    }

    companion object {
        private const val HEADER_BYTES = 6
        private const val CHECKSUM_BYTES = 4
        private val MAGIC = byteArrayOf('W'.code.toByte(), 'M'.code.toByte(), 'K'.code.toByte(), '2'.code.toByte())
        const val HEADER_BITS = HEADER_BYTES * 8

        fun requiredBitCount(headerBits: BooleanArray): Int? {
            if (headerBits.size < HEADER_BITS) return null
            val header = headerBits.toBytes(HEADER_BYTES)
            if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
            val length = ((header[4].toInt() and 0xff) shl 8) or (header[5].toInt() and 0xff)
            return (HEADER_BYTES + length + CHECKSUM_BYTES) * 8
        }

        fun fromBitArray(bits: BooleanArray): WatermarkPayload? {
            val requiredBits = requiredBitCount(bits) ?: return null
            if (bits.size < requiredBits) return null
            val frame = bits.toBytes(requiredBits / 8)
            val payloadEnd = frame.size - CHECKSUM_BYTES
            val checksum = CRC32().apply { update(frame, 0, payloadEnd) }.value
            for (index in 0 until CHECKSUM_BYTES) {
                if (frame[payloadEnd + index] != (checksum ushr (24 - index * 8)).toByte()) {
                    return null
                }
            }
            val fields = frame.copyOfRange(HEADER_BYTES, payloadEnd)
                .toString(Charsets.UTF_8).split('|')
            if (fields.size != 3 || fields[0].isEmpty() || fields[1].isEmpty()) return null
            val timestamp = fields[2].toLongOrNull() ?: return null
            return WatermarkPayload(fields[0], fields[1], timestamp)
        }

        private fun ByteArray.toBits(): BooleanArray = BooleanArray(size * 8) { index ->
            (this[index / 8].toInt() and (1 shl (7 - index % 8))) != 0
        }

        private fun BooleanArray.toBytes(byteCount: Int): ByteArray = ByteArray(byteCount) { index ->
            var value = 0
            for (bit in 0 until 8) {
                if (this[index * 8 + bit]) value = value or (1 shl (7 - bit))
            }
            value.toByte()
        }
    }
}
