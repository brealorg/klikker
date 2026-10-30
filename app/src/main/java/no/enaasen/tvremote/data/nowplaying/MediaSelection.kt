package no.enaasen.tvremote.data.nowplaying

import no.enaasen.tvremote.data.model.MediaSessionInfo
import no.enaasen.tvremote.data.model.MediaState

/**
 * What [packageName] is playing, if anything: a playing session before the
 * others. Sessions without a title or already stopped say nothing worth showing.
 */
fun List<MediaSessionInfo>.forApp(packageName: String): MediaSessionInfo? =
    filter { it.packageName == packageName && it.state != MediaState.Stopped && it.title != null }
        .let { sessions -> sessions.firstOrNull { it.state == MediaState.Playing } ?: sessions.firstOrNull() }
