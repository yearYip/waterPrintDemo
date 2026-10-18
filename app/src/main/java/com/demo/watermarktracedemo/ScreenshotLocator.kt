package com.demo.watermarktracedemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds a previously generated photo inside a screenshot, including app chrome.
 * Only visual thumbnails are cached. A payload must still decode from the actual
 * selected image with a valid CRC; this cache never returns provenance data.
 */
object ScreenshotLocator {
    private const val LIMIT = 8
    private const val DIRECTORY = "watermark_locator"

    fun remember(context: Context, bitmap: Bitmap) {
        val directory = File(context.filesDir, DIRECTORY)
        check(directory.isDirectory || directory.mkdirs())
        val name = System.currentTimeMillis().toString() + "_" + bitmap.width + "_" + bitmap.height + ".png"
        val file = File(directory, name)
        val thumbnail = thumbnail(bitmap)
        try {
            file.outputStream().use { check(thumbnail.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            if (thumbnail !== bitmap) thumbnail.recycle()
        }
        directory.listFiles()?.filter { it.extension == "png" }
            ?.sortedByDescending { it.name.substringBefore('_').toLongOrNull() ?: 0L }
            ?.drop(LIMIT)?.forEach { it.delete() }
    }

    private fun thumbnail(bitmap: Bitmap): Bitmap {
        // Area averaging avoids aliasing tiny texture into the localization template.
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val sums = DoubleArray(64 * 64)
        val counts = IntArray(64 * 64)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val cell = (y * 64 / bitmap.height) * 64 + x * 64 / bitmap.width
                val p = pixels[y * bitmap.width + x]
                sums[cell] += Color.red(p) * 0.299 + Color.green(p) * 0.587 + Color.blue(p) * 0.114
                counts[cell]++
            }
        }
        return Bitmap.createBitmap(IntArray(64 * 64) {
            val luma = (sums[it] / counts[it].coerceAtLeast(1)).roundToInt()
            Color.rgb(luma, luma, luma)
        }, 64, 64, Bitmap.Config.ARGB_8888)
    }

