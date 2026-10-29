package no.enaasen.tvremote.data.security

import android.annotation.SuppressLint
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * TVs use self-signed certificates. During pairing any certificate is
 * accepted (the pairing code authenticates the TV's public key); afterwards
 * the certificate seen at pairing time is pinned.
 */
@SuppressLint("CustomX509TrustManager") // Pins the paired certificate; the system CA store cannot validate self-signed TVs.
class PinningTrustManager(private val pinnedSha256: ByteArray?) : X509TrustManager {

    @Volatile
    var serverCertificate: X509Certificate? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw CertificateException("Empty certificate chain")
        if (pinnedSha256 != null && !MessageDigest.isEqual(certificate.sha256(), pinnedSha256)) {
            throw CertificateException("TV certificate does not match the one seen at pairing")
        }
        serverCertificate = certificate
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not accepted")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

fun X509Certificate.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(encoded)
