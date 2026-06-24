package com.gallerysync.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.provider.Settings
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets


class SyncService : Service() {
    companion object {
        const val ACTION_START = "com.gallerysync.app.START_SYNC"
        const val ACTION_STOP = "com.gallerysync.app.STOP_SYNC"
        private const val CHANNEL_ID = "sync_status"
        private const val NOTIFICATION_ID = 101
        private const val SYNC_INTERVAL_MS = 30_000L
    }

    private val prefs by lazy { getSharedPreferences("gallery-sync", MODE_PRIVATE) }
    private val deviceId by lazy {
        "android-" + Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }

    @Volatile
    private var running = false
    private var worker: Thread? = null

    data class MediaItem(
        val id: String,
        val name: String,
        val uri: Uri,
        val type: String,
        val size: Long,
        val album: String,
        val mimeType: String,
        val dateCreated: Long,
        val dateModified: Long,
        val width: Int,
        val height: Int,
        val duration: Long,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.edit().putBoolean("sync_enabled", false).apply()
                stopWorker()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startWorker()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopWorker()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (prefs.getBoolean("sync_enabled", false) && hasMediaPermission()) {
            val restartIntent = Intent(applicationContext, SyncService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(restartIntent)
            } else {
                applicationContext.startService(restartIntent)
            }
        }
    }

