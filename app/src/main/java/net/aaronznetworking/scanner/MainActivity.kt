package net.aaronznetworking.scanner

import android.content.ComponentName
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
    private var scannerJob: Job? = null
    private var liveEventsJob: Job? = null
    private var scannerGeneration = 0L
    private val listenerSessionId = "android-${UUID.randomUUID()}"

    private data class TalkgroupOption(
        val id: Int,
        val name: String,
        val agency: String
    )

    private data class TranscriptEntry(
        val id: String,
        val label: String,
        val text: String
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

    private val talkgroups = mutableListOf<TalkgroupOption>()
    private val blockedTalkgroups = mutableSetOf<Int>()
    private val pendingTranscripts = linkedMapOf<String, String>()
    private val seenTranscriptIds = mutableSetOf<String>()
    private val recentTranscripts = mutableListOf<TranscriptEntry>()
    private val activeAnnouncements = mutableListOf<AnnouncementEntry>()
    private var announcementDialogShowing = false

    private val prefs by lazy {
        getSharedPreferences("scanner_prefs", MODE_PRIVATE)
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

        loadSavedBlockedTalkgroups()
        renderRecentTranscripts()
        renderAnnouncements()
        updateTalkgroupButton()

        // Load each initial API independently so one failed request cannot block the other.
        scope.launch { loadTalkgroups() }
        scope.launch { updateAnnouncements() }

        startLiveEvents()

        scope.launch {
            while (isActive) {
                updateStatsAndAlerts()
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
                    // Re-sync after a dropped connection so a change is not missed.
                    withContext(Dispatchers.Main) {
                        launch { loadTalkgroups() }
                        launch { updateAnnouncements() }
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
            "talkgroups_changed" -> {
                withContext(Dispatchers.Main) {
                    loadTalkgroups()
                }
            }

            "announcements_changed" -> {
                withContext(Dispatchers.Main) {
                    updateAnnouncements()
                }
            }
        }
    }

    private fun startScanner() {
        scannerGeneration += 1
        val generation = scannerGeneration

        scannerJob?.cancel()
        scannerJob = null
        cursor = null

        if (controllerFuture.isDone) {
            controllerFuture.get().stop()
            controllerFuture.get().clearMediaItems()
        }

        running = true
        findViewById<Button>(R.id.startButton).text = "STOP SCANNER"
        findViewById<TextView>(R.id.status).text = "Starting at live edge…"
        findViewById<TextView>(R.id.nowPlaying).text = "Scanning…"
        findViewById<TextView>(R.id.details).text = ""

        scannerJob = scope.launch {
            withContext(Dispatchers.IO) {
                sendListenerHeartbeat()
            }

            val liveEdge = withContext(Dispatchers.IO) {
                getJson("/api/call-queue/latest")
                    ?.optString("latest_id")
                    ?.takeIf { it.isNotBlank() && it != "null" }
            }

            if (!running || generation != scannerGeneration) {
                return@launch
            }

            cursor = liveEdge

            findViewById<TextView>(R.id.status).text =
                if (cursor.isNullOrBlank()) {
                    "Connecting to live calls…"
                } else {
                    "Waiting for next call…"
                }

            while (isActive && running && generation == scannerGeneration) {
                if (cursor.isNullOrBlank()) {
                    val refreshedLiveEdge = withContext(Dispatchers.IO) {
                        getJson("/api/call-queue/latest")
                            ?.optString("latest_id")
                            ?.takeIf { it.isNotBlank() && it != "null" }
                    }

                    if (!running || generation != scannerGeneration) {
                        break
                    }

                    cursor = refreshedLiveEdge
                } else {
                    pollCalls(generation)
                }

                delay(1200)
            }
        }
    }

    private fun stopScanner() {
        running = false
        scannerGeneration += 1
        scannerJob?.cancel()
        scannerJob = null
        cursor = null

        scope.launch(Dispatchers.IO) {
            sendListenerLeave()
        }

        if (controllerFuture.isDone) {
            controllerFuture.get().stop()
            controllerFuture.get().clearMediaItems()
        }

        findViewById<Button>(R.id.startButton).text = "START SCANNER"
        findViewById<TextView>(R.id.status).text = "Stopped"
        findViewById<TextView>(R.id.nowPlaying).text = "Scanning…"
        findViewById<TextView>(R.id.details).text = ""
    }

    private suspend fun pollCalls(generation: Long) {
        if (!running || generation != scannerGeneration) return

        val after = cursor ?: return

        // Fetch the unfiltered server queue and apply this listener's choices locally.
        // This prevents stale Redis preferences from blocking Android playback.
        val json = withContext(Dispatchers.IO) {
            getJson("/api/call-queue?limit=20&after=$after")
        } ?: return

        if (!running || generation != scannerGeneration) return

        val calls = json.optJSONArray("calls") ?: return

        for (i in 0 until calls.length()) {
            if (!running || generation != scannerGeneration) break

            val call = calls.getJSONObject(i)
            val callId = call.optString("id")

            if (callId.isNotBlank()) {
                cursor = callId
            }

            val tg = call.optString("talkgroup").toIntOrNull()

            if (tg != null && tg in blockedTalkgroups) {
                continue
            }

            playCallAndWait(call, generation)
        }

        val latest = json.optString("latest_id", "")
        if (latest.isNotBlank() && latest != "null" && latest > (cursor ?: "")) {
            cursor = latest
        }
    }

    private suspend fun playCallAndWait(call: JSONObject, generation: Long) {
        if (!running || generation != scannerGeneration) return

        val controller = withContext(Dispatchers.IO) {
            controllerFuture.get()
        }

        if (!running || generation != scannerGeneration) return

        val label = call.optString(
            "talkgroupLabel",
            "Talkgroup ${call.optString("talkgroup")}"
        )

        val frequency = formatFrequency(call.optString("frequency"))
        val radio = call.optString("source")
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

        findViewById<TextView>(R.id.nowPlaying).text = label
        findViewById<TextView>(R.id.details).text =
            "TGID $tg  •  $frequency  •  Radio $radio"
        findViewById<TextView>(R.id.status).text = "Playing"

        controller.setMediaItem(
            MediaItem.fromUri(base + audioUrl)
        )
        controller.prepare()
        controller.play()

        while (
            currentCoroutineContext().isActive &&
            running &&
            generation == scannerGeneration &&
            controller.playerError == null &&
            controller.playbackState != Player.STATE_ENDED
        ) {
            delay(150)
        }

        if (!running || generation != scannerGeneration) return

        if (controller.playerError != null) {
            findViewById<TextView>(R.id.status).text =
                "Audio error: ${controller.playerError?.errorCodeName ?: "unknown"}"
        } else {
            findViewById<TextView>(R.id.status).text = "Waiting for next call…"
        }

        findViewById<TextView>(R.id.nowPlaying).text = "Scanning…"
        findViewById<TextView>(R.id.details).text = ""
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
                    recentTranscripts.add(
                        0,
                        TranscriptEntry(callId, label, text)
                    )

                    while (recentTranscripts.size > 3) {
                        recentTranscripts.removeAt(recentTranscripts.lastIndex)
                    }

                    renderRecentTranscripts()
                }
            }

            "filtered", "error" -> {
                pendingTranscripts.remove(callId)
            }
        }
    }

    private fun renderRecentTranscripts() {
        val transcriptView = findViewById<TextView>(R.id.transcript)

        transcriptView.text = if (recentTranscripts.isEmpty()) {
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
        if (
            items.isEmpty() ||
            announcementDialogShowing ||
            isFinishing ||
            isDestroyed
        ) {
            return
        }

        val seen = prefs.getStringSet("seen_announcements", emptySet())
            .orEmpty()
            .toMutableSet()

        val unseen = items.filter { it.fingerprint !in seen }

        if (unseen.isEmpty()) return

        val dialogText = unseen.joinToString("\n\n") {
            "${it.title}\n${it.message}"
        }

        announcementDialogShowing = true

        AlertDialog.Builder(this)
            .setTitle("📢 Scanner Announcement")
            .setMessage(dialogText)
            .setPositiveButton("OK") { _, _ ->
                unseen.forEach { seen.add(it.fingerprint) }
                prefs.edit()
                    .putStringSet(
                        "seen_announcements",
                        seen.toList().takeLast(100).toSet()
                    )
                    .apply()
            }
            .setOnDismissListener {
                unseen.forEach { seen.add(it.fingerprint) }
                prefs.edit()
                    .putStringSet(
                        "seen_announcements",
                        seen.toList().takeLast(100).toSet()
                    )
                    .apply()
                announcementDialogShowing = false
            }
            .show()
    }

    private suspend fun loadTalkgroups(): Boolean {
        val data = withContext(Dispatchers.IO) {
            getJson("/api/public/talkgroups")
        } ?: return false

        val arr = data.optJSONArray("talkgroups") ?: return false
        val incoming = mutableListOf<TalkgroupOption>()

        for (i in 0 until arr.length()) {
            val tg = arr.getJSONObject(i)
            val id = tg.optInt("talkgroup_id", 0)
            if (id <= 0) continue

            val agency = if (tg.isNull("agency")) {
                ""
            } else {
                tg.optString("agency", "")
            }

            incoming.add(
                TalkgroupOption(
                    id = id,
                    name = tg.optString("name", "Talkgroup $id"),
                    agency = agency
                )
            )
        }

        talkgroups.clear()
        talkgroups.addAll(incoming)
        updateTalkgroupButton()

        return incoming.isNotEmpty()
    }

    private fun loadSavedBlockedTalkgroups() {
        blockedTalkgroups.clear()
        blockedTalkgroups.addAll(
            prefs.getStringSet("blocked_talkgroups", emptySet())
                .orEmpty()
                .mapNotNull { it.toIntOrNull() }
        )
    }

    private fun saveBlockedTalkgroups() {
        prefs.edit()
            .putStringSet(
                "blocked_talkgroups",
                blockedTalkgroups.map { it.toString() }.toSet()
            )
            .apply()
    }

    private fun updateTalkgroupButton() {
        val button = findViewById<Button>(R.id.talkgroupButton)

        if (talkgroups.isEmpty()) {
            button.text = "CHOOSE TALKGROUPS"
            return
        }

        val existingIds = talkgroups.map { it.id }.toSet()
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
            if (tg.agency.isBlank()) {
                "${tg.name} (TG ${tg.id})"
            } else {
                "${tg.name} (TG ${tg.id})\n${tg.agency}"
            }
        }.toTypedArray()

        val checked = BooleanArray(talkgroups.size) { index ->
            talkgroups[index].id !in blockedTalkgroups
        }

        AlertDialog.Builder(this)
            .setTitle("Choose Talkgroups")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                blockedTalkgroups.clear()

                for (i in talkgroups.indices) {
                    if (!checked[i]) {
                        blockedTalkgroups.add(talkgroups[i].id)
                    }
                }

                saveBlockedTalkgroups()
                updateTalkgroupButton()

                scope.launch(Dispatchers.IO) {
                    pushListenerPreferences()
                }
            }
            .setNeutralButton("Enable All") { _, _ ->
                blockedTalkgroups.clear()
                saveBlockedTalkgroups()
                updateTalkgroupButton()

                scope.launch(Dispatchers.IO) {
                    pushListenerPreferences()
                }
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

        val body = json.toRequestBody(
            "application/json; charset=utf-8".toMediaType()
        )

        http.newCall(
            Request.Builder()
                .url(base + "/api/listener/preferences")
                .put(body)
                .build()
        ).execute().use { response ->
            if (response.isSuccessful) {
                JSONObject(response.body?.string() ?: "{}")
            } else {
                null
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun formatFrequency(value: String): String {
        return try {
            val hz = value.toLong()
            String.format("%.4f MHz", hz / 1_000_000.0)
        } catch (_: Exception) {
            value
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
                            (0 until k.length()).joinToString(", ") {
                                k.getString(it)
                            }
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

        val body = json.toRequestBody(
            "application/json; charset=utf-8".toMediaType()
        )

        http.newCall(
            Request.Builder()
                .url(base + path)
                .post(body)
                .build()
        ).execute().use { response ->
            if (response.isSuccessful) {
                JSONObject(response.body?.string() ?: "{}")
            } else {
                null
            }
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
            if (response.isSuccessful) {
                JSONObject(response.body?.string() ?: "{}")
            } else {
                null
            }
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
        cursor = null

        runBlocking(Dispatchers.IO) {
            sendListenerLeave()
        }

        if (controllerFuture.isDone) {
            controllerFuture.get().stop()
            controllerFuture.get().clearMediaItems()
        }

        MediaController.releaseFuture(controllerFuture)
        scope.cancel()
        super.onDestroy()
    }
}
