package no.enaasen.tvremote.data.nowplaying

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

    /** The TV's media sessions, or null if the companion isn't there. */
    suspend fun fetch(host: String): List<MediaSessionInfo>? = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                socket.getOutputStream().apply {
                    write("GET /nowplaying HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                if (!response.startsWith("HTTP/1.1 200")) return@withContext null
                parse(response.substringAfter("\r\n\r\n"))
            }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
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
            )
        }
    }
}
