package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** One luminance DCT coefficient pair per 8x8 block, repeated in a two-dimensional grid. */
object DctWatermark {
    enum class FailureReason { INVALID_GEOMETRY, INSUFFICIENT_BLOCKS, INVALID_PAYLOAD }
    class CarrierException(message: String) : IllegalArgumentException(message)

    const val MAX_PAYLOAD_BITS = 512
    private const val BLOCK = 8
    private const val ROWS = 16
    private const val PILOT_COLUMNS = 4
    private const val MIN_COLUMNS = 12
    private const val QUANTUM = 80.0
    private const val MAX_SEARCH_EDGE = 512
    private val screenshotGains = doubleArrayOf(1.0, 1.15, 1.3, 1.5, 2.0, 2.5, 3.0, 4.0)
    private val basis = DoubleArray(64) { index ->
        val x = index % BLOCK
        val y = index / BLOCK
        0.5 / kotlin.math.sqrt(8.0) *
            (cos(PI * (2 * x + 1) / 16) - cos(PI * (2 * y + 1) / 16))
    }
    private val pilots = BooleanArray(ROWS * PILOT_COLUMNS) { index ->
        // Fixed, balanced, non-periodic sync pattern. It carries no payload or secret.
        var value = index + 0x51e2
        value = (value xor (value ushr 7)) * 0x45d9f3b
        value = (value xor (value ushr 13)) * 0x45d9f3b
        value xor (value ushr 16) and 1 != 0
    }

    fun embed(source: Bitmap, bits: BooleanArray): Bitmap {
        require(bits.size <= MAX_PAYLOAD_BITS && WatermarkPayload.fromBitArray(bits) != null)
        val code = encode(bits)
        val columns = columnsFor(code.size)
        if (source.width / BLOCK < columns || source.height / BLOCK < ROWS)
            throw CarrierException("图片太小，无法容纳完整水印周期（至少 ${columns * BLOCK}×${ROWS * BLOCK} 像素）")
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        for (by in 0 until source.height / BLOCK) for (bx in 0 until source.width / BLOCK) {
            val row = by % ROWS
            val column = bx % columns
            val bit = if (column < PILOT_COLUMNS) pilots[row * PILOT_COLUMNS + column]
                else code[(row * (columns - PILOT_COLUMNS) + column - PILOT_COLUMNS) % code.size]
            val difference = coefficientDifference(pixels, source.width, bx * BLOCK, by * BLOCK)
            val offset = if (bit) QUANTUM * 0.75 else QUANTUM * 0.25
            val target = Math.rint((difference - offset) / QUANTUM) * QUANTUM + offset
            val halfChange = (target - difference) / 2.0
            for (i in 0 until 64) {
                val position = (by * BLOCK + i / BLOCK) * source.width + bx * BLOCK + i % BLOCK
                val pixel = pixels[position]
                val delta = (halfChange * basis[i]).roundToInt()
                pixels[position] = Color.argb(Color.alpha(pixel),
                    (Color.red(pixel) + delta).coerceIn(0, 255),
                    (Color.green(pixel) + delta).coerceIn(0, 255),
                    (Color.blue(pixel) + delta).coerceIn(0, 255))
            }
        }
        return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
    }

    fun extract(bitmap: Bitmap, corners: FloatArray? = null): WatermarkPayload? = Reader(bitmap).extract(corners)

    class Reader(private val source: Bitmap) {
        var bestFailure: FailureReason? = null
            private set
        private var searchedUsableBlocks = false

