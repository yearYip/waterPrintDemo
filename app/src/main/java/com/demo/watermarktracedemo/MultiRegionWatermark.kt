package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.min
import kotlin.math.roundToInt

/** Each region contains the complete payload, so losing other regions does not lose the message. */
object MultiRegionWatermark {
    private const val MIN_REGION_SIZE = 320
    private const val MAX_GENERATED_REGIONS_PER_AXIS = 6

    data class Result(val bitmap: Bitmap, val regions: Int, val screenshotRegions: Int)

    fun regions(width: Int, height: Int): List<Rect> {
        val columns = (width / MIN_REGION_SIZE).coerceIn(1, MAX_GENERATED_REGIONS_PER_AXIS)
        val rows = (height / MIN_REGION_SIZE).coerceIn(1, MAX_GENERATED_REGIONS_PER_AXIS)
        return buildList {
            for (row in 0 until rows) for (column in 0 until columns) {
                add(Rect(
                    column * width / columns,
                    row * height / rows,
                    (column + 1) * width / columns,
                    (row + 1) * height / rows
                ))
            }
        }
    }

    fun embed(source: Bitmap, bits: BooleanArray, onProgress: (String) -> Unit = {}): Result {
        val regions = regions(source.width, source.height)
        require(regions.all { min(it.width(), it.height()) >= 64 }) {
            "图片太小，无法写入角点定位信号"
        }
        require(regions.all { it.width().toLong() * it.height() >= bits.size }) {
            "水印区域太小，无法容纳完整数据"
        }
        val expected = WatermarkPayload.fromBitArray(bits) ?: error("水印数据无效")
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        var screenshotRegions = 0
        try {
            for ((index, region) in regions.withIndex()) {
                onProgress("正在写入水印区域 ${index + 1}/${regions.size}…")
                val tile = Bitmap.createBitmap(
                    source, region.left, region.top, region.width(), region.height()
                )
                try {
                    val frequency = try {
                        FrequencyWatermark.embed(tile, bits)
                    } catch (_: FrequencyWatermark.CarrierException) {
                        null
                    }
                    // The chroma pilots mark each tile corner; the PNG payload is written last.
                    val marked = try {
                        val located = CornerLocator.embed(frequency ?: tile)
                        try {
                            HalfToneWatermark.embed(located, bits)
                        } finally {
                            located.recycle()
                        }
                    } finally {
                        frequency?.recycle()
                    }
                    try {
                        check(WatermarkPayload.fromBitArray(
                            HalfToneWatermark.extract(marked, bits.size)
                        ) == expected) { "水印区域的 PNG 原图回读失败" }
                        onProgress("正在校验水印区域 ${index + 1}/${regions.size}…")
                        if (frequency != null && screenshotReadable(marked, expected)) {
                            screenshotRegions++
                        }
                        val pixels = IntArray(region.width() * region.height())
                        marked.getPixels(pixels, 0, region.width(), 0, 0, region.width(), region.height())
                        output.setPixels(
                            pixels, 0, region.width(), region.left, region.top,
                            region.width(), region.height()
                        )
                    } finally {
                        marked.recycle()
                    }
                } finally {
                    tile.recycle()
                }
            }
            return Result(output, regions.size, screenshotRegions)
        } catch (error: Exception) {
            output.recycle()
            throw error
        }
    }

    private fun screenshotReadable(bitmap: Bitmap, expected: WatermarkPayload): Boolean =
        runCatching {
            if (FrequencyWatermark.extract(bitmap, refine = false) != expected) return@runCatching false
            val scale = min(0.85f, 384f / min(bitmap.width, bitmap.height))
            val smaller = if (scale < 1f) Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).roundToInt(),
                (bitmap.height * scale).roundToInt(), true
            ) else bitmap
            try {
                FrequencyWatermark.extract(smaller, refine = false) == expected
            } finally {
                if (smaller !== bitmap) smaller.recycle()
            }
        }.getOrDefault(false)

}
