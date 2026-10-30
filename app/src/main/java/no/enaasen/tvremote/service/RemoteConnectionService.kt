package no.enaasen.tvremote.service

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
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import no.enaasen.tvremote.MainActivity
import no.enaasen.tvremote.R
import no.enaasen.tvremote.data.TvRepository
import no.enaasen.tvremote.data.apps.AppCatalog
import no.enaasen.tvremote.data.diagnostics.DiagnosticCategory
import no.enaasen.tvremote.data.diagnostics.DiagnosticLog
import no.enaasen.tvremote.data.model.AppShortcut
import no.enaasen.tvremote.data.model.ConnectionState
import no.enaasen.tvremote.data.model.MediaSessionInfo
import no.enaasen.tvremote.data.model.MediaState
import no.enaasen.tvremote.data.model.tv
import no.enaasen.tvremote.data.nowplaying.forApp

/**
 * Keeps the selected TV connected while the app is closed, so the remote is
 * ready the moment it's opened. Its notification doubles as a quick remote:
 * when the TV is playing something it becomes a media notification, with the
 * title, progress and playback controls, backed by a media session so the
 * controls also show in the quick settings and on the lock screen.
 */
@AndroidEntryPoint
class RemoteConnectionService : Service() {

    @Inject
    lateinit var repository: TvRepository

    @Inject
    lateinit var log: DiagnosticLog

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var session: MediaSession