        fun extract(
            corners: FloatArray? = null,
            onProgress: (String) -> Unit = {}
        ): WatermarkPayload? {
            bestFailure = null
            searchedUsableBlocks = false
            val image = if (corners == null) source else rectify(source, corners) ?: run {
                bestFailure = FailureReason.INVALID_GEOMETRY
                return null
            }
            try {
                onProgress("正在读取原尺寸水印…")
                searchAligned(image, doubleArrayOf(1.0))?.let { return it }
                search(image, 1.0)?.let { return it }
                val bounds = ScreenshotPreprocessor.contentBounds(image)
                val full = Rect(0, 0, image.width, image.height)
                // A margin detector can mistake a flat part of a photo for UI. Always retain
                // the full image as an independent candidate, and require a valid payload CRC.
                val regions = if (bounds == full) listOf(full) else listOf(bounds, full)
                for (region in regions) {
                    onProgress("正在自动定位图片并估计缩放比例…")
                    val candidates = estimateScales(image, region)
                    val cropped = if (region == full) image else Bitmap.createBitmap(
                        image, region.left, region.top, region.width(), region.height()
                    )
                    try {
                        for ((index, candidate) in candidates.withIndex()) {
                            onProgress("正在验证自动缩放候选 ${index + 1}/${candidates.size}…")
                            val normalized = Bitmap.createScaledBitmap(
                                cropped, candidate.width, candidate.height, true
                            )
                            try {
                                val gains = (listOf(candidate.gain) + screenshotGains.toList())
                                    .distinct().toDoubleArray()
                                searchAligned(normalized, gains)?.let {
                                    bestFailure = null
                                    return it
                                }
                            } finally { if (normalized !== cropped) normalized.recycle() }
                        }
                    } finally { if (cropped !== image) cropped.recycle() }
                }
                onProgress("正在搜索局部裁剪水印…")
                val scales = if (image.width < 256 || image.height < 256)
                    doubleArrayOf(2.0, 1.25, 4.0 / 3.0, 1.5)
                else doubleArrayOf(1.25, 4.0 / 3.0, 1.5, 2.0)
                for (scale in scales) {
                    val width = (image.width * scale).roundToInt()
                    val height = (image.height * scale).roundToInt()
                    if (width.toLong() * height > 8_000_000) continue
                    val enlarged = if (scale == 1.0) image else
                        Bitmap.createScaledBitmap(image, width, height, false)
                    try {
                        val gains = if (scale == 1.0) doubleArrayOf(1.0) else
                            doubleArrayOf(1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0)
                        for (gain in gains) search(enlarged, gain)?.let {
                            bestFailure = null
                            return it
                        }
                    }
                    finally { if (enlarged !== image) enlarged.recycle() }
                }
                bestFailure = if (searchedUsableBlocks) FailureReason.INVALID_PAYLOAD
                    else FailureReason.INSUFFICIENT_BLOCKS
                return null
            } finally { if (image !== source) image.recycle() }
        }

        private data class ScaleCandidate(
            val width: Int, val height: Int, val gain: Double, val score: Double
        )

        /**
         * Full-image screenshots preserve the first four pilot columns at the image boundary.
         * Test every restored integer width in a bounded scale range, independent of any
         * particular original resolution. Three vertical repeats make false peaks less likely.
         * Partial crops still use the phase/period search below.
         */
        private fun estimateScales(image: Bitmap, region: Rect): List<ScaleCandidate> {
            val samples = ScreenshotPreprocessor.PilotSamples(image, region)
            val candidates = ArrayList<ScaleCandidate>()
            val minimum = maxOf(96, (region.width() * 0.5).roundToInt())
            val maximum = min(4096, region.width() * 2)
            for (width in minimum..maximum) {
                val height = (region.height().toDouble() * width / region.width()).roundToInt()
                if (height < 128 || width.toLong() * height > 8_000_000) continue
                val sx = region.width().toDouble() / width
                val sy = region.height().toDouble() / height
                val rows = min(48, height / BLOCK)
                val scores = DoubleArray(screenshotGains.size)
                for (row in 0 until rows) for (col in 0 until PILOT_COLUMNS) {
                    var difference = 0.0
                    for (i in 0 until 64) {
                        val x = (col * BLOCK + i % BLOCK + 0.5) * sx - 0.5
                        val y = (row * BLOCK + i / BLOCK + 0.5) * sy - 0.5
                        difference += samples.sample(x, y) * basis[i]
                    }
                    val expected = if (pilots[(row % ROWS) * PILOT_COLUMNS + col]) -1 else 1
                    for (g in scores.indices)
                        scores[g] += expected * sin(2 * PI * difference * screenshotGains[g] / QUANTUM)
                }
                val gainIndex = scores.indices.maxBy { scores[it] }
                val score = scores[gainIndex] / (rows * PILOT_COLUMNS)
                if (score > 0.18)
                    candidates.add(ScaleCandidate(width, height, screenshotGains[gainIndex], score))
            }
            // Keep adjacent widths: a one-pixel size difference can move distant blocks enough
            // to change the CRC, even if both sizes give a similar pilot score.
            return candidates.sortedByDescending { it.score }.take(12)
        }

