package com.demo.watermarktracedemo

import android.graphics.Bitmap

object WatermarkGenerator {
    data class Result(val bitmap: Bitmap)

    fun generate(source: Bitmap, bits: BooleanArray, onProgress: (String) -> Unit = {}): Result {
        require(bits.size <= DctWatermark.MAX_PAYLOAD_BITS) { "用户 ID 过长" }
        return Result(WholeImageWatermark.embed(source, bits, onProgress))
    }
}
