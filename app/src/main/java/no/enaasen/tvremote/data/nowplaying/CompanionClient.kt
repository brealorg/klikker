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
 * serves it as JSON over plain HTTP to phones it has paired with (see [pairingLink]). A raw socket, because Android blocks
 * cleartext HTTP to addresses an app can't list up front.
 */
internal object CompanionClient {
    const val PORT = 6477
    private const val CONNECT_TIMEOUT_MS = 1_500
    private const val READ_TIMEOUT_MS = 2_000
    // The companion may have to download the picture first.
    private const val ART_READ_TIMEOUT_MS = 6_000

    /** What asking the companion for the TV's media sessions came to. */
    sealed interface Fetched {
        class Sessions(val sessions: List<MediaSessionInfo>) : Fetched

        /** The companion is there but doesn't know this phone: it has to be paired. */
        data object Unauthorized : Fetched

        data object Unavailable : Fetched
    }

    private sealed interface Answer {
        class Ok(val body: ByteArray) : Answer

        data object Unauthorized : Answer

        data object Unavailable : Answer
    }

    suspend fun fetch(host: String, secret: String): Fetched = when (val answer = request(host, secret, "GET", "/nowplaying")) {
        is Answer.Ok -> try {
            Fetched.Sessions(parse(answer.body.toString(Charsets.UTF_8)))
        } catch (_: JSONException) {
            Fetched.Unavailable
        }
        Answer.Unauthorized -> Fetched.Unauthorized
        Answer.Unavailable -> Fetched.Unavailable
    }

    /** The picture [packageName]'s session carries, or null if it has none or it can't be had. */
    suspend fun fetchArt(host: String, secret: String, packageName: String): Bitmap? =
        (request(host, secret, "GET", "/art?package=$packageName", readTimeoutMs = ART_READ_TIMEOUT_MS) as? Answer.Ok)
            ?.let { BitmapFactory.decodeByteArray(it.body, 0, it.body.size) }

    /** Asks the companion to move [packageName]'s playback to [positionMs]; false if it can't. */
    suspend fun seek(host: String, secret: String, packageName: String, positionMs: Long): Boolean =
        request(host, secret, "POST", "/seek?package=$packageName&position=$positionMs") is Answer.Ok

    /** The link that makes the companion on the TV remember [secret]; the phone opens it through the remote protocol. */
    fun pairingLink(secret: String) = "tvremote-companion://pair?secret=$secret"

    private suspend fun request(
        host: String,
        secret: String,
        method: String,
        path: String,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): Answer = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = readTimeoutMs
                socket.getOutputStream().apply {
                    write(
                        ("$method $path HTTP/1.1\r\nHost: $host\r\nAuthorization: Bearer $secret\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
                    )
                    flush()
                }
                val response = socket.getInputStream().readBytes()
                val bodyStart = response.indexOfHeadersEnd()
                val status = if (bodyStart < 0) "" else response.decodeToString(0, minOf(bodyStart, 16))
                when {
                    status.startsWith("HTTP/1.1 200") -> Answer.Ok(response.copyOfRange(bodyStart, response.size))
                    status.startsWith("HTTP/1.1 401") -> Answer.Unauthorized
                    else -> Answer.Unavailable
                }
            }
        } catch (_: IOException) {
            Answer.Unavailable
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
