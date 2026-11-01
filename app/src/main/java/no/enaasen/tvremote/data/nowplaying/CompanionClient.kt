package no.enaasen.tvremote.data.nowplaying

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.enaasen.tvremote.data.model.MediaSessionInfo
import no.enaasen.tvremote.data.model.MediaState
import no.enaasen.tvremote.data.security.PinningTrustManager
import no.enaasen.tvremote.data.security.sha256
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads what the TV's apps are playing from the companion app on the TV, which
 * serves it as JSON over HTTPS to phones it has paired with (see [pairingLink]).
 * Its certificate is self-signed, so before trusting it the phone has the
 * companion prove it holds the secret (see [CompanionChallenge]), then pins it.
 * A raw socket, since that certificate is not one the system would accept.
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

        /** The certificate is not the one verified before, as after a reinstall: worth asking again at once. */
        data object CertificateChanged : Fetched

        data object Unavailable : Fetched
    }

    private sealed interface Answer {
        class Ok(val body: ByteArray) : Answer

        data object Unauthorized : Answer

        data object CertificateChanged : Answer

        data object Unavailable : Answer
    }

    suspend fun fetch(host: String, secret: String): Fetched = when (val answer = request(host, secret, "GET", "/nowplaying")) {
        is Answer.Ok -> try {
            Fetched.Sessions(parse(answer.body.toString(Charsets.UTF_8)))
        } catch (_: JSONException) {
            Fetched.Unavailable
        }
        Answer.Unauthorized -> Fetched.Unauthorized
        Answer.CertificateChanged -> Fetched.CertificateChanged
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

    // The certificate each companion was shown to have by the challenge, by host. It lives only as long
    // as the app does, so a companion is verified again after a restart, and when its certificate changes.
    private val verified = ConcurrentHashMap<String, ByteArray>()

    private suspend fun request(
        host: String,
        secret: String,
        method: String,
        path: String,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): Answer = withContext(Dispatchers.IO) {
        try {
            val certificate = verified[host] ?: when (val proof = verify(host, secret)) {
                is Verification.Verified -> proof.certificateSha256.also { verified[host] = it }
                Verification.Unauthorized -> return@withContext Answer.Unauthorized
                Verification.Failed -> return@withContext Answer.Unavailable
            }
            val text = "$method $path HTTP/1.1\r\nHost: $host\r\nAuthorization: Bearer $secret\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n"
            exchange(host, PinningTrustManager(certificate), text, readTimeoutMs).first
        } catch (_: SSLException) {
            // Only a certificate verified before can have changed. A handshake that fails the first time is
            // just a companion that can't be reached that way. Either way it is verified afresh next time.
            if (verified.remove(host) != null) Answer.CertificateChanged else Answer.Unavailable
        } catch (_: IOException) {
            Answer.Unavailable
        }
    }

    private sealed interface Verification {
        class Verified(val certificateSha256: ByteArray) : Verification

        /** The companion doesn't know this phone's secret. */
        data object Unauthorized : Verification

        data object Failed : Verification
    }

    /**
     * Asks the companion to prove, with [secret], that the certificate it presents is its own. Nothing
     * secret is sent: only the name of the secret and a nonce.
     */
    private fun verify(host: String, secret: String): Verification {
        val nonce = ByteArray(16).also(SecureRandom()::nextBytes).toHex()
        val trust = PinningTrustManager(null)
        val text = "GET /challenge?kid=${CompanionChallenge.keyId(secret)}&nonce=$nonce HTTP/1.1\r\n" +
            "Host: $host\r\nConnection: close\r\n\r\n"
        val (answer, _) = exchange(host, trust, text, READ_TIMEOUT_MS)
        val certificate = trust.serverCertificate?.sha256() ?: return Verification.Failed
        return when (answer) {
            is Answer.Ok ->
                if (CompanionChallenge.accepts(secret, nonce, certificate, answer.body.toString(Charsets.US_ASCII))) {
                    Verification.Verified(certificate)
                } else {
                    Verification.Failed
                }
            Answer.Unauthorized -> Verification.Unauthorized
            Answer.CertificateChanged, Answer.Unavailable -> Verification.Failed
        }
    }

    /** Sends [text] over TLS, trusting what [trust] allows, and reads the whole answer. */
    private fun exchange(host: String, trust: PinningTrustManager, text: String, readTimeoutMs: Int): Pair<Answer, X509Certificate?> {
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
            plain.soTimeout = readTimeoutMs
            val socket = tls.socketFactory.createSocket(plain, host, PORT, true) as SSLSocket
            socket.startHandshake()
            socket.outputStream.apply {
                write(text.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val response = socket.inputStream.readBytes()
            val bodyStart = response.indexOfHeadersEnd()
            val status = if (bodyStart < 0) "" else response.decodeToString(0, minOf(bodyStart, 16))
            val answer = when {
                status.startsWith("HTTP/1.1 200") -> Answer.Ok(response.copyOfRange(bodyStart, response.size))
                status.startsWith("HTTP/1.1 401") -> Answer.Unauthorized
                else -> Answer.Unavailable
            }
            return answer to trust.serverCertificate
        } finally {
            plain.close()
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
