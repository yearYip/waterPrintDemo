package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.min

/** Conservative removal of uniform screenshot margins; the caller keeps the original as a fallback. */
internal object ScreenshotPreprocessor {
    fun contentBounds(image: Bitmap): Rect {
        val bounds = Rect(0, 0, image.width, image.height)
        fun background(horizontal: Boolean, position: Int, start: Int, end: Int): Int? {
            val colors = IntArray(64) { i ->
                val along = start + (end - start - 1) * i / 63
                if (horizontal) image.getPixel(along, position) else image.getPixel(position, along)
            }
            val reference = Color.rgb(
                colors.map { Color.red(it) }.sorted()[32],
                colors.map { Color.green(it) }.sorted()[32],
                colors.map { Color.blue(it) }.sorted()[32]
            )
            return reference.takeIf { colors.count { close(it, reference) } >= 61 }
        }
        fun trim(horizontal: Boolean, fromStart: Boolean) {
            val start = if (horizontal) bounds.left else bounds.top
            val end = if (horizontal) bounds.right else bounds.bottom
            val low = if (horizontal) bounds.top else bounds.left
            val high = if (horizontal) bounds.bottom else bounds.right
            var position = if (fromStart) low else high - 1
            val reference = background(horizontal, position, start, end) ?: return
            // Retain enough pixels for a watermark period, even with very large margins.
            // A completely uniform image has no boundary and must be left intact.
            val minimum = if (horizontal) 128 else 96
            val limit = if (fromStart) high - minimum else low + minimum
            while (if (fromStart) position < limit else position >= limit) {
                var matches = 0
                for (i in 0 until 64) {
                    val along = start + (end - start - 1) * i / 63
                    val color = if (horizontal) image.getPixel(along, position)
                        else image.getPixel(position, along)
                    if (close(color, reference)) matches++
                }
                if (matches < 61) break
                position += if (fromStart) 1 else -1
            }
            if (if (fromStart) position >= limit else position < limit) return
            val removed = if (fromStart) position - low else high - 1 - position
            if (removed < 8) return
            if (horizontal) {
                if (fromStart) bounds.top = position else bounds.bottom = position + 1
            } else {
                if (fromStart) bounds.left = position else bounds.right = position + 1
            }
        }
        trim(true, true)
        trim(true, false)
        trim(false, true)
        trim(false, false)
        return if (bounds.width() >= 96 && bounds.height() >= 128) bounds
            else Rect(0, 0, image.width, image.height)
    }

    private fun close(a: Int, b: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) <= 16 &&
            abs(Color.green(a) - Color.green(b)) <= 16 &&
            abs(Color.blue(a) - Color.blue(b)) <= 16

    /** Only the first pilot strip is needed, so scale estimation does not copy the whole image. */
    class PilotSamples(image: Bitmap, bounds: Rect) {
        private val width = min(bounds.width(), 66)
        private val height = min(bounds.height(), 770)
        private val luma: DoubleArray

        init {
            val pixels = IntArray(width * height)
            image.getPixels(pixels, 0, width, bounds.left, bounds.top, width, height)
            luma = DoubleArray(pixels.size) {
                val p = pixels[it]
                (77 * Color.red(p) + 150 * Color.green(p) + 29 * Color.blue(p)) / 256.0
            }
        }

        fun sample(x: Double, y: Double): Double {
            val px = x.coerceIn(0.0, width - 1.0)
            val py = y.coerceIn(0.0, height - 1.0)
            val ix = px.toInt()
            val iy = py.toInt()
            val nx = min(ix + 1, width - 1)
            val ny = min(iy + 1, height - 1)
            val fx = px - ix
            val fy = py - iy
            val a = luma[iy * width + ix] * (1 - fx) + luma[iy * width + nx] * fx
            val b = luma[ny * width + ix] * (1 - fx) + luma[ny * width + nx] * fx
            return a * (1 - fy) + b * fy
        }
    }
}
