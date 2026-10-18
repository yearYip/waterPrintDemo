package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.roundToInt

/**
 * Legacy spatial watermark format, retained for older generated images.
 * New image generation uses FrequencyWatermark; do not use the rectangular
 * luminance offsets below for new photographs.
 */
object SpatialWatermark {
    const val WIDTH = 720
    const val HEIGHT = 400
    private const val TILES_PER_AXIS = 2
    private const val FRAME = 2
    private const val CELL_WIDTH = 12
    private const val CELL_HEIGHT = 16
    private const val PAIRS_PER_ROW = WIDTH / CELL_WIDTH / 2
    private const val ROWS = HEIGHT / CELL_HEIGHT
    private const val CODE_BITS = PAIRS_PER_ROW * ROWS
    private const val DATA_BITS_PER_BLOCK = 11
    private const val CODE_BITS_PER_BLOCK = 15
    const val MAX_PAYLOAD_BITS = CODE_BITS / CODE_BITS_PER_BLOCK * DATA_BITS_PER_BLOCK
    private const val DARK = 246
    private const val LIGHT = 255
    private const val MIN_PAIR_CONTRAST = 2
    private const val TARGET_PAIR_CONTRAST = 4
    private const val MAX_LUMA_ADJUSTMENT = 2
    private val parityPositions = setOf(1, 2, 4, 8)

