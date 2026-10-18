package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Each region contains the complete payload, so losing other regions does not lose the message. */
object MultiRegionWatermark {
    private const val MIN_REGION_SIZE = 320
    private const val MAX_REGIONS_PER_AXIS = 4

    data class Result(val bitmap: Bitmap, val regions: Int, val screenshotRegions: Int)

    fun regions(width: Int, height: Int): List<Rect> {
        val columns = (width / MIN_REGION_SIZE).coerceIn(1, MAX_REGIONS_PER_AXIS)
        val rows = (height / MIN_REGION_SIZE).coerceIn(1, MAX_REGIONS_PER_AXIS)
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

    fun embed(source: Bitmap, bits: BooleanArray): Result {
        val regions = regions(source.width, source.height)
        require(regions.size > 1) { "图片尺寸不足以分成多个水印区域" }
        require(regions.all { it.width().toLong() * it.height() >= bits.size }) {
            "水印区域太小，无法容纳完整数据"
        }
        val expected = WatermarkPayload.fromBitArray(bits) ?: error("水印数据无效")
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        var screenshotRegions = 0
        try {
            for (region in regions) {
                val tile = Bitmap.createBitmap(
                    source, region.left, region.top, region.width(), region.height()
                )
                try {
                    val frequency = try {
                        FrequencyWatermark.embed(tile, bits)
                    } catch (_: FrequencyWatermark.CarrierException) {
                        null
                    }
                    // Keep an exact-PNG copy in every region, including frequency regions.
                    val marked = try {
                        HalfToneWatermark.embed(frequency ?: tile, bits)
                    } finally {
                        frequency?.recycle()
                    }
                    try {
                        check(WatermarkPayload.fromBitArray(
                            HalfToneWatermark.extract(marked, bits.size)
                        ) == expected) { "水印区域的 PNG 原图回读失败" }
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
            val scale = min(1f, 384f / min(bitmap.width, bitmap.height))
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

    /** Try plausible grids when the complete image bounds are known. */
    fun extractFromBounds(reader: FrequencyWatermark.Reader, corners: FloatArray): WatermarkPayload? {
        if (corners.size != 8) return null
        val width = hypot(corners[2] - corners[0], corners[3] - corners[1])
        val height = hypot(corners[6] - corners[0], corners[7] - corners[1])
        if (width < 320f || height < 320f) return null
        for (rows in 1..4) for (columns in 1..4) {
            if (rows * columns < 2) continue
            if (width / columns < 160f || height / rows < 160f) continue
            val cornersToTry = listOf(
                0 to 0, 0 to (columns - 1),
                (rows - 1) to 0, (rows - 1) to (columns - 1)
            ).distinct()
            for ((row, column) in cornersToTry) {
                val tile = FloatArray(8)
                for (point in 0 until 4) {
                    val u = (column + if (point == 1 || point == 2) 1 else 0).toFloat() / columns
                    val v = (row + if (point >= 2) 1 else 0).toFloat() / rows
                    tile[point * 2] = corners[0] * (1 - u) * (1 - v) +
                        corners[2] * u * (1 - v) + corners[4] * u * v + corners[6] * (1 - u) * v
                    tile[point * 2 + 1] = corners[1] * (1 - u) * (1 - v) +
                        corners[3] * u * (1 - v) + corners[5] * u * v + corners[7] * (1 - u) * v
                }
                reader.extract(tile, refine = false)?.let { return it }
            }
        }
        return null
    }
}
