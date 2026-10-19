package com.demo.watermarktracedemo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
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
import kotlin.math.ceil
import kotlin.math.floor
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
                            "自动定位失败：未找到候选水印区域（用时 ${elapsedText(startedAt)}）。请手动选取完整区域的四角。"
                        } else {
                            "已找到候选区域，但未能解析出有效水印（用时 ${elapsedText(startedAt)}）。${diagnosticText(decoded.pngFailure, decoded.frequencyFailure)}点击红框选取四角，可拖动蓝点调整。"
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
                        var frequencyFailure: FrequencyWatermark.FailureReason? = null
                        var pngFailure: PngFailure? = null
                        for ((index, timeMs) in times.withIndex()) {
                            showProgress("正在抽取录屏画面 ${index + 1}/${times.size}…")
                            val frame = retriever.getFrameAtTime(
                                timeMs * 1000,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                            ) ?: continue
                            val decoded = decodeAutomatic(frame, ::showProgress)
                            if (!decoded.noCompleteRegion) foundRegion = true
                            frequencyFailure = strongerFailure(frequencyFailure, decoded.frequencyFailure)
                            pngFailure = strongerPngFailure(pngFailure, decoded.pngFailure)
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
                            previewCandidates, previewTimeMs, frequencyFailure, pngFailure
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
                            "自动定位失败：抽样帧中未找到候选水印区域（用时 ${elapsedText(startedAt)}）。可拖动视频位置，再手动选取完整区域的四角。"
                        } else {
                            "抽样帧中已找到候选区域，但未能解析出有效水印（用时 ${elapsedText(startedAt)}）。${diagnosticText(scan.pngFailure, scan.frequencyFailure)}点击红框选取四角，可拖动蓝点调整。"
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
                    if (first.payload != null) return@runCatching first
                    val uri = videoUri ?: return@runCatching first
                    withRetriever(uri) { retriever ->
                        var foundCandidate = !first.noCompleteRegion
                        var frequencyFailure = first.frequencyFailure
                        var pngFailure = first.pngFailure
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
                            pngFailure = strongerPngFailure(pngFailure, decoded.pngFailure)
                        }
                        DecodeResult(
                            noCompleteRegion = !foundCandidate,
                            frequencyFailure = frequencyFailure,
                            pngFailure = pngFailure
                        )
                    }
                }
            }
            result.fold(
                onSuccess = { decoded ->
                    if (decoded.payload != null) showPayload(decoded.payload, startedAt)
                    else if (decoded.noCompleteRegion) {
                        tvResult.text = "蓝框内未找到候选水印区域，也未能直接读取水印（用时 ${elapsedText(startedAt)}）。${diagnosticText(decoded.pngFailure, decoded.frequencyFailure)}请扩大蓝框，确保包含一个完整区域。"
                    } else {
                        if (videoUri == null) selection.setCandidateRegions(decoded.candidateRegions)
                        tvResult.text = "蓝框内已找到候选区域，但未能解析出有效水印（用时 ${elapsedText(startedAt)}）。${diagnosticText(decoded.pngFailure, decoded.frequencyFailure)}请调整四角，或尝试其他完整区域。"
                    }
                },
                onFailure = { tvResult.text = "选中区域处理出错（用时 ${elapsedText(startedAt)}）：${it.message ?: "未知错误"}" }
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
        return decodeLocated(bitmap, onProgress)
    }

    private fun decodeLocated(
        bitmap: Bitmap,
        onProgress: (String) -> Unit,
        manualCorners: FloatArray? = null
    ): DecodeResult {
        val reader = FrequencyWatermark.Reader(bitmap)
        if (manualCorners != null) {
            onProgress("正在校正选中区域…")
            reader.extract(manualCorners)?.let { return DecodeResult(it) }
        }
        val bounds = if (manualCorners != null) {
            selectedBounds(bitmap, manualCorners) ?: return DecodeResult(
                noCompleteRegion = true,
                frequencyFailure = FrequencyWatermark.FailureReason.INVALID_GEOMETRY
            )
        } else {
            Rect(0, 0, bitmap.width, bitmap.height)
        }
        onProgress(if (manualCorners == null) "正在寻找水印角点…" else "正在蓝框内寻找水印角点…")
        var earlyPayload: WatermarkPayload? = null
        var pngFailure: PngFailure? = null
        fun tryPng(corners: FloatArray): WatermarkPayload? {
            val result = readExactPngRegion(bitmap, corners)
            pngFailure = strongerPngFailure(pngFailure, result.failure)
            return result.payload
        }
        val searchBitmap = if (manualCorners == null) bitmap else Bitmap.createBitmap(
            bitmap, bounds.left, bounds.top, bounds.width(), bounds.height()
        )
        fun toSourceCorners(localCorners: FloatArray): FloatArray = FloatArray(localCorners.size) { index ->
            localCorners[index] + (if (index % 2 == 0) bounds.left else bounds.top).toFloat()
        }
        fun findCandidates(maxSearchEdge: Int): List<FloatArray> =
            CornerLocator.candidates(searchBitmap, maxSearchEdge) { localCorners ->
                val corners = toSourceCorners(localCorners)
                if (manualCorners != null && !candidateCenterInside(corners, manualCorners)) {
                    false
                } else {
                    onProgress("已找到候选区域，正在校验水印…")
                    earlyPayload = tryPng(corners)
                        ?: reader.extract(corners, refine = false)
                    earlyPayload != null
                }
            }.map(::toSourceCorners).filter { corners ->
                manualCorners == null || candidateCenterInside(corners, manualCorners)
            }
        val candidates = try {
            var found = findCandidates(CornerLocator.FAST_SEARCH_EDGE)
            if (found.isEmpty() && earlyPayload == null &&
                maxOf(searchBitmap.width, searchBitmap.height) > CornerLocator.FAST_SEARCH_EDGE
            ) {
                onProgress("常规定位未找到候选区域，正在高清重试…")
                found = findCandidates(CornerLocator.RETRY_SEARCH_EDGE)
            }
            found
        } finally {
            if (searchBitmap !== bitmap) searchBitmap.recycle()
        }
        earlyPayload?.let { return DecodeResult(it) }
        if (candidates.isEmpty()) return DecodeResult(
            noCompleteRegion = true,
            frequencyFailure = reader.bestFailure,
            pngFailure = pngFailure
        )
        onProgress("正在校正候选区域…")
        for (corners in candidates) {
            tryPng(corners)?.let { return DecodeResult(it) }
            reader.extract(corners, refine = false)?.let { return DecodeResult(it) }
        }
        val refinedCandidates = if (manualCorners == null) candidates.take(4) else candidates
        for ((index, corners) in refinedCandidates.withIndex()) {
            onProgress("正在校正候选区域 ${index + 1}/${refinedCandidates.size}…")
            reader.extract(corners)?.let { return DecodeResult(it) }
        }
        return DecodeResult(
            candidateRegions = candidates,
            frequencyFailure = reader.bestFailure,
            pngFailure = pngFailure
        )
    }

    private fun selectedBounds(bitmap: Bitmap, corners: FloatArray): Rect? {
        if (corners.size != 8 || corners.any { !it.isFinite() }) return null
        val left = floor(minOf(corners[0], corners[2], corners[4], corners[6]).toDouble())
            .toInt().coerceIn(0, bitmap.width - 1)
        val top = floor(minOf(corners[1], corners[3], corners[5], corners[7]).toDouble())
            .toInt().coerceIn(0, bitmap.height - 1)
        val right = ceil(maxOf(corners[0], corners[2], corners[4], corners[6]).toDouble())
            .toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ceil(maxOf(corners[1], corners[3], corners[5], corners[7]).toDouble())
            .toInt().coerceIn(top + 1, bitmap.height)
        return Rect(left, top, right, bottom).takeIf { it.width() >= 32 && it.height() >= 32 }
    }

    private fun candidateCenterInside(candidate: FloatArray, selection: FloatArray): Boolean {
        val x = (candidate[0] + candidate[2] + candidate[4] + candidate[6]) / 4f
        val y = (candidate[1] + candidate[3] + candidate[5] + candidate[7]) / 4f
        var inside = false
        var previous = 3
        for (index in 0 until 4) {
            val x1 = selection[index * 2]
            val y1 = selection[index * 2 + 1]
            val x2 = selection[previous * 2]
            val y2 = selection[previous * 2 + 1]
            if ((y1 > y) != (y2 > y) && x < (x2 - x1) * (y - y1) / (y2 - y1) + x1) {
                inside = !inside
            }
            previous = index
        }
        return inside
    }

    private fun strongerFailure(
        first: FrequencyWatermark.FailureReason?,
        second: FrequencyWatermark.FailureReason?
    ): FrequencyWatermark.FailureReason? = when {
        first == null -> second
        second == null || first.ordinal >= second.ordinal -> first
        else -> second
    }

    private fun strongerPngFailure(first: PngFailure?, second: PngFailure?): PngFailure? = when {
        first == null -> second
        second == null || first.ordinal >= second.ordinal -> first
        else -> second
    }

    private fun diagnosticText(
        pngFailure: PngFailure?,
        frequencyFailure: FrequencyWatermark.FailureReason?
    ): String {
        val lines = listOf(pngFailureText(pngFailure), frequencyFailureText(frequencyFailure))
            .filter { it.isNotEmpty() }
        return if (lines.isEmpty()) "" else lines.joinToString("\n", prefix = "\n", postfix = "\n")
    }

    private fun pngFailureText(reason: PngFailure?): String = when (reason) {
        PngFailure.INVALID_BOUNDS -> "原始 PNG 读取：选中区域边界无效。"
        PngFailure.HEADER_NOT_FOUND -> "原始 PNG 读取：未找到有效数据头。"
        PngFailure.DATA_INCOMPLETE -> "原始 PNG 读取：数据长度超出选中区域。"
        PngFailure.INVALID_PAYLOAD -> "原始 PNG 读取：数据格式或 CRC 校验未通过。"
        null -> ""
    }

    private fun frequencyFailureText(reason: FrequencyWatermark.FailureReason?): String = when (reason) {
        FrequencyWatermark.FailureReason.INVALID_GEOMETRY ->
            "频域诊断：四角无法建立有效校正坐标，或区域边长小于 96 像素。"
        FrequencyWatermark.FailureReason.INSUFFICIENT_BLOCKS ->
            "频域诊断：可用的导频采样块不足 64 个。"
        FrequencyWatermark.FailureReason.WEAK_SIGNAL ->
            "频域诊断：导频信号未达到解码阈值。"
        FrequencyWatermark.FailureReason.INVALID_PAYLOAD ->
            "频域诊断：已尝试恢复数据，但水印格式或 CRC 校验未通过。"
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
        val png = readExactPngRegion(source, corners)
        png.payload?.let { return DecodeResult(it) }
        val decoded = decodeLocated(source, onProgress, corners)
        return decoded.copy(pngFailure = strongerPngFailure(png.failure, decoded.pngFailure))
    }

    private fun decodeExactPngRegion(source: Bitmap, corners: FloatArray): WatermarkPayload? =
        readExactPngRegion(source, corners).payload

    private fun readExactPngRegion(source: Bitmap, corners: FloatArray): PngReadResult {
        if (corners.size != 8 || corners.any { !it.isFinite() }) {
            return PngReadResult(failure = PngFailure.INVALID_BOUNDS)
        }
        val left = corners[0].roundToInt().coerceIn(0, source.width - 1)
        val top = corners[1].roundToInt().coerceIn(0, source.height - 1)
        val right = corners[4].roundToInt().coerceIn(left + 1, source.width)
        val bottom = corners[5].roundToInt().coerceIn(top + 1, source.height)
        if (right - left < 48 || bottom - top < 1) {
            return PngReadResult(failure = PngFailure.INVALID_BOUNDS)
        }
        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        return try {
            val header = HalfToneWatermark.extract(crop, WatermarkPayload.HEADER_BITS)
            val count = WatermarkPayload.requiredBitCount(header)
                ?: return PngReadResult(failure = PngFailure.HEADER_NOT_FOUND)
            if (count > crop.width.toLong() * crop.height) {
                return PngReadResult(failure = PngFailure.DATA_INCOMPLETE)
            }
            val payload = WatermarkPayload.fromBitArray(HalfToneWatermark.extract(crop, count))
            if (payload != null) PngReadResult(payload)
            else PngReadResult(failure = PngFailure.INVALID_PAYLOAD)
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
        val frequencyFailure: FrequencyWatermark.FailureReason? = null,
        val pngFailure: PngFailure? = null
    )

    private data class DecodeResult(
        val payload: WatermarkPayload? = null,
        val noCompleteRegion: Boolean = false,
        val candidateRegions: List<FloatArray> = emptyList(),
        val frequencyFailure: FrequencyWatermark.FailureReason? = null,
        val pngFailure: PngFailure? = null
    )

    private enum class PngFailure {
        INVALID_BOUNDS,
        HEADER_NOT_FOUND,
        DATA_INCOMPLETE,
        INVALID_PAYLOAD
    }

    private data class PngReadResult(
        val payload: WatermarkPayload? = null,
        val failure: PngFailure? = null
    )
}
