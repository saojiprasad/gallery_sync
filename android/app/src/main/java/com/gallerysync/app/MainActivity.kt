package com.gallerysync.app

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.util.Size
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets


class MainActivity : Activity() {
    private val syncIntervalMs = 30_000L
    private val prefs by lazy { getSharedPreferences("gallery-sync", MODE_PRIVATE) }
    private val deviceId by lazy {
        "android-" + Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }

    private lateinit var backendInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    @Volatile
    private var running = false
    private var syncThread: Thread? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onDestroy() {
        stopSync()
        super.onDestroy()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTextColor(0xFF172033.toInt())
        }

        val subtitle = TextView(this).apply {
            text = "Sync your gallery to a local backend or public relay URL."
            textSize = 15f
            setTextColor(0xFF5A6578.toInt())
            setPadding(0, dp(8), 0, dp(18))
        }

        backendInput = EditText(this).apply {
            hint = "https://gallery.example.com"
            setText(prefs.getString("backend_url", "https://gallery.example.com"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }

        tokenInput = EditText(this).apply {
            hint = "Sync token"
            setText(prefs.getString("sync_token", "change-me"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }

        val permissionButton = Button(this).apply {
            text = "Grant Media Permission"
            setOnClickListener { requestMediaPermission() }
        }

        val startButton = Button(this).apply {
            text = "Start Sync"
            setOnClickListener { startSyncLoop() }
        }

        val onceButton = Button(this).apply {
            text = "Sync Now"
            setOnClickListener {
                saveBackendSettings()
                runInBackground { syncOnce() }
            }
        }

        val stopButton = Button(this).apply {
            text = "Stop"
            setOnClickListener { stopSync() }
        }

        statusText = TextView(this).apply {
            text = if (hasMediaPermission()) "Permission granted" else "Media permission needed"
            textSize = 15f
            setTextColor(0xFF172033.toInt())
            setPadding(0, dp(14), 0, dp(8))
        }

        logText = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(0xFF4E5C72.toInt())
            setPadding(0, dp(8), 0, 0)
        }

        container.addView(title)
        container.addView(subtitle)
        container.addView(backendInput)
        container.addView(tokenInput)
        container.addView(permissionButton, buttonParams())
        container.addView(startButton, buttonParams())
        container.addView(onceButton, buttonParams())
        container.addView(stopButton, buttonParams())
        container.addView(statusText)
        container.addView(logText)
        scroll.addView(container)
        setContentView(scroll)
    }

    private fun buttonParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(10)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun requestMediaPermission() {
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            updateStatus("Permission already granted")
            return
        }
        requestPermissions(missing.toTypedArray(), 44)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 44 && hasMediaPermission()) {
            updateStatus("Permission granted. Tap Start Sync.")
        } else {
            updateStatus("Permission denied. Gallery sync cannot start.")
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasMediaPermission(): Boolean {
        return requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun startSyncLoop() {
        if (!hasMediaPermission()) {
            requestMediaPermission()
            return
        }
        if (running) {
            updateStatus("Sync is already running")
            return
        }
        saveBackendSettings()
        running = true
        syncThread = Thread {
            log("Sync loop started")
            while (running) {
                try {
                    syncOnce()
                } catch (error: Exception) {
                    log("Sync error: ${error.message}")
                    updateStatus("Sync error: ${error.message}")
                }

                var slept = 0L
                while (running && slept < syncIntervalMs) {
                    Thread.sleep(1_000)
                    slept += 1_000
                }
            }
            log("Sync loop stopped")
        }.apply { start() }
    }

    private fun stopSync() {
        running = false
        syncThread = null
        updateStatus("Sync stopped")
    }

    private fun runInBackground(block: () -> Unit) {
        Thread {
            try {
                block()
            } catch (error: Exception) {
                log("Error: ${error.message}")
                updateStatus("Error: ${error.message}")
            }
        }.start()
    }

    private fun saveBackendSettings() {
        prefs.edit()
            .putString("backend_url", backendInput.text.toString().trim().trimEnd('/'))
            .putString("sync_token", tokenInput.text.toString().trim())
            .apply()
    }

    private fun backendUrl(): String {
        return prefs.getString("backend_url", "")?.trim()?.trimEnd('/') ?: ""
    }

    private fun syncToken(): String {
        return prefs.getString("sync_token", "")?.trim() ?: ""
    }

    private fun syncOnce() {
        if (!hasMediaPermission()) {
            updateStatus("Media permission needed")
            return
        }
        if (backendUrl().isBlank()) {
            updateStatus("Enter laptop backend URL")
            return
        }
        if (syncToken().isBlank()) {
            updateStatus("Enter sync token")
            return
        }

        updateStatus("Registering device")
        registerDevice()

        updateStatus("Scanning gallery")
        val items = scanMedia()
        log("Found ${items.size} media files")

        updateStatus("Uploading metadata")
        postJson("/sync-metadata", metadataJson(items))

        updateStatus("Uploading thumbnails")
        var thumbCount = 0
        for (item in items) {
            if (thumbnailUploaded(item)) {
                continue
            }
            val thumb = createThumbnail(item) ?: continue
            uploadFile("/upload-thumbnail", mapOf("device_id" to deviceId, "media_id" to item.id), "file", thumb.name, "image/jpeg") {
                thumb.inputStream()
            }
            markThumbnailUploaded(item)
            thumbCount += 1
        }
        log("Uploaded $thumbCount thumbnails")

        updateStatus("Checking download requests")
        handleDownloadRequests(items.associateBy { it.id })

        updateStatus("Synced ${items.size} files")
    }

    private fun registerDevice() {
        val body = "{"
            .plus("\"device_id\":${jsonString(deviceId)},")
            .plus("\"device_name\":${jsonString(Build.MODEL ?: "Android device")},")
            .plus("\"app_name\":${jsonString(getString(R.string.app_name))}")
            .plus("}")
        postJson("/register-device", body)
    }

    private fun scanMedia(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        items.addAll(queryImages())
        items.addAll(queryVideos())
        return items.sortedByDescending { it.dateModified }
    }

    private fun queryImages(): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        return queryMedia(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection = projection,
            type = "image",
            idPrefix = "image",
            durationColumn = null,
        )
    }

    private fun queryVideos(): List<MediaItem> {
        val projection = arrayOf(
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
        )
        return queryMedia(
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection = projection,
            type = "video",
            idPrefix = "video",
            durationColumn = MediaStore.Video.Media.DURATION,
        )
    }

    private fun queryMedia(
        collection: Uri,
        projection: Array<String>,
        type: String,
        idPrefix: String,
        durationColumn: String?,
    ): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            while (cursor.moveToNext()) {
                val rowId = cursor.longValue(MediaStore.MediaColumns._ID)
                val contentUri = ContentUris.withAppendedId(collection, rowId)
                items.add(
                    MediaItem(
                        id = "$idPrefix-$rowId",
                        name = cursor.stringValue(MediaStore.MediaColumns.DISPLAY_NAME, "$idPrefix-$rowId"),
                        uri = contentUri,
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
            }
        }
        return items
    }

    private fun Cursor.stringValue(column: String, fallback: String): String {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) {
            return fallback
        }
        return getString(index) ?: fallback
    }

    private fun Cursor.longValue(column: String): Long {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) {
            return 0L
        }
        return getLong(index)
    }

