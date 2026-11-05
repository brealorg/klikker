package no.enaasen.tvremote.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.RSAKeyGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * The client certificate this app presents to TVs. The TV remembers it at
 * pairing time, so it must stay stable. The private key never leaves the
 * Android Keystore.
 *
 * A TV tells phones apart by the certificate's name and serial number, not its
 * key, so two installs with the same ones replace each other's pairing. Each
 * install therefore creates its own random name and serial when it first needs
 * a certificate, and keeps them with the key. Never change the alias: the phone
 * would get a new identity and every TV would forget it.
 */
@Singleton
class ClientIdentity @Inject constructor(@ApplicationContext context: Context) {

    private val isDevBuild = context.packageName.endsWith(".dev")
    private val alias = if (isDevBuild) "tv_remote_client_dev_2" else "tv_remote_client_2"

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    val certificate: X509Certificate
        get() {
            ensureKeyPair()
            return keyStore.getCertificate(alias) as X509Certificate
        }

    private val privateKey: PrivateKey
        get() {
            ensureKeyPair()
            return keyStore.getKey(alias, null) as PrivateKey
        }

    fun sslContext(trustManager: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(arrayOf(SingleKeyManager(alias, certificate, privateKey)), arrayOf(trustManager), null)
        }

    @Synchronized
    private fun ensureKeyPair() {
        if (keyStore.containsAlias(alias)) return
        // Older versions shared one fixed name and serial between all installs.
        LEGACY_ALIASES.forEach { if (keyStore.containsAlias(it)) keyStore.deleteEntry(it) }
        val random = SecureRandom()
        val suffix = ByteArray(4).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val serial = BigInteger(62, random).add(BigInteger.TWO)
        // Conscrypt signs TLS handshakes with keystore keys either through
        // Signature (PKCS#1 / PSS) or a raw RSA private-key operation, so the
        // key allows both.
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT,
        )
            .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512,
            )
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                KeyProperties.SIGNATURE_PADDING_RSA_PSS,
            )
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(false)
            .setCertificateSubject(X500Principal(if (isDevBuild) "CN=TV Remote Dev $suffix" else "CN=TV Remote $suffix"))
            .setCertificateSerialNumber(serial)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    private class SingleKeyManager(
        private val alias: String,
        private val certificate: X509Certificate,
        private val privateKey: PrivateKey,
    ) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = privateKey
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        val LEGACY_ALIASES = listOf("tv_remote_client", "tv_remote_client_dev")
    }
}
