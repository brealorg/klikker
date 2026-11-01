package no.enaasen.tvremote.companion

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * How the companion proves to a phone that it is the TV's companion and not
 * something in between. The phone sends a random nonce; the answer is an HMAC,
 * made with the secret the phone paired with, of the nonce and the fingerprint
 * of the certificate the phone sees. Someone in the middle has a certificate of
 * their own and doesn't know the secret, so can't give an answer that fits it.
 * The phone app has the same two functions.
 */
internal object Challenge {
    private const val CONTEXT = "tvremote-companion-v1|"

    /** Names a secret without giving it away, so the companion knows which one to answer with. */
    fun keyId(secret: String): String =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).copyOf(8).toHex()

    fun proof(secret: String, nonce: String, certificateSha256: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            doFinal(CONTEXT.toByteArray() + nonce.toByteArray() + "|".toByteArray() + certificateSha256)
        }

    fun isNonce(text: String?): Boolean = text != null && Regex("[0-9a-f]{16,64}").matches(text)
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
