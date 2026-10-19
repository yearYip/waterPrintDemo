package com.demo.watermarktracedemo

/** Frequency framing only; carrier geometry, pilots, QIM period and strength stay in FrequencyWatermark. */
internal object FrequencyPayloadCodec {
    const val MAX_PAYLOAD_BITS = 512
    const val DATA_BLOCKS = 3584
    private const val HEADER_BLOCKS = 768
    private const val TAIL_BITS = 6
    private const val LEGACY_CODE_BITS = (MAX_PAYLOAD_BITS + TAIL_BITS) * 2

    fun encode(bits: BooleanArray): BooleanArray {
        require(bits.size <= MAX_PAYLOAD_BITS && WatermarkPayload.fromBitArray(bits) != null)
        if (!WatermarkPayload.isCompact(bits)) {
            val code = convolutionalEncode(bits.copyOf(MAX_PAYLOAD_BITS))
            return BooleanArray(DATA_BLOCKS) { code[it % code.size] }
        }
        require(WatermarkPayload.requiredBitCount(bits) == bits.size)
        val header = convolutionalEncode(bits.copyOfRange(0, WatermarkPayload.HEADER_BITS))
        val body = convolutionalEncode(bits.copyOfRange(WatermarkPayload.HEADER_BITS, bits.size))
        return BooleanArray(DATA_BLOCKS) { index ->
            if (index < HEADER_BLOCKS) header[index % header.size]
            else body[(index - HEADER_BLOCKS) % body.size]
        }
    }

    /** A positive vote means zero, matching sin(2*pi*coefficient/period) in the existing carrier. */
    fun decode(votes: DoubleArray): WatermarkPayload? {
        require(votes.size == DATA_BLOCKS)
        val headerCodeBits = (WatermarkPayload.HEADER_BITS + TAIL_BITS) * 2
        val headerVotes = DoubleArray(headerCodeBits)
        for (index in 0 until HEADER_BLOCKS) headerVotes[index % headerCodeBits] += votes[index]
        val header = viterbi(headerVotes)
        val bitCount = WatermarkPayload.requiredBitCount(header)
        if (WatermarkPayload.isCompact(header) && bitCount != null &&
            bitCount in WatermarkPayload.HEADER_BITS + 8..MAX_PAYLOAD_BITS
        ) {
            val bodyCodeBits = (bitCount - WatermarkPayload.HEADER_BITS + TAIL_BITS) * 2
            val bodyVotes = DoubleArray(bodyCodeBits)
            for (index in HEADER_BLOCKS until DATA_BLOCKS) {
                bodyVotes[(index - HEADER_BLOCKS) % bodyCodeBits] += votes[index]
            }
            WatermarkPayload.fromBitArray(header + viterbi(bodyVotes))?.let { return it }
        }
        // Historical WMK2 used one fixed 512-bit frame over all data blocks.
        val legacyVotes = DoubleArray(LEGACY_CODE_BITS)
        for (index in votes.indices) legacyVotes[index % LEGACY_CODE_BITS] += votes[index]
        val legacy = viterbi(legacyVotes)
        if (WatermarkPayload.isCompact(legacy)) return null
        return WatermarkPayload.fromBitArray(legacy)
    }

    private fun convolutionalEncode(bits: BooleanArray): BooleanArray {
        val output = BooleanArray((bits.size + TAIL_BITS) * 2)
        var state = 0
        for (time in 0 until bits.size + TAIL_BITS) {
            val bit = if (time < bits.size && bits[time]) 1 else 0
            val register = (state shl 1) or bit
            output[time * 2] = Integer.bitCount(register and 0x6d) % 2 != 0
            output[time * 2 + 1] = Integer.bitCount(register and 0x4f) % 2 != 0
            state = register and 63
        }
        return output
    }

    private fun viterbi(votes: DoubleArray): BooleanArray {
        val times = votes.size / 2
        var metrics = DoubleArray(64) { Double.NEGATIVE_INFINITY }.apply { this[0] = 0.0 }
        val previous = Array(times) { IntArray(64) }
        for (time in 0 until times) {
            val next = DoubleArray(64) { Double.NEGATIVE_INFINITY }
            for (state in 0 until 64) {
                if (!metrics[state].isFinite()) continue
                for (bit in 0..1) {
                    val register = (state shl 1) or bit
                    val destination = register and 63
                    val a = Integer.bitCount(register and 0x6d) % 2
                    val b = Integer.bitCount(register and 0x4f) % 2
                    val metric = metrics[state] + votes[time * 2] * (if (a == 0) 1 else -1) +
                        votes[time * 2 + 1] * (if (b == 0) 1 else -1)
                    if (metric > next[destination]) {
                        next[destination] = metric
                        previous[time][destination] = state
                    }
                }
            }
            metrics = next
        }
        val bits = BooleanArray(times - TAIL_BITS)
        var state = 0
        for (time in times - 1 downTo 0) {
            if (time < bits.size) bits[time] = state and 1 != 0
            state = previous[time][state]
        }
        return bits
    }
}
