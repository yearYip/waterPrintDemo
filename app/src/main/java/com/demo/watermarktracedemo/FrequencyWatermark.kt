package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Zero-DC DCT/QIM carrier with compact WMK3 and legacy WMK2 frequency framing.
 *
 * Normalization is part of both embedding and extraction, rather than assuming
 * that a screenshot has the same pixel dimensions as the saved PNG.
 * No reference image or local payload lookup is used to decode the message.
 */
object FrequencyWatermark {
    class CarrierException(message: String) : IllegalArgumentException(message)
    enum class FailureReason {
        INVALID_GEOMETRY,
        INSUFFICIENT_BLOCKS,
        WEAK_SIGNAL,
        INVALID_PAYLOAD
    }
    const val MAX_PAYLOAD_BITS = FrequencyPayloadCodec.MAX_PAYLOAD_BITS
    private const val SIZE = 512
    private const val BLOCK = 8
    private const val GRID = SIZE / BLOCK
    private const val BLOCKS = GRID * GRID
    private const val PILOTS = 512
    private const val PERIOD = 20.0
    private val extractionGains = doubleArrayOf(0.85, 0.9, 0.95, 1.0, 1.05, 1.1, 1.15, 1.2, 1.25, 1.3, 1.4, 1.5)
    private val recoveryGains = doubleArrayOf(0.65, 0.75, 1.6, 1.7, 1.8, 2.0, 2.2)
    private val alignmentGains = extractionGains + recoveryGains
    private val basis = DoubleArray(BLOCK * BLOCK) { index ->
        val x = index % BLOCK
        val y = index / BLOCK
        0.25 * cos(PI * (2 * x + 1) / 16) * cos(PI * (2 * y + 1) * 2 / 16)
    }
    private val order = IntArray(BLOCKS) { it }.apply {
        val random = Random(0x574d4632L)
        for (i in lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val old = this[i]
            this[i] = this[j]
            this[j] = old
        }
    }
    private val pilotBits = BooleanArray(PILOTS).also { bits ->
        val random = Random(0x50494c4f54L)
        for (i in bits.indices) bits[i] = random.nextBoolean()
    }

    fun embed(source: Bitmap, bits: BooleanArray): Bitmap {
        require(bits.size <= MAX_PAYLOAD_BITS) { "用户 ID 过长，无法放入隐形水印" }
        if (min(source.width, source.height) < 320) throw CarrierException("图片短边不足 320 像素")
        val original = IntArray(source.width * source.height)
        source.getPixels(original, 0, source.width, 0, 0, source.width, source.height)
        val pixels = original.copyOf()
        val residual = FloatArray(original.size)
        val originalPlane = Plane.fromPixels(original, source.width, source.height)
        val normalized = normalize(originalPlane, fullCorners(source.width, source.height))
            ?: error("无法建立水印坐标")
        val code = FrequencyPayloadCodec.encode(bits)
        check(code.size == BLOCKS - PILOTS)
        val targets = DoubleArray(BLOCKS) { Double.NaN }
        val blockLimits = FloatArray(BLOCKS) { 2f }
        var available = 0
        for (logical in 0 until BLOCKS) {
            val block = order[logical]
            val statistics = stats(normalized, block)
            // Light smooth regions can carry a weaker signal; don't force dark flat regions.
            val smooth = statistics.textureVariance < 1
            if (statistics.mean < 12 || statistics.mean > 254 ||
                (smooth && statistics.mean < 96)
            ) continue
            blockLimits[block] = when {
                smooth -> 1f
                logical < PILOTS -> 3f
                else -> 2f
            }
            val bit = if (logical < PILOTS) pilotBits[logical] else code[logical - PILOTS]
            val coefficient = coefficient(normalized, block)
            val center = if (bit) PERIOD * 0.75 else PERIOD * 0.25
            targets[block] = Math.rint((coefficient - center) / PERIOD) * PERIOD + center
            available++
        }
        // Let actual decoding decide quality instead of requiring 50% textured blocks.
        if (available < BLOCKS / 8) throw CarrierException("可用于截图水印的区域不足")

        repeat(4) {
            val plane = normalize(
                Plane.fromPixels(pixels, source.width, source.height),
                fullCorners(source.width, source.height)
            ) ?: error("无法建立水印坐标")
            val correction = FloatArray(SIZE * SIZE)
            for (block in targets.indices) {
                val target = targets[block]
                if (target.isNaN()) continue
                val delta = target - coefficient(plane, block)
                val bx = block % GRID * BLOCK
                val by = block / GRID * BLOCK
                for (y in 0 until BLOCK) {
                    for (x in 0 until BLOCK) {
                        correction[(by + y) * SIZE + bx + x] =
                            (delta * basis[y * BLOCK + x]).toFloat()
                    }
                }
            }
            val correctionPlane = Plane(SIZE, SIZE, correction)
            for (y in 0 until source.height) {
                for (x in 0 until source.width) {
                    val index = y * source.width + x
                    val originalPixel = original[index]
                    val luma = originalPlane.values[index]
                    val blockX = ((x + 0.5f) * GRID / source.width).toInt().coerceIn(0, GRID - 1)
                    val blockY = ((y + 0.5f) * GRID / source.height).toInt().coerceIn(0, GRID - 1)
                    val block = blockY * GRID + blockX
                    if (targets[block].isNaN()) continue
                    val limit = if (luma < 48f) 1f else blockLimits[block]
                    val delta = correctionPlane.sample(
                        (x + 0.5f) * SIZE / source.width - 0.5f,
                        (y + 0.5f) * SIZE / source.height - 0.5f
                    )
                    residual[index] = (residual[index] + delta).coerceIn(-limit, limit)
                    val offset = residual[index].roundToInt()
                    pixels[index] = Color.argb(
                        Color.alpha(originalPixel),
                        (Color.red(originalPixel) + offset).coerceIn(0, 255),
                        (Color.green(originalPixel) + offset).coerceIn(0, 255),
                        (Color.blue(originalPixel) + offset).coerceIn(0, 255)
                    )
                }
            }
        }

        val result = Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
        try {
            val expected = WatermarkPayload.fromBitArray(bits) ?: error("水印数据无效")
            if (extract(result, refine = false) != expected)
                throw CarrierException("当前图片未通过频域水印回读校验")
            // Validate a resampled image, not just the untouched PNG.
            val scale = min(1f, 384f / min(result.width, result.height))
            val smaller = Bitmap.createScaledBitmap(
                result, (result.width * scale).roundToInt(), (result.height * scale).roundToInt(), true
            )
            try {
                if (extract(smaller, refine = false) != expected)
                    throw CarrierException("当前图片未通过截图缩放校验")
            } finally {
                if (smaller !== result) smaller.recycle()
            }
            return result
        } catch (e: Exception) {
            result.recycle()
            throw e
        }
    }

