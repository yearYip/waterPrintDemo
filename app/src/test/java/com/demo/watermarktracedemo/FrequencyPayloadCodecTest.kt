package com.demo.watermarktracedemo

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class FrequencyPayloadCodecTest {
    private fun soft(bits: BooleanArray) = DoubleArray(bits.size) { if (bits[it]) -1.0 else 1.0 }

    @Test fun actualLengthsDecodeIncludingMaximumCapacity() {
        val cases = listOf(WatermarkPayload("1", "x", 0), PayloadFixtures.payload,
            PayloadFixtures.payload.copy(userId = "a".repeat(38)))
        assertEquals(512, cases.last().toBitArray().size)
        for (payload in cases) {
            val encoded = FrequencyPayloadCodec.encode(payload.toBitArray())
            assertEquals(3584, encoded.size)
            assertEquals(payload, FrequencyPayloadCodec.decode(soft(encoded)))
        }
    }

    @Test fun interleavedErasuresAndBitErrorsRecoverCompleteInformation() {
        val payload = PayloadFixtures.payload
        val votes = soft(FrequencyPayloadCodec.encode(payload.toBitArray()))
        val random = Random(20261019)
        for (index in votes.indices) {
            val event = random.nextInt(100)
            if (event < 20) votes[index] = 0.0
            else if (event < 27) votes[index] = -votes[index]
        }
        assertEquals(payload, FrequencyPayloadCodec.decode(votes))
    }

    @Test fun unreadableHeaderOrBodyNeverReturnsAnUncheckedPayload() {
        val original = soft(FrequencyPayloadCodec.encode(PayloadFixtures.payload.toBitArray()))
        val missingHeader = original.copyOf().apply { fill(0.0, 0, 768) }
        val missingBody = original.copyOf().apply { fill(0.0, 768, size) }
        assertNull(FrequencyPayloadCodec.decode(missingHeader))
        assertNull(FrequencyPayloadCodec.decode(missingBody))
        assertNull(FrequencyPayloadCodec.decode(DoubleArray(3584)))
    }

    @Test fun historicalFixedLengthCodeStillDecodes() {
        // Independently reproduce the historical wire mapping instead of using the new writer.
        val bits = PayloadFixtures.legacyBits
        val encoded = BooleanArray(1036)
        var state = 0
        repeat(518) { time ->
            val register = (state shl 1) or if (time < bits.size && bits[time]) 1 else 0
            encoded[time * 2] = Integer.bitCount(register and 0x6d) % 2 != 0
            encoded[time * 2 + 1] = Integer.bitCount(register and 0x4f) % 2 != 0
            state = register and 63
        }
        val repeated = BooleanArray(3584) { encoded[it % encoded.size] }
        assertArrayEquals(repeated, FrequencyPayloadCodec.encode(bits))
        assertEquals(PayloadFixtures.payload, FrequencyPayloadCodec.decode(soft(repeated)))
    }
}
