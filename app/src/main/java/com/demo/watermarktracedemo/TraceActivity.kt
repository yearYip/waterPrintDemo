package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
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
import java.util.Locale

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
        val startedAt = SystemClock.elapsedRealtime()
        activeJob?.cancel()
        videoUri = null
        videoDurationMs = 0L
        hideAlignment()
        activeJob = lifecycleScope.launch {
            tvResult.text = "正在读取图片…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = loadBitmap(uri)
                    try {
                        bitmap to decodeAutomatic(bitmap, ::showProgress)
                    } catch (error: Exception) {
                        bitmap.recycle()
                        throw error
                    }
                }
            }
            result.fold(
                onSuccess = { (bitmap, decoded) ->
                    if (decoded.payload != null) {
                        bitmap.recycle()
                        showPayload(decoded.payload, startedAt)
                    } else if (decoded.noCompleteRegion) {
                        bitmap.recycle()
                        tvResult.text = noRegionMessage(startedAt)
                    } else {
                        showAlignment(bitmap, "自动解析未成功（用时 ${elapsedText(startedAt)}）。请选取一个完整水印区域的四角。")
                    }
                },
                onFailure = { tvResult.text = "解析失败（用时 ${elapsedText(startedAt)}）：${it.message ?: "未知错误"}" }
            )
        }
    }

    private fun loadVideo(uri: Uri) {
        val startedAt = SystemClock.elapsedRealtime()
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
                        var foundRegion = false
                        for ((index, timeMs) in times.withIndex()) {
                            showProgress("正在抽取录屏画面 ${index + 1}/${times.size}…")
                            val frame = retriever.getFrameAtTime(
                                timeMs * 1000,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                            ) ?: continue
                            if (preview == null || index == times.size / 2) {
                                preview?.recycle()
                                preview = frame
                            }
                            val decoded = decodeAutomatic(frame, ::showProgress)
                            if (!decoded.noCompleteRegion) foundRegion = true
                            if (decoded.payload != null) {
                                if (frame !== preview) frame.recycle()
                                preview?.recycle()
                                return@withRetriever VideoScanResult(decoded.payload, null, durationMs, false)
                            }
                            if (frame !== preview) frame.recycle()
                        }
                        VideoScanResult(
                            null, preview ?: error("无法抽取视频画面"), durationMs, !foundRegion
                        )
                    }
                }
            }
            result.fold(
                onSuccess = { scan ->
                    videoDurationMs = scan.durationMs
                    if (scan.payload != null) showPayload(scan.payload, startedAt)
                    else if (scan.noCompleteRegion) {
                        scan.frame?.recycle()
                        tvResult.text = noRegionMessage(startedAt)
                    } else {
                        videoSeek.progress = 500
                        showAlignment(
                            scan.frame!!,
                            "未在抽样帧中自动识别（用时 ${elapsedText(startedAt)}）。可拖动视频位置，再选取完整区域的四角。"
                        )
                    }
                },
                onFailure = { tvResult.text = "解析失败（用时 ${elapsedText(startedAt)}）：${it.message ?: "未知错误"}" }
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
        val startedAt = SystemClock.elapsedRealtime()
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
                    val first = decodeAligned(preview, corners, ::showProgress)
                    if (first.payload != null || first.noCompleteRegion) return@runCatching first
                    val uri = videoUri ?: return@runCatching first
                    withRetriever(uri) { retriever ->
                        for (timeMs in sampleTimes(retriever)) {
                            val frame = retriever.getFrameAtTime(
                                timeMs * 1000,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                            ) ?: continue
                            val decoded = try {
                                decodeAligned(frame, corners, ::showProgress)
                            } finally {
                                frame.recycle()
                            }
                            if (decoded.payload != null || decoded.noCompleteRegion) {
                                return@withRetriever decoded
                            }
                        }
                        DecodeResult()
                    }
                }
            }
            result.fold(
                onSuccess = { decoded ->
                    if (decoded.payload != null) showPayload(decoded.payload, startedAt)
                    else if (decoded.noCompleteRegion) tvResult.text = noRegionMessage(startedAt)
                    else tvResult.text = "未能通过水印校验（用时 ${elapsedText(startedAt)}）。请框选一个完整区域，避免包含按钮或界面留白。"
                },
                onFailure = { tvResult.text = "校正失败（用时 ${elapsedText(startedAt)}）：${it.message ?: "未知错误"}" }
            )
        }
    }

    private fun decodeAutomatic(bitmap: Bitmap, onProgress: (String) -> Unit): DecodeResult {
        onProgress("正在读取水印数据…")
        // Exact PNG watermarks need no screenshot localization or frequency search.
        val header = HalfToneWatermark.extract(bitmap, WatermarkPayload.HEADER_BITS)
        val bitCount = WatermarkPayload.requiredBitCount(header)
        if (bitCount != null && bitCount.toLong() <= bitmap.width.toLong() * bitmap.height) {
            WatermarkPayload.fromBitArray(HalfToneWatermark.extract(bitmap, bitCount))?.let {
                return DecodeResult(it)
            }
        }
        val regions = MultiRegionWatermark.regions(bitmap.width, bitmap.height)
        if (regions.size > 1) {
            val first = regions.first()
            decodeExactPngRegion(bitmap, floatArrayOf(
                first.left.toFloat(), first.top.toFloat(),
                first.right.toFloat(), first.top.toFloat(),
                first.right.toFloat(), first.bottom.toFloat(),
                first.left.toFloat(), first.bottom.toFloat()
            ))?.let { return DecodeResult(it) }
        }
        val reader = FrequencyWatermark.Reader(bitmap)
        return decodeLocated(bitmap, reader, onProgress)
    }

    private fun decodeLocated(
        bitmap: Bitmap,
        reader: FrequencyWatermark.Reader,
        onProgress: (String) -> Unit,
        manualCorners: FloatArray? = null
    ): DecodeResult {
        onProgress("正在寻找水印角点…")
        var earlyPayload: WatermarkPayload? = null
        val candidates = CornerLocator.candidates(bitmap) { corners ->
            onProgress("已找到候选区域，正在校验水印…")
            earlyPayload = decodeExactPngRegion(bitmap, corners)
                ?: reader.extract(corners, refine = false)
            earlyPayload != null
        }
        earlyPayload?.let { return DecodeResult(it) }
        if (candidates.isEmpty()) return DecodeResult(noCompleteRegion = true)
        onProgress("正在校正候选区域…")
        if (manualCorners != null) {
            reader.extract(manualCorners)?.let { return DecodeResult(it) }
        }
        for (corners in candidates) {
            decodeExactPngRegion(bitmap, corners)?.let { return DecodeResult(it) }
            reader.extract(corners, refine = false)?.let { return DecodeResult(it) }
        }
        for (corners in candidates.take(4)) {
            reader.extract(corners)?.let { return DecodeResult(it) }
        }
        return DecodeResult()
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

    private fun decodeAligned(
        source: Bitmap,
        corners: FloatArray,
        onProgress: (String) -> Unit
    ): DecodeResult {
        onProgress("正在校正并验证选中区域…")
        decodeExactPngRegion(source, corners)?.let { return DecodeResult(it) }
        val reader = FrequencyWatermark.Reader(source)
        return decodeLocated(source, reader, onProgress, corners)
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

    private fun showProgress(message: String) {
        tvResult.post {
            if (activeJob?.isActive == true) tvResult.text = message
        }
    }

    private fun showPayload(payload: WatermarkPayload, startedAt: Long) {
        hideAlignment()
        tvResult.text =
            "水印解析成功（用时 ${elapsedText(startedAt)}）\n用户 ID: ${payload.userId}\n设备 ID: ${payload.deviceId}\n时间戳: ${payload.timestamp}"
    }

    private fun noRegionMessage(startedAt: Long): String =
        "未找到包含四个角点的完整水印区域，已停止解析（用时 ${elapsedText(startedAt)}）。请选择保留完整区域的局部图片。"

    private fun elapsedText(startedAt: Long): String = String.format(
        Locale.getDefault(), "%.1f 秒", (SystemClock.elapsedRealtime() - startedAt) / 1000.0
    )

    private companion object {
        const val MAX_IMAGE_EDGE = 4096
    }

    private data class VideoScanResult(
        val payload: WatermarkPayload?,
        val frame: Bitmap?,
        val durationMs: Long,
        val noCompleteRegion: Boolean
    )

    private data class DecodeResult(
        val payload: WatermarkPayload? = null,
        val noCompleteRegion: Boolean = false
    )
}
