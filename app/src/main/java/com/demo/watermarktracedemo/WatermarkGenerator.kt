package com.demo.watermarktracedemo

import android.graphics.Bitmap

object WatermarkGenerator {
    data class Result(val bitmap: Bitmap, val regions: Int)

    fun generate(source: Bitmap, bits: BooleanArray, onProgress: (String) -> Unit = {}): Result {
        require(bits.size <= DwtDctSvdWatermark.MAX_PAYLOAD_BITS) { "用户 ID 过长" }
        val result = MultiRegionWatermark.embed(source, bits, onProgress)
        return Result(result.bitmap, result.regions)
    }
}
