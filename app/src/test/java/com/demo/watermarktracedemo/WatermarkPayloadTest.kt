package com.demo.watermarktracedemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class WatermarkPayloadTest {
    @Test fun compactSizeAndLegacyCompatibility() {
        val compact = PayloadFixtures.payload.toBitArray()
        assertEquals(376, PayloadFixtures.legacyBits.size)
        assertEquals(256, compact.size)
        assertTrue(WatermarkPayload.isCompact(compact))
        assertEquals(PayloadFixtures.payload, WatermarkPayload.fromBitArray(compact))
        assertEquals(PayloadFixtures.payload, WatermarkPayload.fromBitArray(PayloadFixtures.legacyBits))
        assertEquals(376, WatermarkPayload.requiredBitCount(PayloadFixtures.legacyBits))
    }

    @Test fun timestampAndDeviceFormatsPreserveExactValues() {
        val timestamps = listOf(0L, 0xffffffffffffL, 0x1000000000000L, Long.MIN_VALUE, Long.MAX_VALUE, -1L)
        val devices = listOf("0123456789abcdef", "0123456789ABCDEF", "设备-123", "unknown")
        for (timestamp in timestamps) for (device in devices) {
            val original = WatermarkPayload("用户42", device, timestamp)
            assertEquals(original, WatermarkPayload.fromBitArray(original.toBitArray()))
        }
    }

    @Test fun everyFrameByteIsCoveredByChecksum() {
        val bits = PayloadFixtures.payload.toBitArray()
        for (byte in 0 until bits.size / 8) {
            val damaged = bits.copyOf()
            damaged[byte * 8] = !damaged[byte * 8]
            assertNull(WatermarkPayload.fromBitArray(damaged))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WatermarkPayload("", "device", 1L).toBitArray()
        }
    }

    @Test
    fun roundTripWithUnicodeAndExtraImageBits() {
        val original = WatermarkPayload("用户42", "device123", 1720000000000L)
        val bits = original.toBitArray()
        assertEquals(bits.size, WatermarkPayload.requiredBitCount(bits))
        assertEquals(original, WatermarkPayload.fromBitArray(bits + BooleanArray(128)))
    }

    @Test
    fun rejectsCorruptionAndTruncation() {
        val bits = WatermarkPayload("user", "device", 1720000000000L).toBitArray()
        assertNull(WatermarkPayload.fromBitArray(bits.copyOf(bits.size - 8)))
        bits[WatermarkPayload.HEADER_BITS + 2] = !bits[WatermarkPayload.HEADER_BITS + 2]
        assertNull(WatermarkPayload.fromBitArray(bits))
        assertNull(WatermarkPayload.requiredBitCount(BooleanArray(WatermarkPayload.HEADER_BITS)))
    }
}
