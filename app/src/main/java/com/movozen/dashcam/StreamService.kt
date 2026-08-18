package com.movozen.dashcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.SurfaceView
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.generic.GenericStream

/**
 * Owns the encoders + RTMP connections.
 *
 * It lives in a foreground service (type camera|microphone) so the stream keeps
 * running when the screen locks or the app is backgrounded — that is the Tier 3
 * requirement in the brief.
 *
 * FRONT feed  -> {ROLLNO}_front
 * BACK  feed  -> {ROLLNO}_back
 */
class StreamService : Service() {

    enum class Feed { FRONT, BACK }
    enum class State { IDLE, CONNECTING, LIVE, RETRYING, ERROR }

    interface Listener {
        fun onLog(line: String)
        fun onState(state: State, detail: String)
        fun onStats(kbps: Long, uptimeSeconds: Long, dualActive: Boolean)
    }

    companion object {
        private const val TAG = "Dashcam"
        private const val CHANNEL_ID = "dashcam_stream"
        private const val NOTIF_ID = 4242
    }

    inner class LocalBinder : Binder() {
        val service: StreamService get() = this@StreamService
    }

    private val binder = LocalBinder()
    private val ui = Handler(Looper.getMainLooper())

    var listener: Listener? = null
        set(value) {
            field = value
            value?.onState(state, stateDetail)
        }

    private var frontStream: GenericStream? = null
    private var backStream: GenericStream? = null

    private var frontUrl = ""
    private var backUrl = ""

    /** true while the user wants to be live — drives auto-reconnect. */
    private var wantStreaming = false
    private var frontLive = false
    private var backLive = false
    private var dualRequested = false
    var dualActive = false
        private set

    private var frontAttempts = 0
    private var backAttempts = 0

    private var startedAt = 0L
    private var frontKbps = 0L
    private var backKbps = 0L

    private var state = State.IDLE
    private var stateDetail = "Not streaming"

    private var wakeLock: PowerManager.WakeLock? = null
    private var previewSurface: SurfaceView? = null
    private var previewRunning = false
    private var prepared = false

    // ---------------------------------------------------------------- lifecycle

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground("Dashcam ready")
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- public API

    /** Builds the front encoder and shows the camera preview. Call once permissions are granted. */
    fun initPreview(surface: SurfaceView) {
        previewSurface = surface
        try {
            if (frontStream == null) {
                frontStream = GenericStream(
                    applicationContext,
                    Checker(Feed.FRONT),
                    Camera2Source(applicationContext),
                    MicrophoneSource()
                )
                log("Encoder created (H.264 ${Config.WIDTH}x${Config.HEIGHT} / AAC ${Config.SAMPLE_RATE}Hz)")
            }
            if (!prepared) {
                val ok = frontStream!!.prepareVideo(Config.WIDTH, Config.HEIGHT, Config.VIDEO_BITRATE) &&
                        frontStream!!.prepareAudio(Config.SAMPLE_RATE, Config.STEREO, Config.AUDIO_BITRATE)
                if (!ok) {
                    setState(State.ERROR, "Encoder init failed")
                    log("ERROR: device could not initialise H.264/AAC encoder at these settings")
                    return
                }
                prepared = true
            }
            if (!previewRunning) {
                frontStream!!.startPreview(surface)
                previewRunning = true
                log("Preview started")
            }
        } catch (e: Exception) {
            log("Preview error: ${e.message}")
        }
    }

    /** Screen off / surface gone: drop the preview but KEEP streaming. */
    fun releasePreview() {
        try {
            if (previewRunning) {
                frontStream?.stopPreview()
                previewRunning = false
                log("Preview released (stream unaffected)")
            }
        } catch (e: Exception) {
            log("stopPreview: ${e.message}")
        }
    }

    fun switchCamera() {
        try {
            (frontStream?.videoSource as? Camera2Source)?.switchCamera()
            log("Camera switched on FRONT feed")
        } catch (e: Exception) {
            log("switchCamera failed: ${e.message}")
        }
    }

    /**
     * @param key   roll-number key, e.g. BTECH_25002_23
     * @param dual  also try to publish the rear camera to {key}_back
     */
    fun start(key: String, dual: Boolean) {
        if (wantStreaming) return
        frontUrl = Config.frontUrl(key)
        backUrl = Config.backUrl(key)
        dualRequested = dual
        wantStreaming = true
        frontAttempts = 0
        backAttempts = 0
        startedAt = System.currentTimeMillis()

        acquireWakeLock()
        goForeground("Connecting…")
        setState(State.CONNECTING, "Connecting to server")
        log("PUSH  $frontUrl")

        val s = frontStream
        if (s == null) {
            setState(State.ERROR, "Encoder not ready — grant camera + mic and reopen")
            wantStreaming = false
            return
        }
        try {
            s.startStream(frontUrl)
        } catch (e: Exception) {
            log("startStream threw: ${e.message}")
            scheduleReconnect(Feed.FRONT)
        }

        if (dual) ui.postDelayed({ startBack() }, 1500)
        ui.post(ticker)
    }

