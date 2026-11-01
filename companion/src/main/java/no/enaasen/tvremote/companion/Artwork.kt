package no.enaasen.tvremote.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The picture a session carries for what it plays: a poster, an album cover. */
internal class Art private constructor(val token: String, private val bitmap: Bitmap?, private val uri: String?) {

    @Volatile
    private var encoded: ByteArray? = null

    /** The picture as a JPEG of at most [MAX_SIDE_PX] on its longest side, or null if it can't be had. */
    fun jpeg(): ByteArray? = encoded ?: encode()?.also { encoded = it }

    private fun encode(): ByteArray? {
        val source = bitmap ?: uri?.let(::download) ?: return null
        val scale = MAX_SIDE_PX.toFloat() / maxOf(source.width, source.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
        } else {
            source
        }
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
    }

    private fun download(address: String): Bitmap? = try {
        (URL(address).openConnection() as HttpURLConnection).run {
            connectTimeout = DOWNLOAD_TIMEOUT_MS
            readTimeout = DOWNLOAD_TIMEOUT_MS
            try {
                // Only the host: the rest of the address can carry a token.
                Log.i(TAG, "Downloading art from ${url.host}: HTTP $responseCode, $contentType")
                BitmapFactory.decodeStream(inputStream)
            } finally {
                disconnect()
            }
        }
    } catch (e: IOException) {
        // Not the exception: its message is the whole address, which can carry a token.
        Log.w(TAG, "Could not download art: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "Artwork"

        @Volatile
        private var lastNoArt: String? = null
        private const val MAX_SIDE_PX = 480
        private const val JPEG_QUALITY = 85
        private const val DOWNLOAD_TIMEOUT_MS = 3_000

        private val BITMAP_KEYS = listOf(
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        )

        private val URI_KEYS = listOf(
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        )

        /** A picture that is already a bitmap, such as the large icon of a media notification. */
        fun ofBitmap(token: String, bitmap: Bitmap) = Art(token, bitmap, null)

        fun of(metadata: MediaMetadata): Art? {
            BITMAP_KEYS.firstNotNullOfOrNull { metadata.getBitmap(it) }?.let { return Art("b${it.generationId}", it, null) }
            val uris = URI_KEYS.mapNotNull { metadata.getString(it)?.takeIf(String::isNotBlank) }
            uris.firstOrNull(::mayDownload)?.let { return Art("u${it.hashCode()}", null, it) }
            // Say why there is no picture, without the addresses: they can carry a token.
            val reason = if (uris.isEmpty()) {
                "the session has no picture or picture address (keys: ${metadata.keySet().sorted()})"
            } else {
                "skipped ${uris.map { "${scheme(it).ifEmpty { "no scheme" }} to ${host(it).ifEmpty { "no host" }}" }}"
            }
            // The phone asks every few seconds: say it once, not on every poll.
            if (reason != lastNoArt) {
                lastNoArt = reason
                Log.i(TAG, "No art: $reason")
            }
            return null
        }

        /** https always; plain http only to a device on the local network, where a media server usually lives. */
        private fun mayDownload(address: String): Boolean = when (scheme(address)) {
            "https" -> true
            "http" -> isLocalHost(host(address))
            else -> false
        }

        private fun scheme(address: String) = address.substringBefore(':', "").lowercase()

        private fun host(address: String) = runCatching { java.net.URI(address).host }.getOrNull().orEmpty()

        private fun isLocalHost(host: String): Boolean {
            if (host.endsWith(".local")) return true
            val octets = host.split('.').mapNotNull { it.toIntOrNull() }
            if (octets.size != 4 || host.split('.').size != 4) return false
            return octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)
        }
    }
}

internal fun MediaController.art(): Art? = metadata?.let(Art::of)
