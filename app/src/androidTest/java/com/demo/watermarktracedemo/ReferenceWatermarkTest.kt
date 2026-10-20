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
class ReferenceWatermarkTest {
    private val payload = WatermarkPayload("123456", "0123456789abcdef", 1792378692705L)

    private fun image(width: Int, height: Int): Bitmap {
        val random = Random(71)
        return Bitmap.createBitmap(IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val texture = random.nextInt(31)
            Color.rgb((60 + x * 110 / width + texture).coerceAtMost(255),
                (75 + y * 100 / height + texture).coerceAtMost(255),
                (95 + texture + (x + y) * 45 / (width + height)).coerceAtMost(255))
        }, width, height, Bitmap.Config.ARGB_8888)
    }

    @Test fun nativeSizeRoundTrip() {
        val source = image(512, 480)
        val marked = ReferenceWatermark.embed(source, payload.toBitArray())
        try {
            assertEquals(512, marked.width)
            assertEquals(480, marked.height)
            assertEquals(payload, ReferenceWatermark.extract(marked))
        } finally { marked.recycle(); source.recycle() }
    }

    @Test fun jpegRoundTrip() {
        val source = image(640, 640)
        val marked = ReferenceWatermark.embed(source, payload.toBitArray())
        try {
            val bytes = ByteArrayOutputStream().use { stream ->
                assertTrue(marked.compress(Bitmap.CompressFormat.JPEG, 90, stream))
                stream.toByteArray()
            }
            val jpeg = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try { assertEquals(payload, ReferenceWatermark.extract(jpeg)) }
            finally { jpeg.recycle() }
        } finally { marked.recycle(); source.recycle() }
    }

    @Test fun missingWatermarkDoesNotReturnPayload() {
        val source = image(512, 480)
        try { assertNull(ReferenceWatermark.extract(source)) }
        finally { source.recycle() }
    }

    @Test fun flatImageRoundTrip() {
        val source = Bitmap.createBitmap(IntArray(512 * 512) { Color.rgb(120, 150, 180) },
            512, 512, Bitmap.Config.ARGB_8888)
        val marked = ReferenceWatermark.embed(source, payload.toBitArray())
        try { assertEquals(payload, ReferenceWatermark.extract(marked)) }
        finally { marked.recycle(); source.recycle() }
    }

    @Test fun manualCornersRecoverFullImageFromCanvas() {
        val source = image(512, 480)
        val marked = ReferenceWatermark.embed(source, payload.toBitArray())
        val screenshot = Bitmap.createBitmap(700, 650, Bitmap.Config.ARGB_8888)
        try {
            android.graphics.Canvas(screenshot).drawBitmap(marked, 80f, 70f, null)
            val corners = floatArrayOf(80f, 70f, 592f, 70f, 592f, 550f, 80f, 550f)
            assertEquals(payload, ReferenceWatermark.extract(screenshot, corners))
        } finally { screenshot.recycle(); marked.recycle(); source.recycle() }
    }

    @Test fun resizedFullImageIsRecoveredAutomatically() {
        val source = image(640, 640)
        val marked = ReferenceWatermark.embed(source, payload.toBitArray())
        try {
            for (edge in intArrayOf(480, 320)) {
                val small = Bitmap.createScaledBitmap(marked, edge, edge, true)
                try { assertEquals("edge=$edge", payload, ReferenceWatermark.extract(small)) }
                finally { small.recycle() }
            }
        } finally { marked.recycle(); source.recycle() }
    }

    @Test fun reducedImageInsideScreenshotWithManualCorners() {
        val source = image(640, 640)
        val marked = ReferenceWatermark.embed(source, payload.toBitArray())
        val small = Bitmap.createScaledBitmap(marked, 320, 320, true)
        val screenshot = Bitmap.createBitmap(500, 480, Bitmap.Config.ARGB_8888)
        try {
            android.graphics.Canvas(screenshot).drawBitmap(small, 80f, 70f, null)
            val corners = floatArrayOf(80f, 70f, 400f, 70f, 400f, 390f, 80f, 390f)
            assertEquals(payload, ReferenceWatermark.extract(screenshot, corners))
        } finally { screenshot.recycle(); small.recycle(); marked.recycle(); source.recycle() }
    }

    @Test fun arbitraryInteriorCropRecoversWithoutOriginalImage() {
        val source = image(640, 640)
        val marked = ReferenceWatermark.embed(source, payload.toBitArray())
        val crop = Bitmap.createBitmap(marked, 89, 113, 352, 344)
        try { assertEquals(payload, ReferenceWatermark.extract(crop)) }
        finally { crop.recycle(); marked.recycle(); source.recycle() }
    }

    @Test fun jpegInteriorCropRecoversWithoutOriginalImage() {
        val source = image(640, 640)
        val marked = ReferenceWatermark.embed(source, payload.toBitArray())
        val crop = Bitmap.createBitmap(marked, 89, 113, 352, 344)
        try {
            val bytes = ByteArrayOutputStream().use { stream ->
                assertTrue(crop.compress(Bitmap.CompressFormat.JPEG, 90, stream))
                stream.toByteArray()
            }
            val jpeg = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try { assertEquals(payload, ReferenceWatermark.extract(jpeg)) }
            finally { jpeg.recycle() }
        } finally { crop.recycle(); marked.recycle(); source.recycle() }
    }

    @Test fun reducedInteriorCropRecoversWithoutOriginalImage() {
        val source = image(640, 640)
        val marked = ReferenceWatermark.embed(source, payload.toBitArray())
        val crop = Bitmap.createBitmap(marked, 89, 113, 352, 344)
        val reduced = Bitmap.createScaledBitmap(crop, 176, 172, true)
        try { assertEquals(payload, ReferenceWatermark.extract(reduced)) }
        finally { reduced.recycle(); crop.recycle(); marked.recycle(); source.recycle() }
    }

    @Test fun severalCropPositionsAtRecommendedSize() {
        val source = image(640, 640)
        val marked = ReferenceWatermark.embed(source, payload.toBitArray())
        try {
            for ((x, y) in listOf(0 to 0, 37 to 54, 196 to 330, 250 to 230)) {
                val crop = Bitmap.createBitmap(marked, x, y, 320, 256)
                try { assertEquals("crop=($x,$y)", payload, ReferenceWatermark.extract(crop)) }
                finally { crop.recycle() }
            }
        } finally { marked.recycle(); source.recycle() }
    }

    @Test fun recommendedSizeCropSurvivesJpeg90() {
        val source = image(640, 640)
        val marked = ReferenceWatermark.embed(source, payload.toBitArray())
        val crop = Bitmap.createBitmap(marked, 37, 54, 320, 256)
        try {
            val bytes = ByteArrayOutputStream().use { stream ->
                assertTrue(crop.compress(Bitmap.CompressFormat.JPEG, 90, stream))
                stream.toByteArray()
            }
            val jpeg = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try { assertEquals(payload, ReferenceWatermark.extract(jpeg)) }
            finally { jpeg.recycle() }
        } finally { crop.recycle(); marked.recycle(); source.recycle() }
    }
}
