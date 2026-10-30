package no.enaasen.tvremote.companion

import android.app.Notification
import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * Notification access is what lets an app read other apps' media sessions.
 * While it is granted the system keeps this service bound, which keeps the
 * server running.
 *
 * Sessions come from two places: the active ones, and the media notifications.
 * Google Cast's receiver keeps its session inactive while it plays, so it is
 * missing from the first; its notification carries the session's token.
 */
class MediaListenerService : NotificationListenerService() {
    @Volatile
    private var activeControllers: List<MediaController> = emptyList()

    private val notificationControllers = ConcurrentHashMap<String, MediaController>()

    private val server = NowPlayingServer { path ->
        val all = (activeControllers + notificationControllers.values).distinctBy { it.sessionToken }
        when {
            path == "/nowplaying" -> HttpResponse(JSON, nowPlayingJson(all).toByteArray(Charsets.UTF_8))
            path.startsWith("/art?package=") -> {
                val packageName = path.substringAfter("package=").substringBefore('&')
                all.firstNotNullOfOrNull { controller -> controller.takeIf { it.packageName == packageName }?.art() }
                    ?.jpeg()
                    ?.let { HttpResponse("image/jpeg", it) }
            }
            else -> null
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener {
        activeControllers = it.orEmpty()
    }

    override fun onListenerConnected() {
        val manager = getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(this, MediaListenerService::class.java)
        manager.addOnActiveSessionsChangedListener(sessionsListener, component)
        activeControllers = manager.getActiveSessions(component)
        activeNotifications.orEmpty().forEach(::track)
        server.start()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = track(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        notificationControllers.remove(sbn.key)
    }

    private fun track(sbn: StatusBarNotification) {
        val token = sbn.notification.mediaSessionToken()
        if (token == null) notificationControllers.remove(sbn.key) else notificationControllers[sbn.key] = MediaController(this, token)
    }

    override fun onListenerDisconnected() = stop()

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private companion object {
        const val JSON = "application/json; charset=utf-8"
    }

    private fun stop() {
        server.stop()
        runCatching { getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsListener) }
        activeControllers = emptyList()
        notificationControllers.clear()
    }
}

private fun Notification.mediaSessionToken(): MediaSession.Token? =
    if (Build.VERSION.SDK_INT >= 33) {
        extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
    } else {
        @Suppress("DEPRECATION")
        extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
    }
