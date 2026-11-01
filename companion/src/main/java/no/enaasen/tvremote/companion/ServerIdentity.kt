package no.enaasen.tvremote.companion

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * The companion's own TLS certificate: a self-signed one whose key lives in the
 * Android keystore and is made on first use. Phones don't trust it for being
 * signed by anyone; they check it with [Challenge] when they pair.
 */
internal class ServerIdentity private constructor(val tls: SSLContext, val certificateSha256: ByteArray) {
    companion object {
        private const val ALIAS = "companion-tls-2"
        private const val OLD_ALIAS = "companion-tls"
        private const val VALID_YEARS = 30L

        fun load(): ServerIdentity {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            // The first key only allowed SHA-256, which TLS can ask to sign with something else than.
            if (keyStore.containsAlias(OLD_ALIAS)) keyStore.deleteEntry(OLD_ALIAS)
            if (!keyStore.containsAlias(ALIAS)) generate()
            val certificate = keyStore.getCertificate(ALIAS) as X509Certificate
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore, null) }.keyManagers
            val tls = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
            return ServerIdentity(tls, MessageDigest.getInstance("SHA-256").digest(certificate.encoded))
        }

        private fun generate() {
            val now = System.currentTimeMillis()
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // Whichever a TLS handshake picks; the certificate itself is signed with SHA-256.
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal("CN=tvremote-companion"))
                .setCertificateNotBefore(Date(now - DAY_MS))
                .setCertificateNotAfter(Date(now + VALID_YEARS * 365 * DAY_MS))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }

        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
