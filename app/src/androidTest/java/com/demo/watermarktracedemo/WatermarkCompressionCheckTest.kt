package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Random
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatermarkCompressionCheckTest {
    private val payload = WatermarkPayload("123456", "0123456789abcdef", 1792378692705L)

    @Test
    fun compressionCheckDoesNotTrustKnownPayloadOrRetileTheResizedImage() {
        // Before resize: 6 x 2 regions. After resize, recomputing would give 5 x 1.
        val source = Bitmap.createBitmap(1920, 640, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLACK)
        try {
            val results = WatermarkCompressionCheck.verify(source, payload)
            assertEquals(listOf(75, 50), results.map { it.quality })
            for (result in results) {
                assertNull(result.error)
                assertEquals(1600, result.width)
                assertEquals(533, result.height)
                assertEquals(12, result.totalRegions)
                assertEquals(0, result.readableRegions)
                assertEquals(0, result.maxReadablePilots)
                assertEquals(0.0, result.bestPilotScore, 0.0)
                assertFalse(result.candidateFound)
                assertFalse(result.automaticReadable)
            }
            assertFalse(source.isRecycled)
            assertEquals(Color.BLACK, source.getPixel(100, 100))
        } finally {
            source.recycle()
        }
    }

    @Test
    fun enhancedCarrierPreservesRoundTripAndPixelLimits() {
        val random = Random(42)
        val pixels = IntArray(512 * 512) { index ->
            val value = if (index % 512 < 256) 170 else 140 + random.nextInt(40)
            Color.rgb(value, value, value)
        }
        val source = Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888)
        try {
            val marked = FrequencyWatermark.embed(source, payload.toBitArray())
            try {
                assertEquals(payload, FrequencyWatermark.extract(marked, refine = false))
                val actual = IntArray(pixels.size)
                marked.getPixels(actual, 0, 512, 0, 0, 512, 512)
                for (index in pixels.indices) {
                    val delta = kotlin.math.abs(Color.red(actual[index]) - Color.red(pixels[index]))
                    assertTrue("Carrier exceeds ±3 at $index", delta <= 3)
                    // Interior of the flat half, clear of interpolation at the boundary.
                    if (index % 512 < 240) assertTrue("Flat area exceeds ±1", delta <= 1)
                    assertEquals(Color.red(actual[index]), Color.green(actual[index]))
                    assertEquals(Color.red(actual[index]), Color.blue(actual[index]))
                }
            } finally {
                marked.recycle()
            }
        } finally {
            source.recycle()
        }
    }
}
