package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.min

/** Each region contains the complete payload, so losing other regions does not lose the message. */
object MultiRegionWatermark {
    private const val MIN_REGION_SIZE = 320
    private const val MAX_GENERATED_REGIONS_PER_AXIS = 6

    data class Result(val bitmap: Bitmap, val regions: Int)

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
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        try {
            for ((index, region) in regions.withIndex()) {
                onProgress("正在写入水印区域 ${index + 1}/${regions.size}…")
                val tile = Bitmap.createBitmap(
                    source, region.left, region.top, region.width(), region.height()
                )
                try {
                    val carrier = DwtDctSvdWatermark.embed(tile, bits)
                    val marked = try {
                        CornerLocator.embed(carrier)
                    } finally {
                        carrier.recycle()
                    }
                    try {
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
            return Result(output, regions.size)
        } catch (error: Exception) {
            output.recycle()
            throw error
        }
    }

}
