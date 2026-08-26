package net.aaronznetworking.scanner

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val base = "https://scannerlive.aaronznetworking.net"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val eventHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var controllerFuture: ListenableFuture<MediaController>

    private var running = false
    private var cursor: String? = null
    private var liveEdgeReceivedAt: Instant? = null
    private var scannerJob: Job? = null
    private var liveEventsJob: Job? = null
    private var weatherJob: Job? = null
    private var scannerGeneration = 0L
    private val listenerSessionId = "android-${UUID.randomUUID()}"

    private var networkInterrupted = false
    private var weatherInterrupting = false
    private var weatherPolling = false
    private var weatherRefreshPending = false
    private var locationPermissionRequested = false
    private var lastWeatherTakeoverAt = 0L
    private var activeSeriousWeatherAlert: JSONObject? = null
    private val feeds = mutableListOf<FeedOption>()
    private var selectedFeedSlug = "wv-cabell-001"
    private var weatherRadioTalkgroup: String? = null
    private var weatherCollapsed = true
    private var audioFocusLostAt = 0L

    private var textToSpeech: TextToSpeech? = null
    private var textToSpeechReady = false

    private data class TalkgroupOption(
        val id: Int,
        val selectionKey: String,
        val name: String,
        val talkgroupIds: String,
        val allIds: Set<Int>
    )

    private data class TranscriptEntry(
        val id: String,
        val label: String,
        val text: String
    )

    private data class RecentCallEntry(
        val id: String,
        val label: String,
        val tgid: String,
        val time: String
    )

    private data class AnnouncementEntry(
        val id: Int,
        val title: String,
        val message: String,
        val startsAt: String,
        val updatedAt: String
    ) {
        val fingerprint: String
            get() = "$id|$title|$message|$startsAt|$updatedAt"
    }

    private data class WeatherPoint(
        val lat: Double,
        val lon: Double,
        val city: String = "",
        val state: String = ""
    )

    private data class FeedOption(
        val slug: String,
        val name: String,
        val location: String,
        val weatherRadioTalkgroup: String?
    )

    private val talkgroups = mutableListOf<TalkgroupOption>()
    private val blockedTalkgroups = mutableSetOf<String>()
    private val pendingTranscripts = linkedMapOf<String, String>()
    private val seenTranscriptIds = mutableSetOf<String>()
    private val recentTranscripts = mutableListOf<TranscriptEntry>()
    private val recentCalls = mutableListOf<RecentCallEntry>()
    private val activeAnnouncements = mutableListOf<AnnouncementEntry>()
    private var announcementDialogShowing = false

    private val prefs by lazy {
        getSharedPreferences("scanner_prefs", MODE_PRIVATE)
    }

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (running) {
                networkInterrupted = true
                runOnUiThread {
                    findViewById<TextView>(R.id.status).text =
                        "Network interrupted — waiting to reconnect…"
                }
            }
        }

        override fun onAvailable(network: Network) {
            if (running && networkInterrupted && !weatherInterrupting) {
                networkInterrupted = false
                scope.launch {
                    restartScannerAtLiveEdge("Network restored — returning to live calls…")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        controllerFuture = MediaController.Builder(
            this,
            SessionToken(
                this,
                ComponentName(this, ScannerPlaybackService::class.java)
            )
        ).buildAsync()

        controllerFuture.addListener(
            {
                try {
                    controllerFuture.get().addListener(object : Player.Listener {
                        override fun onPlayWhenReadyChanged(
                            playWhenReady: Boolean,
                            reason: Int
                        ) {
                            if (
                                !playWhenReady &&
                                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS &&
                                running &&
                                !weatherInterrupting
                            ) {
                                audioFocusLostAt = System.currentTimeMillis()
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (isPlaying && audioFocusLostAt > 0 && running && !weatherInterrupting) {
                                val interruptedFor = System.currentTimeMillis() - audioFocusLostAt
                                audioFocusLostAt = 0L

                                // Very short notification tones do not reset the scanner.
                                // Longer phone/media interruptions return to the live edge.
                                if (interruptedFor >= 2500) {
                                    scope.launch {
                                        restartScannerAtLiveEdge(
                                            "Audio interruption ended — returning to live calls…"
                                        )
                                    }
                                }
                            }
                        }
                    })
                } catch (_: Exception) {
                }
            },
            ContextCompat.getMainExecutor(this)
        )

        textToSpeech = TextToSpeech(this) { status ->
            textToSpeechReady = status == TextToSpeech.SUCCESS
            if (textToSpeechReady) {
                textToSpeech?.language = Locale.US
            }
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (!running) startScanner() else stopScanner()
        }

        findViewById<Button>(R.id.talkgroupButton).setOnClickListener {
            scope.launch {
                if (talkgroups.isEmpty()) {
                    val button = findViewById<Button>(R.id.talkgroupButton)
                    button.text = "LOADING TALKGROUPS…"
                    val loaded = loadTalkgroups()
                    if (!loaded) {
                        updateTalkgroupButton()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Talkgroups")
                            .setMessage("Could not load the talkgroup list from the server. Please try again.")
                            .setPositiveButton("OK", null)
                            .show()
                        return@launch
                    }
                }
                showTalkgroupDialog()
            }
        }

        findViewById<Button>(R.id.feedButton).setOnClickListener { showFeedDialog() }

        loadSavedBlockedTalkgroups()
        setupCollapsibleSections()
        setupWeatherControls()
        setupWeatherCollapse()
        renderRecentTranscripts()
        renderRecentCalls()
        renderAnnouncements()
        updateTalkgroupButton()

        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }

        // Load each initial API independently so one failed request cannot block the other.
        scope.launch { loadFeeds() }
        scope.launch { updateAnnouncements() }
        scope.launch { updateWeather() }

        startLiveEvents()

        scope.launch {
            while (isActive) {
                updateStatsAndAlerts()
                loadTalkgroups()
                delay(15000)
            }
        }

        scope.launch {
            while (isActive) {
                if (running) {
                    withContext(Dispatchers.IO) {
                        sendListenerHeartbeat()
                    }
                }
                delay(10000)
            }
        }

        scope.launch {
            while (isActive) {
                pollOnePendingTranscript()
                delay(1000)
            }
        }

        weatherJob = scope.launch {
            while (isActive) {
                updateWeather()
                delay(5000)
            }
        }
    }

    override fun onResume() {
        super.onResume()

        scope.launch { loadTalkgroups() }

        if (
            running &&
            audioFocusLostAt > 0 &&
            System.currentTimeMillis() - audioFocusLostAt >= 2500 &&
            !weatherInterrupting
        ) {
            audioFocusLostAt = 0L
            scope.launch {
                restartScannerAtLiveEdge("Returning to live calls…")
            }
        }
    }

    private fun startLiveEvents() {
        liveEventsJob?.cancel()

        liveEventsJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val request = Request.Builder()
                        .url(base + "/api/public/events")
                        .header("Accept", "text/event-stream")
                        .header("Cache-Control", "no-cache")
                        .build()

                    eventHttp.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IllegalStateException("SSE HTTP ${response.code}")
                        }

                        withContext(Dispatchers.Main) {
                            launch { updateWeather() }
                        }

                        val source = response.body?.source()
                            ?: throw IllegalStateException("Empty SSE response")

                        var eventName = ""
                        val dataLines = mutableListOf<String>()

                        while (isActive) {
                            val line = source.readUtf8Line() ?: break

                            when {
                                line.startsWith("event:") -> {
                                    eventName = line.substringAfter(':').trim()
                                }

                                line.startsWith("data:") -> {
                                    dataLines.add(line.substringAfter(':').trimStart())
                                }

                                line.isBlank() -> {
                                    if (
                                        eventName == "scanner-update" &&
                                        dataLines.isNotEmpty()
                                    ) {
                                        handleLiveEvent(dataLines.joinToString("\n"))
                                    }

                                    eventName = ""
                                    dataLines.clear()
                                }
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (_: Exception) {
                    // Reconnect below.
                }

                if (isActive) {
                    withContext(Dispatchers.Main) {
                        launch { loadTalkgroups() }
                        launch { updateAnnouncements() }
                        launch { updateWeather() }
                    }
                    delay(2000)
                }
            }
        }
    }

    private suspend fun handleLiveEvent(raw: String) {
        val event = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }

        when (event.optString("type")) {
            "talkgroups_changed" -> withContext(Dispatchers.Main) {
                loadTalkgroups()
            }

            "announcements_changed" -> withContext(Dispatchers.Main) {
                updateAnnouncements()
            }

            "weather_test_changed" -> withContext(Dispatchers.Main) {
                updateWeather()
            }
        }
    }

    private fun startScanner() {
        running = true
        findViewById<Button>(R.id.startButton).text = "STOP SCANNER"
        findViewById<TextView>(R.id.nowPlaying).text = "Scanning…"
        findViewById<TextView>(R.id.details).text = ""

        scope.launch(Dispatchers.IO) {
            sendListenerHeartbeat()
        }

        scope.launch {
            restartScannerAtLiveEdge("Starting at live edge…")
        }
    }

    private suspend fun restartScannerAtLiveEdge(message: String) {
        if (!running) return

        scannerGeneration += 1
        val generation = scannerGeneration

        scannerJob?.cancel()
        scannerJob = null
        cursor = null
        liveEdgeReceivedAt = null

        if (controllerFuture.isDone) {
            try {
                controllerFuture.get().stop()
                controllerFuture.get().clearMediaItems()
            } catch (_: Exception) {
            }
        }

        findViewById<TextView>(R.id.status).text = message
        findViewById<TextView>(R.id.nowPlaying).text = "Scanning…"
        findViewById<TextView>(R.id.details).text = ""

        scannerJob = scope.launch {
            scannerLoop(generation)
        }
    }

    private suspend fun scannerLoop(generation: Long) {
        while (currentCoroutineContext().isActive && running && generation == scannerGeneration) {
            if (cursor.isNullOrBlank()) {
                val liveEdgeJson = withContext(Dispatchers.IO) {
                    getJson("/api/call-queue?feed=${URLEncoder.encode(selectedFeedSlug, "UTF-8")}&limit=1")
                }

                val liveEdge = liveEdgeJson
                    ?.optString("latest_id")
                    ?.takeIf { it.isNotBlank() && it != "null" }

                val liveEdgeTime = try {
                    liveEdgeJson
                        ?.optJSONArray("calls")
                        ?.optJSONObject(0)
                        ?.optString("received_at")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { Instant.parse(it) }
                } catch (_: Exception) {
                    null
                }

                if (!running || generation != scannerGeneration) return

                if (liveEdge.isNullOrBlank()) {
                    findViewById<TextView>(R.id.status).text =
                        if (networkInterrupted) {
                            "Network interrupted — waiting to reconnect…"
                        } else {
                            "Connecting to live calls…"
                        }
                    delay(1500)
                    continue
                }

                cursor = liveEdge
                liveEdgeReceivedAt = liveEdgeTime
                findViewById<TextView>(R.id.status).text = "Waiting for next call…"
            } else {
                pollCalls(generation)
            }

            delay(1000)
        }
    }

    private fun stopScanner() {
        running = false
        scannerGeneration += 1
        scannerJob?.cancel()
        scannerJob = null
        cursor = null
        audioFocusLostAt = 0L

        scope.launch(Dispatchers.IO) {
            sendListenerLeave()
        }

        if (controllerFuture.isDone) {
            try {
                controllerFuture.get().stop()
                controllerFuture.get().clearMediaItems()
            } catch (_: Exception) {
            }
        }

        findViewById<Button>(R.id.startButton).text = "START SCANNER"
        findViewById<TextView>(R.id.status).text = "Stopped"
        findViewById<TextView>(R.id.nowPlaying).text = "Scanning…"
        findViewById<TextView>(R.id.details).text = ""
    }

    private suspend fun pollCalls(generation: Long) {
        if (!running || generation != scannerGeneration || weatherInterrupting) return

        val after = cursor ?: return

        val json = withContext(Dispatchers.IO) {
            getJson("/api/call-queue?feed=${URLEncoder.encode(selectedFeedSlug, "UTF-8")}&limit=20&after=$after")
        } ?: return

        if (!running || generation != scannerGeneration || weatherInterrupting) return

        val calls = json.optJSONArray("calls") ?: return

        for (i in 0 until calls.length()) {
            if (!running || generation != scannerGeneration || weatherInterrupting) break

            val call = calls.getJSONObject(i)
            val callId = call.optString("id")

            if (callId.isNotBlank()) {
                cursor = callId
            }

            // Defensive backlog guard: even if the queue endpoint returns stale
            // records after a restart/feed change, never play anything that was already
            // present when this scanner session established its live edge.
            val callReceivedAt = try {
                call.optString("received_at")
                    .takeIf { it.isNotBlank() }
                    ?.let { Instant.parse(it) }
            } catch (_: Exception) {
                null
            }

            val edgeTime = liveEdgeReceivedAt
            if (edgeTime != null && callReceivedAt != null && !callReceivedAt.isAfter(edgeTime)) {
                continue
            }

            if (isTalkgroupBlocked(call)) continue

            playCallAndWait(call, generation)
        }

        val latest = json.optString("latest_id", "")
        if (latest.isNotBlank() && latest != "null" && latest > (cursor ?: "")) {
            cursor = latest
        }
    }

    private fun callSelectionKey(call: JSONObject): String {
        val databaseId = call.optInt("talkgroup_database_id", 0)
        if (databaseId > 0) return "db:$databaseId"

        return "sdr:${call.optString("talkgroup").trim()}"
    }

    private fun isTalkgroupBlocked(call: JSONObject): Boolean {
        val key = callSelectionKey(call)
        if (key in blockedTalkgroups) return true

        // Backward compatibility for preferences saved by 0.2.13 and earlier.
        val legacyTgid = call.optString("talkgroup").trim()
        return legacyTgid.isNotBlank() && legacyTgid in blockedTalkgroups
    }

    private suspend fun playCallAndWait(call: JSONObject, generation: Long) {
        if (!running || generation != scannerGeneration || weatherInterrupting) return

        val controller = withContext(Dispatchers.IO) {
            controllerFuture.get()
        }

        if (!running || generation != scannerGeneration || weatherInterrupting) return

        val label = call.optString(
            "talkgroupLabel",
            "Talkgroup ${call.optString("talkgroup")}"
        )
        val tg = call.optString("talkgroup")
        val audioUrl = call.optString("audio_url")
        val callId = call.optString("id")

        if (audioUrl.isBlank()) {
            findViewById<TextView>(R.id.status).text = "Call had no audio URL"
            return
        }

        if (callId.isNotBlank() && callId !in seenTranscriptIds) {
            pendingTranscripts[callId] = label
            while (pendingTranscripts.size > 20) {
                pendingTranscripts.remove(pendingTranscripts.keys.first())
            }
        }

        // Compact one-line app display: TGID + database/SDRTrunk-resolved name only.
        findViewById<TextView>(R.id.nowPlaying).text = "TGID $tg • $label"
        findViewById<TextView>(R.id.details).text = ""
        findViewById<TextView>(R.id.status).text = "Playing"

        controller.setMediaItem(MediaItem.fromUri(base + audioUrl))
        controller.prepare()
        controller.play()

        var lastPosition = -1L
        var lastProgressAt = System.currentTimeMillis()
        var watchdogTriggered = false

        while (
            currentCoroutineContext().isActive &&
            running &&
            generation == scannerGeneration &&
            !weatherInterrupting &&
            controller.playerError == null &&
            controller.playbackState != Player.STATE_ENDED
        ) {
            val position = controller.currentPosition
            if (position > lastPosition + 100) {
                lastPosition = position
                lastProgressAt = System.currentTimeMillis()
            }

            if (
                audioFocusLostAt == 0L &&
                System.currentTimeMillis() - lastProgressAt > 15000
            ) {
                watchdogTriggered = true
                break
            }

            delay(200)
        }

        if (!running || generation != scannerGeneration || weatherInterrupting) return

        if (watchdogTriggered) {
            controller.stop()
            controller.clearMediaItems()
            findViewById<TextView>(R.id.status).text =
                "Audio stalled — returning to live calls…"
            addRecentCall(callId, label, tg)
            restartScannerAtLiveEdge("Audio recovered — waiting for next live call…")
            return
        }

        if (controller.playerError != null) {
            findViewById<TextView>(R.id.status).text =
                "Audio error — skipping call"
        } else {
            findViewById<TextView>(R.id.status).text = "Waiting for next call…"
        }

        addRecentCall(callId, label, tg)
        findViewById<TextView>(R.id.nowPlaying).text = "Scanning…"
        findViewById<TextView>(R.id.details).text = ""
    }

    private fun addRecentCall(id: String, label: String, tgid: String) {
        if (id.isBlank() || recentCalls.any { it.id == id }) return

        val time = SimpleDateFormat("h:mm:ss a", Locale.US).format(Date())
        recentCalls.add(0, RecentCallEntry(id, label, tgid, time))
        while (recentCalls.size > 10) recentCalls.removeAt(recentCalls.lastIndex)
        renderRecentCalls()
    }

    private fun renderRecentCalls() {
        findViewById<TextView>(R.id.recentCalls).text =
            if (recentCalls.isEmpty()) {
                "No calls yet."
            } else {
                recentCalls.joinToString("\n") {
                    "${it.time} • TGID ${it.tgid} • ${it.label}"
                }
            }
    }

    private suspend fun pollOnePendingTranscript() {
        val next = pendingTranscripts.entries.firstOrNull() ?: return
        val callId = next.key
        val label = next.value

        val data = withContext(Dispatchers.IO) {
            getJson("/api/call-detail/$callId")
        } ?: return

        when (data.optString("transcription_status", "pending")) {
            "complete" -> {
                pendingTranscripts.remove(callId)
                val text = data.optString("transcript").trim()

                if (text.isNotBlank() && callId !in seenTranscriptIds) {
                    seenTranscriptIds.add(callId)
                    recentTranscripts.add(0, TranscriptEntry(callId, label, text))
                    while (recentTranscripts.size > 3) {
                        recentTranscripts.removeAt(recentTranscripts.lastIndex)
                    }
                    renderRecentTranscripts()
                }
            }

            "filtered", "error" -> pendingTranscripts.remove(callId)
        }
    }

    private fun renderRecentTranscripts() {
        findViewById<TextView>(R.id.transcript).text =
            if (recentTranscripts.isEmpty()) {
                "Waiting for completed transcripts…"
            } else {
                recentTranscripts.joinToString("\n\n") {
                    "${it.label}\n${it.text}"
                }
            }
    }

    private suspend fun updateAnnouncements() {
        val data = withContext(Dispatchers.IO) {
            getJson("/api/public/announcements")
        } ?: return

        val arr = data.optJSONArray("announcements") ?: return
        val incoming = mutableListOf<AnnouncementEntry>()

        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val id = item.optInt("id", 0)
            if (id <= 0) continue

            incoming.add(
                AnnouncementEntry(
                    id = id,
                    title = item.optString("title", "Announcement"),
                    message = item.optString("message", ""),
                    startsAt = item.optString("starts_at", ""),
                    updatedAt = item.optString("updated_at", "")
                )
            )
        }

        activeAnnouncements.clear()
        activeAnnouncements.addAll(incoming)
        renderAnnouncements()
        showNewAnnouncementPopup(incoming)
    }

    private fun renderAnnouncements() {
        val section = findViewById<View>(R.id.announcementSection)
        val textView = findViewById<TextView>(R.id.announcementText)

        if (activeAnnouncements.isEmpty()) {
            section.visibility = View.GONE
            textView.text = ""
            return
        }

        section.visibility = View.VISIBLE
        textView.text = activeAnnouncements.joinToString("\n\n") {
            "${it.title}\n${it.message}"
        }
    }

    private fun showNewAnnouncementPopup(items: List<AnnouncementEntry>) {
        if (items.isEmpty() || announcementDialogShowing || isFinishing || isDestroyed) return

        val seen = prefs.getStringSet("seen_announcements", emptySet())
            .orEmpty().toMutableSet()
        val unseen = items.filter { it.fingerprint !in seen }
        if (unseen.isEmpty()) return

        val dialogText = unseen.joinToString("\n\n") { "${it.title}\n${it.message}" }
        announcementDialogShowing = true

        AlertDialog.Builder(this)
            .setTitle("📢 Scanner Announcement")
            .setMessage(dialogText)
            .setPositiveButton("OK") { _, _ ->
                unseen.forEach { seen.add(it.fingerprint) }
                prefs.edit()
                    .putStringSet("seen_announcements", seen.toList().takeLast(100).toSet())
                    .apply()
            }
            .setOnDismissListener {
                unseen.forEach { seen.add(it.fingerprint) }
                prefs.edit()
                    .putStringSet("seen_announcements", seen.toList().takeLast(100).toSet())
                    .apply()
                announcementDialogShowing = false
            }
            .show()
    }

    private suspend fun loadFeeds(): Boolean {
        val data = withContext(Dispatchers.IO) { getJson("/api/feeds") } ?: return false
        val arr = data.optJSONArray("feeds") ?: return false
        feeds.clear()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val slug = item.optString("slug").trim()
            if (slug.isBlank()) continue
            val weather = item.optJSONObject("weather_radio")
            feeds.add(FeedOption(slug, item.optString("name", slug), item.optString("location", ""), weather?.optString("talkgroup_id")?.takeIf { it.isNotBlank() && it != "null" }))
        }
        if (feeds.isEmpty()) return false
        val saved = prefs.getString("selected_feed_slug", "").orEmpty()
        applySelectedFeed(feeds.firstOrNull { it.slug == saved } ?: feeds.first())
        findViewById<Button>(R.id.feedButton).visibility = if (feeds.size > 1) View.VISIBLE else View.GONE
        return loadTalkgroups()
    }

    private fun applySelectedFeed(feed: FeedOption) {
        selectedFeedSlug = feed.slug
        weatherRadioTalkgroup = feed.weatherRadioTalkgroup
        prefs.edit().putString("selected_feed_slug", feed.slug).apply()
        findViewById<Button>(R.id.feedButton).text = "FEED: ${feed.name}"
        val nws = findViewById<CheckBox>(R.id.weatherNwsRepeatEnabled)
        nws.visibility = if (weatherRadioTalkgroup == null) View.GONE else View.VISIBLE
        if (weatherRadioTalkgroup == null) nws.isChecked = false
    }

    private fun showFeedDialog() {
        if (feeds.size <= 1) return
        val labels = feeds.map { if (it.location.isBlank()) it.name else "${it.name} • ${it.location}" }.toTypedArray()
        val checked = feeds.indexOfFirst { it.slug == selectedFeedSlug }
        AlertDialog.Builder(this).setTitle("Select Scanner Feed").setSingleChoiceItems(labels, checked) { dialog, which ->
            applySelectedFeed(feeds[which]); talkgroups.clear(); blockedTalkgroups.clear(); loadSavedBlockedTalkgroups()
            scope.launch { loadTalkgroups(); if (running) restartScannerAtLiveEdge("Feed changed — returning to live calls…") }
            dialog.dismiss()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun setupWeatherCollapse() {
        val auto = findViewById<CheckBox>(R.id.weatherAutoExpand)
        auto.isChecked = prefs.getBoolean("weather_auto_expand", true)
        auto.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("weather_auto_expand", v).apply() }
        weatherCollapsed = prefs.getBoolean("weather_collapsed", true)
        findViewById<Button>(R.id.weatherCollapse).setOnClickListener {
            weatherCollapsed = !weatherCollapsed
            prefs.edit().putBoolean("weather_collapsed", weatherCollapsed).apply()
            applyWeatherCollapsedState()
        }
        applyWeatherCollapsedState()
    }

    private fun applyWeatherCollapsedState() {
        val section = findViewById<LinearLayout>(R.id.weatherSection)
        val collapse = findViewById<Button>(R.id.weatherCollapse)
        for (i in 0 until section.childCount) {
            val child = section.getChildAt(i)
            child.visibility = if (i <= 1 || !weatherCollapsed) View.VISIBLE else View.GONE
        }
        collapse.visibility = View.VISIBLE
        collapse.text = if (weatherCollapsed) "EXPAND" else "COLLAPSE"
    }

    private fun updateWeatherCollapseForAlert(active: Boolean) {
        if (!prefs.getBoolean("weather_auto_expand", true)) return
        weatherCollapsed = if (active) false else prefs.getBoolean("weather_collapsed", true)
        applyWeatherCollapsedState()
    }

    private suspend fun loadTalkgroups(): Boolean {
        val data = withContext(Dispatchers.IO) {
            getJson("/api/public/talkgroups?feed=${URLEncoder.encode(selectedFeedSlug, "UTF-8")}")
        } ?: return false

        val arr = data.optJSONArray("talkgroups") ?: return false
        val incoming = mutableListOf<TalkgroupOption>()

        for (i in 0 until arr.length()) {
            val tg = arr.getJSONObject(i)
            val id = tg.optInt("talkgroup_id", 0)
            if (id <= 0) continue

            val idsText = tg.optString("talkgroup_ids", id.toString())
                .trim().ifBlank { id.toString() }
            val allIds = idsText.split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .toSet()
                .ifEmpty { setOf(id) }

            val databaseId = tg.optInt("database_id", 0)
            val selectionKey = tg.optString("selection_key").trim().ifBlank {
                if (databaseId > 0) "db:$databaseId" else "sdr:$id"
            }

            incoming.add(
                TalkgroupOption(
                    id = id,
                    selectionKey = selectionKey,
                    name = tg.optString("name", "Talkgroup $id"),
                    talkgroupIds = idsText,
                    allIds = allIds
                )
            )
        }

        talkgroups.clear()
        talkgroups.addAll(incoming)

        val legacyBlocked = blockedTalkgroups
            .filter { it.all(Char::isDigit) }
            .mapNotNull { it.toIntOrNull() }
            .toSet()

        if (legacyBlocked.isNotEmpty()) {
            val migrated = incoming
                .filter { tg -> tg.allIds.any { it in legacyBlocked } }
                .map { it.selectionKey }

            blockedTalkgroups.removeAll { it.all(Char::isDigit) }
            blockedTalkgroups.addAll(migrated)
            saveBlockedTalkgroups()
        }

        updateTalkgroupButton()
        return incoming.isNotEmpty()
    }

    private fun loadSavedBlockedTalkgroups() {
        blockedTalkgroups.clear()
        blockedTalkgroups.addAll(
            prefs.getStringSet("blocked_talkgroups", emptySet())
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
        )
    }

    private fun saveBlockedTalkgroups() {
        prefs.edit()
            .putStringSet("blocked_talkgroups", blockedTalkgroups.toSet())
            .apply()
    }

    private fun updateTalkgroupButton() {
        val button = findViewById<Button>(R.id.talkgroupButton)
        if (talkgroups.isEmpty()) {
            button.text = "CHOOSE TALKGROUPS"
            return
        }

        val existingIds = talkgroups.map { it.selectionKey }.toSet()
        val blockedExisting = blockedTalkgroups.count { it in existingIds }
        val enabled = (talkgroups.size - blockedExisting).coerceAtLeast(0)
        button.text = "TALKGROUPS: $enabled/${talkgroups.size} ENABLED"
    }

    private fun showTalkgroupDialog() {
        if (talkgroups.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Talkgroups")
                .setMessage("No talkgroups are currently available.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val labels = talkgroups.map { tg ->
            val prefix = if (tg.talkgroupIds.contains(',')) "TGIDs" else "TGID"
            "$prefix ${tg.talkgroupIds} • ${tg.name}"
        }.toTypedArray()

        val checked = BooleanArray(talkgroups.size) { index ->
            talkgroups[index].selectionKey !in blockedTalkgroups
        }

        AlertDialog.Builder(this)
            .setTitle("Choose Talkgroups")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                blockedTalkgroups.clear()
                for (i in talkgroups.indices) {
                    if (!checked[i]) blockedTalkgroups.add(talkgroups[i].selectionKey)
                }
                saveBlockedTalkgroups()
                updateTalkgroupButton()
                scope.launch(Dispatchers.IO) { pushListenerPreferences() }
            }
            .setNeutralButton("Enable All") { _, _ ->
                blockedTalkgroups.clear()
                saveBlockedTalkgroups()
                updateTalkgroupButton()
                scope.launch(Dispatchers.IO) { pushListenerPreferences() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pushListenerPreferences(): JSONObject? = try {
        val blocked = org.json.JSONArray()
        blockedTalkgroups.sorted().forEach { blocked.put(it) }

        val json = JSONObject()
            .put("session_id", listenerSessionId)
            .put("blocked_talkgroups", blocked)
            .toString()

        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())

        http.newCall(
            Request.Builder()
                .url(base + "/api/listener/preferences")
                .put(body)
                .build()
        ).execute().use { response ->
            if (response.isSuccessful) JSONObject(response.body?.string() ?: "{}") else null
        }
    } catch (_: Exception) {
        null
    }

    private fun setupWeatherControls() {
        val locationRadio = findViewById<RadioButton>(R.id.weatherUseLocation)
        val zipRadio = findViewById<RadioButton>(R.id.weatherUseZip)
        val zipRow = findViewById<LinearLayout>(R.id.weatherZipRow)
        val zipInput = findViewById<EditText>(R.id.weatherZipCode)
        val alertsEnabled = findViewById<CheckBox>(R.id.weatherAlertsEnabled)
        val soundEnabled = findViewById<CheckBox>(R.id.weatherAlertSoundEnabled)
        val speechEnabled = findViewById<CheckBox>(R.id.weatherAlertSpeechEnabled)
        val repeatEnabled = findViewById<CheckBox>(R.id.weatherNwsRepeatEnabled)

        val mode = prefs.getString("weather_location_mode", "current") ?: "current"
        locationRadio.isChecked = mode == "current"
        zipRadio.isChecked = mode == "zip"
        zipRow.visibility = if (mode == "zip") View.VISIBLE else View.GONE
        zipInput.setText(prefs.getString("weather_zip", "") ?: "")

        alertsEnabled.isChecked = prefs.getBoolean("weather_alerts_enabled", false)
        soundEnabled.isChecked = prefs.getBoolean("weather_alert_sound_enabled", false)
        speechEnabled.isChecked = prefs.getBoolean("weather_alert_speech_enabled", true)
        repeatEnabled.isChecked = prefs.getBoolean("weather_nws_repeat_enabled", true)

        findViewById<RadioGroup>(R.id.weatherLocationGroup).setOnCheckedChangeListener { _, checkedId ->
            val newMode = if (checkedId == R.id.weatherUseZip) "zip" else "current"
            prefs.edit().putString("weather_location_mode", newMode).apply()
            zipRow.visibility = if (newMode == "zip") View.VISIBLE else View.GONE

            if (newMode == "current") requestLocationPermissionIfNeeded()
            scope.launch { updateWeather() }
        }

        findViewById<Button>(R.id.weatherZipSet).setOnClickListener {
            val zip = zipInput.text.toString().trim()
            if (!zip.matches(Regex("^\\d{5}$"))) {
                findViewById<TextView>(R.id.weatherAlertsText).text =
                    "Enter a valid 5-digit ZIP code."
                return@setOnClickListener
            }
            prefs.edit()
                .putString("weather_location_mode", "zip")
                .putString("weather_zip", zip)
                .apply()
            zipRadio.isChecked = true
            scope.launch { updateWeather() }
        }

        alertsEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("weather_alerts_enabled", checked).apply()
            if (!checked) {
                findViewById<TextView>(R.id.weatherAlertsText).text =
                    "Weather alerts are disabled."
            }
            scope.launch { updateWeather() }
        }

        soundEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("weather_alert_sound_enabled", checked).apply()
        }

        speechEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("weather_alert_speech_enabled", checked).apply()
        }

        repeatEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("weather_nws_repeat_enabled", checked).apply()
        }

        if (mode == "current") requestLocationPermissionIfNeeded()
    }

    private fun requestLocationPermissionIfNeeded() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) return

        if (locationPermissionRequested) return
        locationPermissionRequested = true

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            scope.launch { updateWeather() }
        }
    }

    private suspend fun getWeatherPoint(): WeatherPoint? {
        val mode = prefs.getString("weather_location_mode", "current") ?: "current"

        if (mode == "zip") {
            val zip = prefs.getString("weather_zip", "")?.trim().orEmpty()
            if (!zip.matches(Regex("^\\d{5}$"))) return null

            val encoded = URLEncoder.encode(zip, "UTF-8")
            val data = withContext(Dispatchers.IO) {
                getJson("/api/weather/location/zip?zip_code=$encoded")
            } ?: return null

            return WeatherPoint(
                lat = data.optDouble("latitude"),
                lon = data.optDouble("longitude"),
                city = data.optString("city", ""),
                state = data.optString("state_abbreviation", data.optString("state", ""))
            )
        }

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            withContext(Dispatchers.Main) { requestLocationPermissionIfNeeded() }
            return null
        }

        val location = getBestDeviceLocation() ?: return null
        return WeatherPoint(location.latitude, location.longitude)
    }

    private suspend fun getBestDeviceLocation(): Location? = withContext(Dispatchers.Main) {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (
            ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return@withContext null

        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        val last = providers.mapNotNull { provider ->
            try { manager.getLastKnownLocation(provider) } catch (_: Exception) { null }
        }.maxByOrNull { it.time }

        if (last != null) return@withContext last

        val deferred = CompletableDeferred<Location?>()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!deferred.isCompleted) deferred.complete(location)
            }
        }

        val provider = providers.firstOrNull { manager.isProviderEnabled(it) }
            ?: return@withContext null

        try {
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        } catch (_: Exception) {
            return@withContext null
        }

        val result = withTimeoutOrNull(8000) { deferred.await() }
        try { manager.removeUpdates(listener) } catch (_: Exception) {}
        result
    }

    private suspend fun updateWeather() {
        if (weatherPolling) {
            weatherRefreshPending = true
            return
        }

        weatherPolling = true
        try {
            val point = getWeatherPoint()
            if (point == null) {
                findViewById<TextView>(R.id.weatherLocation).text =
                    "Allow location access or enter a ZIP code"
                findViewById<TextView>(R.id.weatherCurrent).text =
                    "--°   Waiting for location"
                return
            }

            val current = withContext(Dispatchers.IO) {
                getJson("/api/weather/current?lat=${point.lat}&lon=${point.lon}")
            }

            if (current != null) {
                val location = current.optJSONObject("location")
                val city = point.city.ifBlank { location?.optString("city", "") ?: "" }
                val state = point.state.ifBlank { location?.optString("state", "") ?: "" }
                findViewById<TextView>(R.id.weatherLocation).text =
                    listOf(city, state).filter { it.isNotBlank() }.joinToString(", ")
                        .ifBlank { "Current Location" }

                val temp = if (current.has("temperature") && !current.isNull("temperature")) {
                    current.optInt("temperature").toString() + "°" + current.optString("temperature_unit", "F")
                } else "--°"
                val condition = current.optString("condition", "Weather unavailable")
                findViewById<TextView>(R.id.weatherCurrent).text = "$temp   $condition"

                val details = mutableListOf<String>()
                if (current.has("humidity") && !current.isNull("humidity")) {
                    details.add("Humidity ${current.optDouble("humidity").toInt()}%")
                }
                val wind = listOf(
                    current.optString("wind_direction", ""),
                    current.optString("wind_speed", "")
                ).filter { it.isNotBlank() }.joinToString(" ")
                if (wind.isNotBlank()) details.add("Wind $wind")
                findViewById<TextView>(R.id.weatherDetails).text = details.joinToString(" • ")
            }

            if (!prefs.getBoolean("weather_alerts_enabled", false)) {
                findViewById<TextView>(R.id.weatherAlertsText).text =
                    "Weather alerts are disabled."
                return
            }

            val alertsData = withContext(Dispatchers.IO) {
                getJson("/api/weather/alerts?lat=${point.lat}&lon=${point.lon}")
            } ?: return

            val alerts = alertsData.optJSONArray("alerts")
            if (alerts == null || alerts.length() == 0) {
                findViewById<TextView>(R.id.weatherAlertsText).text =
                    "✓ No active weather alerts\nNational Weather Service"
                return
            }

            val display = mutableListOf<String>()
            val newSerious = mutableListOf<JSONObject>()
            val seen = prefs.getStringSet("seen_weather_alert_ids", emptySet())
                .orEmpty().toMutableSet()

            for (i in 0 until alerts.length()) {
                val alert = alerts.getJSONObject(i)
                val id = weatherAlertId(alert)
                val event = alert.optString("event", "Weather Alert")
                val area = alert.optString("area", "")
                val headline = alert.optString("headline", "")
                val severity = alert.optString("severity", "")

                display.add(
                    buildList {
                        add("⚠ $event")
                        if (area.isNotBlank()) add(area)
                        if (severity.isNotBlank()) add(severity)
                        if (headline.isNotBlank()) add(headline)
                    }.joinToString("\n")
                )

                if (id !in seen && weatherAlertShouldInterrupt(alert)) {
                    newSerious.add(alert)
                }
                if (id.isNotBlank()) seen.add(id)
            }

            findViewById<TextView>(R.id.weatherAlertsText).text =
                display.joinToString("\n\n")

            prefs.edit()
                .putStringSet("seen_weather_alert_ids", seen.toList().takeLast(200).toSet())
                .apply()

            val seriousActive = (0 until alerts.length())
                .map { alerts.getJSONObject(it) }
                .firstOrNull { weatherAlertShouldInterrupt(it) }

            activeSeriousWeatherAlert = seriousActive
            updateWeatherCollapseForAlert(seriousActive != null)

            val now = System.currentTimeMillis()
            val repeatDue =
                seriousActive != null &&
                weatherRadioTalkgroup != null &&
                prefs.getBoolean("weather_nws_repeat_enabled", true) &&
                lastWeatherTakeoverAt > 0L &&
                now - lastWeatherTakeoverAt >= WeatherTakeoverConfig.REPEAT_MS

            when {
                newSerious.isNotEmpty() ->
                    runWeatherAlertInterruption(
                        newSerious.first(),
                        initial = true
                    )

                repeatDue ->
                    runWeatherAlertInterruption(
                        seriousActive!!,
                        initial = false
                    )
            }
        } finally {
            weatherPolling = false
            if (weatherRefreshPending) {
                weatherRefreshPending = false
                scope.launch { updateWeather() }
            }
        }
    }

    private fun weatherAlertId(alert: JSONObject): String {
        return alert.optString("id").ifBlank {
            listOf(
                alert.optString("event"),
                alert.optString("area"),
                alert.optString("effective"),
                alert.optString("expires")
            ).joinToString("|")
        }
    }

    private fun weatherAlertShouldInterrupt(alert: JSONObject): Boolean {
        val severity = alert.optString("severity").lowercase(Locale.US)
        val event = alert.optString("event").lowercase(Locale.US)
        return severity == "extreme" || severity == "severe" ||
            event.contains("warning") || event.contains("emergency")
    }

    private suspend fun runWeatherAlertInterruption(
        alert: JSONObject,
        initial: Boolean
    ) {
        if (weatherInterrupting) return
        weatherInterrupting = true
        lastWeatherTakeoverAt = System.currentTimeMillis()
        val scannerWasRunning = running

        try {
            if (scannerWasRunning) {
                scannerGeneration += 1
                scannerJob?.cancel()
                scannerJob = null
                cursor = null
                if (controllerFuture.isDone) {
                    try {
                        controllerFuture.get().stop()
                        controllerFuture.get().clearMediaItems()
                    } catch (_: Exception) {}
                }
            }

            findViewById<TextView>(R.id.status).text =
                "${alert.optString("event", "Weather warning")} — WEATHER ALERT"

            if (initial && prefs.getBoolean("weather_alert_sound_enabled", false)) {
                playWeatherAlertTone()
            }

            if (initial && prefs.getBoolean("weather_alert_speech_enabled", true)) {
                speakOfficialWeatherAlert(alert)
            }

            if (scannerWasRunning && running) {
                playLatestNwsSegment()
            }
        } finally {
            weatherInterrupting = false

            if (scannerWasRunning && running) {
                restartScannerAtLiveEdge(
                    "Weather alert complete — returning to live calls…"
                )
            }
        }
    }

    private suspend fun playLatestNwsSegment() {
        findViewById<TextView>(R.id.status).text =
            "Weather alert — switching to NWS Weather Radio…"

        val data = withContext(Dispatchers.IO) {
            weatherRadioTalkgroup?.let { tg -> getJson("/api/call-queue/latest-talkgroup?talkgroup=${URLEncoder.encode(tg, "UTF-8")}&max_age_seconds=300") }
        } ?: return

        val call = data.optJSONObject("call") ?: return
        val audioUrl = call.optString("audio_url")
        if (audioUrl.isBlank()) return

        val controller = withContext(Dispatchers.IO) {
            controllerFuture.get()
        }

        findViewById<TextView>(R.id.nowPlaying).text =
            call.optString("talkgroupLabel", "National Weather Service")
        findViewById<TextView>(R.id.details).text =
            "TGID ${call.optString("talkgroup", weatherRadioTalkgroup ?: "")} • ${call.optString("frequency", "")}"

        val finished = CompletableDeferred<Unit>()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && !finished.isCompleted) {
                    finished.complete(Unit)
                }
            }
        }

        controller.addListener(listener)
        try {
            controller.setMediaItem(MediaItem.fromUri(base + audioUrl))
            controller.prepare()
            controller.play()
            withTimeoutOrNull(WeatherTakeoverConfig.TAKEOVER_MS) {
                finished.await()
            }
        } finally {
            controller.removeListener(listener)
            controller.stop()
            controller.clearMediaItems()
        }
    }

    private suspend fun playWeatherAlertTone() {
        withContext(Dispatchers.Main) {
            val done = CompletableDeferred<Unit>()
            val player = MediaPlayer()

            try {
                player.setAudioAttributes(
                    PlatformAudioAttributes.Builder()
                        .setUsage(PlatformAudioAttributes.USAGE_ALARM)
                        .setContentType(PlatformAudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                player.setDataSource(base + "/weather-alert.wav?v=7")
                player.setVolume(1f, 1f)
                player.setOnPreparedListener { it.start() }
                player.setOnCompletionListener {
                    it.release()
                    if (!done.isCompleted) done.complete(Unit)
                }
                player.setOnErrorListener { mp, _, _ ->
                    try { mp.release() } catch (_: Exception) {}
                    if (!done.isCompleted) done.complete(Unit)
                    true
                }
                player.prepareAsync()
            } catch (_: Exception) {
                try { player.release() } catch (_: Exception) {}
                if (!done.isCompleted) done.complete(Unit)
            }

            withTimeoutOrNull(12000) { done.await() }
        }
    }

    private suspend fun speakOfficialWeatherAlert(alert: JSONObject) {
        if (!textToSpeechReady) return

        // Do not paraphrase safety-critical content. Speak only official fields
        // returned by NWS (or the explicitly marked Admin test fields).
        val parts = mutableListOf<String>()
        listOf(
            alert.optString("event", ""),
            alert.optString("area", ""),
            alert.optString("headline", ""),
            alert.optString("instruction", "").ifBlank {
                alert.optString("description", "")
            }
        ).forEach { value ->
            val clean = value.replace(Regex("\\s+"), " ").trim()
            if (clean.isNotBlank() && clean !in parts) parts.add(clean)
        }

        if (parts.isEmpty()) return
        val spoken = parts.joinToString(". ")

        withContext(Dispatchers.Main) {
            textToSpeech?.speak(
                spoken,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "weather-${System.currentTimeMillis()}"
            )
        }

        withTimeoutOrNull(60000) {
            while (textToSpeech?.isSpeaking == true) delay(200)
        }
    }

    private fun setupCollapsibleSections() {
        setupCollapse(
            R.id.announcementCollapse,
            R.id.announcementContent,
            "collapse_announcements"
        )
        setupCollapse(
            R.id.transcriptCollapse,
            R.id.transcriptContent,
            "collapse_transcripts"
        )
        setupCollapse(
            R.id.alertsCollapse,
            R.id.alertsContent,
            "collapse_alerts"
        )
        setupCollapse(
            R.id.recentCallsCollapse,
            R.id.recentCallsContent,
            "collapse_recent_calls"
        )
    }

    private fun setupCollapse(buttonId: Int, contentId: Int, key: String) {
        val button = findViewById<Button>(buttonId)
        val content = findViewById<View>(contentId)

        fun applyState(collapsed: Boolean) {
            content.visibility = if (collapsed) View.GONE else View.VISIBLE
            button.text = if (collapsed) "EXPAND" else "COLLAPSE"
        }

        applyState(prefs.getBoolean(key, false))

        button.setOnClickListener {
            val collapsed = content.visibility != View.GONE
            prefs.edit().putBoolean(key, collapsed).apply()
            applyState(collapsed)
        }
    }

    private suspend fun updateStatsAndAlerts() = withContext(Dispatchers.IO) {
        val stats = getJson("/api/listeners/stats")
        val alertsJson = getJson("/api/alerts/active?hours=2")

        withContext(Dispatchers.Main) {
            if (stats != null) {
                val web = stats.optInt("web")
                val android = stats.optInt("android")
                val ios = stats.optInt("ios")

                val breakdown = buildList {
                    add("Web $web")
                    add("App $android")
                    if (ios > 0) add("iOS $ios")
                }.joinToString(" • ")

                findViewById<TextView>(R.id.listeners).text =
                    "Listeners: ${stats.optInt("listeners")}     Peak: ${stats.optInt("peak")}\n$breakdown"
            }

            val arr = alertsJson?.optJSONArray("alerts")
            findViewById<TextView>(R.id.alerts).text =
                if (arr == null || arr.length() == 0) {
                    "No active major incidents"
                } else {
                    (0 until arr.length()).joinToString("\n\n") { i ->
                        val a = arr.getJSONObject(i)
                        val keywords = a.optJSONArray("keywords")?.let { k ->
                            (0 until k.length()).joinToString(", ") { k.getString(it) }
                        } ?: "Alert"
                        "🚨 ${a.optString("talkgroupLabel")}\n$keywords\n${a.optString("transcript")}"
                    }
                }
        }
    }

    private fun sendListenerHeartbeat(): JSONObject? {
        return postListenerEvent("/api/listeners/heartbeat")
    }

    private fun sendListenerLeave(): JSONObject? {
        return postListenerEvent("/api/listeners/leave")
    }

    private fun postListenerEvent(path: String): JSONObject? = try {
        val json = JSONObject()
            .put("session_id", listenerSessionId)
            .put("platform", "android")
            .toString()

        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())

        http.newCall(
            Request.Builder()
                .url(base + path)
                .post(body)
                .build()
        ).execute().use { response ->
            if (response.isSuccessful) JSONObject(response.body?.string() ?: "{}") else null
        }
    } catch (_: Exception) {
        null
    }

    private fun getJson(path: String): JSONObject? = try {
        http.newCall(
            Request.Builder()
                .url(base + path)
                .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                .build()
        ).execute().use { response ->
            if (response.isSuccessful) JSONObject(response.body?.string() ?: "{}") else null
        }
    } catch (_: Exception) {
        null
    }

    override fun onDestroy() {
        running = false
        scannerGeneration += 1
        scannerJob?.cancel()
        scannerJob = null
        liveEventsJob?.cancel()
        liveEventsJob = null
        weatherJob?.cancel()
        weatherJob = null
        cursor = null

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }

        runBlocking(Dispatchers.IO) {
            sendListenerLeave()
        }

        if (controllerFuture.isDone) {
            try {
                controllerFuture.get().stop()
                controllerFuture.get().clearMediaItems()
            } catch (_: Exception) {
            }
        }

        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null

        MediaController.releaseFuture(controllerFuture)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 2001
    }
}
