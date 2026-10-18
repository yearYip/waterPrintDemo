package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Four small, balanced chroma pilots identify the corners of each independent tile.
 * The pilots carry no user data. Only a payload decoded from the selected image is trusted.
 */
object CornerLocator {
    private const val CELLS = 8
    private const val MAX_SEARCH_EDGE = 800
    private const val MAX_PEAKS_PER_CORNER = 40
    private const val MIN_SCORE = 0.43
    private val patterns = Array(4) { kind ->
        IntArray(CELLS * CELLS) { if (it < CELLS * CELLS / 2) 1 else -1 }.also { signs ->
            val random = java.util.Random(0x434f524e45524cL + kind)
            for (index in signs.lastIndex downTo 1) {
                val other = random.nextInt(index + 1)
                val previous = signs[index]
                signs[index] = signs[other]
                signs[other] = previous
            }
        }
    }

    private data class Peak(val x: Float, val y: Float, val size: Int, val score: Double)
    private data class Candidate(val corners: FloatArray, val score: Double)

    fun embed(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        require(min(width, height) >= 64) { "图片短边不足 64 像素，无法写入角点信号" }
        val size = (min(width, height) / 8).coerceIn(8, 80)
        val inset = max(2, size / 4)
        val lefts = intArrayOf(inset, width - inset - size, width - inset - size, inset)
        val tops = intArrayOf(inset, inset, height - inset - size, height - inset - size)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        for (kind in 0..3) {
            for (y in 0 until size) for (x in 0 until size) {
                val cell = (y * CELLS / size) * CELLS + x * CELLS / size
                val sign = patterns[kind][cell]
                val index = (tops[kind] + y) * width + lefts[kind] + x
                val pixel = pixels[index]
                pixels[index] = Color.argb(
                    Color.alpha(pixel),
                    (Color.red(pixel) + sign * 6).coerceIn(0, 255),
                    (Color.green(pixel) - sign * 2).coerceIn(0, 255),
                    (Color.blue(pixel) - sign * 6).coerceIn(0, 255)
                )
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Searches a bounded set of pilot sizes and image positions, independent of saved thumbnails.
     * A callback may stop the search as soon as a real watermark passes CRC verification.
     */
    fun candidates(bitmap: Bitmap, onEarlyCandidate: (FloatArray) -> Boolean = { false }): List<FloatArray> {
        val scale = min(1f, MAX_SEARCH_EDGE.toFloat() / max(bitmap.width, bitmap.height))
        val reduced = if (scale < 1f) Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true
        ) else bitmap
        val width = reduced.width
        val height = reduced.height
        val pixels = IntArray(width * height)
        try {
            reduced.getPixels(pixels, 0, width, 0, 0, width, height)
        } finally {
            if (reduced !== bitmap) reduced.recycle()
        }
        val chroma = IntArray(pixels.size) { index ->
            Color.red(pixels[index]) - Color.blue(pixels[index])
        }
        val peaks = Array(4) { mutableListOf<Peak>() }
        val tried = mutableListOf<FloatArray>()
        var earlyAttempts = 0
        var size = 8
        val scores = DoubleArray(4)
        val largest = min(128, min(width, height) / 3)
        while (size <= largest) {
            val step = max(2, size / 10)
            var top = 0
            while (top + size <= height) {
                var left = 0
                while (left + size <= width) {
                    score(chroma, width, left, top, size, scores)
                    for (kind in 0..3) {
                        if (scores[kind] >= MIN_SCORE) {
                            addPeak(peaks[kind], Peak(
                                left + size / 2f, top + size / 2f, size, scores[kind]
                            ))
                        }
                    }
                    left += step
                }
                top += step
            }
            if (earlyAttempts < 3) {
                for (candidate in assemble(peaks, width, height).take(3 - earlyAttempts)) {
                    if (tried.any { sameCorners(it, candidate.corners) }) continue
                    tried.add(candidate.corners)
                    earlyAttempts++
                    if (onEarlyCandidate(rescale(candidate.corners, bitmap, width, height))) {
                        return emptyList()
                    }
                }
            }
            size = max(size + 1, (size * 1.25f).roundToInt())
        }
        return assemble(peaks, width, height).take(12).map {
            rescale(it.corners, bitmap, width, height)
        }
    }

    private fun score(
        chroma: IntArray, width: Int, left: Int, top: Int, size: Int, products: DoubleArray
    ) {
        products.fill(0.0)
        var sum = 0.0
        var square = 0.0
        for (cellY in 0 until CELLS) for (cellX in 0 until CELLS) {
            val x = left + ((cellX + 0.5f) * size / CELLS).toInt()
            val y = top + ((cellY + 0.5f) * size / CELLS).toInt()
            val value = chroma[y * width + x].toDouble()
            sum += value
            square += value * value
            val cell = cellY * CELLS + cellX
            for (kind in 0..3) products[kind] += value * patterns[kind][cell]
        }
        val variance = square - sum * sum / (CELLS * CELLS)
        if (variance < 1.0) {
            products.fill(0.0)
            return
        }
        val denominator = sqrt(variance * CELLS * CELLS)
        for (kind in 0..3) products[kind] /= denominator
    }

    private fun addPeak(peaks: MutableList<Peak>, peak: Peak) {
        val nearby = peaks.indexOfFirst {
            abs(it.x - peak.x) < max(it.size, peak.size) * 0.65f &&
                abs(it.y - peak.y) < max(it.size, peak.size) * 0.65f
        }
        if (nearby >= 0) {
            if (peak.score <= peaks[nearby].score) return
            peaks.removeAt(nearby)
        }
        peaks.add(peak)
        peaks.sortByDescending { it.score }
        if (peaks.size > MAX_PEAKS_PER_CORNER) peaks.removeAt(peaks.lastIndex)
    }

    private fun assemble(peaks: Array<MutableList<Peak>>, width: Int, height: Int): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        for (tl in peaks[0]) {
            val topRights = peaks[1].asSequence().filter { tr ->
                similarSize(tl, tr) && tr.x - tl.x in 4f * tl.size..20f * tl.size &&
                    abs(tr.y - tl.y) < tl.size
            }.take(4)
            val bottomLefts = peaks[3].asSequence().filter { bl ->
                similarSize(tl, bl) && bl.y - tl.y in 4f * tl.size..20f * tl.size &&
                    abs(bl.x - tl.x) < tl.size
            }.take(4).toList()
            for (tr in topRights) for (bl in bottomLefts) {
                val expectedX = tr.x + bl.x - tl.x
                val expectedY = tr.y + bl.y - tl.y
                val br = peaks[2].filter {
                    similarSize(tl, it) &&
                        abs(it.x - expectedX) < tl.size * 1.5f &&
                        abs(it.y - expectedY) < tl.size * 1.5f
                }.maxByOrNull { it.score } ?: continue
                val fx = 0.75f * tl.size / (tr.x - tl.x)
                val fy = 0.75f * tl.size / (bl.y - tl.y)
                fun point(u: Float, v: Float, x: Boolean): Float {
                    val a = if (x) tl.x else tl.y
                    val b = if (x) tr.x else tr.y
                    val c = if (x) br.x else br.y
                    val d = if (x) bl.x else bl.y
                    return a * (1 - u) * (1 - v) + b * u * (1 - v) +
                        c * u * v + d * (1 - u) * v
                }
                val positions = floatArrayOf(-fx, -fy, 1 + fx, -fy, 1 + fx, 1 + fy, -fx, 1 + fy)
                val corners = FloatArray(8) { index ->
                    point(positions[index / 2 * 2], positions[index / 2 * 2 + 1], index % 2 == 0)
                }
                if (corners[0] < -tl.size || corners[1] < -tl.size ||
                    corners[4] > width + tl.size || corners[5] > height + tl.size
                ) continue
                val geometry = abs(tr.y - tl.y) + abs(bl.x - tl.x) +
                    abs(br.x - tr.x) + abs(br.y - bl.y)
                val score = tl.score + tr.score + br.score + bl.score -
                    geometry / (tl.size * 10)
                candidates.add(Candidate(corners, score))
            }
        }
        val unique = mutableListOf<Candidate>()
        for (candidate in candidates.sortedByDescending { it.score }) {
            if (unique.none { sameCorners(it.corners, candidate.corners) }) unique.add(candidate)
            if (unique.size == 12) break
        }
        return unique
    }

    private fun similarSize(a: Peak, b: Peak): Boolean =
        b.size >= a.size * 0.75f && b.size <= a.size * 1.33f

    private fun sameCorners(a: FloatArray, b: FloatArray): Boolean =
        a.indices.all { abs(a[it] - b[it]) < 5f }

    private fun rescale(corners: FloatArray, bitmap: Bitmap, width: Int, height: Int): FloatArray =
        FloatArray(corners.size) { index ->
            corners[index] * (if (index % 2 == 0) bitmap.width.toFloat() / width
            else bitmap.height.toFloat() / height)
        }
}
