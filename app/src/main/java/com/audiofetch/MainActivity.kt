package com.audiofetch

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.net.Uri
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
import org.json.JSONArray
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Update check
        lifecycleScope.launch {
            val update = withContext(Dispatchers.IO) {
                UpdateChecker.checkForUpdate(BuildConfig.VERSION_CODE)
            }
            if (update != null) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update available — ${update.versionName}")
                    .setMessage(update.changelog)
                    .setPositiveButton("Update Now") { _, _ ->
                        UpdateChecker.downloadAndInstall(this@MainActivity, update)
                    }
                    .setNeutralButton("View on GitHub") { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)))
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        binding.fetchBtn.setOnClickListener { handleInput() }

        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                handleInput()
                true
            } else false
        }
    }

    /** Entry point — decides whether to search or download directly. */
    private fun handleInput() {
        val input = binding.urlInput.text?.toString()?.trim() ?: ""
        if (input.isEmpty()) {
            setStatus("no input provided.", StatusType.ERROR)
            return
        }

        if (isUrl(input)) {
            startDownload(input)
        } else {
            startSearch(input)
        }
    }

    private fun isUrl(text: String): Boolean {
        return Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://").containsMatchIn(text)
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private fun startSearch(query: String) {
        setStatus("searching…", StatusType.NEUTRAL)
        binding.fetchBtn.isEnabled = false
        binding.progressBar.isVisible = true

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runSearch(query) }

            binding.fetchBtn.isEnabled = true
            binding.progressBar.isVisible = false

            if (result.startsWith("ERROR:")) {
                setStatus(result, StatusType.ERROR)
            } else {
                showSearchResults(result)
            }
        }
    }

    private fun runSearch(query: String): String {
        return try {
            val py = Python.getInstance()
            val module = py.getModule("main")
            module.callAttr("search_audio", query).toString()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun showSearchResults(jsonString: String) {
        try {
            val arr = JSONArray(jsonString)
            if (arr.length() == 0) {
                setStatus("no results found.", StatusType.ERROR)
                return
            }

            // Build display labels and url map
            val labels = Array(arr.length()) { i ->
                val item = arr.getJSONObject(i)
                val title  = item.optString("title", "Unknown")
                val artist = item.optString("artist", "")
                if (artist.isNotEmpty()) "$title\n$artist" else title
            }
            val urls = Array(arr.length()) { i ->
                arr.getJSONObject(i).optString("url", "")
            }

            setStatus("${arr.length()} results found.", StatusType.NEUTRAL)

            AlertDialog.Builder(this)
                .setTitle("select a track")
                .setItems(labels) { _, which ->
                    val selectedUrl = urls[which]
                    if (selectedUrl.isNotEmpty()) {
                        startDownload(selectedUrl)
                    } else {
                        setStatus("ERROR: no url for that result.", StatusType.ERROR)
                    }
                }
                .setNegativeButton("cancel", null)
                .show()

        } catch (e: Exception) {
            setStatus("ERROR: failed to parse results.", StatusType.ERROR)
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private fun startDownload(url: String) {
        setStatus("fetching… this may take a moment.", StatusType.NEUTRAL)
        binding.fetchBtn.isEnabled = false
        binding.progressBar.isVisible = true

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runDownload(url) }

            binding.fetchBtn.isEnabled = true
            binding.progressBar.isVisible = false

            when {
                result.startsWith("ERROR:") -> {
                    setStatus(result, StatusType.ERROR)
                }
                result.trim().startsWith("{") -> {
                    // playlist result (JSON)
                    handlePlaylistResult(result)
                }
                else -> {
                    // single track (file path)
                    setStatus("done → saved to Downloads", StatusType.SUCCESS)
                    saveToDownloads(result)
                }
            }
        }
    }

    private fun handlePlaylistResult(jsonString: String) {
        try {
            val obj = org.json.JSONObject(jsonString)
            val folder = obj.optString("folder", "")
            val name = obj.optString("name", "playlist")
            val done = obj.optInt("done", 0)
            val failed = obj.optInt("failed", 0)

            if (folder.isEmpty() || done == 0) {
                setStatus("ERROR: playlist download failed.", StatusType.ERROR)
                return
            }

            setStatus("saving $done tracks…", StatusType.NEUTRAL)
            binding.progressBar.isVisible = true

            lifecycleScope.launch {
                withContext(Dispatchers.IO) { saveFolderToDownloads(folder, name) }
                binding.progressBar.isVisible = false
                val msg = if (failed > 0) {
                    "done → $done saved, $failed failed → Downloads/$name"
                } else {
                    "done → $done tracks saved → Downloads/$name"
                }
                setStatus(msg, if (failed > 0) StatusType.NEUTRAL else StatusType.SUCCESS)
            }
        } catch (e: Exception) {
            setStatus("ERROR: failed to parse playlist result.", StatusType.ERROR)
        }
    }

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

    // ── Save to Downloads ─────────────────────────────────────────────────────

    private fun saveToDownloads(srcPath: String) {
        val src = File(srcPath)
        if (!src.exists()) return

        val mimeType = when (src.extension.lowercase()) {
            "mp3"  -> "audio/mpeg"
            "m4a"  -> "audio/mp4"
            "opus" -> "audio/opus"
            "webm" -> "audio/webm"
            else   -> "audio/*"
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

        src.delete()
    }

    /** Save every file in a local folder into Downloads/<playlistName>/ */
    private fun saveFolderToDownloads(folderPath: String, playlistName: String) {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return

        val audioFiles = folder.listFiles { f ->
            f.isFile && f.extension.lowercase() in listOf("mp3", "m4a", "opus", "webm", "aac")
        } ?: emptyArray()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (src in audioFiles) {
                val mimeType = when (src.extension.lowercase()) {
                    "mp3"  -> "audio/mpeg"
                    "m4a"  -> "audio/mp4"
                    "opus" -> "audio/opus"
                    "webm" -> "audio/webm"
                    else   -> "audio/*"
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$playlistName")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
                resolver.openOutputStream(uri)?.use { out -> src.inputStream().copyTo(out) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } else {
            val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val destDir = File(baseDir, playlistName)
            destDir.mkdirs()
            for (src in audioFiles) {
                src.copyTo(File(destDir, src.name), overwrite = true)
            }
        }

        folder.deleteRecursively()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
