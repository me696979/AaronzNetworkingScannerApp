package net.aaronznetworking.scanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class ScannerPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var backgroundJob: Job? = null
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val base = "https://scannerlive.aaronznetworking.net"
    private var networkRevision = 0L
    private var connectivityManager: ConnectivityManager? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { networkRevision++; Log.i("ScannerNetwork", "Network available") }
        override fun onLost(network: Network) { networkRevision++; Log.w("ScannerNetwork", "Network lost") }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { connectivityManager?.registerDefaultNetworkCallback(networkCallback) }
        catch (e: Exception) { Log.w("ScannerNetwork", "Network callback unavailable", e) }

        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
        }

        session = MediaSession.Builder(this, player).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MONITOR -> {
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Scanner monitoring", NotificationManager.IMPORTANCE_LOW)
                )
                val launch = PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("Scannerz Network")
                    .setContentText("Monitoring for scanner calls")
                    .setContentIntent(launch)
                    .setOngoing(true)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .build()
                startForeground(NOTIFICATION_ID, notification)
            }
            ACTION_BACKGROUND -> {
                if (backgroundJob?.isActive != true) {
                    val feed = intent.getStringExtra(EXTRA_FEED) ?: "wv-cabell-001"
                    val resumeAfter = intent.getStringExtra(EXTRA_CURSOR)?.takeIf { it.isNotBlank() }
                    backgroundJob = scope.launch { monitorInBackground(feed, resumeAfter) }
                }
            }
            ACTION_FOREGROUND -> {
                backgroundJob?.cancel()
                backgroundJob = null
            }
            ACTION_STOP_MONITOR -> {
                backgroundJob?.cancel()
                backgroundJob = null
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun fetchJson(path: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(base + path).build()).execute().use { response ->
                if (response.isSuccessful) JSONObject(response.body?.string() ?: "{}") else null
            }
        } catch (_: Exception) { null }
    }

    private suspend fun monitorInBackground(feed: String, resumeAfter: String?) {
        val player = session?.player ?: return
        val encoded = URLEncoder.encode(feed, "UTF-8")
        var cursor: String? = resumeAfter
        // Preserve calls queued during the Activity-to-service handoff.
        var edge = if (resumeAfter == null) Instant.now() else Instant.MIN
        while (currentCoroutineContext().isActive) {
            try {
                if (cursor == null) {
                    val json = fetchJson("/api/call-queue/latest?feed=$encoded")
                    val latest = json?.optString("latest_id")?.takeIf { it.isNotBlank() && it != "null" }
                    if (latest != null) {
                        cursor = latest
                        edge = Instant.now()
                    }
                } else {
                    val json = fetchJson("/api/call-queue?feed=$encoded&limit=20&after=$cursor")
                    val calls = json?.optJSONArray("calls")
                    if (calls != null) {
                        for (i in 0 until calls.length()) {
                            if (!currentCoroutineContext().isActive) break
                            val call = calls.optJSONObject(i) ?: continue
                            val id = call.optString("id")
                            if (id.isNotBlank()) cursor = id
                            val received = try { Instant.parse(call.optString("received_at")) } catch (_: Exception) { null }
                            if (received != null && !received.isAfter(edge)) continue
                            val blocked = getSharedPreferences("scanner_prefs", MODE_PRIVATE)
                                .getStringSet("blocked_talkgroups", emptySet()).orEmpty()
                            val db = call.optInt("talkgroup_database_id", 0)
                            val tg = call.optString("talkgroup").trim()
                            if ((db > 0 && "db:$db" in blocked) || "sdr:$tg" in blocked || tg in blocked) continue
                            val audio = call.optString("audio_url")
                            if (audio.isBlank()) continue
                            val url = if (audio.startsWith("https://")) audio else base + audio
                            var attempt = 0
                            var resumePosition = 0L
                            while (currentCoroutineContext().isActive && attempt < 3) {
                                attempt++
                                val revisionAtStart = networkRevision
                                player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                                player.prepare()
                                if (resumePosition > 0L) player.seekTo(resumePosition)
                                player.play()
                                var stalledAt = System.currentTimeMillis()
                                var lastPosition = -1L
                                while (currentCoroutineContext().isActive &&
                                    player.playbackState != Player.STATE_ENDED &&
                                    player.playerError == null) {
                                    val position = player.currentPosition
                                    if (position > lastPosition + 100) {
                                        lastPosition = position
                                        stalledAt = System.currentTimeMillis()
                                    }
                                    if (System.currentTimeMillis() - stalledAt > 15000 ||
                                        (networkRevision != revisionAtStart &&
                                            System.currentTimeMillis() - stalledAt > 3000)) break
                                    delay(200)
                                }
                                if (player.playbackState == Player.STATE_ENDED) break
                                resumePosition = player.currentPosition.coerceAtLeast(0L)
                                Log.w("ScannerNetwork", "Playback interrupted for call $id, retry $attempt")
                                player.stop()
                                delay((attempt * 1000L).coerceAtMost(3000L))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Retry polling after transient network or playback failures.
            }
            delay(1000)
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = session

    companion object {
        const val ACTION_MONITOR = "net.aaronznetworking.scanner.MONITOR"
        const val ACTION_BACKGROUND = "net.aaronznetworking.scanner.BACKGROUND"
        const val ACTION_FOREGROUND = "net.aaronznetworking.scanner.FOREGROUND"
        const val EXTRA_FEED = "feed"
        const val EXTRA_CURSOR = "cursor"
        const val ACTION_STOP_MONITOR = "net.aaronznetworking.scanner.STOP_MONITOR"
        private const val CHANNEL = "scanner_monitoring"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onDestroy() {
        try { connectivityManager?.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
