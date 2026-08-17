package net.aaronznetworking.scanner

import android.content.ComponentName
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
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

class MainActivity : AppCompatActivity() {
    private val base = "https://scannerlive.aaronznetworking.net"
    private val http = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var running = false
    private var cursor: String? = null
    private val listenerSessionId = "android-${UUID.randomUUID()}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        controllerFuture = MediaController.Builder(
            this,
            SessionToken(this, ComponentName(this, ScannerPlaybackService::class.java))
        ).buildAsync()

        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (!running) startScanner() else stopScanner()
        }

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
    }

    private fun startScanner() {
        running = true
        findViewById<Button>(R.id.startButton).text = "STOP SCANNER"
        findViewById<TextView>(R.id.status).text = "Starting at live edge…"

        scope.launch {
            withContext(Dispatchers.IO) {
                sendListenerHeartbeat()
            }

            cursor = withContext(Dispatchers.IO) {
                getJson("/api/call-queue/latest")?.optString("latest_id")
            }

            if (cursor.isNullOrBlank()) {
                findViewById<TextView>(R.id.status).text = "Server connection failed - retrying…"
            } else {
                findViewById<TextView>(R.id.status).text = "Waiting for next call…"
            }

            while (running) {
                if (cursor.isNullOrBlank()) {
                    cursor = withContext(Dispatchers.IO) {
                        getJson("/api/call-queue/latest")?.optString("latest_id")
                    }
                } else {
                    pollCalls()
                }
                delay(1500)
            }
        }
    }

    private fun stopScanner() {
        running = false

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

    private suspend fun pollCalls() {
        val after = cursor ?: return

        val json = withContext(Dispatchers.IO) {
            getJson("/api/call-queue?limit=20&after=$after")
        } ?: return

        val calls = json.optJSONArray("calls") ?: return

        for (i in 0 until calls.length()) {
            if (!running) break

            val call = calls.getJSONObject(i)
            cursor = call.optString("id", cursor)
            playCallAndWait(call)
        }
    }

    private suspend fun playCallAndWait(call: JSONObject) {
        val controller = withContext(Dispatchers.IO) {
            controllerFuture.get()
        }

        val label = call.optString(
            "talkgroupLabel",
            "Talkgroup ${call.optString("talkgroup")}"
        )

        val frequency = formatFrequency(call.optString("frequency"))
        val radio = call.optString("source")
        val tg = call.optString("talkgroup")
        val audioUrl = call.optString("audio_url")

        if (audioUrl.isBlank()) return

        findViewById<TextView>(R.id.nowPlaying).text = label
        findViewById<TextView>(R.id.details).text =
            "TGID $tg  •  $frequency  •  Radio $radio"
        findViewById<TextView>(R.id.transcript).text =
            call.optString("transcript", "Transcription pending…")
        findViewById<TextView>(R.id.status).text = "Playing"

        controller.setMediaItem(MediaItem.fromUri(base + audioUrl))
        controller.prepare()
        controller.play()

        while (
            running &&
            controller.playerError == null &&
            controller.playbackState != Player.STATE_ENDED
        ) {
            delay(150)
        }

        if (running) {
            findViewById<TextView>(R.id.nowPlaying).text = "Scanning…"
            findViewById<TextView>(R.id.details).text = ""
            findViewById<TextView>(R.id.status).text = "Waiting for next call…"
        }
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
        if (running) {
            runBlocking(Dispatchers.IO) {
                sendListenerLeave()
            }
        }
        MediaController.releaseFuture(controllerFuture)
        scope.cancel()
        super.onDestroy()
    }
}
