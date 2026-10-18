package com.demo.watermarktracedemo

import android.graphics.Bitmap

/** Keeps invisibility first while explicitly reporting the extraction capability. */
object WatermarkGenerator {
    data class Result(
        val bitmap: Bitmap,
        val screenshotVerified: Boolean,
        val regions: Int = 1,
        val screenshotRegions: Int = 0
    )

    fun generate(source: Bitmap, bits: BooleanArray, onProgress: (String) -> Unit = {}): Result {
        require(WatermarkPayload.fromBitArray(bits) != null) { "水印数据无效" }
        require(bits.size <= FrequencyWatermark.MAX_PAYLOAD_BITS) { "用户 ID 过长" }
        val result = MultiRegionWatermark.embed(source, bits, onProgress)
        return Result(
            result.bitmap, result.screenshotRegions > 0,
            result.regions, result.screenshotRegions
        )
    }
}
