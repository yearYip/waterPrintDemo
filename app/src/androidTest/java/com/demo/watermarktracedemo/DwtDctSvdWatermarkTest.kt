package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DwtDctSvdWatermarkTest {
    private val payload = WatermarkPayload("123456", "0123456789abcdef", 1792378692705L)

    @Test
    fun directCarrierRoundTripAtNativeAndResampledSizes() {
        for (size in intArrayOf(512, 320)) {
            val random = Random(42)
            val pixels = IntArray(size * size) {
                val value = 80 + random.nextInt(120)
                Color.rgb(value, value, value)
            }
            val source = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
            try {
                val marked = DwtDctSvdWatermark.embed(source, payload.toBitArray())
                try {
                    assertEquals("Round trip failed at $size", payload,
                        DwtDctSvdWatermark.extract(marked, refine = false))
                    val actual = IntArray(pixels.size)
                    marked.getPixels(actual, 0, size, 0, 0, size, size)
                    for (index in pixels.indices) {
                        assertTrue("Carrier exceeds ±9 at $index",
                            kotlin.math.abs(Color.red(actual[index]) - Color.red(pixels[index])) <= 9)
                    }
                } finally {
                    marked.recycle()
                }
            } finally {
                source.recycle()
            }
        }
    }

    @Test
    fun completeRegionSurvivesCropAndJpegReencoding() {
        val random = Random(71)
        val source = Bitmap.createBitmap(
            IntArray(640 * 640) {
                val value = 50 + random.nextInt(160)
                Color.rgb(value, value, value)
            }, 640, 640, Bitmap.Config.ARGB_8888
        )
        try {
            val marked = MultiRegionWatermark.embed(source, payload.toBitArray()).bitmap
            try {
                val crop = Bitmap.createBitmap(marked, 320, 0, 320, 320)
                try {
                    assertEquals(payload, DwtDctSvdWatermark.extract(crop, refine = false))
                    val bytes = ByteArrayOutputStream().use { stream ->
                        assertTrue(crop.compress(Bitmap.CompressFormat.JPEG, 85, stream))
                        stream.toByteArray()
                    }
                    val jpeg = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    try {
                        assertEquals(payload, DwtDctSvdWatermark.extract(jpeg, refine = false))
                    } finally {
                        jpeg.recycle()
                    }
                } finally {
                    crop.recycle()
                }
            } finally {
                marked.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun resizedJpegCanLocateACompleteRegion() {
        val random = Random(96)
        val source = Bitmap.createBitmap(
            IntArray(640 * 640) {
                val value = 60 + random.nextInt(150)
                Color.rgb(value, value, value)
            }, 640, 640, Bitmap.Config.ARGB_8888
        )
        try {
            val marked = MultiRegionWatermark.embed(source, payload.toBitArray()).bitmap
            try {
                val resized = Bitmap.createScaledBitmap(marked, 512, 512, true)
                try {
                    val bytes = ByteArrayOutputStream().use { stream ->
                        assertTrue(resized.compress(Bitmap.CompressFormat.JPEG, 85, stream))
                        stream.toByteArray()
                    }
                    val jpeg = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    try {
                        val reader = DwtDctSvdWatermark.Reader(jpeg)
                        val known = reader.extract(floatArrayOf(
                            0f, 0f, 256f, 0f, 256f, 256f, 0f, 256f
                        ), refine = false)
                        var found: WatermarkPayload? = null
                        val candidates = CornerLocator.candidates(jpeg) { corners ->
                            found = reader.extract(corners, refine = false)
                            found != null
                        }
                        if (found == null) {
                            found = candidates.firstNotNullOfOrNull {
                                reader.extract(it, refine = false)
                            }
                        }
                        if (found == null) {
                            found = candidates.take(4).firstNotNullOfOrNull { reader.extract(it) }
                        }
                        assertEquals("known=$known, candidates=${candidates.size}, first=${candidates.firstOrNull()?.joinToString()}, failure=${reader.bestFailure}", payload, found)
                    } finally {
                        jpeg.recycle()
                    }
                } finally {
                    resized.recycle()
                }
            } finally {
                marked.recycle()
            }
        } finally {
            source.recycle()
        }
    }
}
