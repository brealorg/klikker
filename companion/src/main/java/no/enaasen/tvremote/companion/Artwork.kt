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

    /** The picture as a JPEG of at most [MAX_SIDE_PX] on its longest side, or null if it can't be had. */
    fun jpeg(): ByteArray? {
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
        private const val MAX_SIDE_PX = 480
        private const val JPEG_QUALITY = 85
        private const val DOWNLOAD_TIMEOUT_MS = 3_000

        private val BITMAP_KEYS = listOf(
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        )

        // Only https: Android blocks cleartext downloads, and a lookup that can't work is no use.
        private val URI_KEYS = listOf(
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        )

        fun of(metadata: MediaMetadata): Art? {
            BITMAP_KEYS.firstNotNullOfOrNull { metadata.getBitmap(it) }?.let { return Art("b${it.generationId}", it, null) }
            URI_KEYS.firstNotNullOfOrNull { metadata.getString(it)?.takeIf { uri -> uri.startsWith("https://") } }
                ?.let { return Art("u${it.hashCode()}", null, it) }
            return null
        }
    }
}

internal fun MediaController.art(): Art? = metadata?.let(Art::of)
