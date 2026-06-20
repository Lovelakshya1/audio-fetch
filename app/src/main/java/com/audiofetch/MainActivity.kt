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

            val results = (0 until arr.length()).map { i ->
                val item = arr.getJSONObject(i)
                SearchResult(
                    title = item.optString("title", "Unknown"),
                    artist = item.optString("artist", ""),
                    thumbnail = item.optString("thumbnail", ""),
                    url = item.optString("url", "")
                )
            }

            setStatus("${results.size} results found.", StatusType.NEUTRAL)

            SearchResultsBottomSheet(results) { selected ->
                if (selected.url.isNotEmpty()) {
                    startDownload(selected.url)
                } else {
                    setStatus("ERROR: no url for that result.", StatusType.ERROR)
                }
            }.show(supportFragmentManager, "search_results")

        } catch (e: Exception) {
            setStatus("ERROR: failed to parse results.", StatusType.ERROR)
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private fun startDownload(url: String) {
        setStatus("checking…", StatusType.NEUTRAL)
        binding.fetchBtn.isEnabled = false
        binding.progressBar.isVisible = true

        lifecycleScope.launch {
            val tmpDir = cacheDir.absolutePath
            val infoResult = withContext(Dispatchers.IO) { runGetPlaylistInfo(url, tmpDir) }

            if (infoResult.startsWith("ERROR:")) {
                binding.fetchBtn.isEnabled = true
                binding.progressBar.isVisible = false
                setStatus(infoResult, StatusType.ERROR)
                return@launch
            }

            val infoObj = try { org.json.JSONObject(infoResult) } catch (e: Exception) { null }
            val isPlaylist = infoObj?.optBoolean("is_playlist", false) ?: false

            if (isPlaylist) {
                val folder = infoObj!!.optString("folder", "")
                val total = infoObj.optInt("total", 0)
                runPlaylistDownloadWithProgress(url, tmpDir, folder, total)
            } else {
                setStatus("fetching… this may take a moment.", StatusType.NEUTRAL)
                val result = withContext(Dispatchers.IO) { runDownload(url, tmpDir, knownSingle = true) }

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
    }

    @Volatile private var downloadResult: String? = null

    /** Runs the playlist download on one coroutine while polling progress.json on another. */
    private suspend fun runPlaylistDownloadWithProgress(
        url: String,
        tmpDir: String,
        folder: String,
        total: Int
    ) {
        downloadResult = null

        // Download coroutine
        val downloadJob = lifecycleScope.launch {
            downloadResult = withContext(Dispatchers.IO) { runDownload(url, tmpDir) }
        }

        // Poller coroutine — updates status text until download finishes
        val pollerJob = lifecycleScope.launch {
            while (downloadResult == null) {
                val progressJson = withContext(Dispatchers.IO) { runGetProgress(folder) }
                try {
                    val p = org.json.JSONObject(progressJson)
                    val current = p.optInt("current", 0)
                    val totalFromFile = p.optInt("total", total)
                    val track = p.optString("track", "")
                    val status = p.optString("status", "")

                    when (status) {
                        "starting" -> setStatus("starting playlist…", StatusType.NEUTRAL)
                        "downloading" -> {
                            val trimmedTrack = if (track.length > 28) track.take(28) + "…" else track
                            setStatus("$current/$totalFromFile — $trimmedTrack", StatusType.NEUTRAL)
                        }
                        "done" -> setStatus("finishing up…", StatusType.NEUTRAL)
                    }
                } catch (e: Exception) {
                    // progress.json not written yet, ignore
                }
                kotlinx.coroutines.delay(600)
            }
        }

        downloadJob.join()
        pollerJob.cancel()

        binding.fetchBtn.isEnabled = true
        binding.progressBar.isVisible = false

        val result = downloadResult ?: "ERROR: unknown failure"
        when {
            result.startsWith("ERROR:") -> setStatus(result, StatusType.ERROR)
            result.trim().startsWith("{") -> handlePlaylistResult(result)
            else -> {
                setStatus("done → saved to Downloads", StatusType.SUCCESS)
                saveToDownloads(result)
            }
        }
    }

    private fun runGetPlaylistInfo(url: String, tmpDir: String): String {
        return try {
            val py = Python.getInstance()
            val module = py.getModule("main")
            module.callAttr("get_playlist_info", url, tmpDir).toString()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun runGetProgress(folder: String): String {
        return try {
            val py = Python.getInstance()
            val module = py.getModule("main")
            module.callAttr("get_progress", folder).toString()
        } catch (e: Exception) {
            "{}"
        }
    }

    private suspend fun handlePlaylistResult(jsonString: String) {
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

            withContext(Dispatchers.IO) { saveFolderToDownloads(folder, name) }
            binding.progressBar.isVisible = false

            val msg = if (failed > 0) {
                "done → $done saved, $failed failed → Downloads/$name"
            } else {
                "done → $done tracks saved → Downloads/$name"
            }
            setStatus(msg, if (failed > 0) StatusType.NEUTRAL else StatusType.SUCCESS)
        } catch (e: Exception) {
            setStatus("ERROR: failed to parse playlist result.", StatusType.ERROR)
        }
    }

    private fun runDownload(url: String, tmpDir: String, knownSingle: Boolean = false): String {
        return try {
            val py = Python.getInstance()
            val module = py.getModule("main")
            module.callAttr("download_audio", url, tmpDir, knownSingle).toString()
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
