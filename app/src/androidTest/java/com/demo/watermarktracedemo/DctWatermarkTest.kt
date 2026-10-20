package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DctWatermarkTest {
    private val payload = WatermarkPayload("123456", "0123456789abcdef", 1792378692705L)

    private fun image(width: Int = 640, height: Int = 640): Bitmap {
        val random = Random(77)
        return Bitmap.createBitmap(IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val texture = random.nextInt(35)
            Color.rgb(65 + x * 95 / width + texture,
                70 + y * 95 / height + texture,
                85 + texture + (x + y) * 55 / (width + height))
        }, width, height, Bitmap.Config.ARGB_8888)
    }

    @Test fun fullAndUnmarked() {
        val source = image()
        val marked = DctWatermark.embed(source, payload.toBitArray())
        try {
            assertEquals(payload, DctWatermark.extract(marked))
            assertNull(DctWatermark.extract(source))
        } finally { marked.recycle(); source.recycle() }
    }

    @Test fun arbitraryCropAndJpeg() {
        val source = image()
        val marked = DctWatermark.embed(source, payload.toBitArray())
        val crop = Bitmap.createBitmap(marked, 89, 113, 352, 344)
        try {
            assertEquals(payload, DctWatermark.extract(crop))
            val out = ByteArrayOutputStream()
            crop.compress(Bitmap.CompressFormat.JPEG, 90, out)
            val bytes = out.toByteArray()
            val jpeg = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try { assertEquals(payload, DctWatermark.extract(jpeg)) }
            finally { jpeg.recycle() }
        } finally { crop.recycle(); marked.recycle(); source.recycle() }
    }

    @Test fun cropPositionsAndResize() {
        val source = image()
        val marked = DctWatermark.embed(source, payload.toBitArray())
        try {
            for ((x, y) in listOf(0 to 0, 37 to 54, 196 to 330, 250 to 230)) {
                val crop = Bitmap.createBitmap(marked, x, y, 320, 256)
                try { assertEquals("crop=$x,$y", payload, DctWatermark.extract(crop)) }
                finally { crop.recycle() }
            }
            val small = Bitmap.createScaledBitmap(marked, 320, 320, true)
            try { assertEquals(payload, DctWatermark.extract(small)) }
            finally { small.recycle() }
        } finally { marked.recycle(); source.recycle() }
    }

    @Test fun reducedCropAndJpeg85() {
        val source = image()
        val marked = DctWatermark.embed(source, payload.toBitArray())
        val crop = Bitmap.createBitmap(marked, 89, 113, 352, 344)
        val reduced = Bitmap.createScaledBitmap(crop, 176, 172, true)
        try {
            val output = ByteArrayOutputStream()
            assertTrue(crop.compress(Bitmap.CompressFormat.JPEG, 85, output))
            val bytes = output.toByteArray()
            val jpeg = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try { assertEquals(payload, DctWatermark.extract(jpeg)) }
            finally { jpeg.recycle() }
            assertEquals(payload, DctWatermark.extract(reduced))
        } finally { reduced.recycle(); crop.recycle(); marked.recycle(); source.recycle() }
    }

    @Test fun flatImageAndChangeMagnitude() {
        val source = Bitmap.createBitmap(IntArray(512 * 512) {
            Color.rgb(120, 150, 180)
        }, 512, 512, Bitmap.Config.ARGB_8888)
        val marked = DctWatermark.embed(source, payload.toBitArray())
        try {
            assertEquals(payload, DctWatermark.extract(marked))
            val pixels = IntArray(512 * 512)
            marked.getPixels(pixels, 0, 512, 0, 0, 512, 512)
            val maximum = pixels.maxOf { kotlin.math.abs(Color.red(it) - 120) }
            assertTrue("maximum luminance change=$maximum", maximum <= 10)
        } finally { marked.recycle(); source.recycle() }
    }
}
