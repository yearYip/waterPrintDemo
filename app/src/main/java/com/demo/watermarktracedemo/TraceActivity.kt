package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class TraceActivity : AppCompatActivity() {
    private lateinit var tvResult: TextView
    private lateinit var alignmentHint: TextView
    private lateinit var selection: QuadSelectionView
    private lateinit var decodeRegionButton: Button
    private lateinit var videoSeek: SeekBar
    private lateinit var videoTime: TextView
    private var previewBitmap: Bitmap? = null
    private var videoUri: Uri? = null
    private var videoDurationMs = 0L
    private var activeJob: Job? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadImage(uri)
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadVideo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trace)
        tvResult = findViewById(R.id.tv_result)
        alignmentHint = findViewById(R.id.tv_alignment_hint)
        selection = findViewById(R.id.quad_selection)
        decodeRegionButton = findViewById(R.id.btn_decode_region)
        videoSeek = findViewById(R.id.seek_video)
        videoTime = findViewById(R.id.tv_video_time)
        findViewById<Button>(R.id.btn_back_main).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_select_img).setOnClickListener {
            pickImage.launch("image/*")
        }
        findViewById<Button>(R.id.btn_select_video).setOnClickListener {
            pickVideo.launch("video/*")
        }
        decodeRegionButton.setOnClickListener { decodeSelectedRegion() }
        videoSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val seconds = videoDurationMs * progress / 1000 / 1000
                videoTime.text = "视频位置：${seconds} 秒。拖动后松开以载入该帧。"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                loadVideoFrameAt(videoDurationMs * seekBar.progress / 1000)
            }
        })
    }

    private fun loadImage(uri: Uri) {
        activeJob?.cancel()
        videoUri = null
        videoDurationMs = 0L
        hideAlignment()
        activeJob = lifecycleScope.launch {
            tvResult.text = "正在解析图片…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = loadBitmap(uri)
                    bitmap to decodeAutomatic(bitmap)
                }
            }
            result.fold(
                onSuccess = { (bitmap, payload) ->
                    if (payload != null) {
                        bitmap.recycle()
                        showPayload(payload)
                    }
                    else showAlignment(bitmap, "自动解析未成功。请按左上、右上、右下、左下顺序选取整张图片或一个完整水印区域的四角。")
                },
                onFailure = { tvResult.text = "图片读取失败：${it.message ?: "未知错误"}" }
            )
        }
    }

    private fun loadVideo(uri: Uri) {
        activeJob?.cancel()
        videoUri = uri
        hideAlignment()
        activeJob = lifecycleScope.launch {
            tvResult.text = "正在抽取录屏画面…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    withRetriever(uri) { retriever ->
                        val times = sampleTimes(retriever)
                        val durationMs = videoDuration(retriever)
                        var preview: Bitmap? = null
                        for ((index, timeMs) in times.withIndex()) {
                            val frame = retriever.getFrameAtTime(
                                timeMs * 1000,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                            ) ?: continue
                            if (preview == null || index == times.size / 2) {
                                preview?.recycle()
                                preview = frame
                            }
                            val payload = decodeAutomatic(frame)
                            if (payload != null) {
                                if (frame !== preview) frame.recycle()
                                preview?.recycle()
                                return@withRetriever VideoScanResult(payload, null, durationMs)
                            }
                            if (frame !== preview) frame.recycle()
                        }
                        VideoScanResult(null, preview ?: error("无法抽取视频画面"), durationMs)
                    }
                }
            }
            result.fold(
                onSuccess = { scan ->
                    videoDurationMs = scan.durationMs
                    if (scan.payload != null) showPayload(scan.payload)
                    else {
                        videoSeek.progress = 500
                        showAlignment(
                            scan.frame!!,
                            "未在抽样帧中自动识别。可拖动视频位置，再按顺序选取整张图片或一个完整水印区域的四角。"
                        )
                    }
                },
                onFailure = { tvResult.text = "视频读取失败：${it.message ?: "未知错误"}" }
            )
        }
    }

    private fun loadVideoFrameAt(timeMs: Long) {
        val uri = videoUri ?: return
        activeJob?.cancel()
        activeJob = lifecycleScope.launch {
            tvResult.text = "正在载入视频画面…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    withRetriever(uri) { retriever ->
                        retriever.getFrameAtTime(
                            timeMs * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        ) ?: error("无法读取该帧")
                    }
                }
            }
            result.fold(
                onSuccess = { frame ->
                    hideAlignment()
                    showAlignment(frame, "请按左上、右上、右下、左下顺序选取整张图片或一个完整水印区域的四角。")
                },
                onFailure = { tvResult.text = "载入帧失败：${it.message ?: "未知错误"}" }
            )
        }
    }

    private fun decodeSelectedRegion() {
        val corners = selection.corners()
        val preview = previewBitmap
        if (corners == null || preview == null) {
            tvResult.text = "请依次点选四个角，再点击解析。"
            return
        }
        activeJob?.cancel()
        activeJob = lifecycleScope.launch {
            tvResult.text = "正在校正并解析…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    decodeAligned(preview, corners)?.let { return@runCatching it }
                    val uri = videoUri ?: return@runCatching null
                    withRetriever(uri) { retriever ->
                        for (timeMs in sampleTimes(retriever)) {
                            val frame = retriever.getFrameAtTime(
                                timeMs * 1000,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                            ) ?: continue
                            val payload = try {
                                decodeAligned(frame, corners)
                            } finally {
                                frame.recycle()
                            }
                            if (payload != null) return@withRetriever payload
                        }
                        null
                    }
                }
            }
            result.fold(
                onSuccess = { payload ->
                    if (payload != null) showPayload(payload)
                    else tvResult.text = "未能通过水印校验。请框选整张图片或一个完整水印区域，避免包含按钮和界面留白；若裁剪切断了所有完整区域，或拍屏失焦，可能无法恢复。"
                },
                onFailure = { tvResult.text = "校正失败：${it.message ?: "未知错误"}" }
            )
        }
    }

    private fun decodeAutomatic(bitmap: Bitmap): WatermarkPayload? {
        // Exact PNG watermarks need no screenshot localization or frequency search.
        val header = HalfToneWatermark.extract(bitmap, WatermarkPayload.HEADER_BITS)
        val bitCount = WatermarkPayload.requiredBitCount(header)
        if (bitCount != null && bitCount.toLong() <= bitmap.width.toLong() * bitmap.height) {
            WatermarkPayload.fromBitArray(HalfToneWatermark.extract(bitmap, bitCount))?.let { return it }
        }
        val reader = FrequencyWatermark.Reader(bitmap)
        reader.extract(refine = false)?.let { return it }
        // Thumbnails only propose image coordinates. The message is decoded from the screenshot.
        val located = ScreenshotLocator.candidates(this, bitmap)
        for (corners in located) {
            decodeExactPngRegion(bitmap, corners)?.let { return it }
            reader.extract(corners)?.let { return it }
        }
        located.firstOrNull()?.let { corners ->
            MultiRegionWatermark.extractFromBounds(reader, corners)?.let { return it }
        }
        MultiRegionWatermark.extractFromBounds(
            reader, floatArrayOf(
                0f, 0f, bitmap.width.toFloat(), 0f,
                bitmap.width.toFloat(), bitmap.height.toFloat(),
                0f, bitmap.height.toFloat()
            )
        )?.let { return it }
        if (bitmap.width == SpatialWatermark.WIDTH && bitmap.height == SpatialWatermark.HEIGHT) {
            SpatialWatermark.decode(bitmap)?.let { return it }
        }
        val pixelCount = bitmap.width.toLong() * bitmap.height
        if (pixelCount <= MAX_SPATIAL_PIXELS) {
            SpatialWatermark.findFramedPanel(bitmap)?.let { return it }
            SpatialWatermark.decodeScaled(bitmap)?.let { return it }
        }
        if (pixelCount > MAX_AUTOMATIC_PIXELS) return null
        return HalfToneWatermark.findInScreenshot(bitmap)
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = contentResolver.openInputStream(uri) ?: error("无法读取图片")
        boundsStream.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) error("图片格式不受支持")
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > MAX_IMAGE_EDGE) {
            options.inSampleSize *= 2
        }
        return contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: error("无法解码图片")
    }

    private fun decodeAligned(source: Bitmap, corners: FloatArray): WatermarkPayload? {
        decodeExactPngRegion(source, corners)?.let { return it }
        FrequencyWatermark.extract(source, corners)?.let { return it }
        MultiRegionWatermark.extractFromBounds(FrequencyWatermark.Reader(source), corners)?.let { return it }
        val target = floatArrayOf(
            0f, 0f,
            SpatialWatermark.WIDTH.toFloat(), 0f,
            SpatialWatermark.WIDTH.toFloat(), SpatialWatermark.HEIGHT.toFloat(),
            0f, SpatialWatermark.HEIGHT.toFloat()
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(corners, 0, target, 0, 4)) return null
        val corrected = Bitmap.createBitmap(
            SpatialWatermark.WIDTH, SpatialWatermark.HEIGHT, Bitmap.Config.ARGB_8888
        )
        corrected.eraseColor(Color.WHITE)
        Canvas(corrected).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return try {
            SpatialWatermark.decode(corrected)
                ?: SpatialWatermark.decodeIncludingFrame(corrected)
                ?: decodeAlignedTiled(source, corners)
        } finally {
            corrected.recycle()
        }
    }

    private fun decodeExactPngRegion(source: Bitmap, corners: FloatArray): WatermarkPayload? {
        if (corners.size != 8) return null
        val left = corners[0].roundToInt().coerceIn(0, source.width - 1)
        val top = corners[1].roundToInt().coerceIn(0, source.height - 1)
        val right = corners[4].roundToInt().coerceIn(left + 1, source.width)
        val bottom = corners[5].roundToInt().coerceIn(top + 1, source.height)
        if (right - left < 48 || bottom - top < 1) return null
        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        return try {
            val header = HalfToneWatermark.extract(crop, WatermarkPayload.HEADER_BITS)
            val count = WatermarkPayload.requiredBitCount(header) ?: return null
            if (count > crop.width.toLong() * crop.height) return null
            WatermarkPayload.fromBitArray(HalfToneWatermark.extract(crop, count))
        } finally {
            if (crop !== source) crop.recycle()
        }
    }

    private fun decodeAlignedTiled(source: Bitmap, corners: FloatArray): WatermarkPayload? {
        val target = floatArrayOf(
            0f, 0f,
            (SpatialWatermark.WIDTH * 2).toFloat(), 0f,
            (SpatialWatermark.WIDTH * 2).toFloat(), (SpatialWatermark.HEIGHT * 2).toFloat(),
            0f, (SpatialWatermark.HEIGHT * 2).toFloat()
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(corners, 0, target, 0, 4)) return null
        val corrected = Bitmap.createBitmap(
            SpatialWatermark.WIDTH * 2,
            SpatialWatermark.HEIGHT * 2,
            Bitmap.Config.ARGB_8888
        )
        corrected.eraseColor(Color.WHITE)
        return try {
            Canvas(corrected).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            SpatialWatermark.decodeScaled(corrected)
        } finally {
            corrected.recycle()
        }
    }

    private inline fun <T> withRetriever(uri: Uri, block: (MediaMetadataRetriever) -> T): T {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            return block(retriever)
        } finally {
            retriever.release()
        }
    }

    private fun sampleTimes(retriever: MediaMetadataRetriever): List<Long> {
        val durationMs = videoDuration(retriever)
        return listOf(0L, durationMs / 4, durationMs / 2, durationMs * 3 / 4, durationMs - 1)
            .map { it.coerceAtLeast(0L) }
            .distinct()
    }

    private fun videoDuration(retriever: MediaMetadataRetriever): Long =
        retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

    private fun showAlignment(bitmap: Bitmap, message: String) {
        previewBitmap = bitmap
        selection.setBitmap(bitmap)
        alignmentHint.visibility = View.VISIBLE
        selection.visibility = View.VISIBLE
        decodeRegionButton.visibility = View.VISIBLE
        videoSeek.visibility = if (videoUri != null && videoDurationMs > 0) View.VISIBLE else View.GONE
        videoTime.visibility = videoSeek.visibility
        tvResult.text = message
    }

    private fun hideAlignment() {
        selection.setBitmap(null)
        previewBitmap?.recycle()
        previewBitmap = null
        alignmentHint.visibility = View.GONE
        selection.visibility = View.GONE
        decodeRegionButton.visibility = View.GONE
        videoSeek.visibility = View.GONE
        videoTime.visibility = View.GONE
    }

    private fun showPayload(payload: WatermarkPayload) {
        hideAlignment()
        tvResult.text =
            "水印解析成功\n用户 ID: ${payload.userId}\n设备 ID: ${payload.deviceId}\n时间戳: ${payload.timestamp}"
    }

    private companion object {
        const val MAX_SPATIAL_PIXELS = 8_000_000L
        const val MAX_AUTOMATIC_PIXELS = 3_000_000L
        const val MAX_IMAGE_EDGE = 4000
    }

    private data class VideoScanResult(
        val payload: WatermarkPayload?,
        val frame: Bitmap?,
        val durationMs: Long
    )
}
