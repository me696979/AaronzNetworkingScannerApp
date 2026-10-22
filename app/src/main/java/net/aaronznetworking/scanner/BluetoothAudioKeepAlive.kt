package net.aaronznetworking.scanner

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * Optional Bluetooth-only output keepalive for head units that gate short scanner clips.
 * Does not alter MP3 data, the call queue, or the normal ExoPlayer configuration.
 */
class BluetoothAudioKeepAlive(context: Context) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var scannerActive = false
    private var track: AudioTrack? = null
    @Volatile private var writing = false
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    init {
        manager.registerAudioDeviceCallback(callback, handler)
    }

    fun setScannerActive(active: Boolean) {
        scannerActive = active
        refresh()
    }

    private fun bluetoothOutputAvailable(): Boolean =
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }

    private fun refresh() {
        if (scannerActive && bluetoothOutputAvailable()) start() else stop()
    }

    private fun start() {
        if (track != null) return
        val sampleRate = 16000
        val bufferSize = maxOf(
            AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            ), 4096
        )
        if (bufferSize <= 0) return
        try {
            val audio = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()
            if (audio.state != AudioTrack.STATE_INITIALIZED) {
                audio.release()
                return
            }
            track = audio
            writing = true
            audio.play()
            thread(name = "scanner-bluetooth-keepalive", isDaemon = true) {
                // Low-level alternating signal rather than digital zero, which some
                // car stereos treat as silence. Approximately -78 dBFS.
                val samples = ShortArray(2048) { i -> if (i % 2 == 0) 4 else -4 }
                while (writing && track === audio) {
                    val result = audio.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    if (result < 0) break
                }
            }
        } catch (_: Exception) {
            stop()
        }
    }

    private fun stop() {
        writing = false
        val old = track ?: return
        track = null
        try { old.pause() } catch (_: Exception) {}
        try { old.flush() } catch (_: Exception) {}
        try { old.release() } catch (_: Exception) {}
    }

    fun release() {
        manager.unregisterAudioDeviceCallback(callback)
        stop()
    }
}
