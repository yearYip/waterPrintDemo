package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.Color

object HalfToneWatermark {
    // 嵌入半色调抖动隐形水印，修改像素RGB最低位
    fun embed(originBmp: Bitmap, bits: BooleanArray): Bitmap {
        require(bits.isNotEmpty())
        val w = originBmp.width
        val h = originBmp.height
        val out = originBmp.copy(Bitmap.Config.ARGB_8888, true)
        val rowsPerPayload = (bits.size + w - 1) / w

        for(y in 0 until h){
            for(x in 0 until w){
                val bitIndex = (y % rowsPerPayload) * w + x
                if (bitIndex >= bits.size) break
                val pixel = out.getPixel(x,y)
                var r = Color.red(pixel)
                var g = Color.green(pixel)
                var b = Color.blue(pixel)
                val targetBit = bits[bitIndex]

                // 修改蓝色通道最低1bit，人眼最不敏感
                b = if(targetBit){
                    if(b%2 ==0) b+1 else b
                }else{
                    if(b%2 ==1) b-1 else b
                }
                val newPixel = Color.argb(Color.alpha(pixel), r,g,b)
                out.setPixel(x,y, newPixel)
            }
        }
        return out
    }

    // 提取水印bit流
    fun extract(bmp: Bitmap, maxReadBits:Int): BooleanArray {
        val w = bmp.width
        val h = bmp.height
        val bits = mutableListOf<Boolean>()
        for(y in 0 until h){
            for(x in 0 until w){
                if(bits.size >= maxReadBits) break
                val pixel = bmp.getPixel(x,y)
                val b = Color.blue(pixel)
                bits.add(b%2 == 1)
            }
            if(bits.size >= maxReadBits) break
        }
        return bits.toBooleanArray()
    }

    fun findInScreenshot(bmp: Bitmap): WatermarkPayload? {
        val magic = 0x574d4b31 // "WMK1", MSB first
        for (y in 0 until bmp.height) {
            var window = 0
            for (x in 0 until bmp.width) {
                window = (window shl 1) or (Color.blue(bmp.getPixel(x, y)) and 1)
                if (x < 31 || window != magic) continue
                val startX = x - 31
                if (startX + WatermarkPayload.HEADER_BITS > bmp.width) continue
                val header = extractRow(bmp, startX, y, WatermarkPayload.HEADER_BITS)
                val bitCount = WatermarkPayload.requiredBitCount(header) ?: continue
                if (bitCount > FrequencyWatermark.MAX_PAYLOAD_BITS) continue
                if (bitCount <= bmp.width - startX) {
                    val payload = WatermarkPayload.fromBitArray(extractRow(bmp, startX, y, bitCount))
                    if (payload != null) return payload
                }
                // A narrow tile stores one complete message over several rows.
                val maxWidth = minOf(800, bmp.width - startX, bitCount - 1)
                for (tileWidth in WatermarkPayload.HEADER_BITS..maxWidth) {
                    if (y + (bitCount + tileWidth - 1) / tileWidth > bmp.height) continue
                    val wrapped = BooleanArray(bitCount) { index ->
                        (Color.blue(bmp.getPixel(
                            startX + index % tileWidth, y + index / tileWidth
                        )) and 1) == 1
                    }
                    WatermarkPayload.fromBitArray(wrapped)?.let { return it }
                }
            }
        }
        return null
    }

    private fun extractRow(bmp: Bitmap, startX: Int, y: Int, count: Int): BooleanArray =
        BooleanArray(count) { index ->
            (Color.blue(bmp.getPixel(startX + index, y)) and 1) == 1
        }
}
