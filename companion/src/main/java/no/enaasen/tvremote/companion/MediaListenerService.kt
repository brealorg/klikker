package no.enaasen.tvremote.companion

import android.app.Notification
import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
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

    /** The large icon of each media notification by key, the picture of last resort: some apps put it only there. */
    private val notificationArt = ConcurrentHashMap<String, Pair<String, Art>>()

    /** The session's own picture, else the one on its app's media notification. */
    private fun artOf(controller: MediaController): Art? =
        controller.art() ?: notificationArt.values.firstOrNull { it.first == controller.packageName }?.second

    private val server = NowPlayingServer(authorize = { PairingStore.accepts(this, it) }, command = ::command, route = { path ->
        val all = (activeControllers + notificationControllers.values).distinctBy { it.sessionToken }
        when {
            path == "/nowplaying" -> HttpResponse(JSON, nowPlayingJson(all, ::artOf).toByteArray(Charsets.UTF_8))
            path.startsWith("/art?package=") -> {
                val packageName = path.substringAfter("package=").substringBefore('&')
                all.firstNotNullOfOrNull { controller -> controller.takeIf { it.packageName == packageName }?.let(::artOf) }
                    ?.jpeg()
                    ?.let { HttpResponse("image/jpeg", it) }
            }
            else -> null
        }
    })

    /** `/seek?package=…&position=<ms>`: moves the playing session of that app, if one can seek. */
    private fun command(path: String): Boolean {
        if (!path.startsWith("/seek?")) return false
        val params = path.substringAfter('?').split("&")
        fun param(name: String): String? = params.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")
        val packageName = param("package") ?: return false
        val position = param("position")?.toLongOrNull()?.takeIf { it >= 0 } ?: return false
        val all = (activeControllers + notificationControllers.values).distinctBy { it.sessionToken }
        val target = all.filter { it.packageName == packageName && it.canSeek() }
            .minByOrNull { if (it.playbackState?.state == PlaybackState.STATE_PLAYING) 0 else 1 } ?: return false
        target.transportControls.seekTo(position)
        return true
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
        notificationArt.remove(sbn.key)
    }

    private fun track(sbn: StatusBarNotification) {
        val token = sbn.notification.mediaSessionToken()
        if (token == null) {
            notificationControllers.remove(sbn.key)
            notificationArt.remove(sbn.key)
            return
        }
        notificationControllers[sbn.key] = MediaController(this, token)
        val icon = sbn.notification.getLargeIcon()?.let { runCatching { it.loadDrawable(this) }.getOrNull() }?.toBitmap()
        if (icon == null) notificationArt.remove(sbn.key) else notificationArt[sbn.key] = sbn.packageName to Art.ofBitmap("n${icon.generationId}", icon)
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
        notificationArt.clear()
    }
}

private fun Notification.mediaSessionToken(): MediaSession.Token? =
    if (Build.VERSION.SDK_INT >= 33) {
        extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
    } else {
        @Suppress("DEPRECATION")
        extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
    }

private fun Drawable.toBitmap(): Bitmap? {
    if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return null
    return Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888).also {
        setBounds(0, 0, it.width, it.height)
        draw(Canvas(it))
    }
}

private fun MediaController.canSeek() = playbackState?.let { it.actions and PlaybackState.ACTION_SEEK_TO != 0L } == true
