package no.enaasen.tvremote.data.nowplaying

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.enaasen.tvremote.data.model.MediaSessionInfo
import no.enaasen.tvremote.data.model.MediaState
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads what the TV's apps are playing from the companion app on the TV, which
 * serves it as JSON over plain HTTP. A raw socket, because Android blocks
 * cleartext HTTP to addresses an app can't list up front.
 */
internal object CompanionClient {
    const val PORT = 6477
    private const val CONNECT_TIMEOUT_MS = 1_500
    private const val READ_TIMEOUT_MS = 2_000
    // The companion may have to download the picture first.
    private const val ART_READ_TIMEOUT_MS = 6_000

    /** The TV's media sessions, or null if the companion isn't there. */
    suspend fun fetch(host: String): List<MediaSessionInfo>? = try {
        get(host, "/nowplaying")?.toString(Charsets.UTF_8)?.let(::parse)
    } catch (_: JSONException) {
        null
    }

    /** The picture [packageName]'s session carries, or null if it has none or it can't be had. */
    suspend fun fetchArt(host: String, packageName: String): Bitmap? =
        get(host, "/art?package=$packageName", readTimeoutMs = ART_READ_TIMEOUT_MS)
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    /** The body of a 200 answer to `GET [path]`, or null if the companion isn't there or says no. */
    private suspend fun get(host: String, path: String, readTimeoutMs: Int = READ_TIMEOUT_MS): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = readTimeoutMs
                    socket.getOutputStream().apply {
                        write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                    val response = socket.getInputStream().readBytes()
                    val bodyStart = response.indexOfHeadersEnd()
                    if (bodyStart < 0 || !response.decodeToString(0, minOf(bodyStart, 16)).startsWith("HTTP/1.1 200")) {
                        return@withContext null
                    }
                    response.copyOfRange(bodyStart, response.size)
                }
            } catch (_: IOException) {
                null
            }
        }

    /** Where the body starts, after the blank line ending the headers, or -1. */
    private fun ByteArray.indexOfHeadersEnd(): Int {
        for (i in 0..size - 4) {
            if (this[i] == '\r'.code.toByte() && this[i + 1] == '\n'.code.toByte() &&
                this[i + 2] == '\r'.code.toByte() && this[i + 3] == '\n'.code.toByte()
            ) {
                return i + 4
            }
        }
        return -1
    }

    fun parse(json: String): List<MediaSessionInfo> {
        val sessions = JSONObject(json).getJSONArray("sessions")
        return (0 until sessions.length()).map { index ->
            val session = sessions.getJSONObject(index)
            MediaSessionInfo(
                packageName = session.getString("package"),
                state = when (session.optString("state")) {
                    "playing" -> MediaState.Playing
                    "paused" -> MediaState.Paused
                    "buffering" -> MediaState.Buffering
                    else -> MediaState.Stopped
                },
                title = session.optString("title").takeIf { it.isNotBlank() },
                subtitle = session.optString("subtitle").takeIf { it.isNotBlank() },
                positionMs = session.optLong("positionMs", -1).takeIf { it >= 0 },
                durationMs = session.optLong("durationMs", -1).takeIf { it > 0 },
                artToken = session.optString("art").takeIf { it.isNotBlank() },
            )
        }
    }
}
