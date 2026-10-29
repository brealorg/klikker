package no.enaasen.tvremote.data.protocol

import com.google.protobuf.ByteString
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.enaasen.tvremote.data.security.ClientIdentity
import no.enaasen.tvremote.data.security.PinningTrustManager
import no.enaasen.tvremote.data.security.sha256
import no.enaasen.tvremote.proto.pairing.PairingEncoding
import no.enaasen.tvremote.proto.pairing.PairingMessage
import no.enaasen.tvremote.proto.pairing.PairingMessageKt
import no.enaasen.tvremote.proto.pairing.RoleType
import no.enaasen.tvremote.proto.pairing.pairingConfiguration
import no.enaasen.tvremote.proto.pairing.pairingEncoding
import no.enaasen.tvremote.proto.pairing.pairingMessage
import no.enaasen.tvremote.proto.pairing.pairingOption
import no.enaasen.tvremote.proto.pairing.pairingRequest
import no.enaasen.tvremote.proto.pairing.pairingSecret

class PairingException(message: String) : IOException(message)

/** The entered code does not match the TV's certificate; the session stays open for another try. */
class WrongPairingCodeException : Exception()

/**
 * One pairing attempt over the polo protocol. [start] makes the TV display a
 * code; [submitCode] completes pairing and returns the TV certificate's
 * SHA-256 for pinning.
 */
class PairingSession(
    private val identity: ClientIdentity,
    private val host: String,
    private val port: Int = PAIRING_PORT,
) : Closeable {

    private val trustManager = PinningTrustManager(pinnedSha256 = null)
    private var socket: SSLSocket? = null

    suspend fun start(clientName: String) = withContext(Dispatchers.IO) {
        socket = identity.sslContext(trustManager).openSocket(host, port).apply {
            soTimeout = 0 // the TV waits indefinitely for the user to type the code
        }
        send { pairingRequest = pairingRequest { serviceName = SERVICE_NAME; this.clientName = clientName } }
        receive { it.hasPairingRequestAck() }
        send {
            pairingOption = pairingOption {
                inputEncodings += HEX_ENCODING
                preferredRole = RoleType.ROLE_TYPE_INPUT
            }
        }
        receive { it.hasPairingOption() }
        send {
            pairingConfiguration = pairingConfiguration {
                encoding = HEX_ENCODING
                clientRole = RoleType.ROLE_TYPE_INPUT
            }
        }
        receive { it.hasPairingConfigurationAck() }
    }

    suspend fun submitCode(code: String): ByteArray = withContext(Dispatchers.IO) {
        val serverCertificate = trustManager.serverCertificate
            ?: throw PairingException("Not connected")
        val secret = computeSecret(
            client = identity.certificate.publicKey as RSAPublicKey,
            server = serverCertificate.publicKey as? RSAPublicKey
                ?: throw PairingException("Unsupported TV key type"),
            code = code,
        )
        send { pairingSecret = pairingSecret { this.secret = ByteString.copyFrom(secret) } }
        receive { it.hasPairingSecretAck() }
        serverCertificate.sha256()
    }

    override fun close() {
        runCatching { socket?.close() }
    }

    private fun send(block: PairingMessageKt.Dsl.() -> Unit) {
        val message = pairingMessage {
            protocolVersion = 2
            status = PairingMessage.Status.STATUS_OK
            block()
        }
        message.writeFramedTo(checkNotNull(socket).outputStream)
    }

    private fun receive(expected: (PairingMessage) -> Boolean): PairingMessage {
        val message = PairingMessage.parseDelimitedFrom(checkNotNull(socket).inputStream)
            ?: throw EOFException("TV closed the pairing connection")
        if (message.status != PairingMessage.Status.STATUS_OK) {
            throw PairingException("TV rejected pairing: ${message.status}")
        }
        if (!expected(message)) throw PairingException("Unexpected pairing message")
        return message
    }

    companion object {
        const val PAIRING_PORT = 6467
        private const val SERVICE_NAME = "atvremote"
        const val CODE_LENGTH = 6

        private val HEX_ENCODING = pairingEncoding {
            type = PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL
            symbolLength = CODE_LENGTH
        }

        /**
         * secret = SHA-256(client modulus, client exponent, server modulus,
         * server exponent, last two code bytes). The first code byte is a
         * checksum: it must equal the first byte of the hash.
         */
        internal fun computeSecret(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray {
            require(code.length == CODE_LENGTH)
            val codeBytes = code.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").run {
                update(client.modulus.unsignedBytes())
                update(client.publicExponent.unsignedBytes())
                update(server.modulus.unsignedBytes())
                update(server.publicExponent.unsignedBytes())
                update(codeBytes, 1, codeBytes.size - 1)
                digest()
            }
            if (hash[0] != codeBytes[0]) throw WrongPairingCodeException()
            return hash
        }

        private fun BigInteger.unsignedBytes(): ByteArray {
            val bytes = toByteArray()
            return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }
    }
}
