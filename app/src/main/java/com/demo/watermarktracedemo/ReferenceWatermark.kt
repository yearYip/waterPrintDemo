package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** DWT-DCT-SVD carrier with a spatially periodic message for crop recovery. */
object ReferenceWatermark {
    class CarrierException(message: String) : IllegalArgumentException(message)
    enum class FailureReason { INVALID_GEOMETRY, INSUFFICIENT_BLOCKS, INVALID_PAYLOAD }
    const val MAX_PAYLOAD_BITS = 512
    private const val SIZE = 512
    private const val D1 = 36.0
    private const val D2 = 20.0
    private const val PERIOD_ROWS = 16
    private const val PERIOD_COLUMNS = 32
    private val dct = Array(4) { u -> DoubleArray(4) { x ->
        (if (u == 0) 0.5 else 1.0 / sqrt(2.0)) * cos(PI * (2 * x + 1) * u / 8)
    } }

    fun embed(source: Bitmap, bits: BooleanArray): Bitmap {
        require(bits.size <= SIZE && WatermarkPayload.fromBitArray(bits) != null)
        val g = Geometry(source.width, source.height)
        if (g.blocks <= SIZE) throw CarrierException("图片太小，容不下固定的 512 位水印")
        val padded = BooleanArray(SIZE) { it < bits.size && bits[it] }
        val permutation = watermarkPermutation()
        val shuffled = BooleanArray(SIZE) { padded[permutation[it]] }
        val fixedOrder = NumpyRandom(1).blockOrder()
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, g.width, 0, 0, g.width, g.height)
        val changes = Array(3) { FloatArray(g.lowWidth * g.lowHeight) }
        for (channel in 0..2) {
            val low = haarLow(pixels, g, channel)
            for (i in 0 until g.blocks) {
                val before = block(low, g, i)
                val order = fixedOrder
                val transformed = permute(dct(before, false), order)
                val svd = svd(transformed)
                val bitIndex = ((i / g.columns) % PERIOD_ROWS) * PERIOD_COLUMNS +
                    (i % g.columns) % PERIOD_COLUMNS
                val bit = if (shuffled[bitIndex]) 1.0 else 0.0
                for (rank in 0..1) {
                    val step = if (rank == 0) D1 else D2
                    val singular = svd.values[rank]
                    val target = (floor(singular / step) + 0.25 + bit * 0.5) * step
                    for (row in 0..3) for (col in 0..3) {
                        transformed[row * 4 + col] +=
                            (target - singular) * svd.left[rank][row] * svd.right[rank][col]
                    }
                }
                val after = dct(unpermute(transformed, order), true)
                val x = i % g.columns * 4
                val y = i / g.columns * 4
                for (n in 0 until 16) changes[channel][(y + n / 4) * g.lowWidth + x + n % 4] =
                    (after[n] - before[n]).toFloat()
            }
        }
        for (y in 0 until g.height) for (x in 0 until g.width) {
            val i = y * g.width + x
            val p = pixels[i]
            val r = Color.red(p).toDouble(); val green = Color.green(p).toDouble()
            val b = Color.blue(p).toDouble()
            val luma = 0.299 * r + 0.587 * green + 0.114 * b
            val lowIndex = y / 2 * g.lowWidth + x / 2
            val yy = luma + changes[0][lowIndex] / 2
            val uu = 0.492 * (b - luma) + 0.5 + changes[1][lowIndex] / 2
            val vv = 0.877 * (r - luma) + 0.5 + changes[2][lowIndex] / 2
            pixels[i] = Color.argb(Color.alpha(p),
                (yy + 1.14 * (vv - 0.5)).roundToInt().coerceIn(0, 255),
                (yy - 0.395 * (uu - 0.5) - 0.581 * (vv - 0.5)).roundToInt().coerceIn(0, 255),
                (yy + 2.032 * (uu - 0.5)).roundToInt().coerceIn(0, 255))
        }
        return Bitmap.createBitmap(pixels, g.width, g.height, Bitmap.Config.ARGB_8888)
    }

    fun extract(bitmap: Bitmap, corners: FloatArray? = null): WatermarkPayload? = Reader(bitmap).extract(corners)

    class Reader(private val source: Bitmap) {
        var bestFailure: FailureReason? = null
            private set

        fun extract(corners: FloatArray? = null): WatermarkPayload? {
            val image = if (corners == null) source else rectify(source, corners) ?: run {
                bestFailure = FailureReason.INVALID_GEOMETRY; return null
            }
            try {
                decode(image, periodic = true)?.let { return it }
                decode(image, periodic = false)?.let { return it }
                // The block grid is defined in source pixels. A screenshot that
                // reduced the whole image needs its former grid size restored.
                for (scale in doubleArrayOf(1.25, 4.0 / 3.0, 1.5, 1.6, 5.0 / 3.0,
                    1.75, 2.0, 2.5, 3.0)) {
                    val width = (image.width * scale).roundToInt()
                    val height = (image.height * scale).roundToInt()
                    if (max(width, height) > 3072 || width.toLong() * height > 7_000_000) continue
                    val enlarged = Bitmap.createScaledBitmap(image, width, height, true)
                    try {
                        decode(enlarged, periodic = true)?.let { return it }
                        decode(enlarged, periodic = false)?.let { return it }
                    }
                    finally { enlarged.recycle() }
                }
                recoverCrop(image)?.let { return it }
                if (image.width < 256 || image.height < 128) {
                    for (scale in doubleArrayOf(4.0 / 3.0, 1.5, 2.0)) {
                        val enlarged = Bitmap.createScaledBitmap(image,
                            (image.width * scale).roundToInt(),
                            (image.height * scale).roundToInt(), true)
                        try { recoverCrop(enlarged)?.let { return it } }
                        finally { if (enlarged !== image) enlarged.recycle() }
                    }
                }
                return null
            } finally { if (image !== source) image.recycle() }
        }

        private fun decode(image: Bitmap, periodic: Boolean): WatermarkPayload? {
                val g = Geometry(image.width, image.height)
                if (g.blocks <= SIZE) {
                    bestFailure = FailureReason.INSUFFICIENT_BLOCKS; return null
                }
                val pixels = IntArray(g.width * g.height)
                image.getPixels(pixels, 0, g.width, 0, 0, g.width, g.height)
                val sum = DoubleArray(SIZE)
                val count = IntArray(SIZE)
                val fixedOrder = NumpyRandom(1).blockOrder()
                for (channel in 0..2) {
                    val low = haarLow(pixels, g, channel)
                    val rng = NumpyRandom(1)
                    for (i in 0 until g.blocks) {
                        val order = if (periodic) fixedOrder else rng.blockOrder()
                        val singular = svd(permute(dct(block(low, g, i), false), order)).values
                        val first = if (singular[0] % D1 > D1 / 2) 1.0 else 0.0
                        val second = if (singular[1] % D2 > D2 / 2) 1.0 else 0.0
                        val bitIndex = if (periodic)
                            ((i / g.columns) % PERIOD_ROWS) * PERIOD_COLUMNS +
                                (i % g.columns) % PERIOD_COLUMNS else i % SIZE
                        sum[bitIndex] += (first * 3 + second) / 4
                        count[bitIndex]++
                    }
                }
                if (count.any { it == 0 }) {
                    bestFailure = FailureReason.INSUFFICIENT_BLOCKS; return null
                }
                val average = DoubleArray(SIZE) { sum[it] / count[it] }
                val scrambled = oneDimKMeans(average)
                val permutation = watermarkPermutation()
                val bits = BooleanArray(SIZE)
                for (i in 0 until SIZE) bits[permutation[i]] = scrambled[i]
                return WatermarkPayload.fromBitArray(bits).also {
                    bestFailure = if (it == null) FailureReason.INVALID_PAYLOAD else null
                }
        }

        private fun recoverCrop(image: Bitmap): WatermarkPayload? {
            if (image.width < 256 || image.height < 128) return null
            // Search a bounded central area; the local pattern repeats across
            // the entire generated image, so an original-size canvas is unnecessary.
            val regionWidth = minOf(image.width, 512)
            val regionHeight = minOf(image.height, 512)
            val region = Bitmap.createBitmap(image,
                (image.width - regionWidth) / 2, (image.height - regionHeight) / 2,
                regionWidth, regionHeight)
            val permutation = watermarkPermutation()
            val plainToEncrypted = IntArray(SIZE)
            for (i in 0 until SIZE) plainToEncrypted[permutation[i]] = i
            val magic = byteArrayOf(0x57, 0x4d, 0x4b, 0x33)
            val fixedOrder = NumpyRandom(1).blockOrder()
            try {
                for (phaseY in 0..7) for (phaseX in 0..7) {
                    val aligned = Bitmap.createBitmap(region, phaseX, phaseY,
                        region.width - phaseX, region.height - phaseY)
                    try {
                        val g = Geometry(aligned.width, aligned.height)
                        if (g.columns < PERIOD_COLUMNS || g.blocks / g.columns < PERIOD_ROWS) continue
                        val pixels = IntArray(g.width * g.height)
                        aligned.getPixels(pixels, 0, g.width, 0, 0, g.width, g.height)
                        val votes = DoubleArray(g.blocks)
                        for (channel in 0..2) {
                            val low = haarLow(pixels, g, channel)
                            for (i in 0 until g.blocks) {
                                val s = svd(permute(dct(block(low, g, i), false), fixedOrder)).values
                                val first = if (s[0] % D1 > D1 / 2) 1.0 else 0.0
                                val second = if (s[1] % D2 > D2 / 2) 1.0 else 0.0
                                votes[i] += (first * 3 + second) / 12
                            }
                        }
                        for (rowOffset in 0 until PERIOD_ROWS) for (colOffset in 0 until PERIOD_COLUMNS) {
                            val sums = DoubleArray(SIZE)
                            val counts = IntArray(SIZE)
                            for (i in 0 until g.blocks) {
                                val index = ((i / g.columns + rowOffset) % PERIOD_ROWS) *
                                    PERIOD_COLUMNS + (i % g.columns + colOffset) % PERIOD_COLUMNS
                                sums[index] += votes[i]
                                counts[index]++
                            }
                            var headerMatches = 0
                            for (bit in 0 until 32) {
                                val encrypted = plainToEncrypted[bit]
                                if (counts[encrypted] == 0) continue
                                val expected = (magic[bit / 8].toInt() and (1 shl (7 - bit % 8))) != 0
                                if ((sums[encrypted] / counts[encrypted] > 0.5) == expected)
                                    headerMatches++
                            }
                            if (headerMatches < 24 || counts.any { it == 0 }) continue
                            val average = DoubleArray(SIZE) { sums[it] / counts[it] }
                            val scrambled = oneDimKMeans(average)
                            val bits = BooleanArray(SIZE)
                            for (i in 0 until SIZE) bits[permutation[i]] = scrambled[i]
                            WatermarkPayload.fromBitArray(bits)?.let {
                                bestFailure = null
                                return it
                            }
                        }
                    } finally { if (aligned !== region) aligned.recycle() }
                }
                return null
            } finally { if (region !== image) region.recycle() }
        }
    }

    private data class Geometry(val width: Int, val height: Int) {
        val lowWidth = (width + 1) / 2
        val lowHeight = (height + 1) / 2
        val columns = lowWidth / 4
        val blocks = columns * (lowHeight / 4)
    }

    private fun haarLow(pixels: IntArray, g: Geometry, channel: Int): FloatArray {
        fun sample(x: Int, y: Int): Double {
            if (x >= g.width || y >= g.height) return 0.0
            val p = pixels[y * g.width + x]
            val r = Color.red(p).toDouble(); val green = Color.green(p).toDouble()
            val b = Color.blue(p).toDouble()
            val yy = 0.299 * r + 0.587 * green + 0.114 * b
            return when (channel) { 0 -> yy; 1 -> 0.492 * (b - yy) + 0.5
                else -> 0.877 * (r - yy) + 0.5 }
        }
        return FloatArray(g.lowWidth * g.lowHeight) { i ->
            val x = i % g.lowWidth * 2; val y = i / g.lowWidth * 2
            ((sample(x, y) + sample(x + 1, y) + sample(x, y + 1) + sample(x + 1, y + 1)) / 2).toFloat()
        }
    }

    private fun block(low: FloatArray, g: Geometry, index: Int): DoubleArray {
        val x = index % g.columns * 4; val y = index / g.columns * 4
        return DoubleArray(16) { low[(y + it / 4) * g.lowWidth + x + it % 4].toDouble() }
    }

    private fun dct(input: DoubleArray, inverse: Boolean): DoubleArray = DoubleArray(16) { i ->
        var sum = 0.0
        for (row in 0..3) for (col in 0..3) {
            sum += if (inverse) dct[row][i / 4] * input[row * 4 + col] * dct[col][i % 4]
                else dct[i / 4][row] * input[row * 4 + col] * dct[i % 4][col]
        }
        sum
    }

    private fun permute(data: DoubleArray, order: IntArray) = DoubleArray(16) { data[order[it]] }
    private fun unpermute(data: DoubleArray, order: IntArray) = DoubleArray(16).also { result ->
        for (i in 0 until 16) result[order[i]] = data[i]
    }

    private data class Svd(val values: DoubleArray, val left: Array<DoubleArray>, val right: Array<DoubleArray>)
    private fun svd(matrix: DoubleArray): Svd {
        val gram = DoubleArray(16)
        for (i in 0..3) for (j in 0..3) for (row in 0..3)
            gram[i * 4 + j] += matrix[row * 4 + i] * matrix[row * 4 + j]
        val vectors = DoubleArray(16) { if (it / 4 == it % 4) 1.0 else 0.0 }
        for (iteration in 0 until 32) {
            var p = 0; var q = 1; var largest = 0.0
            for (i in 0..3) for (j in i + 1..3) if (abs(gram[i * 4 + j]) > largest) {
                largest = abs(gram[i * 4 + j]); p = i; q = j
            }
            if (largest < 1e-9) break
            val angle = 0.5 * atan2(2 * gram[p * 4 + q], gram[q * 4 + q] - gram[p * 4 + p])
            val c = cos(angle); val s = sin(angle)
            for (k in 0..3) {
                val a = gram[k * 4 + p]; val b = gram[k * 4 + q]
                gram[k * 4 + p] = c * a - s * b; gram[k * 4 + q] = s * a + c * b
                val va = vectors[k * 4 + p]; val vb = vectors[k * 4 + q]
                vectors[k * 4 + p] = c * va - s * vb; vectors[k * 4 + q] = s * va + c * vb
            }
            for (k in 0..3) {
                val a = gram[p * 4 + k]; val b = gram[q * 4 + k]
                gram[p * 4 + k] = c * a - s * b; gram[q * 4 + k] = s * a + c * b
            }
        }
        val sorted = (0..3).sortedByDescending { max(0.0, gram[it * 4 + it]) }
        val values = DoubleArray(4) { sqrt(max(0.0, gram[sorted[it] * 4 + sorted[it]])) }
        val right = Array(4) { rank -> DoubleArray(4) { vectors[it * 4 + sorted[rank]] } }
        val left = Array(4) { DoubleArray(4) }
        for (rank in 0..3) {
            if (values[rank] > 1e-8) {
                for (row in 0..3) for (col in 0..3)
                    left[rank][row] += matrix[row * 4 + col] * right[rank][col] / values[rank]
            } else {
                // A constant block has zero singular values. Complete U with an
                // orthogonal vector so that the second quantizer can still write.
                var best = DoubleArray(4)
                var bestNorm = -1.0
                for (axis in 0..3) {
                    val candidate = DoubleArray(4) { if (it == axis) 1.0 else 0.0 }
                    for (previous in 0 until rank) {
                        var dot = 0.0
                        for (row in 0..3) dot += candidate[row] * left[previous][row]
                        for (row in 0..3) candidate[row] -= dot * left[previous][row]
                    }
                    val norm = candidate.sumOf { it * it }
                    if (norm > bestNorm) { best = candidate; bestNorm = norm }
                }
                if (bestNorm > 1e-12) for (row in 0..3)
                    left[rank][row] = best[row] / sqrt(bestNorm)
            }
        }
        return Svd(values, left, right)
    }

    private fun oneDimKMeans(values: DoubleArray): BooleanArray {
        var lower = values.minOrNull() ?: 0.0; var upper = values.maxOrNull() ?: 1.0
        repeat(300) {
            val threshold = (lower + upper) / 2
            var loSum = 0.0; var hiSum = 0.0; var loCount = 0; var hiCount = 0
            for (value in values) if (value > threshold) { hiSum += value; hiCount++ }
                else { loSum += value; loCount++ }
            if (loCount == 0 || hiCount == 0) return@repeat
            lower = loSum / loCount; upper = hiSum / hiCount
            if (abs((lower + upper) / 2 - threshold) < 1e-6) return BooleanArray(values.size) {
                values[it] > (lower + upper) / 2
            }
        }
        return BooleanArray(values.size) { values[it] > (lower + upper) / 2 }
    }

    private fun watermarkPermutation(): IntArray = IntArray(SIZE) { it }.also { order ->
        val random = NumpyRandom(1)
        for (i in order.lastIndex downTo 1) {
            val j = random.interval(i)
            val tmp = order[i]; order[i] = order[j]; order[j] = tmp
        }
    }

    /** NumPy RandomState's MT19937 random_sample and legacy shuffle sequences. */
    private class NumpyRandom(seed: Int) {
        private val state = IntArray(624)
        private var position = 624
        init {
            state[0] = seed
            for (i in 1 until 624) state[i] =
                1812433253 * (state[i - 1] xor (state[i - 1] ushr 30)) + i
        }
        private fun nextInt(): Int {
            if (position >= 624) {
                for (i in 0 until 624) {
                    val y = (state[i] and Int.MIN_VALUE) or (state[(i + 1) % 624] and Int.MAX_VALUE)
                    state[i] = state[(i + 397) % 624] xor (y ushr 1) xor
                        (if (y and 1 != 0) 0x9908b0df.toInt() else 0)
                }
                position = 0
            }
            var y = state[position++]
            y = y xor (y ushr 11)
            y = y xor ((y shl 7) and 0x9d2c5680.toInt())
            y = y xor ((y shl 15) and 0xefc60000.toInt())
            return y xor (y ushr 18)
        }
        fun interval(maximum: Int): Int {
            var mask = maximum
            mask = mask or (mask ushr 1); mask = mask or (mask ushr 2)
            mask = mask or (mask ushr 4); mask = mask or (mask ushr 8)
            mask = mask or (mask ushr 16)
            while (true) { val value = nextInt() and mask; if (value <= maximum) return value }
        }
        private fun randomDouble(): Double {
            val a = nextInt() ushr 5; val b = nextInt() ushr 6
            return (a.toDouble() * 67108864.0 + b) / 9007199254740992.0
        }
        fun blockOrder(): IntArray {
            val values = DoubleArray(16) { randomDouble() }
            return (0 until 16).sortedWith(compareBy<Int> { values[it] }.thenBy { it }).toIntArray()
        }
    }

    private fun rectify(source: Bitmap, corners: FloatArray): Bitmap? {
        if (corners.size != 8 || corners.any { !it.isFinite() }) return null
        val width = ((hypot(corners[2] - corners[0], corners[3] - corners[1]) +
            hypot(corners[4] - corners[6], corners[5] - corners[7])) / 2).roundToInt()
        val height = ((hypot(corners[6] - corners[0], corners[7] - corners[1]) +
            hypot(corners[4] - corners[2], corners[5] - corners[3])) / 2).roundToInt()
        if (width < 32 || height < 32 || width > 4096 || height > 4096) return null
        val target = floatArrayOf(0f, 0f, width.toFloat(), 0f,
            width.toFloat(), height.toFloat(), 0f, height.toFloat())
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(corners, 0, target, 0, 4)) return null
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(output).drawBitmap(source, matrix,
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return output
    }
}
