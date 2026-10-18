package com.demo.watermarktracedemo

import android.graphics.Bitmap

/** Keeps invisibility first while explicitly reporting the extraction capability. */
object WatermarkGenerator {
    data class Result(val bitmap: Bitmap, val screenshotVerified: Boolean)

    fun generate(source: Bitmap, bits: BooleanArray): Result {
        val expected = WatermarkPayload.fromBitArray(bits)
            ?: throw IllegalArgumentException("水印数据无效")
        require(bits.size <= FrequencyWatermark.MAX_PAYLOAD_BITS) { "用户 ID 过长" }
        try {
            return Result(FrequencyWatermark.embed(source, bits), screenshotVerified = true)
        } catch (_: FrequencyWatermark.CarrierException) {
            // A failed frequency attempt recycles its image and leaves source untouched.
            // Do not increase visible strength to force screenshot robustness.
        }
        require(source.width.toLong() * source.height >= bits.size) { "图片太小，无法容纳水印数据" }
        val bitmap = HalfToneWatermark.embed(source, bits)
        try {
            check(WatermarkPayload.fromBitArray(HalfToneWatermark.extract(bitmap, bits.size)) == expected) {
                "PNG 原图水印回读失败"
            }
            return Result(bitmap, screenshotVerified = false)
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
    }
}
