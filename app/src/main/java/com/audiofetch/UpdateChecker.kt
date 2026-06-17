package com.audiofetch

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.Uri
import android.app.DownloadManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val changelog: String,
    val downloadUrl: String,
    val expectedSize: Long
)

object UpdateChecker {
    private const val REPO_OWNER = "kabirvvv"
    private const val REPO_NAME = "audio-fetch"
    private const val API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    fun checkForUpdate(currentVersionCode: Int): UpdateInfo? {
        return try {
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tagName = json.getString("tag_name")
            val remoteCode = tagName.removePrefix("v").toIntOrNull() ?: return null
            if (remoteCode <= currentVersionCode) return null

            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            var size: Long = -1
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    apkUrl = a.getString("browser_download_url")
                    size = a.getLong("size")
                    break
                }
            }
            apkUrl ?: return null

            UpdateInfo(remoteCode, json.optString("name", tagName),
                json.optString("body", "No changelog provided."), apkUrl, size)
        } catch (e: Exception) { null }
    }

    fun downloadAndInstall(context: Context, update: UpdateInfo) {
        val destFile = File(context.getExternalFilesDir("updates"), "audiofetch-update.apk")
        if (destFile.exists()) destFile.delete() // always start clean, never reuse a stale/partial file

        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("AudioFetch update")
            .setDestinationInExternalFilesDir(context, "updates", "audiofetch-update.apk")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return
                context.unregisterReceiver(this)

                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                var success = false
                if (cursor.moveToFirst()) {
                    val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    success = cursor.getInt(statusIdx) == DownloadManager.STATUS_SUCCESSFUL
                }
                cursor.close()

                val sizeOk = update.expectedSize <= 0 || destFile.length() == update.expectedSize

                if (success && sizeOk) {
                    installApk(context)
                } else {
                    Toast.makeText(context,
                        "Update download failed or was corrupted (got ${destFile.length()} bytes, expected ${update.expectedSize}). Please try again.",
                        Toast.LENGTH_LONG).show()
                    destFile.delete()
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_EXPORTED)
    }

    private fun installApk(context: Context) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")))
            return
        }
        val apkFile = File(context.getExternalFilesDir("updates"), "audiofetch-update.apk")
        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        })
    }
}
