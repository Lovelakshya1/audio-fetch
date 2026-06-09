package com.audiofetch

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.audiofetch.databinding.ActivityMainBinding
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start Chaquopy once (idempotent after first call)
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        binding.fetchBtn.setOnClickListener { startDownload() }

        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                startDownload()
                true
            } else false
        }
    }

    private fun startDownload() {
        val url = binding.urlInput.text?.toString()?.trim() ?: ""
        if (url.isEmpty()) {
            setStatus("no url provided.", StatusType.ERROR)
            return
        }

        setStatus("fetching… this may take a moment.", StatusType.NEUTRAL)
        binding.fetchBtn.isEnabled = false
        binding.progressBar.isVisible = true

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runDownload(url)
            }

            binding.fetchBtn.isEnabled = true
            binding.progressBar.isVisible = false

            if (result.startsWith("ERROR:")) {
                setStatus(result, StatusType.ERROR)
            } else {
                setStatus("done → saved to Downloads", StatusType.SUCCESS)
                saveToDownloads(result)
            }
        }
    }

    /**
     * Runs on IO dispatcher. Calls Python main.download_audio(url, tmpDir)
     * Returns the local file path on success, or "ERROR: …" on failure.
     */
    private fun runDownload(url: String): String {
        return try {
            val py = Python.getInstance()
            val module = py.getModule("main")
            val tmpDir = cacheDir.absolutePath
            module.callAttr("download_audio", url, tmpDir).toString()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    /**
     * Copies the downloaded tmp file to the public Downloads folder,
     * using MediaStore on API 29+ and direct File copy below that.
     */
    private fun saveToDownloads(srcPath: String) {
        val src = File(srcPath)
        if (!src.exists()) return

        val mimeType = when (src.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            "webm" -> "audio/webm"
            else -> "audio/*"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().copyTo(out) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            destDir.mkdirs()
            src.copyTo(File(destDir, src.name), overwrite = true)
        }

        src.delete() // clean up cache
    }

    private enum class StatusType { NEUTRAL, ERROR, SUCCESS }

    private fun setStatus(msg: String, type: StatusType) {
        binding.statusText.text = msg
        binding.statusText.setTextColor(
            getColor(
                when (type) {
                    StatusType.ERROR   -> R.color.status_error
                    StatusType.SUCCESS -> R.color.status_success
                    StatusType.NEUTRAL -> R.color.status_muted
                }
            )
        )
    }
}