    private fun Cursor.intValue(column: String): Int {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) {
            return 0
        }
        return getInt(index)
    }

    private fun metadataJson(items: List<MediaItem>): String {
        return buildString {
            append("{\"device_id\":")
            append(jsonString(deviceId))
            append(",\"items\":[")
            items.forEachIndexed { index, item ->
                if (index > 0) append(',')
                append(itemJson(item))
            }
            append("]}")
        }
    }

    private fun itemJson(item: MediaItem): String {
        return buildString {
            append('{')
            append("\"id\":").append(jsonString(item.id)).append(',')
            append("\"name\":").append(jsonString(item.name)).append(',')
            append("\"uri\":").append(jsonString(item.uri.toString())).append(',')
            append("\"type\":").append(jsonString(item.type)).append(',')
            append("\"size\":").append(item.size).append(',')
            append("\"album\":").append(jsonString(item.album)).append(',')
            append("\"mimeType\":").append(jsonString(item.mimeType)).append(',')
            append("\"dateCreated\":").append(item.dateCreated).append(',')
            append("\"dateModified\":").append(item.dateModified).append(',')
            append("\"width\":").append(item.width).append(',')
            append("\"height\":").append(item.height).append(',')
            append("\"duration\":").append(item.duration)
            append('}')
        }
    }

    private fun createThumbnail(item: MediaItem): File? {
        return try {
            val bitmap = contentResolver.loadThumbnail(item.uri, Size(256, 256), null)
            val file = File(cacheDir, "thumb_${safeFileName(item.id)}.jpg")
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
            }
            bitmap.recycle()
            file
        } catch (error: Exception) {
            log("Thumbnail skipped for ${item.name}: ${error.message}")
            null
        }
    }

    private fun thumbnailUploaded(item: MediaItem): Boolean {
        return prefs.getLong("thumb.${item.id}", -1L) == item.dateModified
    }

    private fun markThumbnailUploaded(item: MediaItem) {
        prefs.edit().putLong("thumb.${item.id}", item.dateModified).apply()
    }

    private fun handleDownloadRequests(mediaById: Map<String, MediaItem>) {
        val response = getText("/download-requests?device_id=${urlEncode(deviceId)}")
        val requestedIds = Regex("\"media_id\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(response)
            .map { it.groupValues[1] }
            .toSet()

        if (requestedIds.isEmpty()) {
            log("No laptop download requests")
            return
        }

        for (mediaId in requestedIds) {
            val item = mediaById[mediaId]
            if (item == null) {
                log("Requested file no longer exists on phone: $mediaId")
                continue
            }
            updateStatus("Uploading ${item.name}")
            uploadFile(
                endpoint = "/upload-original",
                fields = mapOf("device_id" to deviceId, "media_id" to item.id),
                fileField = "file",
                fileName = item.name,
                mimeType = item.mimeType,
            ) {
                contentResolver.openInputStream(item.uri)
                    ?: throw IllegalStateException("Cannot open ${item.name}")
            }
            log("Uploaded original: ${item.name}")
        }
    }

    private fun postJson(endpoint: String, json: String): String {
        val connection = openConnection(endpoint)
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { output ->
                output.write(json.toByteArray(StandardCharsets.UTF_8))
            }
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
                inputProvider().use { input -> input.copyTo(output) }
                output.writeUtf8("\r\n--$boundary--\r\n")
                output.flush()
            }

            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(endpoint: String): HttpURLConnection {
        val url = URL(backendUrl() + endpoint)
        return (url.openConnection() as HttpURLConnection).apply {
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
        return value.map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_'
        }.joinToString("")
    }

    private fun String.headerSafe(): String {
        return replace("\"", "_").replace("\r", "_").replace("\n", "_")
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private fun updateStatus(message: String) {
        runOnUiThread { statusText.text = message }
    }

    private fun log(message: String) {
        runOnUiThread {
            val current = logText.text.toString()
            val line = "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] $message"
            logText.text = (line + "\n" + current).take(4_000)
        }
    }
}
