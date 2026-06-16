object UpdateChecker {
    private const val API_URL = "https://api.github.com/repos/kabirvvv/audio-fetch/releases/latest"

    // 1. Safe Network Call using Coroutines
    suspend fun checkForUpdate(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            if (conn.responseCode != 200) return@withContext null

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tagName = json.getString("tag_name")
            val remoteCode = tagName.removePrefix("v").toIntOrNull() ?: return@withContext null
            if (remoteCode <= currentVersionCode) return@withContext null

            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    apkUrl = a.getString("browser_download_url")
                    break
                }
            }
            apkUrl ?: return@withContext null

            UpdateInfo(remoteCode, json.optString("name", tagName),
                json.optString("body", "No changelog provided."), apkUrl)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun downloadAndInstall(context: Context, update: UpdateInfo) {
        // Clear out old update file if it exists to prevent collision or stale installs
        val oldFile = File(context.getExternalFilesDir("updates"), "audiofetch-update.apk")
        if (oldFile.exists()) oldFile.delete()

        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("AudioFetch update")
            .setDestinationInExternalFilesDir(context, "updates", "audiofetch-update.apk")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloadId) {
                    ctx.unregisterReceiver(this) // Use broadcast context safely
                    installApk(ctx)
                }
            }
        }
        
        // 2. Safe Backwards-Compatible Receiver registration
        ContextCompat.registerReceiver(
            context, 
            receiver, 
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun installApk(context: Context) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            return
        }
        val apkFile = File(context.getExternalFilesDir("updates"), "audiofetch-update.apk")
        if (!apkFile.exists()) return

        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        })
    }
}
