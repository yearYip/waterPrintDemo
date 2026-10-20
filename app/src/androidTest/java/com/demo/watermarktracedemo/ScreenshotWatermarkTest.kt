package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotWatermarkTest {
    private val payload = WatermarkPayload("123456", "0123456789abcdef", 1792378692705L)

    private fun image(width: Int, height: Int): Bitmap {
        val random = Random(42)
        return Bitmap.createBitmap(IntArray(width * height) { i ->
            val noise = random.nextInt(25)
            Color.rgb(65 + (i % width) * 90 / width + noise,
                70 + (i / width) * 90 / height + noise, 90 + noise)
        }, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun screenshot(marked: Bitmap, width: Int, height: Int, background: Int): Bitmap {
        val scaled = Bitmap.createScaledBitmap(marked, width, height, true)
        val frame = Bitmap.createBitmap(width + 54, height + 360, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(background)
            drawBitmap(scaled, 27f, 137f, null)
            // Small gallery indicators should not prevent margin detection.
            drawCircle(frame.width / 2f, 22f, 3f, Paint().apply { color = Color.GRAY })
        }
        if (scaled !== marked) scaled.recycle()
        return frame
    }

    @Test fun detectsBlackAndWhiteMarginsOnRectangularImages() {
        val source = image(768, 512)
        try {
            for (background in intArrayOf(Color.BLACK, Color.WHITE)) {
                val frame = screenshot(source, 883, 589, background)
                try {
                    assertEquals(Rect(27, 137, 910, 726), ScreenshotPreprocessor.contentBounds(frame))
                } finally { frame.recycle() }
            }
        } finally { source.recycle() }
    }

    @Test fun smallImageInsideLargeMarginsAndUniformImage() {
        val source = image(240, 160)
        val frame = screenshot(source, 240, 160, Color.BLACK)
        val blank = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        try {
            blank.eraseColor(Color.WHITE)
            assertEquals(Rect(27, 137, 267, 297), ScreenshotPreprocessor.contentBounds(frame))
            assertEquals(Rect(0, 0, 600, 900), ScreenshotPreprocessor.contentBounds(blank))
        } finally { blank.recycle(); frame.recycle(); source.recycle() }
    }

    @Test fun tooSmallImageReportsInsufficientBlocks() {
        val source = image(20, 20)
        try {
            val reader = DctWatermark.Reader(source)
            assertEquals(null, reader.extract())
            assertEquals(DctWatermark.FailureReason.INSUFFICIENT_BLOCKS, reader.bestFailure)
        } finally { source.recycle() }
    }

    @Test fun enlargedJpegScreenshotWithoutOriginalDimensions() =
        checkScreenshot(768, 512, 883, 589, Color.BLACK, 90)

    @Test fun reducedScreenshotWithWhiteMargins() =
        checkScreenshot(896, 640, 762, 544, Color.WHITE, null)

    private fun checkScreenshot(
        width: Int, height: Int, displayedWidth: Int, displayedHeight: Int,
        background: Int, jpegQuality: Int?
    ) {
        val source = image(width, height)
        val marked = DctWatermark.embed(source, payload.toBitArray())
        val frame = screenshot(marked, displayedWidth, displayedHeight, background)
        var input = frame
        try {
            if (jpegQuality != null) {
                val output = ByteArrayOutputStream()
                frame.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
                val bytes = output.toByteArray()
                input = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            assertEquals(payload, DctWatermark.Reader(input).extract {
                Log.i("WatermarkScreenshotTest", it)
            })
        } finally {
            if (input !== frame) input.recycle()
            frame.recycle()
            marked.recycle()
            source.recycle()
        }
    }

    /** Optional local fixtures supplied through adb; private photos are not packaged in the APK. */
    @Test fun suppliedGalleryScreenshot() {
        val directory = InstrumentationRegistry.getArguments().getString("watermarkFixtureDir")
        assumeNotNull(directory)
        val original = BitmapFactory.decodeFile(File(directory!!, "original.png").path)
        val screenshot = BitmapFactory.decodeFile(File(directory, "screenshot.jpg").path)
        assertNotNull("Original fixture could not be loaded", original)
        assertNotNull("Screenshot fixture could not be loaded", screenshot)
        try {
            val expected = DctWatermark.extract(original)
            assertNotNull("Original PNG must decode before comparing the screenshot", expected)
            assertEquals(Rect(0, 360, 1200, 1560), ScreenshotPreprocessor.contentBounds(screenshot))
            assertEquals(expected, DctWatermark.Reader(screenshot).extract {
                Log.i("WatermarkScreenshotTest", it)
            })
        } finally { original.recycle(); screenshot.recycle() }
    }
}
