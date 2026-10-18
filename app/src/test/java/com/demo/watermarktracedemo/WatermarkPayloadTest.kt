package com.demo.watermarktracedemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatermarkPayloadTest {
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
