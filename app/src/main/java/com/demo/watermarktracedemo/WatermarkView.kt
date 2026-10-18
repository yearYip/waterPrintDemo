package com.demo.watermarktracedemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class WatermarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var watermarkBitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(210, 210, 210)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }

    fun setWatermarkBitmap(bitmap: Bitmap?) {
        watermarkBitmap = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = watermarkBitmap ?: return
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val destination = RectF(0f, 0f, bitmap.width * scale, bitmap.height * scale)
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
        canvas.drawRect(destination, borderPaint)
    }
}
