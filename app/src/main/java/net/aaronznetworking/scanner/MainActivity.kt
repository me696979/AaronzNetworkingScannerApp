package net.aaronznetworking.scanner

import android.content.ComponentName
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val base = "https://scannerlive.aaronznetworking.net"
    private val http = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var running = false
    private var cursor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        controllerFuture = MediaController.Builder(this, SessionToken(this, ComponentName(this, ScannerPlaybackService::class.java))).buildAsync()
        findViewById<Button>(R.id.startButton).setOnClickListener { if (!running) startScanner() else stopScanner() }
        scope.launch { while (isActive) { updateStatsAndAlerts(); delay(15000) } }
    }

    private fun startScanner() {
        running = true
        findViewById<Button>(R.id.startButton).text = "STOP SCANNER"
        findViewById<TextView>(R.id.status).text = "Starting at live edge…"
        scope.launch {
            cursor = getJson("/api/call-queue/latest")?.optString("latest_id")
            findViewById<TextView>(R.id.status).text = "Waiting for next call…"
            while (running) { pollCalls(); delay(2000) }
        }
    }

    private fun stopScanner() {
        running = false
        if (controllerFuture.isDone) controllerFuture.get().stop()
        findViewById<Button>(R.id.startButton).text = "START SCANNER"
        findViewById<TextView>(R.id.status).text = "Stopped"
        findViewById<TextView>(R.id.nowPlaying).text = "Scanning…"
    }

    private suspend fun pollCalls() = withContext(Dispatchers.IO) {
        val after = cursor ?: return@withContext
        val json = getJson("/api/call-queue?limit=20&after=$after") ?: return@withContext
        val calls = json.optJSONArray("calls") ?: return@withContext
        for (i in 0 until calls.length()) {
            if (!running) break
            val call = calls.getJSONObject(i)
            cursor = call.optString("id", cursor)
            withContext(Dispatchers.Main) { playCall(call) }
            while (running && controllerFuture.isDone && controllerFuture.get().isPlaying) delay(200)
        }
    }

    private fun playCall(call: JSONObject) {
        val label = call.optString("talkgroupLabel", "Talkgroup ${call.optString("talkgroup")}")
        findViewById<TextView>(R.id.nowPlaying).text = label
        findViewById<TextView>(R.id.details).text = "TGID ${call.optString("talkgroup")} • ${call.optString("frequency")} • Radio ${call.optString("source")}"
        findViewById<TextView>(R.id.transcript).text = call.optString("transcript", "Transcription pending…")
        findViewById<TextView>(R.id.status).text = "Playing"
        controllerFuture.addListener({
            val c = controllerFuture.get()
            c.setMediaItem(MediaItem.fromUri(base + call.optString("audio_url")))
            c.prepare(); c.play()
        }, mainExecutor)
    }

    private suspend fun updateStatsAndAlerts() = withContext(Dispatchers.IO) {
        val stats = getJson("/api/listeners/stats")
        val alertsJson = getJson("/api/alerts/active?hours=2")
        withContext(Dispatchers.Main) {
            if (stats != null) findViewById<TextView>(R.id.listeners).text = "Listeners: ${stats.optInt("listeners")}   Peak: ${stats.optInt("peak")}"
            val arr = alertsJson?.optJSONArray("alerts")
            findViewById<TextView>(R.id.alerts).text = if (arr == null || arr.length() == 0) "No active major incidents" else (0 until arr.length()).joinToString("\n\n") { i ->
                val a = arr.getJSONObject(i); "🚨 ${a.optString("talkgroupLabel")}\n${a.optJSONArray("keywords")?.let { k -> (0 until k.length()).joinToString(", ") { k.getString(it) } } ?: "Alert"}\n${a.optString("transcript")}"
            }
        }
    }

    private fun getJson(path: String): JSONObject? = try {
        http.newCall(Request.Builder().url(base + path).cacheControl(okhttp3.CacheControl.FORCE_NETWORK).build()).execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string() ?: "{}") else null }
    } catch (_: Exception) { null }

    override fun onDestroy() { MediaController.releaseFuture(controllerFuture); scope.cancel(); super.onDestroy() }
}
