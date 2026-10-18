package com.demo.watermarktracedemo

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Test

class SpatialWatermarkTest {
    @Test
    fun decodesWithBrightnessGradientAndNoise() {
        val original = WatermarkPayload("alice", "1234567890abcdef", 1720000000000L)
        val luma = SpatialWatermark.renderLuma(original.toBitArray())
        for (index in luma.indices) {
            val x = index % SpatialWatermark.WIDTH
            val y = index / SpatialWatermark.WIDTH
            val gradient = x * 9 / SpatialWatermark.WIDTH +
                y * 7 / SpatialWatermark.HEIGHT
            val noise = (index * 17 % 5) - 2
            luma[index] = (luma[index] - gradient + noise).coerceIn(0, 255)
        }
        assertEquals(original, SpatialWatermark.decodeLuma(luma))
    }

    @Test
    fun correctsOneWrongCellPairPerHammingBlock() {
        val original = WatermarkPayload("bob", "1234567890abcdef", 1720000000000L)
        val luma = SpatialWatermark.renderLuma(original.toBitArray())
        for (block in 0 until 20) {
            val pairIndex = block * 15 + 3
            val row = pairIndex / 30
            val pair = pairIndex % 30
            for (y in row * 16 until (row + 1) * 16) {
                for (x in pair * 24 until (pair + 1) * 24) {
                    val index = y * SpatialWatermark.WIDTH + x
                    luma[index] = if (luma[index] < 253) 255 else 250
                }
            }
        }
        assertEquals(original, SpatialWatermark.decodeLuma(luma))
    }

    @Test
    fun survivesModerateResizeAndJpegCompression() {
        val original = WatermarkPayload("alice", "1234567890abcdef", 1720000000000L)
        val luma = SpatialWatermark.renderLuma(original.toBitArray())
        val source = BufferedImage(SpatialWatermark.WIDTH, SpatialWatermark.HEIGHT, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until SpatialWatermark.HEIGHT) {
            for (x in 0 until SpatialWatermark.WIDTH) {
                val value = luma[y * SpatialWatermark.WIDTH + x]
                source.setRGB(x, y, (value shl 16) or (value shl 8) or value)
            }
        }
        val smaller = BufferedImage(540, 300, BufferedImage.TYPE_INT_RGB)
        smaller.createGraphics().use { graphics ->
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(source, 0, 0, 540, 300, null)
        }
        val buffer = ByteArrayOutputStream()
        ImageIO.write(smaller, "jpg", buffer)
        val compressed = ImageIO.read(ByteArrayInputStream(buffer.toByteArray()))
        val restored = BufferedImage(SpatialWatermark.WIDTH, SpatialWatermark.HEIGHT, BufferedImage.TYPE_INT_RGB)
        restored.createGraphics().use { graphics ->
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(compressed, 0, 0, SpatialWatermark.WIDTH, SpatialWatermark.HEIGHT, null)
        }
        val result = IntArray(luma.size) { index ->
            val pixel = restored.getRGB(index % SpatialWatermark.WIDTH, index / SpatialWatermark.WIDTH)
            pixel and 0xff
        }
        assertEquals(original, SpatialWatermark.decodeLuma(result))
    }

    private inline fun <T : java.awt.Graphics2D, R> T.use(block: (T) -> R): R {
        try {
            return block(this)
        } finally {
            dispose()
        }
    }
}
