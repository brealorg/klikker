package no.enaasen.tvremote.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
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
import no.enaasen.tvremote.data.diagnostics.DiagnosticCategory
import no.enaasen.tvremote.data.diagnostics.DiagnosticLog
import no.enaasen.tvremote.data.model.AppShortcut
import no.enaasen.tvremote.data.model.ConnectionState
import no.enaasen.tvremote.data.model.tv

/**
 * Keeps the selected TV connected while the app is closed, so the remote is
 * ready the moment it's opened. Its notification doubles as a quick remote.
 */
@AndroidEntryPoint
class RemoteConnectionService : Service() {

    @Inject
    lateinit var repository: TvRepository

    @Inject
    lateinit var log: DiagnosticLog

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(repository.connectionState.value, emptyList()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        repository.setBackgroundServiceRunning(true)
        log.info(DiagnosticCategory.Service, "Background connection started")

        scope.launch {
            combine(repository.connectionState, repository.shortcuts, ::Pair).collect { (state, shortcuts) ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(state, shortcuts))
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

    private fun buildNotification(state: ConnectionState, shortcuts: List<AppShortcut>) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tv_fill1)
            .setContentTitle(state.tv?.name ?: getString(R.string.app_name))
            .setContentText(state.statusText(shortcuts))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (state is ConnectionState.Connected) {
                    addKeyAction(R.drawable.ic_volume_down, R.string.volume_down, KeyEvent.KEYCODE_VOLUME_DOWN)
                    addKeyAction(R.drawable.ic_play_pause, R.string.key_play_pause, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    addKeyAction(R.drawable.ic_volume_up, R.string.volume_up, KeyEvent.KEYCODE_VOLUME_UP)
                }
            }
            .build()

    private fun NotificationCompat.Builder.addKeyAction(icon: Int, label: Int, keyCode: Int) = addAction(
        icon,
        getString(label),
        PendingIntent.getService(
            this@RemoteConnectionService,
            keyCode,
            Intent(this@RemoteConnectionService, RemoteConnectionService::class.java).putExtra(EXTRA_KEY_CODE, keyCode),
            PendingIntent.FLAG_IMMUTABLE,
        ),
    )

    private fun ConnectionState.statusText(shortcuts: List<AppShortcut>): String = when (this) {
        is ConnectionState.Connected -> status.currentAppPackage
            ?.let { pkg -> shortcuts.find { it.packageName == pkg }?.label }
            ?: getString(R.string.status_connected)
        is ConnectionState.Unreachable -> getString(R.string.status_unreachable)
        is ConnectionState.PairingRequired -> getString(R.string.status_pairing_required)
        else -> getString(R.string.status_connecting)
    }

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_KEY_CODE = "key_code"

        /** Must be called while the app is in the foreground. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RemoteConnectionService::class.java))
        }
    }
}
