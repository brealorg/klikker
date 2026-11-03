package no.enaasen.tvremote.data.protocol

import java.io.IOException
import javax.net.ssl.SSLProtocolException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingRequiredExceptionTest {
    private val alert = "error:10000416:SSL routines:OPENSSL_internal:SSLV3_ALERT_CERTIFICATE_UNKNOWN"

    @Test
    fun certificateAlertIsFoundInTheCauseChain() {
        // Protobuf wraps the TLS failure, so the alert sits two levels down.
        val wrapped = IOException("Read error", SSLProtocolException(alert))
        assertTrue(PairingRequiredException(wrapped).certificateRejected)
    }

    @Test
    fun badCertificateCounts() =
        assertTrue(PairingRequiredException(SSLProtocolException("SSLV3_ALERT_BAD_CERTIFICATE")).certificateRejected)

    @Test
    fun plainHandshakeFailureIsNotConclusive() =
        assertFalse(PairingRequiredException(SSLProtocolException("Connection reset")).certificateRejected)

    @Test
    fun noCauseIsNotConclusive() = assertFalse(PairingRequiredException().certificateRejected)
}
