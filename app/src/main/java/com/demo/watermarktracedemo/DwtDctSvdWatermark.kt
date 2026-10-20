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
 * Blind DWT-DCT-SVD carrier inspired by guofei9987/blind_watermark.
 * Each independent tile is normalized to a 512 square. A Haar low-frequency
 * band is split into 4x4 blocks; one keyed, quantized singular value carries
 * one repeated error-corrected bit per block. The framing and CRC remain local.
 */
object DwtDctSvdWatermark {
    class CarrierException(message: String) : IllegalArgumentException(message)
    enum class FailureReason { INVALID_GEOMETRY, INSUFFICIENT_BLOCKS, WEAK_SIGNAL, INVALID_PAYLOAD }
    const val MAX_PAYLOAD_BITS = FrequencyPayloadCodec.MAX_PAYLOAD_BITS
    private const val SIZE = 512
    private const val LOW = SIZE / 2
    private const val BLOCK = 4
    private const val GRID = LOW / BLOCK
    private const val BLOCKS = GRID * GRID
    private const val PILOTS = 512
    private const val PERIOD = 60.0
    private const val PASSWORD = 0x42574d5f32303236L
    private val gains = doubleArrayOf(0.65, 0.8, 0.9, 1.0, 1.1, 1.2, 1.35, 1.5, 1.7)
    private val dct = Array(4) { frequency ->
        DoubleArray(4) { position ->
            (if (frequency == 0) 0.5 else 1.0 / kotlin.math.sqrt(2.0)) *
                cos(PI * (2 * position + 1) * frequency / 8)
        }
    }
    private val order = IntArray(BLOCKS) { it }.apply {
        val random = Random(PASSWORD)
        for (i in lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val value = this[i]
            this[i] = this[j]
            this[j] = value
        }
    }
    private val shuffles = Array(BLOCKS) { index ->
        IntArray(16) { it }.apply {
            val random = Random(PASSWORD xor (index.toLong() * -7046029254386353131L))
            for (i in lastIndex downTo 1) {
                val j = random.nextInt(i + 1)
                val value = this[i]
                this[i] = this[j]
                this[j] = value
            }
        }
    }
    private val pilotBits = BooleanArray(PILOTS).also {
        val random = Random(PASSWORD xor 0x50494c4f54L)
        for (i in it.indices) it[i] = random.nextBoolean()
    }

