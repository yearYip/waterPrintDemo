package com.demo.watermarktracedemo

internal object PayloadFixtures {
    val payload = WatermarkPayload("123456", "0123456789abcdef", 1792378692705L)
    // Historical WMK2 bytes, including its CRC32. Never produced by the new writer.
    val legacyBits = hexToBits("574d4b3200253132333435367c303132333435363738396162636465667c31373932333738363932373035b017eb8c")

    fun hexToBits(hex: String): BooleanArray {
        val bytes = hex.chunked(2).map { it.toInt(16) }
        return BooleanArray(bytes.size * 8) { (bytes[it / 8] and (1 shl (7 - it % 8))) != 0 }
    }
}
