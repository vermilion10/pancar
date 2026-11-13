package com.github.vermilion10.pancar.stream

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.github.vermilion10.pancar.MainActivity
import com.github.vermilion10.pancar.R
import com.github.vermilion10.pancar.settings.StreamSettings
import com.github.vermilion10.pancar.youtube.Broadcast
import com.github.vermilion10.pancar.youtube.YouTubeLiveMonitor
import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.audio.SilenceAudioSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.rtmp.RtmpStream
import com.pedro.library.util.BitrateAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class StreamService : Service(), ConnectChecker {

    companion object {
        private const val TAG = "StreamService"
        private const val ACTION_START = "com.github.vermilion10.pancar.action.START"
        private const val ACTION_STOP = "com.github.vermilion10.pancar.action.STOP"
        private const val ACTION_TOGGLE_MIC = "com.github.vermilion10.pancar.action.TOGGLE_MIC"
        private const val ACTION_TOGGLE_PRIVACY = "com.github.vermilion10.pancar.action.TOGGLE_PRIVACY"
        private const val EXTRA_BROADCAST_ID = "broadcast_id"
        private const val EXTRA_CHAT_ID = "live_chat_id"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "live"
        private const val NOTIFICATION_ID = 1
        private const val MAX_RETRIES = 10
        private const val RETRY_DELAY_MS = 5_000L
        private const val SAMPLE_RATE = 48_000
        private const val AUDIO_BITRATE = 160_000

        fun start(
            context: Context,
            resultCode: Int,
            projectionData: Intent,
            settings: StreamSettings,
            broadcast: Broadcast? = null,
        ) {
            val intent = Intent(context, StreamService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_PROJECTION_DATA, projectionData)
                .putExtra(EXTRA_BROADCAST_ID, broadcast?.id)
                .putExtra(EXTRA_CHAT_ID, broadcast?.liveChatId)
            ContextCompat.startForegroundService(context, settings.writeTo(intent))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, StreamService::class.java).setAction(ACTION_STOP))
        }

        fun toggleMic(context: Context) {
            context.startService(Intent(context, StreamService::class.java).setAction(ACTION_TOGGLE_MIC))
        }

        fun togglePrivacy(context: Context) {
            context.startService(Intent(context, StreamService::class.java).setAction(ACTION_TOGGLE_PRIVACY))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var stream: RtmpStream? = null
    private var projection: MediaProjection? = null
    private var mixSource: MixAudioSource? = null
    private var bitrateAdapter: BitrateAdapter? = null
    private var bubble: LiveBubble? = null
    private var attempt = 0
    private var stopping = false
    private var privacyFilter: ImageFilterRender? = null
    private var monitor: YouTubeLiveMonitor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_STOP -> shutdown()
            ACTION_TOGGLE_MIC -> toggleMic()
            ACTION_TOGGLE_PRIVACY -> togglePrivacy()
            // A restart without a projection token can't capture anything.
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun begin(intent: Intent) {
        if (stream != null) return
        stopping = false
        val settings = StreamSettings.fromIntent(intent)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_PROJECTION_DATA, Intent::class.java)

        val audioAllowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val useMic = settings.micEnabled && audioAllowed

        // Android 14 requires the foreground service to be running before getMediaProjection().
        createChannel()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (useMic) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(getString(R.string.notif_connecting), null), types)
        StreamController.connecting()

        val mp = data?.let {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, it)
        }
        if (mp == null) {
            shutdown("Screen capture permission was not granted.")
            return
        }
        projection = mp
        val projectionCallback = object : MediaProjection.Callback() {
            // The user ended capture from the system UI.
            override fun onStop() {
                handler.post { shutdown() }
            }
        }

        val audio: AudioSource = when {
            !audioAllowed -> SilenceAudioSource()
            useMic -> MixAudioSource(mp).also { mixSource = it }
            else -> InternalAudioSource(mp)
        }

        val quality = settings.quality
        val s = RtmpStream(this, this, ScreenSource(applicationContext, mp, projectionCallback), audio)
        // MediaProjection only emits frames when the screen changes; keep a steady frame rate for the server.
        s.getGlInterface().setForceRender(true, quality.fps)
        val prepared = try {
            s.prepareVideo(quality.width, quality.height, quality.bitrate, quality.fps, iFrameInterval = 2, rotation = 0) &&
                s.prepareAudio(SAMPLE_RATE, true, AUDIO_BITRATE, echoCanceler = useMic, noiseSuppressor = useMic)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "prepare failed", e)
            false
        }
        if (!prepared) {
            s.release()
            shutdown("This phone can't encode ${quality.label}. Try a lower quality.")
            return
        }
        when (audio) {
            is MixAudioSource -> {
                audio.microphoneVolume = settings.micVolume
                audio.internalVolume = settings.gameVolume
            }
            is InternalAudioSource -> audio.internalVolume = settings.gameVolume
        }

        s.getStreamClient().apply {
            setReTries(MAX_RETRIES)
            setLogs(false)
        }
        bitrateAdapter = BitrateAdapter { bps -> stream?.setVideoBitrateOnFly(bps) }.apply {
            setMaxBitrate(quality.bitrate)
        }
        stream = s
        attempt = 0

        try {
            s.startStream(settings.endpoint)
        } catch (e: Exception) {
            Log.w(TAG, "startStream failed", e)
            shutdown("Couldn't start the stream: ${e.message}")
            return
        }

        intent.getStringExtra(EXTRA_BROADCAST_ID)?.let { videoId ->
            monitor = YouTubeLiveMonitor(applicationContext, videoId, intent.getStringExtra(EXTRA_CHAT_ID))
                .also { it.start(scope) }
        }
        privacyFilter = ImageFilterRender().apply {
            setImage(privacyCard(quality.width, quality.height))
            setScale(100f, 100f)
            setPosition(0f, 0f)
        }

        if (Settings.canDrawOverlays(this)) {
            bubble = LiveBubble(
                this,
                hasMic = useMic,
                onToggleMic = ::toggleMic,
                onTogglePrivacy = ::togglePrivacy,
                onStop = { shutdown() },
            ).also { it.show() }
        }
    }

    private fun toggleMic() {
        val mic = mixSource ?: return
        if (mic.isMuted()) mic.unMute() else mic.mute()
        StreamController.setMicMuted(mic.isMuted())
    }

    // Covers the captured screen with a full-frame card; the capture keeps running underneath.
    private fun togglePrivacy() {
        val s = stream ?: return
        val filter = privacyFilter ?: return
        val on = !StreamController.privacy.value
        if (on) s.getGlInterface().addFilter(filter) else s.getGlInterface().removeFilter(filter)
        StreamController.setPrivacy(on)
    }

    private fun privacyCard(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF121016.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = height * 0.09f
        }
        canvas.drawText("Be right back", width / 2f, height * 0.5f, paint)
        paint.apply {
            color = 0xFF8A8494.toInt()
            typeface = Typeface.DEFAULT
            textSize = height * 0.04f
        }
        canvas.drawText("The stream will continue shortly", width / 2f, height * 0.5f + height * 0.09f, paint)
        return bitmap
    }

    private fun shutdown(failure: String? = null) {
        if (stopping) return
        stopping = true
        monitor?.stop()
        monitor = null
        privacyFilter = null
        bubble?.dismiss()
        bubble = null
        stream?.let {
            if (it.isStreaming) it.stopStream()
            it.release()
        }
        stream = null
        mixSource = null
        projection?.stop()
        projection = null
        StreamController.stopped(failure)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ConnectChecker callbacks arrive on the RTMP thread.

    override fun onConnectionStarted(url: String) {}

    override fun onConnectionSuccess() {
        handler.post {
            val s = stream ?: return@post
            attempt = 0
            s.getStreamClient().setReTries(MAX_RETRIES)
            StreamController.live()
            updateNotification(getString(R.string.notif_live), (StreamController.status.value as? StreamStatus.Live)?.since)
        }
    }

    override fun onConnectionFailed(reason: String) {
        handler.post {
            val s = stream ?: return@post
            if (s.getStreamClient().reTry(RETRY_DELAY_MS, reason)) {
                attempt++
                StreamController.reconnecting(attempt, reason)
                updateNotification(getString(R.string.notif_reconnecting), null)
            } else {
                shutdown("Connection failed: $reason")
            }
        }
    }

    override fun onNewBitrate(bitrate: Long) {
        StreamController.setBitrate(bitrate)
        val s = stream ?: return
        bitrateAdapter?.adaptBitrate(bitrate, s.getStreamClient().hasCongestion())
    }

    override fun onDisconnect() {}

    override fun onAuthError() {
        handler.post { shutdown("The server rejected the stream key.") }
    }

    override fun onAuthSuccess() {}

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(title: String, liveSinceElapsed: Long?) {
        if (stopping) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, liveSinceElapsed))
    }

    private fun notification(title: String, liveSinceElapsed: Long?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (liveSinceElapsed != null) {
                    // Chronometer counts from wall-clock time.
                    val wallStart = System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - liveSinceElapsed)
                    setWhen(wallStart).setUsesChronometer(true).setShowWhen(true)
                }
            }
            .build()
    }
}
