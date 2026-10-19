package com.demo.watermarktracedemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

class QuadSelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var bitmap: Bitmap? = null
    private val points = mutableListOf<Pair<Float, Float>>()
    private var candidateRegions: List<FloatArray> = emptyList()
    private val imageMatrix = Matrix()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        strokeWidth = 2f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 110, 230)
        strokeWidth = 3f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 110, 230)
        textSize = 18f * resources.displayMetrics.density
    }
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var movingIndex = -1

    fun setBitmap(value: Bitmap?) {
        bitmap = value
        points.clear()
        candidateRegions = emptyList()
        updateTransform()
        invalidate()
    }

    fun setCandidateRegions(candidates: List<FloatArray>) {
        candidateRegions = candidates.filter { it.size == 8 }.map { it.copyOf() }
        invalidate()
    }

    fun corners(): FloatArray? {
        if (points.size != 4) return null
        return FloatArray(8) { index ->
            if (index % 2 == 0) points[index / 2].first else points[index / 2].second
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTransform()
    }

    private fun updateTransform() {
        val image = bitmap ?: return
        if (width == 0 || height == 0) return
        scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
        offsetX = (width - image.width * scale) / 2f
        offsetY = (height - image.height * scale) / 2f
        imageMatrix.reset()
        imageMatrix.setScale(scale, scale)
        imageMatrix.postTranslate(offsetX, offsetY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        canvas.drawBitmap(image, imageMatrix, bitmapPaint)
        candidateRegions.forEach { corners ->
            for (index in 0 until 4) {
                val next = (index + 1) % 4
                canvas.drawLine(
                    offsetX + corners[index * 2] * scale,
                    offsetY + corners[index * 2 + 1] * scale,
                    offsetX + corners[next * 2] * scale,
                    offsetY + corners[next * 2 + 1] * scale,
                    candidatePaint
                )
            }
        }
        val radius = 10f * resources.displayMetrics.density
        points.forEachIndexed { index, point ->
            val x = offsetX + point.first * scale
            val y = offsetY + point.second * scale
            canvas.drawCircle(x, y, radius, markerPaint)
            canvas.drawText((index + 1).toString(), x + radius, y - radius, labelPaint)
            if (index > 0) {
                val previous = points[index - 1]
                canvas.drawLine(
                    offsetX + previous.first * scale,
                    offsetY + previous.second * scale,
                    x, y, markerPaint
                )
            }
        }
        if (points.size == 4) {
            val first = points[0]
            val last = points[3]
            canvas.drawLine(
                offsetX + last.first * scale, offsetY + last.second * scale,
                offsetX + first.first * scale, offsetY + first.second * scale,
                markerPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val image = bitmap ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val radius = 28f * resources.displayMetrics.density
                movingIndex = if (points.size == 4) {
                    points.indices.minByOrNull { index ->
                        val point = points[index]
                        hypot(
                            event.x - (offsetX + point.first * scale),
                            event.y - (offsetY + point.second * scale)
                        )
                    }?.takeIf { index ->
                        val point = points[index]
                        hypot(
                            event.x - (offsetX + point.first * scale),
                            event.y - (offsetY + point.second * scale)
                        ) <= radius
                    } ?: -1
                } else {
                    -1
                }
                if (movingIndex < 0) {
                    val imageX = (event.x - offsetX) / scale
                    val imageY = (event.y - offsetY) / scale
                    val candidate = candidateRegions.firstOrNull {
                        containsPoint(it, imageX, imageY)
                    }
                    if (candidate != null) {
                        points.clear()
                        for (index in 0 until 4) {
                            points.add(candidate[index * 2] to candidate[index * 2 + 1])
                        }
                        invalidate()
                        return true
                    }
                    if (points.size == 4) points.clear()
                    points.add(0f to 0f)
                    movingIndex = points.lastIndex
                }
                updatePoint(event, image)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (movingIndex >= 0) updatePoint(event, image)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (movingIndex >= 0) updatePoint(event, image)
                movingIndex = -1
                performClick()
                return true
            }
        }
        return true
    }

    private fun updatePoint(event: MotionEvent, image: Bitmap) {
        val x = ((event.x - offsetX) / scale).coerceIn(0f, image.width.toFloat())
        val y = ((event.y - offsetY) / scale).coerceIn(0f, image.height.toFloat())
        points[movingIndex] = x to y
        invalidate()
    }

    private fun containsPoint(corners: FloatArray, x: Float, y: Float): Boolean {
        var inside = false
        var previous = 3
        for (index in 0 until 4) {
            val x1 = corners[index * 2]
            val y1 = corners[index * 2 + 1]
            val x2 = corners[previous * 2]
            val y2 = corners[previous * 2 + 1]
            if ((y1 > y) != (y2 > y) && x < (x2 - x1) * (y - y1) / (y2 - y1) + x1) {
                inside = !inside
            }
            previous = index
        }
        return inside
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