        /** Once the image boundary and scale are known, average many repeated payloads. */
        private fun searchAligned(image: Bitmap, gains: DoubleArray): WatermarkPayload? {
            val width = min(image.width, 1536) / BLOCK * BLOCK
            val height = min(image.height, 1536) / BLOCK * BLOCK
            if (width < MIN_COLUMNS * BLOCK || height < ROWS * BLOCK) return null
            searchedUsableBlocks = true
            val left = (image.width - width) / (2 * BLOCK) * BLOCK
            val top = (image.height - height) / (2 * BLOCK) * BLOCK
            val pixels = IntArray(width * height)
            image.getPixels(pixels, 0, width, left, top, width, height)
            val blockWidth = width / BLOCK
            val blockHeight = height / BLOCK
            val differences = DoubleArray(blockWidth * blockHeight) { i ->
                coefficientDifference(pixels, width, i % blockWidth * BLOCK, i / blockWidth * BLOCK)
            }
            for (gain in gains) {
                val votes = DoubleArray(differences.size) { sin(2 * PI * differences[it] * gain / QUANTUM) }
                for (columns in MIN_COLUMNS..min(60, blockWidth)) {
                    decode(votes, blockWidth, blockHeight, Candidate(
                        1.0, columns, top / BLOCK % ROWS, left / BLOCK % columns
                    ))?.let { return it }
                }
            }
            return null
        }

        private fun search(image: Bitmap, gain: Double): WatermarkPayload? {
            if (image.width / BLOCK < MIN_COLUMNS || image.height / BLOCK < ROWS) {
                bestFailure = FailureReason.INSUFFICIENT_BLOCKS
                return null
            }
            searchedUsableBlocks = true
            // Use a bounded central region; all generated images carry the same repeating grid.
            val width = min(image.width, MAX_SEARCH_EDGE)
            val height = min(image.height, MAX_SEARCH_EDGE)
            val left = (image.width - width) / 2
            val top = (image.height - height) / 2
            val pixels = IntArray(width * height)
            image.getPixels(pixels, 0, width, left, top, width, height)
            for (phaseY in 0..7) for (phaseX in 0..7) {
                val blockWidth = (width - phaseX) / BLOCK
                val blockHeight = (height - phaseY) / BLOCK
                if (blockWidth < MIN_COLUMNS || blockHeight < ROWS) continue
                val votes = DoubleArray(blockWidth * blockHeight) { i ->
                    val difference = coefficientDifference(pixels, width,
                        phaseX + i % blockWidth * BLOCK,
                        phaseY + i / blockWidth * BLOCK)
                    sin(2 * PI * difference * gain / QUANTUM)
                }
                val candidates = ArrayList<Candidate>()
                for (columns in MIN_COLUMNS..min(60, blockWidth)) {
                    val gridSums = DoubleArray(ROWS * columns)
                    val gridCounts = IntArray(ROWS * columns)
                    for (by in 0 until blockHeight) for (bx in 0 until blockWidth) {
                        val gridIndex = (by % ROWS) * columns + bx % columns
                        gridSums[gridIndex] += votes[by * blockWidth + bx]
                        gridCounts[gridIndex]++
                    }
                    for (rowOffset in 0 until ROWS) for (columnOffset in 0 until columns) {
                        var score = 0.0
                        var count = 0
                        for (row in 0 until ROWS) for (col in 0 until PILOT_COLUMNS) {
                            val sourceRow = (row - rowOffset + ROWS) % ROWS
                            val sourceCol = (col - columnOffset + columns) % columns
                            val gridIndex = sourceRow * columns + sourceCol
                            val expected = if (pilots[row * PILOT_COLUMNS + col]) -1 else 1
                            score += gridSums[gridIndex] * expected
                            count += gridCounts[gridIndex]
                        }
                        if (count >= ROWS * PILOT_COLUMNS && score / count > 0.20)
                            candidates.add(Candidate(score / count, columns, rowOffset, columnOffset))
                    }
                }
                candidates.sortByDescending { it.score }
                for (candidate in candidates.take(128)) {
                    decode(votes, blockWidth, blockHeight, candidate)?.let { return it }
                }
            }
            return null
        }