    fun embed(bits: BooleanArray): Bitmap {
        require(bits.size <= MAX_PAYLOAD_BITS) { "Payload exceeds spatial watermark capacity" }
        val code = encode(bits)
        val pixels = IntArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) {
            val row = y / CELL_HEIGHT
            for (x in 0 until WIDTH) {
                val col = x / CELL_WIDTH
                val bit = code[row * PAIRS_PER_ROW + col / 2]
                val isDark = (col % 2 == 0) == bit
                val shade = if (isDark) DARK else LIGHT
                pixels[y * WIDTH + x] = Color.rgb(shade, shade, shade)
            }
        }
        return Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    }

    /** Embed a low-amplitude spatial signal in the selected photo without changing its dimensions. */
    fun embedInto(photo: Bitmap, bits: BooleanArray): Bitmap {
        require(bits.size <= MAX_PAYLOAD_BITS) { "Payload exceeds spatial watermark capacity" }
        require(photo.width >= 120 && photo.height >= 50) { "图片尺寸太小，无法嵌入拍屏水印" }
        val code = encode(bits)
        val width = photo.width
        val height = photo.height
        val pixels = IntArray(width * height)
        photo.getPixels(pixels, 0, width, 0, 0, width, height)
        val output = pixels.copyOf()
        val embeddedRows = ROWS * TILES_PER_AXIS
        val embeddedColumns = PAIRS_PER_ROW * 2 * TILES_PER_AXIS

        for (rowIndex in 0 until embeddedRows) {
            val row = rowIndex % ROWS
            val cellTop = rowIndex * height / embeddedRows
            val cellBottom = (rowIndex + 1) * height / embeddedRows
            val sampleTop = cellTop + (cellBottom - cellTop) / 4
            val sampleBottom = cellBottom - (cellBottom - cellTop) / 4
            for (pairIndex in 0 until PAIRS_PER_ROW * TILES_PER_AXIS) {
                val pair = pairIndex % PAIRS_PER_ROW
                val leftCol = pairIndex * 2
                val left = cellLuma(pixels, width, leftCol, embeddedColumns, sampleTop, sampleBottom)
                val right = cellLuma(pixels, width, leftCol + 1, embeddedColumns, sampleTop, sampleBottom)
                val bit = code[row * PAIRS_PER_ROW + pair]
                val difference = right - left
                val alreadyEncoded = if (bit) difference >= MIN_PAIR_CONTRAST else difference <= -MIN_PAIR_CONTRAST
                val adjustment = if (alreadyEncoded) 0 else
                    ((if (bit) TARGET_PAIR_CONTRAST else -TARGET_PAIR_CONTRAST) - difference) / 2
                val leftOffset = (-adjustment).coerceIn(-MAX_LUMA_ADJUSTMENT, MAX_LUMA_ADJUSTMENT)
                val rightOffset = adjustment.coerceIn(-MAX_LUMA_ADJUSTMENT, MAX_LUMA_ADJUSTMENT)
                adjustCell(output, width, height, leftCol, rowIndex, embeddedColumns, embeddedRows, leftOffset)
                adjustCell(output, width, height, leftCol + 1, rowIndex, embeddedColumns, embeddedRows, rightOffset)
            }
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Normalize a whole selected image or screenshot to the spatial decoder's grid. */
    fun decodeScaled(bitmap: Bitmap): WatermarkPayload? {
        val normalized = Bitmap.createScaledBitmap(
            bitmap, WIDTH * TILES_PER_AXIS, HEIGHT * TILES_PER_AXIS, true
        )
        return try {
            decodeTiled(normalized)
        } finally {
            if (normalized !== bitmap) normalized.recycle()
        }
    }

    private fun cellLuma(
        pixels: IntArray,
        width: Int,
        col: Int,
        columns: Int,
        sampleTop: Int,
        sampleBottom: Int
    ): Int {
        val x0 = col * width / columns
        val x1 = (col + 1) * width / columns
        val left = x0 + (x1 - x0) / 4
        val right = x1 - (x1 - x0) / 4
        var sum = 0L
        var count = 0
        for (y in sampleTop until sampleBottom) {
            for (x in left until right) {
                val pixel = pixels[y * width + x]
                sum += (Color.red(pixel) * 77 + Color.green(pixel) * 150 + Color.blue(pixel) * 29) shr 8
                count++
            }
        }
        return if (count == 0) 0 else (sum / count).toInt()
    }

    private fun adjustCell(
        pixels: IntArray,
        width: Int,
        height: Int,
        col: Int,
        row: Int,
        columns: Int,
        rows: Int,
        offset: Int
    ) {
        if (offset == 0) return
        val x0 = col * width / columns
        val x1 = (col + 1) * width / columns
        val y0 = row * height / rows
        val y1 = (row + 1) * height / rows
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val index = y * width + x
                val pixel = pixels[index]
                pixels[index] = Color.argb(
                    Color.alpha(pixel),
                    (Color.red(pixel) + offset).coerceIn(0, 255),
                    (Color.green(pixel) + offset).coerceIn(0, 255),
                    (Color.blue(pixel) + offset).coerceIn(0, 255)
                )
            }
        }
    }

    private fun decodeTiled(bitmap: Bitmap): WatermarkPayload? {
        if (bitmap.width != WIDTH * TILES_PER_AXIS ||
            bitmap.height != HEIGHT * TILES_PER_AXIS
        ) return null
        val code = BooleanArray(CODE_BITS)
        for (row in 0 until ROWS) {
            for (pair in 0 until PAIRS_PER_ROW) {
                var contrast = 0
                for (tileY in 0 until TILES_PER_AXIS) {
                    for (tileX in 0 until TILES_PER_AXIS) {
                        contrast += pairContrast(
                            bitmap, tileX * WIDTH, tileY * HEIGHT, pair, row
                        )
                    }
                }
                code[row * PAIRS_PER_ROW + pair] = contrast > 0
            }
        }
        return WatermarkPayload.fromBitArray(decodeCode(code))
    }

    private fun pairContrast(bitmap: Bitmap, offsetX: Int, offsetY: Int, pair: Int, row: Int): Int {
        val leftCol = pair * 2
        val leftX0 = offsetX + leftCol * CELL_WIDTH + CELL_WIDTH / 4
        val leftX1 = offsetX + (leftCol + 1) * CELL_WIDTH - CELL_WIDTH / 4
        val rightX0 = offsetX + (leftCol + 1) * CELL_WIDTH + CELL_WIDTH / 4
        val rightX1 = offsetX + (leftCol + 2) * CELL_WIDTH - CELL_WIDTH / 4
        val y0 = offsetY + row * CELL_HEIGHT + CELL_HEIGHT / 4
        val y1 = offsetY + (row + 1) * CELL_HEIGHT - CELL_HEIGHT / 4
        var difference = 0
        for (y in y0 until y1) {
            for (x in leftX0 until leftX1) difference -= pixelLuma(bitmap.getPixel(x, y))
            for (x in rightX0 until rightX1) difference += pixelLuma(bitmap.getPixel(x, y))
        }
        return difference
    }

    private fun pixelLuma(pixel: Int): Int =
        (Color.red(pixel) * 77 + Color.green(pixel) * 150 + Color.blue(pixel) * 29) shr 8

    /** Locate the black frame around a spatial panel in a screenshot or saved image. */
    fun findFramedPanel(bitmap: Bitmap): WatermarkPayload? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (y in 0 until height) {
            var x = 0
            while (x < width) {
                if (!isDark(pixels[y * width + x])) {
                    x++
                    continue
                }
                val left = x
                while (x < width && isDark(pixels[y * width + x])) x++
                val outerWidth = x - left
                if (outerWidth < 140) continue
                for (frame in intArrayOf(FRAME, 8).distinct()) {
                    val scale = outerWidth.toFloat() / (WIDTH + frame * 2)
                    val expectedBottom = y + ((HEIGHT + frame * 2) * scale).roundToInt() - 1
                    if (expectedBottom >= height) continue
                    for (bottom in (expectedBottom - 3).coerceAtLeast(y + 1)..
                        (expectedBottom + 3).coerceAtMost(height - 1)) {
                        if (!hasDarkBottom(pixels, width, left, x, bottom)) continue
                        val inset = (frame * scale).roundToInt().coerceAtLeast(1)
                        val panelWidth = (WIDTH * scale).roundToInt()
                        val panelHeight = (HEIGHT * scale).roundToInt()
                        val panelLeft = left + inset
                        val panelTop = y + inset
                        if (panelLeft + panelWidth > width || panelTop + panelHeight > height) continue
                        decodeRegion(bitmap, panelLeft, panelTop, panelWidth, panelHeight)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    /** The selected four corners may include the black frame. */
    fun decodeIncludingFrame(bitmap: Bitmap): WatermarkPayload? {
        for (frame in intArrayOf(FRAME, 8).distinct()) {
            val insetX = (bitmap.width * frame.toFloat() / (WIDTH + frame * 2)).roundToInt()
            val insetY = (bitmap.height * frame.toFloat() / (HEIGHT + frame * 2)).roundToInt()
            decodeRegion(
                bitmap, insetX, insetY,
                bitmap.width - insetX * 2, bitmap.height - insetY * 2
            )?.let { return it }
        }
        return null
    }

    private fun decodeRegion(bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int): WatermarkPayload? {
        if (width <= 0 || height <= 0) return null
        val normalized = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            Canvas(normalized).drawBitmap(
                bitmap,
                Rect(left, top, left + width, top + height),
                RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            return decode(normalized)
        } finally {
            normalized.recycle()
        }
    }

    private fun isDark(pixel: Int): Boolean =
        Color.red(pixel) < 160 && Color.green(pixel) < 160 && Color.blue(pixel) < 160

    private fun hasDarkBottom(pixels: IntArray, width: Int, left: Int, right: Int, y: Int): Boolean {
        for (part in 1..7) {
            val x = left + (right - left) * part / 8
            if (!isDark(pixels[y * width + x])) return false
        }
        return true
    }

    fun decode(bitmap: Bitmap): WatermarkPayload? {
        if (bitmap.width != WIDTH || bitmap.height != HEIGHT) return null
        val pixels = IntArray(WIDTH * HEIGHT)
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        val luma = IntArray(pixels.size) { index ->
            val p = pixels[index]
            (Color.red(p) * 77 + Color.green(p) * 150 + Color.blue(p) * 29) shr 8
        }
        return decodeLuma(luma)
    }

    /** Exposed for host-side tests without Android Bitmap. */
    fun decodeLuma(luma: IntArray): WatermarkPayload? {
        if (luma.size != WIDTH * HEIGHT) return null
        val code = BooleanArray(CODE_BITS)
        for (row in 0 until ROWS) {
            for (pair in 0 until PAIRS_PER_ROW) {
                val left = cellMean(luma, pair * 2, row)
                val right = cellMean(luma, pair * 2 + 1, row)
                code[row * PAIRS_PER_ROW + pair] = left < right
            }
        }
        return WatermarkPayload.fromBitArray(decodeCode(code))
    }

    /** Returns the grayscale panel before it is converted to a Bitmap. */
    fun renderLuma(bits: BooleanArray): IntArray {
        require(bits.size <= MAX_PAYLOAD_BITS)
        val code = encode(bits)
        return IntArray(WIDTH * HEIGHT) { index ->
            val y = index / WIDTH
            val x = index % WIDTH
            val col = x / CELL_WIDTH
            val bit = code[y / CELL_HEIGHT * PAIRS_PER_ROW + col / 2]
            if ((col % 2 == 0) == bit) DARK else LIGHT
        }
    }

    private fun cellMean(luma: IntArray, col: Int, row: Int): Int {
        val x0 = col * CELL_WIDTH + CELL_WIDTH / 4
        val x1 = (col + 1) * CELL_WIDTH - CELL_WIDTH / 4
        val y0 = row * CELL_HEIGHT + CELL_HEIGHT / 4
        val y1 = (row + 1) * CELL_HEIGHT - CELL_HEIGHT / 4
        var sum = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) sum += luma[y * WIDTH + x]
        }
        return sum / ((x1 - x0) * (y1 - y0))
    }

    private fun encode(bits: BooleanArray): BooleanArray {
        val code = BooleanArray(CODE_BITS)
        var dataIndex = 0
        for (block in 0 until CODE_BITS / CODE_BITS_PER_BLOCK) {
            val offset = block * CODE_BITS_PER_BLOCK
            for (position in 1..CODE_BITS_PER_BLOCK) {
                if (position !in parityPositions) {
                    code[offset + position - 1] =
                        if (dataIndex < bits.size) bits[dataIndex] else ((dataIndex * 37 + 13) and 1) != 0
                    dataIndex++
                }
            }
            for (parity in parityPositions) {
                var value = false
                for (position in 1..CODE_BITS_PER_BLOCK) {
                    if ((position and parity) != 0 && position != parity) {
                        value = value xor code[offset + position - 1]
                    }
                }
                code[offset + parity - 1] = value
            }
        }
        return code
    }

    private fun decodeCode(code: BooleanArray): BooleanArray {
        val data = BooleanArray(MAX_PAYLOAD_BITS)
        var dataIndex = 0
        for (block in 0 until CODE_BITS / CODE_BITS_PER_BLOCK) {
            val offset = block * CODE_BITS_PER_BLOCK
            var syndrome = 0
            for (parity in parityPositions) {
                var value = false
                for (position in 1..CODE_BITS_PER_BLOCK) {
                    if ((position and parity) != 0) value = value xor code[offset + position - 1]
                }
                if (value) syndrome += parity
            }
            if (syndrome in 1..CODE_BITS_PER_BLOCK) {
                code[offset + syndrome - 1] = !code[offset + syndrome - 1]
            }
            for (position in 1..CODE_BITS_PER_BLOCK) {
                if (position !in parityPositions) data[dataIndex++] = code[offset + position - 1]
            }
        }
        return data
    }
}
