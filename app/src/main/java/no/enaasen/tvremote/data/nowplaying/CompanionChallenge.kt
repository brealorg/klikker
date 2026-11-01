package no.enaasen.tvremote.data.nowplaying

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The phone's side of the companion's challenge: the phone sends a random
 * nonce and gets back an HMAC, made with the secret it paired with, of the
 * nonce and the fingerprint of the certificate it sees. Only the companion
 * that holds the secret can answer for that certificate, so a match shows the
 * certificate is the companion's and not someone's in between. The companion
 * app has the same two functions.
 */
internal object CompanionChallenge {
    private const val CONTEXT = "tvremote-companion-v1|"

    /** Names the secret without giving it away, so the companion knows which one to answer with. */
    fun keyId(secret: String): String =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).copyOf(8).toHex()

    fun proof(secret: String, nonce: String, certificateSha256: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            doFinal(CONTEXT.toByteArray() + nonce.toByteArray() + "|".toByteArray() + certificateSha256)
        }

    /** Whether [answer] (hex) is what the companion holding [secret] would say for a certificate with [certificateSha256]. */
    fun accepts(secret: String, nonce: String, certificateSha256: ByteArray, answer: String): Boolean =
        MessageDigest.isEqual(proof(secret, nonce, certificateSha256).toHex().toByteArray(), answer.trim().lowercase().toByteArray())
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