    fun embed(source: Bitmap, bits: BooleanArray): Bitmap {
        require(bits.size <= MAX_PAYLOAD_BITS && WatermarkPayload.fromBitArray(bits) != null)
        if (min(source.width, source.height) < 320) {
            throw CarrierException("水印区域短边不足 320 像素")
        }
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val original = Plane.fromPixels(pixels, source.width, source.height)
        val normalized = normalize(original, fullCorners(source.width, source.height))
            ?: throw CarrierException("无法建立水印坐标")
        val low = haarLow(normalized)
        val code = FrequencyPayloadCodec.encode(bits)
        val changes = FloatArray(LOW * LOW)
        for (logical in 0 until BLOCKS) {
            val block = order[logical]
            val data = readBlock(low, block)
            val transformed = shuffle(forwardDct(data), shuffles[block])
            val singular = leadingSingular(transformed)
            val bit = if (logical < PILOTS) pilotBits[logical] else code[logical - PILOTS]
            val center = if (bit) PERIOD * 0.75 else PERIOD * 0.25
            val target = Math.rint((singular.value - center) / PERIOD) * PERIOD + center
            val delta = (target - singular.value).coerceIn(-PERIOD / 2, PERIOD / 2)
            val modified = DoubleArray(16) { index ->
                transformed[index] + delta * singular.left[index / 4] * singular.right[index % 4]
            }
            val lowDelta = inverseDct(unshuffle(modified, shuffles[block]))
            val baseX = block % GRID * BLOCK
            val baseY = block / GRID * BLOCK
            for (y in 0 until BLOCK) for (x in 0 until BLOCK) {
                changes[(baseY + y) * LOW + baseX + x] =
                    (lowDelta[y * BLOCK + x] - data[y * BLOCK + x]).toFloat()
            }
        }
        val normalizedDelta = FloatArray(SIZE * SIZE)
        for (y in 0 until LOW) for (x in 0 until LOW) {
            val value = changes[y * LOW + x] * 0.5f
            val top = y * 2 * SIZE + x * 2
            normalizedDelta[top] = value
            normalizedDelta[top + 1] = value
            normalizedDelta[top + SIZE] = value
            normalizedDelta[top + SIZE + 1] = value
        }
        val deltaPlane = Plane(SIZE, SIZE, normalizedDelta)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val index = y * source.width + x
            val pixel = pixels[index]
            val delta = (deltaPlane.sample(
                (x + 0.5f) * SIZE / source.width - 0.5f,
                (y + 0.5f) * SIZE / source.height - 0.5f
            ) * 1.45f).coerceIn(-9f, 9f).roundToInt()
            pixels[index] = Color.argb(
                Color.alpha(pixel),
                (Color.red(pixel) + delta).coerceIn(0, 255),
                (Color.green(pixel) + delta).coerceIn(0, 255),
                (Color.blue(pixel) + delta).coerceIn(0, 255)
            )
        }
        return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
    }

    fun extract(bitmap: Bitmap, corners: FloatArray? = null, refine: Boolean = true): WatermarkPayload? =
        Reader(bitmap).extract(corners, refine)

    class Reader(bitmap: Bitmap) {
        private val source = Plane.fromBitmap(bitmap)
        var bestFailure: FailureReason? = null
            private set

        fun extract(corners: FloatArray? = null, refine: Boolean = true): WatermarkPayload? {
            val initial = corners ?: fullCorners(source.width, source.height)
            decode(initial)?.let { return it }
            if (!refine) return null
            // Chroma locator centers can describe a rectangle slightly inside the
            // embedded tile. Try symmetric expansion before a general pilot search.
            val centerX = (initial[0] + initial[2] + initial[4] + initial[6]) / 4f
            val centerY = (initial[1] + initial[3] + initial[5] + initial[7]) / 4f
            for (padding in floatArrayOf(1.25f, 0.75f, 1.75f, 2.5f)) {
                val factor = 1f + 2f * padding / 256f
                val expanded = FloatArray(8) { index ->
                    val center = if (index % 2 == 0) centerX else centerY
                    center + (initial[index] - center) * factor
                }
                decode(expanded)?.let { return it }
            }
            // Locate the pilot maximum before attempting another full message decode.
            val scale = min(
                kotlin.math.hypot(initial[2] - initial[0], initial[3] - initial[1]),
                kotlin.math.hypot(initial[6] - initial[0], initial[7] - initial[1])
            ) / SIZE
            var best = initial.copyOf()
            var bestScore = pilotScore(best)
            for (dy in -4..4) for (dx in -4..4) {
                val candidate = initial.copyOf()
                for (point in 0 until 4) {
                    candidate[point * 2] += dx * 2f * scale
                    candidate[point * 2 + 1] += dy * 2f * scale
                }
                val score = pilotScore(candidate)
                if (score > bestScore) { best = candidate; bestScore = score }
            }
            if (bestScore > 0.08) decode(best)?.let { return it }
            for (step in floatArrayOf(4f, 2f, 1f, 0.5f)) {
                repeat(2) {
                    for (coordinate in best.indices) for (direction in intArrayOf(-1, 1)) {
                        val candidate = best.copyOf()
                        candidate[coordinate] += direction * step * scale
                        val score = pilotScore(candidate)
                        if (score > bestScore) { best = candidate; bestScore = score }
                    }
                }
                if (bestScore > 0.08) decode(best)?.let { return it }
            }
            return null
        }

        private fun pilotScore(corners: FloatArray): Double {
            val normalized = normalize(source, corners) ?: return -1.0
            val low = haarLow(normalized)
            val values = DoubleArray(128) { index ->
                val logical = index * 4
                val block = order[logical]
                leadingSingular(shuffle(forwardDct(readBlock(low, block)), shuffles[block])).value
            }
            return gains.maxOf { gain ->
                var sum = 0.0
                for (index in values.indices) {
                    val vote = sin(2 * PI * values[index] * gain / PERIOD)
                    sum += if (pilotBits[index * 4]) -vote else vote
                }
                sum / values.size
            }
        }

        private fun decode(corners: FloatArray): WatermarkPayload? {
            val normalized = normalize(source, corners) ?: run {
                record(FailureReason.INVALID_GEOMETRY)
                return null
            }
            val low = haarLow(normalized)
            val singular = DoubleArray(BLOCKS)
            for (logical in 0 until BLOCKS) {
                val block = order[logical]
                singular[logical] = leadingSingular(
                    shuffle(forwardDct(readBlock(low, block)), shuffles[block])
                ).value
            }
            val ranked = gains.map { gain ->
                var score = 0.0
                for (i in 0 until PILOTS) {
                    val vote = sin(2 * PI * singular[i] * gain / PERIOD)
                    score += if (pilotBits[i]) -vote else vote
                }
                gain to score / PILOTS
            }.sortedByDescending { it.second }
            if (ranked.first().second < 0.06) {
                record(FailureReason.WEAK_SIGNAL)
                return null
            }
            for ((gain, _) in ranked.take(4)) {
                val votes = DoubleArray(BLOCKS - PILOTS) { index ->
                    sin(2 * PI * singular[index + PILOTS] * gain / PERIOD)
                }
                FrequencyPayloadCodec.decode(votes)?.let { return it }
            }
            record(FailureReason.INVALID_PAYLOAD)
            return null
        }

        private fun record(reason: FailureReason) {
            if (bestFailure == null || reason.ordinal > bestFailure!!.ordinal) bestFailure = reason
        }
    }

    private data class Singular(val value: Double, val left: DoubleArray, val right: DoubleArray)

    private fun leadingSingular(matrix: DoubleArray): Singular {
        val gram = DoubleArray(16)
        for (i in 0 until 4) for (j in 0 until 4) for (row in 0 until 4) {
            gram[i * 4 + j] += matrix[row * 4 + i] * matrix[row * 4 + j]
        }
        val eigenvectors = DoubleArray(16) { if (it / 4 == it % 4) 1.0 else 0.0 }
        repeat(20) {
            var p = 0
            var q = 1
            var largest = 0.0
            for (i in 0 until 4) for (j in i + 1 until 4) {
                if (abs(gram[i * 4 + j]) > largest) {
                    largest = abs(gram[i * 4 + j]); p = i; q = j
                }
            }
            if (largest < 1e-8) return@repeat
            val angle = 0.5 * kotlin.math.atan2(
                2 * gram[p * 4 + q], gram[q * 4 + q] - gram[p * 4 + p]
            )
            val c = cos(angle)
            val s = sin(angle)
            for (k in 0 until 4) {
                val a = gram[k * 4 + p]; val b = gram[k * 4 + q]
                gram[k * 4 + p] = c * a - s * b
                gram[k * 4 + q] = s * a + c * b
                val v1 = eigenvectors[k * 4 + p]; val v2 = eigenvectors[k * 4 + q]
                eigenvectors[k * 4 + p] = c * v1 - s * v2
                eigenvectors[k * 4 + q] = s * v1 + c * v2
            }
            for (k in 0 until 4) {
                val a = gram[p * 4 + k]; val b = gram[q * 4 + k]
                gram[p * 4 + k] = c * a - s * b
                gram[q * 4 + k] = s * a + c * b
            }
        }
        val best = (0 until 4).maxByOrNull { gram[it * 4 + it] } ?: 0
        val value = kotlin.math.sqrt(gram[best * 4 + best].coerceAtLeast(0.0))
        val right = DoubleArray(4) { eigenvectors[it * 4 + best] }
        val left = DoubleArray(4)
        if (value > 1e-8) for (row in 0 until 4) for (column in 0 until 4) {
            left[row] += matrix[row * 4 + column] * right[column] / value
        }
        return Singular(value, left, right)
    }

    private fun forwardDct(data: DoubleArray): DoubleArray {
        val result = DoubleArray(16)
        for (u in 0 until 4) for (v in 0 until 4) for (y in 0 until 4) for (x in 0 until 4) {
            result[u * 4 + v] += dct[u][y] * data[y * 4 + x] * dct[v][x]
        }
        return result
    }

    private fun inverseDct(data: DoubleArray): DoubleArray {
        val result = DoubleArray(16)
        for (y in 0 until 4) for (x in 0 until 4) for (u in 0 until 4) for (v in 0 until 4) {
            result[y * 4 + x] += dct[u][y] * data[u * 4 + v] * dct[v][x]
        }
        return result
    }

    private fun shuffle(data: DoubleArray, permutation: IntArray) =
        DoubleArray(16) { data[permutation[it]] }

    private fun unshuffle(data: DoubleArray, permutation: IntArray) =
        DoubleArray(16).also { result -> for (i in 0 until 16) result[permutation[i]] = data[i] }

    private fun readBlock(low: FloatArray, block: Int): DoubleArray {
        val x0 = block % GRID * BLOCK
        val y0 = block / GRID * BLOCK
        return DoubleArray(16) { index ->
            low[(y0 + index / BLOCK) * LOW + x0 + index % BLOCK].toDouble()
        }
    }

    private fun haarLow(plane: FloatArray): FloatArray = FloatArray(LOW * LOW) { index ->
        val y = index / LOW * 2
        val x = index % LOW * 2
        (plane[y * SIZE + x] + plane[y * SIZE + x + 1] +
            plane[(y + 1) * SIZE + x] + plane[(y + 1) * SIZE + x + 1]) * 0.5f
    }

    private fun fullCorners(width: Int, height: Int) = floatArrayOf(
        0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat()
    )

    private fun normalize(source: Plane, corners: FloatArray): FloatArray? {
        if (corners.size != 8 || corners.any { !it.isFinite() }) return null
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(fullCorners(SIZE, SIZE), 0, corners, 0, 4)) return null
        val map = FloatArray(9).also { matrix.getValues(it) }
        return FloatArray(SIZE * SIZE) { index ->
            val x = index % SIZE + 0.5f
            val y = index / SIZE + 0.5f
            val divisor = map[6] * x + map[7] * y + map[8]
            if (abs(divisor) < 1e-6f) 0f else source.sample(
                (map[0] * x + map[1] * y + map[2]) / divisor - 0.5f,
                (map[3] * x + map[4] * y + map[5]) / divisor - 0.5f
            )
        }
    }

    private class Plane(val width: Int, val height: Int, val values: FloatArray) {
        fun sample(x: Float, y: Float): Float {
            val sx = x.coerceIn(0f, (width - 1).toFloat())
            val sy = y.coerceIn(0f, (height - 1).toFloat())
            val x0 = floor(sx).toInt(); val y0 = floor(sy).toInt()
            val x1 = min(x0 + 1, width - 1); val y1 = min(y0 + 1, height - 1)
            val dx = sx - x0; val dy = sy - y0
            val top = values[y0 * width + x0] * (1 - dx) + values[y0 * width + x1] * dx
            val bottom = values[y1 * width + x0] * (1 - dx) + values[y1 * width + x1] * dx
            return top * (1 - dy) + bottom * dy
        }

        companion object {
            fun fromBitmap(bitmap: Bitmap): Plane {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                return fromPixels(pixels, bitmap.width, bitmap.height)
            }

            fun fromPixels(pixels: IntArray, width: Int, height: Int): Plane = Plane(
                width, height, FloatArray(pixels.size) { index ->
                    val pixel = pixels[index]
                    Color.red(pixel) * 0.299f + Color.green(pixel) * 0.587f +
                        Color.blue(pixel) * 0.114f
                }
            )
        }
    }
}
