package com.shilapi.xcertplay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayMediaSessionBridge
import com.shilapi.xcertplay.media.CarPlayMediaSource
import com.shilapi.xcertplay.media.MediaCommand
import com.shilapi.xcertplay.media.MediaCommandResult
import com.shilapi.xcertplay.media.NowPlayingState
import com.shilapi.xcertplay.shared.R as UiR
import com.shilapi.xcertplay.web.RootAccess
import com.shilapi.xcertplay.web.WebListenSettings
import com.shilapi.xcertplay.web.WebSession
import com.shilapi.xcertplay.projection.projectionSessions
import java.util.concurrent.CompletableFuture

/** Keeps the connection and its media session alive while no dashboard is visible. */
class DiPlaySessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var media: CarPlayMediaSessionBridge
    private var sessionSubscription: AutoCloseable? = null
    private val main = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var currentState = NowPlayingState()
    private var currentSession: MediaSession? = null
    private var currentArtwork: Bitmap? = null
    private var lastNotificationKey: String? = null
    private var foregroundPosted = false
    private var systemNight = false

    /**
     * CarPlay's day/night mode follows Android's own setting. That used to be read only from
     * `onConfigurationChanged` on the dashboard activity, so with the phone in the car and no
     * screen on, sunset came and went without the iPhone ever being told. Component callbacks on
     * the application are delivered even when no activity is visible.
     */
    private val appearanceCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val night = isDarkMode(newConfig.uiMode)
            if (night == systemNight) return
            systemNight = night
            Log.i(TAG, "system night mode -> $night")
            WebSession.refreshAppearance(this@DiPlaySessionService, newConfig.uiMode)
        }

        override fun onLowMemory() {}
    }

    private val releaseIdleWakeLock = Runnable {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onCreate() {
        super.onCreate()
        com.shilapi.xcertplay.projection.registerProjectionSources(this)
        if (
            com.shilapi.xcertplay.web.TeslaHttpCompatibility.config(this).let {
                it.enabled && it.mode == com.shilapi.xcertplay.network.TeslaHttpMode.ROOT
            }
        )
            RootAccess.checkOnStartup()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(UiR.string.ui_carplay_service),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        media =
            CarPlayMediaSessionBridge(this) { state, session, artwork ->
                currentState = state
                currentSession = session
                currentArtwork = artwork
                if (!destroyed) updateNotification()
            }
        browserMediaBridge = media
        systemNight = isDarkMode(resources.configuration.uiMode)
        application.registerComponentCallbacks(appearanceCallbacks)
        WebSession.setSessionActivityListener { main.post { syncWakeLock() } }
        sessionSubscription =
            projectionSessions.subscribe { _ ->
                main.post {
                    if (!destroyed) {
                        val current = projectionSessions.snapshot()
                        media.bind(current?.media, current?.mediaOutput)
                        updateNotification()
                        syncWakeLock()
                    }
                }
            }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout") // Owned by this foreground service, released in onDestroy.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            media.close()
            runWebAction { WebSession.stop() }
            projectionSessions.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (
            intent?.action == ACTION_MEDIA &&
                !currentState.available &&
                !projectionSessions.hasSession()
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        updateNotification()
        if (intent?.action == ACTION_MEDIA) {
            if (intent.getLongExtra(EXTRA_GENERATION, -1) == media.commandGeneration) {
                intent.getStringExtra(EXTRA_COMMAND)?.let { name ->
                    MediaCommand.entries.firstOrNull { it.name == name }?.let(media::command)
                }
            }
            return START_NOT_STICKY
        }
        runWebAction {
            if (!destroyed) WebSession.start(this)
            updateNotification()
            val sync = { syncWakeLock() }
            if (Looper.myLooper() == Looper.getMainLooper()) sync()
            else main.post { sync() }
        }
        return START_NOT_STICKY
    }

    /**
     * The wake lock exists to keep the encoder and its sockets running while a car is watching.
     * It used to be taken once and never released, so a phone left in the car held a partial wake
     * lock all night and never suspended: 12h of battery for a session nobody was using. Take it
     * only while frames have somewhere to go, keep it for a short grace period in case the
     * browser is reconnecting, then let the phone sleep.
     */
    @SuppressLint("WakelockTimeout") // Released by [releaseIdleWakeLock] whenever the session idles.
    private fun syncWakeLock() {
        if (destroyed) return
        val needed = WebSession.running && (WebSession.hasViewer || projectionSessions.hasSession())
        if (needed) {
            main.removeCallbacks(releaseIdleWakeLock)
            if (wakeLock == null) {
                wakeLock =
                    getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                        .apply {
                            setReferenceCounted(false)
                            acquire()
                        }
            }
            return
        }
        if (wakeLock == null) return
        main.removeCallbacks(releaseIdleWakeLock)
        main.postDelayed(releaseIdleWakeLock, IDLE_RELEASE_DELAY_MILLIS)
    }

    private fun runWebAction(action: () -> Unit) {
        // Ordinary Web listeners are unprivileged; Tesla routing has its own worker.
        action()
    }

    private fun mediaAction(command: MediaCommand, icon: Int, title: Int): Notification.Action {
        val intent =
            Intent(this, DiPlaySessionService::class.java)
                .setAction(ACTION_MEDIA)
                .setData(Uri.parse("wheelplay://media/${media.commandGeneration}/${command.name}"))
                .putExtra(EXTRA_COMMAND, command.name)
                .putExtra(EXTRA_GENERATION, media.commandGeneration)
        val pending =
            PendingIntent.getService(
                this,
                command.ordinal + 10,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Action.Builder(
                Icon.createWithResource(this, icon),
                getString(title),
                pending,
            )
            .build()
    }

    private fun updateNotification() {
        if (destroyed) return
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, DiPlayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_diplay_notification)
                .setContentIntent(open)
                .setOngoing(true)
        val session = currentSession
        if (session != null && currentState.available) {
            builder
                .setContentTitle(
                    currentState.title?.takeIf { it.isNotBlank() }
                        ?: currentState.appName?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.app_name)
                )
                .setContentText(
                    listOfNotNull(currentState.artist, currentState.album)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                )
                .setLargeIcon(currentArtwork)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(
                    mediaAction(
                        MediaCommand.PREVIOUS,
                        R.drawable.ic_media_skip_previous,
                        R.string.notification_action_previous,
                    )
                )
                .addAction(
                    mediaAction(
                        if (currentState.playing) MediaCommand.PAUSE else MediaCommand.PLAY,
                        if (currentState.playing) R.drawable.ic_media_pause
                        else R.drawable.ic_media_play,
                        if (currentState.playing) R.string.notification_action_pause
                        else R.string.notification_action_play,
                    )
                )
                .addAction(
                    mediaAction(
                        MediaCommand.NEXT,
                        R.drawable.ic_media_skip_next,
                        R.string.notification_action_next,
                    )
                )
                .setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(session.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
        } else {
            val stop =
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, DiPlaySessionService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val port =
                if (WebSession.running) WebSession.httpPort else WebListenSettings.httpPort(this)
            builder
                .setContentTitle(getString(R.string.app_name))
                .setContentText(
                    if (WebSession.running)
                        getString(UiR.string.notification_streaming_running, port)
                    else getString(UiR.string.ui_preparing_streaming_service)
                )
                .addAction(
                    Notification.Action.Builder(
                            Icon.createWithResource(this, R.drawable.ic_media_stop),
                            getString(UiR.string.ui_stop_service),
                            stop,
                        )
                        .build()
                )
        }
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (currentState.available && currentState.playing ||
            projectionSessions.snapshot()?.let { it.capabilities.audioPlayback && !it.capabilities.browserAudio } == true)
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (
            Build.VERSION.SDK_INT >= 30 &&
                projectionSessions.snapshot()?.capabilities?.microphone == true &&
                AirPlayPersistence.loadMicrophoneSource(this) != CarPlayMicrophoneSource.DISABLED &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        // Playback metadata ticks continuously, and every repost woke system_server and this
        // process: 7000 notification wake locks a night for a notification that never changed.
        val key =
            listOf(
                    currentSession != null && currentState.available,
                    currentState.title,
                    currentState.artist,
                    currentState.album,
                    currentState.playing,
                    currentArtwork?.let { System.identityHashCode(it) },
                    WebSession.running,
                    WebSession.httpPort,
                    types,
                )
                .joinToString("|")
        if (foregroundPosted && key == lastNotificationKey) return
        val notification = builder.build()
        lastNotificationKey = key
        foregroundPosted = true
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, types)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        destroyed = true
        if (browserMediaBridge === media) browserMediaBridge = null
        application.unregisterComponentCallbacks(appearanceCallbacks)
        WebSession.setSessionActivityListener(null)
        main.removeCallbacks(releaseIdleWakeLock)
        sessionSubscription?.close()
        media.close()
        runWebAction { WebSession.stop() }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        projectionSessions.stop()
        super.onDestroy()
    }

    companion object {
        // Only accessed on the main thread, like the native MediaSession callbacks.
        private var browserMediaBridge: CarPlayMediaSessionBridge? = null
        internal fun browserMediaCommand(source: CarPlayMediaSource, generation: Long, command: MediaCommand): CompletableFuture<MediaCommandResult> =
            browserMediaBridge?.browserCommand(source, generation, command)
                ?: CompletableFuture.completedFuture(MediaCommandResult.UNAVAILABLE)

        internal fun browserMediaSeek(source: CarPlayMediaSource, generation: Long, track: Long, positionMs: Long): CompletableFuture<MediaCommandResult> =
            browserMediaBridge?.browserSeek(source, generation, track, positionMs)
                ?: CompletableFuture.completedFuture(MediaCommandResult.UNAVAILABLE)

        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
        private const val ACTION_MEDIA = "com.shilapi.xcertplay.MEDIA_COMMAND"
        private const val EXTRA_COMMAND = "command"
        private const val EXTRA_GENERATION = "generation"
        private const val CHANNEL = "diplay_connection"
        private const val TAG = "DiPlay-Service"
        private const val WAKE_LOCK_TAG = "WheelPlay:server"

        /** Long enough for a browser reconnecting after its socket dropped, short enough to sleep. */
        private const val IDLE_RELEASE_DELAY_MILLIS = 120_000L
        internal const val NOTIFICATION_ID = 1
    }
}