    private var shownContent: Content? = null
    private var sessionMetadata: SessionMetadata? = null
    private var sessionPlayback: SessionPlayback? = null
    private val appIcons = mutableMapOf<String, Bitmap?>()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSession(this, "TvRemote").apply {
            setCallback(
                object : MediaSession.Callback() {
                    // The TV has one play/pause key, so both just press it.
                    override fun onPlay() = repository.sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    override fun onPause() = repository.sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    override fun onSkipToNext() = repository.sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                    override fun onSkipToPrevious() = repository.sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    override fun onCustomAction(action: String, extras: Bundle?) {
                        when (action) {
                            ACTION_VOLUME_DOWN -> repository.sendKey(KeyEvent.KEYCODE_VOLUME_DOWN)
                            ACTION_VOLUME_UP -> repository.sendKey(KeyEvent.KEYCODE_VOLUME_UP)
                        }
                    }
                },
            )
            setSessionActivity(openAppIntent())
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(Content(repository.connectionState.value, emptyList(), media = null, appLabel = null)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        repository.setBackgroundServiceRunning(true)
        log.info(DiagnosticCategory.Service, "Background connection started")

        scope.launch {
            combine(repository.connectionState, repository.apps, repository.nowPlaying, ::Triple).collect { (state, apps, sessions) ->
                val packageName = (state as? ConnectionState.Connected)?.status?.currentAppPackage
                val media = packageName?.let(sessions::forApp)
                val appLabel = packageName?.let { pkg -> apps.pinned.find { it.packageName == pkg }?.label ?: AppCatalog.label(pkg) }
                updateSession(media, appLabel)
                // The position moves with every poll, but the notification shows it through the session.
                val content = Content(state, apps.pinned, media?.copy(positionMs = null), appLabel)
                if (content != shownContent) {
                    shownContent = content
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(content))
                }
            }
        }
        scope.launch {
            repository.backgroundConnectionWanted.first { wanted -> !wanted }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getIntExtra(EXTRA_KEY_CODE, KeyEvent.KEYCODE_UNKNOWN)
            ?.takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
            ?.let(repository::sendKey)
        // Restarting from the background isn't allowed to show a foreground
        // service; the app restarts it the next time it's opened.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        repository.setBackgroundServiceRunning(false)
        log.info(DiagnosticCategory.Service, "Background connection stopped")
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_connection),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_connection_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Keeps the media session in step with what the TV plays. The system
     * extrapolates the position from the last report, so a new one is sent only
     * when the state changes or the position has drifted.
     */
    private fun updateSession(media: MediaSessionInfo?, appLabel: String?) {
        if (media == null) {
            session.isActive = false
            sessionMetadata = null
            sessionPlayback = null
            return
        }
        // A cast has no subtitle; the app's name fills the line the controls show under the title.
        val subtitle = media.subtitle ?: appLabel
        val metadata = SessionMetadata(media.title, subtitle, media.durationMs, media.artToken)
        if (metadata != sessionMetadata) {
            sessionMetadata = metadata
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, media.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, media.durationMs ?: -1L)
                    .apply { media.art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                    .build(),
            )
        }
        val now = SystemClock.elapsedRealtime()
        val position = media.positionMs ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
        val state = media.state.toPlaybackState()
        val last = sessionPlayback
        val expected = last?.let { it.position + if (it.state == PlaybackState.STATE_PLAYING) now - it.at else 0L }
        if (last == null || last.state != state || expected == null || abs(position - expected) > POSITION_DRIFT_MS) {
            sessionPlayback = SessionPlayback(state, position, now)
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                    )
                    .addCustomAction(ACTION_VOLUME_DOWN, getString(R.string.volume_down), R.drawable.ic_volume_down)
                    .addCustomAction(ACTION_VOLUME_UP, getString(R.string.volume_up), R.drawable.ic_volume_up)
                    .setState(state, position, if (state == PlaybackState.STATE_PLAYING) 1f else 0f, now)
                    .build(),
            )
        }
        session.isActive = true
    }

    private fun buildNotification(content: Content): Notification {
        val media = content.media
        val state = content.state
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(if (media != null) Notification.CATEGORY_TRANSPORT else Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (media != null) {
            val packageName = (state as? ConnectionState.Connected)?.status?.currentAppPackage
            val appLabel = content.appLabel
            builder
                .setSmallIcon(
                    when (media.state) {
                        MediaState.Playing -> R.drawable.ic_play_arrow
                        MediaState.Paused -> R.drawable.ic_pause
                        else -> R.drawable.ic_tv_fill1
                    },
                )
                .setContentTitle(media.title)
                .setContentText(media.subtitle ?: appLabel)
                .setSubText(listOfNotNull(appLabel, getString(media.state.label())).joinToString(" · "))
                .setLargeIcon(media.art ?: packageName?.let(::appIcon))
                .addKeyAction(R.drawable.ic_skip_previous, R.string.media_previous, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                .addKeyAction(
                    if (media.state == MediaState.Playing) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                    R.string.key_play_pause,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                )
                .addKeyAction(R.drawable.ic_skip_next, R.string.media_next, KeyEvent.KEYCODE_MEDIA_NEXT)
                .addKeyAction(R.drawable.ic_volume_down, R.string.volume_down, KeyEvent.KEYCODE_VOLUME_DOWN)
                .addKeyAction(R.drawable.ic_volume_up, R.string.volume_up, KeyEvent.KEYCODE_VOLUME_UP)
                .setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(session.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2),
                )
        } else {
            builder
                .setSmallIcon(R.drawable.ic_tv_fill1)
                .setContentTitle(state.tv?.name ?: getString(R.string.app_name))
                .setContentText(state.statusText(content.shortcuts))
            if (state is ConnectionState.Connected) {
                builder
                    .addKeyAction(R.drawable.ic_volume_down, R.string.volume_down, KeyEvent.KEYCODE_VOLUME_DOWN)
                    .addKeyAction(R.drawable.ic_play_pause, R.string.key_play_pause, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    .addKeyAction(R.drawable.ic_volume_up, R.string.volume_up, KeyEvent.KEYCODE_VOLUME_UP)
            }
        }
        return builder.build()
    }

    private fun Notification.Builder.addKeyAction(icon: Int, label: Int, keyCode: Int): Notification.Builder = addAction(
        Notification.Action.Builder(
            Icon.createWithResource(this@RemoteConnectionService, icon),
            getString(label),
            PendingIntent.getService(
                this@RemoteConnectionService,
                keyCode,
                Intent(this@RemoteConnectionService, RemoteConnectionService::class.java).putExtra(EXTRA_KEY_CODE, keyCode),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build(),
    )

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /** The app's icon from the phone's edition of it, if installed; looked up once per app. */
    private fun appIcon(packageName: String): Bitmap? = appIcons.getOrPut(packageName) {
        AppCatalog.iconPackages(packageName).firstNotNullOfOrNull { candidate ->
            try {
                packageManager.getApplicationIcon(candidate).toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    private fun ConnectionState.statusText(shortcuts: List<AppShortcut>): String = when (this) {
        is ConnectionState.Connected -> status.currentAppPackage
            ?.let { pkg -> shortcuts.find { it.packageName == pkg }?.label ?: AppCatalog.label(pkg) }
            ?: getString(R.string.status_connected)
        is ConnectionState.Unreachable -> getString(R.string.status_unreachable)
        is ConnectionState.PairingRequired -> getString(R.string.status_pairing_required)
        else -> getString(R.string.status_connecting)
    }

    private fun MediaState.label() = when (this) {
        MediaState.Playing -> R.string.now_playing_state_playing
        MediaState.Paused -> R.string.now_playing_state_paused
        MediaState.Buffering -> R.string.now_playing_state_loading
        MediaState.Stopped -> R.string.now_playing_state_stopped
    }

    private fun MediaState.toPlaybackState() = when (this) {
        MediaState.Playing -> PlaybackState.STATE_PLAYING
        MediaState.Paused -> PlaybackState.STATE_PAUSED
        MediaState.Buffering -> PlaybackState.STATE_BUFFERING
        MediaState.Stopped -> PlaybackState.STATE_STOPPED
    }

    /** What the notification shows; it is posted again only when this changes. */
    private data class Content(
        val state: ConnectionState,
        val shortcuts: List<AppShortcut>,
        val media: MediaSessionInfo?,
        val appLabel: String?,
    )

    private data class SessionMetadata(val title: String?, val subtitle: String?, val durationMs: Long?, val artToken: String?)

    private data class SessionPlayback(val state: Int, val position: Long, val at: Long)

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_KEY_CODE = "key_code"
        private const val ACTION_VOLUME_DOWN = "volume_down"
        private const val ACTION_VOLUME_UP = "volume_up"
        private const val POSITION_DRIFT_MS = 2_000L
        private const val ICON_SIZE_PX = 192

        /** Must be called while the app is in the foreground. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RemoteConnectionService::class.java))
        }
    }
}
