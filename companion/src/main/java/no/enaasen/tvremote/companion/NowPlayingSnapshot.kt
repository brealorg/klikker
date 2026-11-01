package no.enaasen.tvremote.companion

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** The media sessions on this device as JSON, for the phone to read. */
internal fun nowPlayingJson(controllers: List<MediaController>, artOf: (MediaController) -> Art?): String {
    val sessions = JSONArray()
    // The system lists the most recently active session first; playing ones go before the rest.
    controllers.sortedBy { if (it.playbackState?.state == PlaybackState.STATE_PLAYING) 0 else 1 }
        .forEach { sessions.put(it.toJson(artOf(it))) }
    return JSONObject().put("sessions", sessions).toString()
}

private fun MediaController.toJson(art: Art?): JSONObject {
    val meta = metadata
    val playback = playbackState
    return JSONObject().apply {
        put("package", packageName)
        put("state", playback?.state.stateName())
        art?.let { put("art", it.token) }
        meta?.let {
            // Streaming apps fill the display fields, music apps the plain ones.
            put("title", it.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: it.text(MediaMetadata.METADATA_KEY_TITLE))
            put(
                "subtitle",
                it.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                    ?: it.text(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: it.text(MediaMetadata.METADATA_KEY_ALBUM),
            )
            put("artist", it.text(MediaMetadata.METADATA_KEY_ARTIST))
            put("album", it.text(MediaMetadata.METADATA_KEY_ALBUM))
            it.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { d -> d > 0 }?.let { d -> put("durationMs", d) }
        }
        playback?.let { put("positionMs", it.livePosition()) }
    }
}

private fun MediaMetadata.text(key: String): String? =
    getString(key)?.takeIf { it.isNotBlank() } ?: getText(key)?.toString()?.takeIf { it.isNotBlank() }

/** The position now, not when the app last reported it. */
private fun PlaybackState.livePosition(): Long {
    if (state != PlaybackState.STATE_PLAYING) return position
    val elapsed = SystemClock.elapsedRealtime() - lastPositionUpdateTime
    return position + (elapsed * playbackSpeed).toLong()
}

private fun Int?.stateName() = when (this) {
    PlaybackState.STATE_PLAYING -> "playing"
    PlaybackState.STATE_PAUSED -> "paused"
    PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "buffering"
    PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE, null -> "stopped"
    else -> "other"
}
