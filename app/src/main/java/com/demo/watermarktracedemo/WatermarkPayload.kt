package com.demo.watermarktracedemo

import java.util.zip.CRC32
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class WatermarkPayload(
    val userId: String,
    val deviceId: String,
    val timestamp: Long
) {
    fun toBitArray(): BooleanArray {
        require('|' !in userId && '|' !in deviceId) { "ID cannot contain |" }
        val user = userId.toByteArray(Charsets.UTF_8)
        val packedDevice = deviceId.matches(Regex("[0-9a-f]{16}"))
        val device = if (packedDevice) ByteArray(8) {
            deviceId.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        } else deviceId.toByteArray(Charsets.UTF_8)
        require(user.isNotEmpty() && device.isNotEmpty()) { "ID cannot be empty" }
        require(user.size <= 255) { "用户 ID 过长" }
        val wideTimestamp = timestamp !in 0..0xffffffffffffL
        val timestampBytes = if (wideTimestamp) 8 else 6
        val bodyLength = 2 + timestampBytes + user.size + device.size
        require(bodyLength <= 0xffff) { "Watermark payload is too long" }
        val frame = ByteArray(HEADER_BYTES + bodyLength + CHECKSUM_BYTES)
        COMPACT_MAGIC.copyInto(frame)
        frame[4] = (bodyLength ushr 8).toByte()
        frame[5] = bodyLength.toByte()
        frame[6] = ((if (packedDevice) 1 else 0) or (if (wideTimestamp) 2 else 0)).toByte()
        frame[7] = user.size.toByte()
        repeat(timestampBytes) {
            frame[8 + it] = (timestamp ushr ((timestampBytes - 1 - it) * 8)).toByte()
        }
        user.copyInto(frame, 8 + timestampBytes)
        device.copyInto(frame, 8 + timestampBytes + user.size)
        val checksum = CRC32().apply { update(frame, 0, frame.size - CHECKSUM_BYTES) }.value
        for (index in 0 until CHECKSUM_BYTES) {
            frame[frame.size - CHECKSUM_BYTES + index] =
                (checksum ushr (24 - index * 8)).toByte()
        }
        return frame.toBits()
    }

    companion object {
        private const val HEADER_BYTES = 6
        private const val CHECKSUM_BYTES = 4
        private val LEGACY_MAGIC = byteArrayOf(0x57, 0x4d, 0x4b, 0x32)
        private val COMPACT_MAGIC = byteArrayOf(0x57, 0x4d, 0x4b, 0x33)
        const val HEADER_BITS = HEADER_BYTES * 8

        internal fun isCompact(bits: BooleanArray): Boolean = bits.size >= HEADER_BITS &&
            bits.toBytes(HEADER_BYTES).copyOfRange(0, 4).contentEquals(COMPACT_MAGIC)

        fun requiredBitCount(headerBits: BooleanArray): Int? {
            if (headerBits.size < HEADER_BITS) return null
            val header = headerBits.toBytes(HEADER_BYTES)
            val magic = header.copyOfRange(0, 4)
            if (!magic.contentEquals(LEGACY_MAGIC) && !magic.contentEquals(COMPACT_MAGIC)) return null
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
            if (frame.copyOfRange(0, 4).contentEquals(COMPACT_MAGIC)) return decodeCompact(frame, payloadEnd)
            val fields = frame.copyOfRange(HEADER_BYTES, payloadEnd)
                .toString(Charsets.UTF_8).split('|')
            if (fields.size != 3 || fields[0].isEmpty() || fields[1].isEmpty()) return null
            val timestamp = fields[2].toLongOrNull() ?: return null
            return WatermarkPayload(fields[0], fields[1], timestamp)
        }

        private fun decodeCompact(frame: ByteArray, payloadEnd: Int): WatermarkPayload? {
            if (payloadEnd < 8) return null
            val flags = frame[6].toInt() and 255
            if (flags and 0xfc != 0) return null
            val timestampBytes = if (flags and 2 != 0) 8 else 6
            val userLength = frame[7].toInt() and 255
            val userStart = 8 + timestampBytes
            val deviceStart = userStart + userLength
            if (userLength == 0 || deviceStart >= payloadEnd) return null
            val packedDevice = flags and 1 != 0
            if (packedDevice && payloadEnd - deviceStart != 8) return null
            var timestamp = 0L
            repeat(timestampBytes) { timestamp = (timestamp shl 8) or (frame[8 + it].toLong() and 255) }
            return runCatching {
                fun utf8(start: Int, length: Int) = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(frame, start, length)).toString()
                val user = utf8(userStart, userLength)
                val device = if (packedDevice) frame.copyOfRange(deviceStart, payloadEnd)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                    else utf8(deviceStart, payloadEnd - deviceStart)
                if ('|' in user || '|' in device) null else WatermarkPayload(user, device, timestamp)
            }.getOrNull()
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
