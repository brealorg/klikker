package no.enaasen.tvremote.data.protocol

import com.google.protobuf.MessageLite
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

private const val CONNECT_TIMEOUT_MS = 3_000

/**
 * The TV pings every ~5 s. Wi-Fi power saving on a sleeping phone can delay
 * delivery, so only this much silence counts as a dead link.
 */
internal const val READ_TIMEOUT_MS = 30_000

/** Thrown when the TV does not recognise this app's certificate. */
class PairingRequiredException(cause: Throwable? = null) : IOException(cause) {
    /** True when the TV answered with an explicit TLS certificate alert, not just a failed handshake. */
    val certificateRejected: Boolean = generateSequence(cause) { it.cause }.any { error ->
        error.message?.let { "CERTIFICATE_UNKNOWN" in it || "BAD_CERTIFICATE" in it || "CERTIFICATE_REQUIRED" in it } == true
    }
}

internal fun SSLContext.openSocket(host: String, port: Int): SSLSocket {
    val plain = Socket()
    try {
        plain.tcpNoDelay = true
        plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        return (socketFactory.createSocket(plain, host, port, true) as SSLSocket).apply {
            soTimeout = READ_TIMEOUT_MS
            startHandshake()
        }
    } catch (e: IOException) {
        plain.close()
        throw e
    }
}

/** Writes a length-delimited message in a single write, so it goes out as one TLS record. */
internal fun MessageLite.writeFramedTo(output: OutputStream) {
    val buffer = ByteArrayOutputStream(serializedSize + 5)
    writeDelimitedTo(buffer)
    output.write(buffer.toByteArray())
    output.flush()
}
