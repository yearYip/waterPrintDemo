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
                    } else {
                        val message = if (decoded.noCompleteRegion) {
                            "图片未能读出水印（用时 ${elapsedText(startedAt)}）。请手动选取带水印图片区域的四角。"
                        } else {
                            "未能解析出有效水印（用时 ${elapsedText(startedAt)}）。${diagnosticText(decoded.frequencyFailure)}请点选四角，可拖动蓝点调整。"
                        }
                        showAlignment(bitmap, message, decoded.candidateRegions)
                    }
                },
                onFailure = { tvResult.text = "图片读取或处理出错（用时 ${elapsedText(startedAt)}）：${it.message ?: "未知错误"}" }
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
                        var previewCandidates: List<FloatArray> = emptyList()
                        var previewTimeMs = 0L
                        var foundRegion = false
                        var frequencyFailure: DctWatermark.FailureReason? = null
                        for ((index, timeMs) in times.withIndex()) {
                            showProgress("正在抽取录屏画面 ${index + 1}/${times.size}…")
                            val frame = retriever.getFrameAtTime(
                                timeMs * 1000,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                            ) ?: continue
                            val decoded = decodeAutomatic(frame, ::showProgress)
                            if (!decoded.noCompleteRegion) foundRegion = true
                            frequencyFailure = strongerFailure(frequencyFailure, decoded.frequencyFailure)
                            if (decoded.payload != null) {
                                frame.recycle()
                                preview?.recycle()
                                return@withRetriever VideoScanResult(decoded.payload, null, durationMs, false)
                            }
                            val keepAsPreview = preview == null ||
                                (decoded.candidateRegions.isNotEmpty() && previewCandidates.isEmpty()) ||
                                (index == times.size / 2 && previewCandidates.isEmpty())
                            if (keepAsPreview) {
                                preview?.recycle()
                                preview = frame
                                previewCandidates = decoded.candidateRegions
                                previewTimeMs = timeMs
                            } else {
                                frame.recycle()
                            }
                        }
                        VideoScanResult(
                            null, preview ?: error("无法抽取视频画面"), durationMs, !foundRegion,
                            previewCandidates, previewTimeMs, frequencyFailure
                        )
                    }
                }
            }
            result.fold(
                onSuccess = { scan ->
                    videoDurationMs = scan.durationMs
                    if (scan.payload != null) showPayload(scan.payload, startedAt)
                    else {
                        videoSeek.progress = if (scan.durationMs > 0L) {
                            (scan.previewTimeMs.toDouble() / scan.durationMs * 1000)
                                .roundToInt().coerceIn(0, 1000)
                        } else 0
                        val message = if (scan.noCompleteRegion) {
                            "抽样帧未读出水印（用时 ${elapsedText(startedAt)}）。可拖动视频位置，再手动选取带水印图片区域的四角。"
                        } else {
                            "抽样帧未能解析出有效水印（用时 ${elapsedText(startedAt)}）。${diagnosticText(scan.frequencyFailure)}请点选四角，可拖动蓝点调整。"
                        }
                        showAlignment(
                            scan.frame!!,
                            message,
                            scan.candidateRegions
                        )
                    }
                },
                onFailure = { tvResult.text = "视频读取或处理出错（用时 ${elapsedText(startedAt)}）：${it.message ?: "未知错误"}" }
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
                    showAlignment(frame, "请按左上、右上、右下、左下顺序选取带水印图片区域的四角。")
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
                    if (first.payload != null) return@runCatching first
                    val uri = videoUri ?: return@runCatching first
                    withRetriever(uri) { retriever ->
                        var foundCandidate = !first.noCompleteRegion
                        var frequencyFailure = first.frequencyFailure
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
                            if (decoded.payload != null) return@withRetriever decoded
                            if (!decoded.noCompleteRegion) foundCandidate = true
                            frequencyFailure = strongerFailure(frequencyFailure, decoded.frequencyFailure)
                        }
                        DecodeResult(
                            noCompleteRegion = !foundCandidate,
                            frequencyFailure = frequencyFailure
                        )
                    }
                }
            }
            result.fold(
                onSuccess = { decoded ->
                    if (decoded.payload != null) showPayload(decoded.payload, startedAt)
                    else if (decoded.noCompleteRegion) {
                        tvResult.text = "选中区域未能读出水印（用时 ${elapsedText(startedAt)}）。${diagnosticText(decoded.frequencyFailure)}请确保四角覆盖带水印的图片区域。"
                    } else {
                        if (videoUri == null) selection.setCandidateRegions(decoded.candidateRegions)
                        tvResult.text = "选中区域未能解析出有效水印（用时 ${elapsedText(startedAt)}）。${diagnosticText(decoded.frequencyFailure)}请调整四角，确保覆盖带水印的图片区域。"
                    }
                },
                onFailure = { tvResult.text = "选中区域处理出错（用时 ${elapsedText(startedAt)}）：${it.message ?: "未知错误"}" }
            )
        }
    }

    private fun decodeAutomatic(bitmap: Bitmap, onProgress: (String) -> Unit): DecodeResult {
        onProgress("正在读取水印数据…")
        val reader = DctWatermark.Reader(bitmap)
        val payload = reader.extract(onProgress = onProgress)
        return DecodeResult(payload = payload, frequencyFailure = reader.bestFailure)
    }
    private fun strongerFailure(
        first: DctWatermark.FailureReason?,
        second: DctWatermark.FailureReason?
    ): DctWatermark.FailureReason? = when {
        first == null -> second
        second == null || first.ordinal >= second.ordinal -> first
        else -> second
    }

    private fun diagnosticText(reason: DctWatermark.FailureReason?): String =
        frequencyFailureText(reason).takeIf { it.isNotEmpty() }?.let { "\n$it\n" } ?: ""

    private fun frequencyFailureText(reason: DctWatermark.FailureReason?): String = when (reason) {
        DctWatermark.FailureReason.INVALID_GEOMETRY ->
            "DCT 诊断：无法建立有效的区域坐标。"
        DctWatermark.FailureReason.INSUFFICIENT_BLOCKS ->
            "DCT 诊断：可用数据块不足。"
        DctWatermark.FailureReason.INVALID_PAYLOAD ->
            "DCT 诊断：水印格式或 CRC 校验未通过。"
        null -> ""
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
        val reader = DctWatermark.Reader(source)
        val payload = reader.extract(corners, onProgress)
        return DecodeResult(payload = payload, frequencyFailure = reader.bestFailure)
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

    private fun showAlignment(
        bitmap: Bitmap,
        message: String,
        candidateRegions: List<FloatArray> = emptyList()
    ) {
        previewBitmap = bitmap
        selection.setBitmap(bitmap)
        selection.setCandidateRegions(candidateRegions)
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
        val noCompleteRegion: Boolean,
        val candidateRegions: List<FloatArray> = emptyList(),
        val previewTimeMs: Long = 0L,
        val frequencyFailure: DctWatermark.FailureReason? = null
    )

    private data class DecodeResult(
        val payload: WatermarkPayload? = null,
        val noCompleteRegion: Boolean = false,
        val candidateRegions: List<FloatArray> = emptyList(),
        val frequencyFailure: DctWatermark.FailureReason? = null
    )

}
