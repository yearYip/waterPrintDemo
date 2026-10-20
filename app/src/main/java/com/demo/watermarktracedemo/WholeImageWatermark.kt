package com.demo.watermarktracedemo

import android.graphics.Bitmap

/** Writes the repeated DCT carrier over the complete image. */
object WholeImageWatermark {
    fun embed(source: Bitmap, bits: BooleanArray, onProgress: (String) -> Unit = {}): Bitmap {
        onProgress("正在对整张图片写入重复 DCT 水印…")
        return DctWatermark.embed(source, bits)
    }
}