    fun stop() {
        wantStreaming = false
        dualRequested = false
        ui.removeCallbacksAndMessages(null)
        try { frontStream?.stopStream() } catch (_: Exception) {}
        try {
            backStream?.stopStream()
            backStream?.release()
        } catch (_: Exception) {}
        backStream = null
        frontLive = false
        backLive = false
        dualActive = false
        releaseWakeLock()
        setState(State.IDLE, "Stopped")
        log("Stream stopped cleanly")
        goForeground("Dashcam idle")
        stopForeground(true)
        stopSelf()
    }

    /** Tier 2: attempt a second, independent RTMP publish from the rear camera. */
    fun startBack() {
        if (!wantStreaming || backStream != null) return
        try {
            log("Opening second camera for $backUrl")
            val b = GenericStream(
                applicationContext,
                Checker(Feed.BACK),
                Camera2Source(applicationContext),
                MicrophoneSource()
            )
            val ok = b.prepareVideo(Config.WIDTH, Config.HEIGHT, Config.VIDEO_BITRATE) &&
                    b.prepareAudio(Config.SAMPLE_RATE, Config.STEREO, Config.AUDIO_BITRATE)
            if (!ok) {
                log("Second encoder refused these settings — staying single-camera")
                b.release()
                return
            }
            backStream = b
            b.startStream(backUrl)
        } catch (e: Exception) {
            log("Concurrent cameras unsupported on this device (${e.message}). " +
                    "Falling back to fast camera switching — use SWITCH CAM.")
            try { backStream?.release() } catch (_: Exception) {}
            backStream = null
            dualActive = false
        }
    }

    fun isStreaming() = wantStreaming

    // ---------------------------------------------------------------- internals

    private fun scheduleReconnect(feed: Feed) {
        if (!wantStreaming) return
        val attempt = if (feed == Feed.FRONT) ++frontAttempts else ++backAttempts
        setState(State.RETRYING, "Reconnecting (try $attempt)")
        log("$feed disconnected — retrying in 3s (attempt $attempt)")
        ui.postDelayed({
            if (!wantStreaming) return@postDelayed
            try {
                if (feed == Feed.FRONT) {
                    frontStream?.stopStream()
                    frontStream?.startStream(frontUrl)
                } else {
                    backStream?.stopStream()
                    backStream?.startStream(backUrl)
                }
            } catch (e: Exception) {
                log("Retry failed: ${e.message}")
                scheduleReconnect(feed)
            }
        }, Config.RECONNECT_DELAY_MS)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!wantStreaming) return
            val secs = (System.currentTimeMillis() - startedAt) / 1000
            listener?.onStats(frontKbps + backKbps, secs, dualActive)
            goForeground(
                if (frontLive) "LIVE ${fmt(secs)} · ${frontKbps + backKbps} kbps" else "Connecting…"
            )
            ui.postDelayed(this, 1000)
        }
    }

    private fun fmt(s: Long) = String.format("%02d:%02d", s / 60, s % 60)

    private inner class Checker(private val feed: Feed) : ConnectChecker {

        override fun onConnectionStarted(url: String) {
            log("$feed connecting → $url")
        }

        override fun onConnectionSuccess() {
            if (feed == Feed.FRONT) {
                frontLive = true
                frontAttempts = 0
                setState(State.LIVE, "Live on server")
            } else {
                backLive = true
                backAttempts = 0
                dualActive = true
                log("BACK feed live — both cameras publishing")
            }
            log("$feed CONNECTED ✔")
        }

        override fun onConnectionFailed(reason: String) {
            log("$feed connection failed: $reason")
            if (feed == Feed.FRONT) frontLive = false else { backLive = false; dualActive = false }
            if (wantStreaming) scheduleReconnect(feed)
            else setState(State.ERROR, reason)
        }

        override fun onNewBitrate(bitrate: Long) {
            if (feed == Feed.FRONT) frontKbps = bitrate / 1000 else backKbps = bitrate / 1000
        }

        override fun onDisconnect() {
            log("$feed disconnected")
            if (feed == Feed.FRONT) frontLive = false else { backLive = false; dualActive = false }
            if (wantStreaming) scheduleReconnect(feed)
        }

        override fun onAuthError() {
            log("$feed auth error")
            setState(State.ERROR, "Auth error")
        }

        override fun onAuthSuccess() {
            log("$feed auth ok")
        }
    }

    private fun stopEverything() {
        wantStreaming = false
        ui.removeCallbacksAndMessages(null)
        try { frontStream?.stopStream(); frontStream?.stopPreview(); frontStream?.release() } catch (_: Exception) {}
        try { backStream?.stopStream(); backStream?.release() } catch (_: Exception) {}
        frontStream = null
        backStream = null
        prepared = false
        previewRunning = false
        releaseWakeLock()
    }

    private fun setState(s: State, detail: String) {
        state = s
        stateDetail = detail
        ui.post { listener?.onState(s, detail) }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        ui.post { listener?.onLog(line) }
    }

    // ---------------------------------------------------------------- foreground

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Dashcam stream", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun goForeground(text: String) {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MoboSafe Dashcam")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dashcam:stream")
        }
        if (wakeLock?.isHeld == false) wakeLock?.acquire(60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }
}
