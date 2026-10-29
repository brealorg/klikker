package no.enaasen.tvremote.data.protocol

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Expected values were produced by the reference algorithm used by the
 * androidtvremote2 Python library, run against the same two certificates.
 */
class PairingSecretTest {

    private val client = loadKey("pairing_client.pem")
    private val server = loadKey("pairing_server.pem")

    @Test
    fun secretMatchesReferenceImplementation() {
        assertEquals(
            "8995fc0cd7381aa11d764c067bb616b84294d806d6351afe80752fdbf5bf75f5",
            PairingSession.computeSecret(client, server, "89A1B2").toHex(),
        )
        assertEquals(
            "34243b61c71b5dac211beef93ac438a1ed7a02321b298c007d8110048e411ecc",
            PairingSession.computeSecret(client, server, "3400FF").toHex(),
        )
    }

    @Test(expected = WrongPairingCodeException::class)
    fun wrongChecksumIsRejected() {
        PairingSession.computeSecret(client, server, "88A1B2")
    }

    private fun loadKey(resource: String): RSAPublicKey {
        val stream = checkNotNull(javaClass.classLoader).getResourceAsStream(resource)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(stream) as X509Certificate
        return certificate.publicKey as RSAPublicKey
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
