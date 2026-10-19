package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/** Diagnostic copies only: the returned PNG is never replaced by a compressed image. */
object WatermarkCompressionCheck {
    private val qualities = intArrayOf(75, 50)
    private const val MAX_CHECK_EDGE = 1600

    data class Result(
        val quality: Int,
        val width: Int,
        val height: Int,
        val totalRegions: Int,
        val readableRegions: Int = 0,
        val bestPilotScore: Double = 0.0,
        val maxReadablePilots: Int = 0,
        val candidateFound: Boolean = false,
        val automaticReadable: Boolean = false,
        val error: String? = null
    )

    fun verify(
        bitmap: Bitmap,
        expected: WatermarkPayload,
        onProgress: (String) -> Unit = {}
    ): List<Result> {
        // Keep the original tiling: recomputing regions after resize changes their count.
        val regions = MultiRegionWatermark.regions(bitmap.width, bitmap.height)
        val scale = min(0.85f, MAX_CHECK_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return qualities.map { quality ->
            try {
                onProgress("正在模拟缩放与 WebP 质量 $quality 压缩…")
                verifyQuality(bitmap, expected, regions, width, height, quality, onProgress)
            } catch (e: Exception) {
                // A diagnostic failure must not discard a valid PNG.
                Result(quality, width, height, regions.size, error = e.message ?: "检查异常")
            }
        }
    }

    private fun verifyQuality(
        bitmap: Bitmap,
        expected: WatermarkPayload,
        regions: List<Rect>,
        width: Int,
        height: Int,
        quality: Int,
        onProgress: (String) -> Unit
    ): Result {
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val compressed = try {
            val bytes = ByteArrayOutputStream().use { stream ->
                @Suppress("DEPRECATION")
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
                check(resized.compress(format, quality, stream)) { "WebP 压缩失败" }
                stream.toByteArray()
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("WebP 解码失败")
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
        try {
            val reader = FrequencyWatermark.Reader(compressed)
            val scaleX = width.toFloat() / bitmap.width
            val scaleY = height.toFloat() / bitmap.height
            var readableRegions = 0
            var bestPilotScore = 0.0
            var maxReadablePilots = 0
            for ((index, region) in regions.withIndex()) {
                onProgress("WebP 质量 $quality：正在检查区域 ${index + 1}/${regions.size}…")
                val corners = floatArrayOf(
                    region.left * scaleX, region.top * scaleY,
                    region.right * scaleX, region.top * scaleY,
                    region.right * scaleX, region.bottom * scaleY,
                    region.left * scaleX, region.bottom * scaleY
                )
                val pilots = reader.pilotDiagnostics(corners)
                bestPilotScore = maxOf(bestPilotScore, pilots.score)
                maxReadablePilots = maxOf(maxReadablePilots, pilots.readableBlocks)
                if (reader.extract(corners, refine = false) == expected) readableRegions++
            }
            onProgress("WebP 质量 $quality：正在检查自动定位回读…")
            val automatic = verifyAutomatic(compressed, reader, expected, quality, onProgress)
            return Result(
                quality, width, height, regions.size, readableRegions, bestPilotScore,
                maxReadablePilots, automatic.first, automatic.second
            )
        } finally {
            compressed.recycle()
        }
    }

    private fun verifyAutomatic(
        bitmap: Bitmap,
        reader: FrequencyWatermark.Reader,
        expected: WatermarkPayload,
        quality: Int,
        onProgress: (String) -> Unit
    ): Pair<Boolean, Boolean> {
        var candidateFound = false
        var earlySuccess = false
        fun search(edge: Int) = CornerLocator.candidates(bitmap, edge) { corners ->
            candidateFound = true
            earlySuccess = reader.extract(corners, refine = false) == expected
            earlySuccess
        }
        var candidates = search(CornerLocator.FAST_SEARCH_EDGE)
        if (!earlySuccess && candidates.isEmpty() &&
            maxOf(bitmap.width, bitmap.height) > CornerLocator.FAST_SEARCH_EDGE
        ) {
            onProgress("WebP 质量 $quality：正在高清重试定位…")
            candidates = search(CornerLocator.RETRY_SEARCH_EDGE)
        }
        candidateFound = candidateFound || candidates.isNotEmpty()
        if (earlySuccess) return true to true
        for (corners in candidates) {
            if (reader.extract(corners, refine = false) == expected) return true to true
        }
        for ((index, corners) in candidates.take(4).withIndex()) {
            onProgress("WebP 质量 $quality：正在校正候选区域 ${index + 1}/${min(4, candidates.size)}…")
            if (reader.extract(corners) == expected) return true to true
        }
        return candidateFound to false
    }
}
