package com.thelightphone.sdk.field

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import com.thelightphone.sdk.SealedLightContext
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Records the microphone to a .wav file from a foreground service, so recording keeps going
 * with the screen locked or the tool in the background. Added to this copy of the SDK for the
 * Field tool. Uses the unprocessed mic (no auto gain or noise suppression) when the phone has it.
 */
object LightFieldRecorder {

    data class State(
        val recording: Boolean = false,
        val file: File? = null,
        val startedAtMillis: Long = 0,
        val frames: Long = 0,
        val sampleRate: Int = 48_000,
        val unprocessed: Boolean = false,
        val error: String? = null,
    ) {
        val elapsedMs: Long get() = frames * 1000 / sampleRate
    }

    internal val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Peak level of the latest audio, 0..1. Also updates while [monitor] runs. */
    internal val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Set when any sample hits full scale since the last start. */
    internal val _clipped = MutableStateFlow(false)
    val clipped: StateFlow<Boolean> = _clipped.asStateFlow()

    fun start(ctx: SealedLightContext, file: File, sampleRate: Int = 48_000) {
        if (_state.value.recording) return
        val c = ctx.androidContext.applicationContext
        _clipped.value = false
        _state.value = State(recording = true, file = file, startedAtMillis = System.currentTimeMillis(), sampleRate = sampleRate)
        c.startForegroundService(
            Intent(c, FieldRecordingService::class.java)
                .setAction(FieldRecordingService.ACTION_START)
                .putExtra(FieldRecordingService.EXTRA_PATH, file.absolutePath)
                .putExtra(FieldRecordingService.EXTRA_RATE, sampleRate)
        )
    }

    fun stop(ctx: SealedLightContext) {
        val c = ctx.androidContext.applicationContext
        c.startService(Intent(c, FieldRecordingService::class.java).setAction(FieldRecordingService.ACTION_STOP))
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    // Level meter while not recording, so you can check the sound before pressing record.

    @Volatile private var monitorRunning = false

    @SuppressLint("MissingPermission")
    fun startMonitor(ctx: SealedLightContext) {
        if (monitorRunning || _state.value.recording) return
        monitorRunning = true
        val am = ctx.androidContext.getSystemService(AudioManager::class.java)
        thread(isDaemon = true, name = "FieldMonitor") {
            val rec = runCatching { openRecord(am, 48_000).first }.getOrNull()
            if (rec == null) {
                monitorRunning = false
                return@thread
            }
            val buf = ShortArray(2048)
            runCatching {
                rec.startRecording()
                while (monitorRunning && !_state.value.recording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) _level.value = peak(buf, n)
                }
            }
            runCatching { rec.stop() }
            rec.release()
            monitorRunning = false
        }
    }

    fun stopMonitor() {
        monitorRunning = false
    }

    internal fun peak(buf: ShortArray, n: Int): Float {
        var p = 0
        for (i in 0 until n) {
            val v = abs(buf[i].toInt())
            if (v > p) p = v
        }
        if (p >= 32_700) _clipped.value = true
        return p / 32_768f
    }

    /** Returns the recorder and whether it is the unprocessed mic. */
    @SuppressLint("MissingPermission")
    internal fun openRecord(am: AudioManager, rate: Int): Pair<AudioRecord, Boolean> {
        val unprocessedOk = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = if (unprocessedOk) listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
        else listOf(MediaRecorder.AudioSource.MIC)
        val minBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        for (src in sources) {
            val r = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(src)
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                    )
                    .setBufferSizeInBytes(maxOf(minBytes, rate)) // ~0.5 s of headroom
                    .build()
            }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) return r to (src == MediaRecorder.AudioSource.UNPROCESSED)
            r?.release()
        }
        error("Microphone is unavailable")
    }
}

/** Owns the microphone while recording. */
internal class FieldRecordingService : Service() {
    companion object {
        const val ACTION_START = "com.thelightphone.sdk.field.START"
        const val ACTION_STOP = "com.thelightphone.sdk.field.STOP"
        const val EXTRA_PATH = "path"
        const val EXTRA_RATE = "rate"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 5150
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                val path = intent.getStringExtra(EXTRA_PATH)
                if (path != null && !running) begin(File(path), intent.getIntExtra(EXTRA_RATE, 48_000))
            }
            else -> end()
        }
        return START_NOT_STICKY
    }

    private fun begin(file: File, rate: Int) {
        LightFieldRecorder.stopMonitor()
        val (rec, unprocessed) = try {
            LightFieldRecorder.openRecord(getSystemService(AudioManager::class.java), rate)
        } catch (t: Throwable) {
            fail("Couldn't open the microphone")
            return
        }
        file.parentFile?.mkdirs()
        val wav = WavWriter(file, rate)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LightField:record").apply { acquire(12 * 60 * 60_000L) }
        running = true
        LightFieldRecorder._state.value = LightFieldRecorder._state.value.copy(
            recording = true, file = file, sampleRate = rate, unprocessed = unprocessed, frames = 0,
        )
        worker = thread(name = "FieldRecorder") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(rate / 10)
            var lastHeader = System.currentTimeMillis()
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    wav.write(buf, n)
                    LightFieldRecorder._level.value = LightFieldRecorder.peak(buf, n)
                    LightFieldRecorder._state.value = LightFieldRecorder._state.value.copy(frames = wav.frames)
                    val now = System.currentTimeMillis()
                    if (now - lastHeader > 2_000) {
                        wav.flushHeader()
                        lastHeader = now
                    }
                }
            } catch (t: Throwable) {
                Log.e("FieldRecorder", "Recording failed", t)
                LightFieldRecorder._state.value = LightFieldRecorder._state.value.copy(error = "Recording stopped unexpectedly")
            } finally {
                runCatching { rec.stop() }
                rec.release()
                runCatching { wav.close() }
                LightFieldRecorder._level.value = 0f
                LightFieldRecorder._state.value = LightFieldRecorder._state.value.copy(recording = false)
            }
        }
    }

    private fun end() {
        running = false
        worker?.let { if (it !== Thread.currentThread()) runCatching { it.join(2_000) } }
        worker = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        if (LightFieldRecorder._state.value.recording) {
            LightFieldRecorder._state.value = LightFieldRecorder._state.value.copy(recording = false)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        LightFieldRecorder._state.value = LightFieldRecorder._state.value.copy(recording = false, error = message)
        end()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Recording")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        if (running) end()
        super.onDestroy()
    }
}