        private fun decode(votes: DoubleArray, width: Int, height: Int, candidate: Candidate): WatermarkPayload? {
            val capacity = ROWS * (candidate.columns - PILOT_COLUMNS)
            val sums = DoubleArray(capacity)
            val counts = IntArray(capacity)
            for (by in 0 until height) for (bx in 0 until width) {
                val col = (bx + candidate.columnOffset) % candidate.columns
                if (col < PILOT_COLUMNS) continue
                val row = (by + candidate.rowOffset) % ROWS
                val index = row * (candidate.columns - PILOT_COLUMNS) + col - PILOT_COLUMNS
                sums[index] += votes[by * width + bx]
                counts[index]++
            }
            // The coded message is cyclically repeated; the decoder can infer its length
            // from the framed header once the first 84 coded bits have been read.
            val coded = BooleanArray(capacity) { counts[it] > 0 && sums[it] < 0 }
            if (capacity < 84) return null
            val header = decodeHamming(coded.copyOfRange(0, 84))
            val size = WatermarkPayload.requiredBitCount(header) ?: return null
            if (size !in WatermarkPayload.HEADER_BITS + 8..MAX_PAYLOAD_BITS) return null
            val codeSize = (size + 3) / 4 * 7
            if (codeSize > capacity || counts.take(codeSize).any { it == 0 }) return null
            val bits = decodeHamming(coded.copyOfRange(0, codeSize)).copyOf(size)
            return WatermarkPayload.fromBitArray(bits)
        }
    }

    private data class Candidate(val score: Double, val columns: Int,
        val rowOffset: Int, val columnOffset: Int)

    private fun columnsFor(codeSize: Int): Int =
        maxOf(MIN_COLUMNS, (codeSize + ROWS - 1) / ROWS + PILOT_COLUMNS)

    private fun encode(bits: BooleanArray): BooleanArray {
        val padded = bits.copyOf((bits.size + 3) / 4 * 4)
        return BooleanArray(padded.size / 4 * 7) { index ->
            val word = index / 7
            val d = BooleanArray(4) { padded[word * 4 + it] }
            val encoded = booleanArrayOf(
                d[0] xor d[1] xor d[3], d[0] xor d[2] xor d[3], d[0],
                d[1] xor d[2] xor d[3], d[1], d[2], d[3])
            encoded[index % 7]
        }
    }

    private fun decodeHamming(code: BooleanArray): BooleanArray {
        val result = BooleanArray(code.size / 7 * 4)
        for (word in 0 until code.size / 7) {
            val start = word * 7
            val bits = BooleanArray(7) { code[start + it] }
            val syndrome = (if (bits[0] xor bits[2] xor bits[4] xor bits[6]) 1 else 0) or
                (if (bits[1] xor bits[2] xor bits[5] xor bits[6]) 2 else 0) or
                (if (bits[3] xor bits[4] xor bits[5] xor bits[6]) 4 else 0)
            if (syndrome in 1..7) bits[syndrome - 1] = !bits[syndrome - 1]
            for (i in 0..3) result[word * 4 + i] = bits[intArrayOf(2, 4, 5, 6)[i]]
        }
        return result
    }

    private fun coefficientDifference(pixels: IntArray, stride: Int, x: Int, y: Int): Double {
        var sum = 0.0
        for (i in 0 until 64) {
            val pixel = pixels[(y + i / BLOCK) * stride + x + i % BLOCK]
            val luma = (77 * Color.red(pixel) + 150 * Color.green(pixel) +
                29 * Color.blue(pixel)) / 256.0
            sum += luma * basis[i]
        }
        return sum
    }

    private fun rectify(source: Bitmap, corners: FloatArray): Bitmap? {
        if (corners.size != 8) return null
        val width = ((kotlin.math.hypot((corners[2] - corners[0]).toDouble(),
            (corners[3] - corners[1]).toDouble()) +
            kotlin.math.hypot((corners[4] - corners[6]).toDouble(),
                (corners[5] - corners[7]).toDouble())) / 2).roundToInt()
        val height = ((kotlin.math.hypot((corners[6] - corners[0]).toDouble(),
            (corners[7] - corners[1]).toDouble()) +
            kotlin.math.hypot((corners[4] - corners[2]).toDouble(),
                (corners[5] - corners[3]).toDouble())) / 2).roundToInt()
        if (width < 64 || height < 64 || width.toLong() * height > 8_000_000) return null
        val matrix = Matrix()
        val target = floatArrayOf(0f, 0f, width.toFloat(), 0f,
            width.toFloat(), height.toFloat(), 0f, height.toFloat())
        if (!matrix.setPolyToPoly(corners, 0, target, 0, 4)) return null
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        canvas.drawBitmap(source, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return result
    }
}