    fun extract(bitmap: Bitmap, corners: FloatArray? = null, refine: Boolean = true): WatermarkPayload? =
        Reader(bitmap).extract(corners, refine)

    data class PilotDiagnostics(val score: Double, val readableBlocks: Int)

    class Reader(bitmap: Bitmap) {
        private val source: Plane
        var bestFailure: FailureReason? = null
            private set
        init {
            val values = FloatArray(bitmap.width * bitmap.height)
            val row = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (x in row.indices) {
                    val pixel = row[x]
                    values[y * bitmap.width + x] =
                        Color.red(pixel) * 0.299f + Color.green(pixel) * 0.587f +
                            Color.blue(pixel) * 0.114f
                }
            }
            source = Plane(bitmap.width, bitmap.height, values)
        }

        fun extract(corners: FloatArray? = null, refine: Boolean = true): WatermarkPayload? =
            extractPlane(source, corners ?: fullCorners(source.width, source.height), refine) { reason ->
                if (bestFailure == null || reason.ordinal > bestFailure!!.ordinal) {
                    bestFailure = reason
                }
            }

        fun alignmentScore(corners: FloatArray): Double = pilotScore(source, corners)

        /** All 512 pilots, using the same eligibility and gain search as the decoder. */
        fun pilotDiagnostics(corners: FloatArray): PilotDiagnostics {
            val plane = normalize(source, corners) ?: return PilotDiagnostics(0.0, 0)
            val coefficients = DoubleArray(PILOTS)
            val eligible = BooleanArray(PILOTS)
            for (logical in 0 until PILOTS) {
                val block = order[logical]
                val statistics = stats(plane, block)
                if (!readable(statistics.mean, statistics.textureVariance)) continue
                coefficients[logical] = coefficient(plane, block)
                eligible[logical] = true
            }
            return PilotDiagnostics(
                rankedGains(coefficients, eligible, alignmentGains).first().second,
                eligible.count { it }
            )
        }

    }

    private fun extractPlane(
        source: Plane,
        corners: FloatArray,
        refine: Boolean,
        onFailure: (FailureReason) -> Unit
    ): WatermarkPayload? {
        if (mapping(corners) == null) {
            onFailure(FailureReason.INVALID_GEOMETRY)
            return null
        }
        var best = corners.copyOf()
        decode(source, best, onFailure)?.let { return it }
        if (!refine) return null

        var bestScore = pilotScore(source, best)
        val scale = min(edgeLength(best, 0, 1), edgeLength(best, 0, 3)) / SIZE
        // A small global shift helps when both sides were selected a few pixels off.
        val initial = best.copyOf()
        for (dy in -2..2) {
            for (dx in -2..2) {
                val candidate = initial.copyOf()
                for (i in 0 until 4) {
                    candidate[i * 2] += dx * 2f * scale
                    candidate[i * 2 + 1] += dy * 2f * scale
                }
                val score = pilotScore(source, candidate)
                if (score > bestScore) { bestScore = score; best = candidate }
            }
        }
        for (step in floatArrayOf(3f, 1.5f, 0.75f, 0.35f)) {
            repeat(2) {
                for (coordinate in best.indices) {
                    for (direction in intArrayOf(-1, 1)) {
                        val candidate = best.copyOf()
                        candidate[coordinate] += direction * step * scale
                        val score = pilotScore(source, candidate)
                        if (score > bestScore) { bestScore = score; best = candidate }
                    }
                }
            }
            if (bestScore > 0.25) decode(source, best, onFailure)?.let { return it }
        }
        // Weak pilots may still carry a CRC-valid payload after corner correction.
        if (bestScore > 0.08) decode(source, best, onFailure)?.let { return it }
        return null
    }

    private fun decode(
        source: Plane,
        corners: FloatArray,
        onFailure: (FailureReason) -> Unit
    ): WatermarkPayload? {
        val plane = normalize(source, corners) ?: run {
            onFailure(FailureReason.INVALID_GEOMETRY)
            return null
        }
        val coefficients = DoubleArray(BLOCKS)
        val eligible = BooleanArray(BLOCKS)
        for (logical in 0 until BLOCKS) {
            val block = order[logical]
            val statistics = stats(plane, block)
            if (!readable(statistics.mean, statistics.textureVariance)) continue
            coefficients[logical] = coefficient(plane, block)
            eligible[logical] = true
        }
        val pilotCount = (0 until PILOTS).count { eligible[it] }
        if (pilotCount < 64) {
            onFailure(FailureReason.INSUFFICIENT_BLOCKS)
            return null
        }
        // Keep the usual decode path first. Compression can attenuate coefficients beyond
        // its gain range, so retry only after it fails; CRC rejects spurious decodes.
        fun decodeAt(gain: Double): WatermarkPayload? {
            val votes = DoubleArray(BLOCKS - PILOTS)
            for (i in PILOTS until BLOCKS) {
                if (eligible[i]) votes[i - PILOTS] =
                    sin(2 * PI * coefficients[i] * gain / PERIOD)
            }
            return FrequencyPayloadCodec.decode(votes)
        }
        var reliableSignal = false
        for ((gain, score) in rankedGains(coefficients, eligible, extractionGains).take(3)) {
            if (score < 0.22) continue
            reliableSignal = true
            decodeAt(gain)?.let { return it }
        }
        for ((gain, score) in rankedGains(coefficients, eligible, recoveryGains).take(3)) {
            if (score < 0.08) continue
            decodeAt(gain)?.let { return it }
        }
        onFailure(if (reliableSignal) FailureReason.INVALID_PAYLOAD else FailureReason.WEAK_SIGNAL)
        return null
    }

    private fun rankedGains(
        coefficients: DoubleArray, eligible: BooleanArray, gains: DoubleArray
    ): List<Pair<Double, Double>> {
        val count = (0 until PILOTS).count { eligible[it] }
        return gains.map { gain ->
            var sum = 0.0
            for (i in 0 until PILOTS) {
                if (!eligible[i]) continue
                val soft = sin(2 * PI * coefficients[i] * gain / PERIOD)
                sum += if (pilotBits[i]) -soft else soft
            }
            gain to if (count == 0) 0.0 else sum / count
        }.sortedByDescending { it.second }
    }

    private fun pilotScore(source: Plane, corners: FloatArray): Double {
        val map = mapping(corners) ?: return -1.0
        val scores = DoubleArray(alignmentGains.size)
        var count = 0
        for (logical in 0 until 128) {
            val block = order[logical * 4]
            val bx = block % GRID * BLOCK
            val by = block / GRID * BLOCK
            var mean = 0.0
            var square = 0.0
            var coefficient = 0.0
            var slopeX = 0.0
            var slopeY = 0.0
            for (y in 0 until BLOCK) {
                for (x in 0 until BLOCK) {
                    val value = mappedSample(source, map, bx + x + 0.5f, by + y + 0.5f)
                    mean += value
                    square += value * value
                    coefficient += value * basis[y * BLOCK + x]
                    slopeX += (x - 3.5) * value
                    slopeY += (y - 3.5) * value
                }
            }
            mean /= BLOCK * BLOCK
            val textureVariance = square / 64 - mean * mean -
                (slopeX * slopeX + slopeY * slopeY) / (336 * 64)
            if (!readable(mean, textureVariance)) continue
            for (i in alignmentGains.indices) {
                val soft = sin(2 * PI * coefficient * alignmentGains[i] / PERIOD)
                scores[i] += if (pilotBits[logical * 4]) -soft else soft
            }
            count++
        }
        return if (count < 24) -1.0 else (scores.maxOrNull() ?: -1.0) / count
    }

    private fun coefficient(plane: FloatArray, block: Int): Double {
        val bx = block % GRID * BLOCK
        val by = block / GRID * BLOCK
        var result = 0.0
        for (y in 0 until BLOCK) {
            for (x in 0 until BLOCK) result += plane[(by + y) * SIZE + bx + x] * basis[y * BLOCK + x]
        }
        return result
    }

    private data class Statistics(val mean: Double, val textureVariance: Double)

    private fun readable(mean: Double, textureVariance: Double): Boolean =
        mean >= 10 && mean <= 254.8 && (textureVariance >= 0.5 || mean >= 94)

    private fun stats(plane: FloatArray, block: Int): Statistics {
        val bx = block % GRID * BLOCK
        val by = block / GRID * BLOCK
        var sum = 0.0
        var square = 0.0
        var slopeX = 0.0
        var slopeY = 0.0
        for (y in 0 until BLOCK) {
            for (x in 0 until BLOCK) {
                val value = plane[(by + y) * SIZE + bx + x].toDouble()
                sum += value
                square += value * value
                slopeX += (x - 3.5) * value
                slopeY += (y - 3.5) * value
            }
        }
        val mean = sum / 64
        // Remove a fitted brightness plane: a smooth dark gradient is not texture.
        val textureVariance = square / 64 - mean * mean -
            (slopeX * slopeX + slopeY * slopeY) / (336 * 64)
        return Statistics(mean, textureVariance.coerceAtLeast(0.0))
    }

    private fun fullCorners(width: Int, height: Int) = floatArrayOf(
        0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat()
    )

    private fun edgeLength(corners: FloatArray, a: Int, b: Int): Float =
        kotlin.math.hypot(corners[a * 2] - corners[b * 2], corners[a * 2 + 1] - corners[b * 2 + 1])

    private fun mapping(corners: FloatArray): FloatArray? {
        if (corners.size != 8 || corners.any { !it.isFinite() }) return null
        if (edgeLength(corners, 0, 1) < 96 || edgeLength(corners, 0, 3) < 96) return null
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(fullCorners(SIZE, SIZE), 0, corners, 0, 4)) return null
        return FloatArray(9).also { matrix.getValues(it) }
    }

    private fun normalize(source: Plane, corners: FloatArray): FloatArray? {
        val map = mapping(corners) ?: return null
        return FloatArray(SIZE * SIZE) { index ->
            mappedSample(source, map, index % SIZE + 0.5f, index / SIZE + 0.5f)
        }
    }

    private fun mappedSample(source: Plane, map: FloatArray, x: Float, y: Float): Float {
        val divisor = map[6] * x + map[7] * y + map[8]
        if (abs(divisor) < 1e-6f) return 0f
        return source.sample(
            (map[0] * x + map[1] * y + map[2]) / divisor - 0.5f,
            (map[3] * x + map[4] * y + map[5]) / divisor - 0.5f
        )
    }

    private class Plane(val width: Int, val height: Int, val values: FloatArray) {
        fun sample(x: Float, y: Float): Float {
            val sx = x.coerceIn(0f, (width - 1).toFloat())
            val sy = y.coerceIn(0f, (height - 1).toFloat())
            val x0 = floor(sx).toInt()
            val y0 = floor(sy).toInt()
            val x1 = min(x0 + 1, width - 1)
            val y1 = min(y0 + 1, height - 1)
            val dx = sx - x0
            val dy = sy - y0
            val top = values[y0 * width + x0] * (1 - dx) + values[y0 * width + x1] * dx
            val bottom = values[y1 * width + x0] * (1 - dx) + values[y1 * width + x1] * dx
            return top * (1 - dy) + bottom * dy
        }

        companion object {
            fun fromPixels(pixels: IntArray, width: Int, height: Int): Plane =
                Plane(width, height, FloatArray(pixels.size) { index ->
                    val pixel = pixels[index]
                    (Color.red(pixel) * 0.299f + Color.green(pixel) * 0.587f + Color.blue(pixel) * 0.114f)
                })
        }
    }
}