    private fun startWorker() {
        prefs.edit().putBoolean("sync_enabled", true).apply()
        startForeground(NOTIFICATION_ID, notification("Sync active"))

        if (running) {
            return
        }
        running = true
        worker = Thread {
            while (running) {
                try {
                    syncOnce()
                    updateNotification("Sync active")
                } catch (error: Exception) {
                    updateNotification("Waiting to reconnect")
                }

                var slept = 0L
                while (running && slept < SYNC_INTERVAL_MS) {
                    Thread.sleep(1_000)
                    slept += 1_000
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun stopWorker() {
        running = false
        worker = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sync status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows when background sync is active."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("$text · media backup active")
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun syncOnce() {
        if (!hasMediaPermission() || backendUrl().isBlank() || syncToken().isBlank()) {
            return
        }

        registerDevice()
        val items = scanMedia()
        val syncResponse = postJson("/sync-metadata", metadataJson(items))

        // Parse which media_ids the server says it is missing
        val serverNeedsUpload = Regex(""""([^"]+)"""")
            .findAll(
                Regex(""""needs_upload"\s*:\s*\[([^]]*)]""")
                    .find(syncResponse)?.groupValues?.getOrNull(1) ?: ""
            )
            .map { it.groupValues[1] }
            .toSet()

        handleDownloadRequests(items.associateBy { it.id })
        uploadAllOriginals(items, serverNeedsUpload)
    }

    private fun registerDevice() {
        val body = """{"device_id":${jsonString(deviceId)},"device_name":${jsonString(Build.MODEL ?: "Android")},"app_name":${jsonString(getString(R.string.app_name))}}"""
        postJson("/register-device", body)
    }

    private fun scanMedia(): List<MediaItem> {
        return (queryImages() + queryVideos()).sortedByDescending { it.dateModified }
    }

    private fun queryImages() = queryMedia(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        ),
        "image",
        "image",
        null,
    )

    private fun queryVideos() = queryMedia(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DURATION,
        ),
        "video",
        "video",
        MediaStore.Video.Media.DURATION,
    )

    private fun queryMedia(
        collection: Uri,
        projection: Array<String>,
        type: String,
        idPrefix: String,
        durationColumn: String?,
    ): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        try {
            contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    try {
                        val rowId = cursor.longValue(MediaStore.MediaColumns._ID)
                        items.add(
                            MediaItem(
                                id = "$idPrefix-$rowId",
                                name = cursor.stringValue(MediaStore.MediaColumns.DISPLAY_NAME, "$idPrefix-$rowId"),
                                uri = ContentUris.withAppendedId(collection, rowId),
                                type = type,
                                size = cursor.longValue(MediaStore.MediaColumns.SIZE),
                                album = cursor.stringValue(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, "Unknown"),
                                mimeType = cursor.stringValue(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream"),
                                dateCreated = cursor.longValue(MediaStore.MediaColumns.DATE_ADDED),
                                dateModified = cursor.longValue(MediaStore.MediaColumns.DATE_MODIFIED),
                                width = cursor.intValue(MediaStore.MediaColumns.WIDTH),
                                height = cursor.intValue(MediaStore.MediaColumns.HEIGHT),
                                duration = if (durationColumn == null) 0L else cursor.longValue(durationColumn),
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: SecurityException) {
        }
        return items
    }

    private fun Cursor.stringValue(column: String, fallback: String = ""): String {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) fallback else getString(index) ?: fallback
    }

    private fun Cursor.longValue(column: String): Long {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) 0L else getLong(index)
    }

    private fun Cursor.intValue(column: String): Int {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) 0 else getInt(index)
    }

    private fun metadataJson(items: List<MediaItem>) = buildString {
        append("""{"device_id":${jsonString(deviceId)},"items":[""")
        items.forEachIndexed { index, item ->
            if (index > 0) append(',')
            append("""{"id":${jsonString(item.id)},"name":${jsonString(item.name)},"uri":${jsonString(item.uri.toString())},"type":${jsonString(item.type)},"size":${item.size},"album":${jsonString(item.album)},"mimeType":${jsonString(item.mimeType)},"dateCreated":${item.dateCreated},"dateModified":${item.dateModified},"width":${item.width},"height":${item.height},"duration":${item.duration}}""")
        }
        append("]}")
    }


    private fun originalUploaded(item: MediaItem): Boolean {
        return prefs.getLong("original.${item.id}", -1L) == item.dateModified
    }

    private fun markOriginalUploaded(item: MediaItem) {
        prefs.edit().putLong("original.${item.id}", item.dateModified).apply()
    }

    private fun handleDownloadRequests(mediaById: Map<String, MediaItem>) {
        val response = getText("/download-requests?device_id=${urlEncode(deviceId)}")
        val ids = Regex(""""media_id"\s*:\s*"([^"]+)"""")
            .findAll(response)
            .map { it.groupValues[1] }
            .toSet()

        for (mediaId in ids) {
            val item = mediaById[mediaId] ?: continue
            uploadFile(
                "/upload-original",
                mapOf("device_id" to deviceId, "media_id" to item.id),
                "file",
                item.name,
                item.mimeType,
            ) {
                contentResolver.openInputStream(item.uri) ?: error("Cannot open item")
            }
        }
    }

    private fun uploadAllOriginals(items: List<MediaItem>, serverNeedsUpload: Set<String> = emptySet()) {
        for (item in items) {
            // Skip only if local prefs say done AND server confirms it has the file
            if (originalUploaded(item) && item.id !in serverNeedsUpload) {
                continue
            }
            uploadFile(
                "/upload-original",
                mapOf("device_id" to deviceId, "media_id" to item.id),
                "file",
                item.name,
                item.mimeType,
            ) {
                contentResolver.openInputStream(item.uri) ?: error("Cannot open item")
            }
            markOriginalUploaded(item)
        }
    }

    private fun postJson(endpoint: String, json: String): String {
        val connection = openConnection(endpoint)
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun getText(endpoint: String): String {
        val connection = openConnection(endpoint)
        return try {
            connection.requestMethod = "GET"
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadFile(
        endpoint: String,
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        mimeType: String,
        inputProvider: () -> InputStream,
    ): String {
        val boundary = "----GallerySync${System.currentTimeMillis()}"
        val connection = openConnection(endpoint)
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setChunkedStreamingMode(1024 * 1024)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            DataOutputStream(connection.outputStream).use { output ->
                for ((key, value) in fields) {
                    output.writeUtf8("--$boundary\r\n")
                    output.writeUtf8("Content-Disposition: form-data; name=\"$key\"\r\n\r\n")
                    output.writeUtf8(value)
                    output.writeUtf8("\r\n")
                }
                output.writeUtf8("--$boundary\r\n")
                output.writeUtf8("Content-Disposition: form-data; name=\"$fileField\"; filename=\"${fileName.headerSafe()}\"\r\n")
                output.writeUtf8("Content-Type: $mimeType\r\n\r\n")
                inputProvider().use { it.copyTo(output) }
                output.writeUtf8("\r\n--$boundary--\r\n")
                output.flush()
            }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(endpoint: String): HttpURLConnection {
        return (URL(backendUrl() + endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("X-Gallery-Sync-Token", syncToken())
        }
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: $text")
        }
        return text
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        write(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun backendUrl(): String = prefs.getString("backend_url", "")?.trim()?.trimEnd('/') ?: ""

    private fun syncToken(): String = prefs.getString("sync_token", "")?.trim() ?: ""

    private fun hasMediaPermission(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                val fullAccess =
                    checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                val selectedAccess =
                    checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
                fullAccess || selectedAccess
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            else -> checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        return buildString {
            append('"')
            for (char in value) {
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }
    }

    private fun safeFileName(value: String): String {
        return value.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
    }

    private fun String.headerSafe(): String {
        return replace("\"", "_").replace("\r", "_").replace("\n", "_")
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }
}