    fun candidates(context: Context, bitmap: Bitmap): List<FloatArray> {
        val references = File(context.filesDir, DIRECTORY).listFiles()
            ?.filter { it.extension == "png" }
            ?.sortedByDescending { it.name.substringBefore('_').toLongOrNull() ?: 0L }
            ?.take(LIMIT) ?: return emptyList()
        val scale = min(1f, 800f / max(bitmap.width, bitmap.height))
        val reduced = Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true
        )
        val image = try { Gray.fromBitmap(reduced) } finally {
            if (reduced !== bitmap) reduced.recycle()
        }
        val found = mutableListOf<Match>()
        for (file in references) {
            val parts = file.nameWithoutExtension.split('_')
            if (parts.size != 3) continue
            val width = parts[1].toFloatOrNull() ?: continue
            val height = parts[2].toFloatOrNull() ?: continue
            if (width <= 0 || height <= 0) continue
            val thumbnail = BitmapFactory.decodeFile(file.absolutePath) ?: continue
            val reference = try { Gray.fromBitmap(thumbnail) } finally { thumbnail.recycle() }
            val coarse = descriptor(reference, 10)
            val fine = descriptor(reference, 16)
            val aspect = width / height
            val maximumWidth = min(image.width.toFloat(), image.height * aspect)
            val minimumWidth = max(70f, maximumWidth * 0.3f)
            var candidateWidth = maximumWidth
            val best = mutableListOf<Match>()
            while (candidateWidth >= minimumWidth) {
                val candidateHeight = candidateWidth / aspect
                val step = max(4f, min(candidateWidth, candidateHeight) / 10)
                val maxX = image.width - candidateWidth
                val maxY = image.height - candidateHeight
                var y = 0f
                while (y <= maxY + step / 2) {
                    var x = 0f
                    while (x <= maxX + step / 2) {
                        val left = min(x, maxX)
                        val top = min(y, maxY)
                        val bounds = floatArrayOf(left, top, left + candidateWidth, top + candidateHeight)
                        val score = correlation(image, bounds, coarse, 10)
                        if (score > 0.5 && (best.size < 3 || score > best.last().score)) {
                            best.add(Match(bounds, score))
                            best.sortByDescending { it.score }
                            while (best.size > 3) best.removeAt(best.lastIndex)
                        }
                        x += step
                    }
                    y += step
                }
                candidateWidth *= 0.88f
            }
            for (match in best) {
                var bounds = match.bounds
                var score = correlation(image, bounds, fine, 16)
                for (step in floatArrayOf(8f, 4f, 2f, 1f, 0.5f)) {
                    repeat(2) {
                        for (coordinate in 0..3) {
                            for (direction in intArrayOf(-1, 1)) {
                                val next = bounds.copyOf()
                                next[coordinate] += direction * step
                                if (!valid(next, image)) continue
                                val nextScore = correlation(image, next, fine, 16)
                                if (nextScore > score) { score = nextScore; bounds = next }
                            }
                        }
                    }
                }
                if (score >= 0.88) found.add(Match(bounds, score))
            }
        }
        val unique = mutableListOf<Match>()
        for (match in found.sortedByDescending { it.score }) {
            if (unique.none { other ->
                    match.bounds.indices.all { abs(match.bounds[it] - other.bounds[it]) < 3 }
                }) unique.add(match)
            if (unique.size == 4) break
        }
        val sx = bitmap.width.toFloat() / image.width
        val sy = bitmap.height.toFloat() / image.height
        return unique.map {
            val b = it.bounds
            floatArrayOf(b[0] * sx, b[1] * sy, b[2] * sx, b[1] * sy,
                b[2] * sx, b[3] * sy, b[0] * sx, b[3] * sy)
        }
    }

    private data class Match(val bounds: FloatArray, val score: Double)

    private fun valid(bounds: FloatArray, image: Gray): Boolean =
        bounds[0] >= 0 && bounds[1] >= 0 && bounds[2] <= image.width &&
            bounds[3] <= image.height && bounds[2] - bounds[0] >= 64 && bounds[3] - bounds[1] >= 64

    private fun descriptor(image: Gray, size: Int): DoubleArray {
        val bounds = floatArrayOf(0f, 0f, image.width.toFloat(), image.height.toFloat())
        val values = DoubleArray(size * size) { index ->
            areaSample(image, index % size, index / size, size, bounds)
        }
        val mean = values.average()
        for (i in values.indices) values[i] -= mean
        val norm = sqrt(values.sumOf { it * it })
        if (norm > 1e-6) for (i in values.indices) values[i] /= norm
        return values
    }

    private fun correlation(image: Gray, bounds: FloatArray, reference: DoubleArray, size: Int): Double {
        var sum = 0.0
        var square = 0.0
        var product = 0.0
        for (y in 0 until size) {
            for (x in 0 until size) {
                val value = areaSample(image, x, y, size, bounds)
                sum += value
                square += value * value
                product += value * reference[y * size + x]
            }
        }
        val variance = square - sum * sum / (size * size)
        return if (variance > 1e-6) product / sqrt(variance) else 0.0
    }

    private fun areaSample(image: Gray, x: Int, y: Int, size: Int, bounds: FloatArray): Double {
        val width = bounds[2] - bounds[0]
        val height = bounds[3] - bounds[1]
        var sum = 0.0
        for (dy in 0..1) {
            for (dx in 0..1) {
                sum += image.sample(
                    bounds[0] + (x + 0.25f + dx * 0.5f) * width / size - 0.5f,
                    bounds[1] + (y + 0.25f + dy * 0.5f) * height / size - 0.5f
                )
            }
        }
        return sum / 4
    }

    private class Gray(val width: Int, val height: Int, val values: FloatArray) {
        fun sample(x: Float, y: Float): Float {
            val sx = x.coerceIn(0f, (width - 1).toFloat())
            val sy = y.coerceIn(0f, (height - 1).toFloat())
            val left = floor(sx).toInt()
            val top = floor(sy).toInt()
            val right = min(left + 1, width - 1)
            val bottom = min(top + 1, height - 1)
            val dx = sx - left
            val dy = sy - top
            return (values[top * width + left] * (1 - dx) + values[top * width + right] * dx) * (1 - dy) +
                (values[bottom * width + left] * (1 - dx) + values[bottom * width + right] * dx) * dy
        }

        companion object {
            fun fromBitmap(bitmap: Bitmap): Gray {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                return Gray(bitmap.width, bitmap.height, FloatArray(pixels.size) {
                    val p = pixels[it]
                    ((p ushr 16) and 255) * 0.299f + ((p ushr 8) and 255) * 0.587f + (p and 255) * 0.114f
                })
            }
        }
    }
}
