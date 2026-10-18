package com.demo.watermarktracedemo

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var etUserId: EditText
    private lateinit var watermarkView: WatermarkView
    private lateinit var deviceId: String
    private var pendingSaveBitmap: Bitmap? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) generateWatermark(uri)
    }
    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val bitmap = pendingSaveBitmap
        pendingSaveBitmap = null
        if (granted && bitmap != null) {
            saveImage(bitmap)
        } else if (bitmap != null) {
            Toast.makeText(this, "水印已生成，但未保存到相册", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        etUserId = findViewById(R.id.et_userid)
        watermarkView = findViewById(R.id.watermarkView)
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

        findViewById<Button>(R.id.btn_gen_watermark).setOnClickListener {
            Toast.makeText(this, "正在打开图片选择器", Toast.LENGTH_SHORT).show()
            pickImage.launch(arrayOf("image/*"))
        }

        findViewById<Button>(R.id.btn_goto_trace).setOnClickListener {
            startActivity(Intent(this, TraceActivity::class.java))
        }
    }

    private fun generateWatermark(uri: Uri) {
        val userId = etUserId.text.toString().trim()
        if (userId.isEmpty()) {
            etUserId.error = "请输入用户 ID"
            return
        }
        if ('|' in userId) {
            etUserId.error = "用户 ID 不能包含 |"
            return
        }
        val bits = try {
            WatermarkPayload(userId, deviceId, System.currentTimeMillis()).toBitArray()
        } catch (e: IllegalArgumentException) {
            etUserId.error = e.message
            return
        }
        if (bits.size > FrequencyWatermark.MAX_PAYLOAD_BITS) {
            etUserId.error = "用户 ID 过长，无法放入隐形水印"
            return
        }

        val generateButton = findViewById<Button>(R.id.btn_gen_watermark)
        val status = findViewById<TextView>(R.id.tv_watermark_status)
        generateButton.isEnabled = false
        status.visibility = View.VISIBLE
        status.text = "正在生成隐形水印…"
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val source = loadBitmap(uri)
                        try {
                            WatermarkGenerator.generate(source, bits)
                        } finally {
                            source.recycle()
                        }
                    }
                }
                result.onSuccess { generated ->
                    watermarkView.setWatermarkBitmap(generated.bitmap)
                    status.text = if (generated.screenshotVerified) {
                        "水印已生成，已通过原图和缩放回读校验。"
                    } else {
                        "水印已生成：请使用保存的 PNG 原图解析。当前图片未通过截图提取校验。"
                    }
                    saveImageToGallery(generated.bitmap)
                }.onFailure {
                    status.text = "生成失败：" + it.message
                    Toast.makeText(this@MainActivity, "生成失败：" + it.message, Toast.LENGTH_LONG).show()
                }
            } finally {
                generateButton.isEnabled = true
            }
        }
    }

    private fun saveImageToGallery(bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingSaveBitmap = bitmap
            requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveImage(bitmap)
        }
    }

    private fun saveImage(bitmap: Bitmap) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = savePng(bitmap)
                    // Localization is optional; cache failures must not hide a successful save.
                    runCatching { ScreenshotLocator.remember(this@MainActivity, bitmap) }
                    uri
                }
            }
            result.onSuccess {
                Toast.makeText(this@MainActivity, "水印已保存到相册", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@MainActivity, "水印已生成，但保存失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun savePng(bitmap: Bitmap): Uri {
        val name = "watermark_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WatermarkDemo")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "WatermarkDemo"
                )
                check(directory.isDirectory || directory.mkdirs()) { "无法创建相册目录" }
                put(MediaStore.Images.Media.DATA, File(directory, name).absolutePath)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建图片")
        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "无法保存图片" }
            } ?: error("无法写入图片")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                check(contentResolver.update(uri, ready, null, null) > 0) { "无法发布图片" }
            }
            return uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = contentResolver.openInputStream(uri) ?: error("无法读取图片")
        boundsStream.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        require(options.outWidth > 0 && options.outHeight > 0) { "图片格式不受支持" }

        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > MAX_IMAGE_EDGE) {
            options.inSampleSize *= 2
        }
        return contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: error("无法解码图片")
    }

    private companion object {
        const val MAX_IMAGE_EDGE = 1600
    }

}
