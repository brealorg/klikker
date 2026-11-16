package no.enaasen.tvremote.data.samsung

import android.annotation.SuppressLint
import android.util.Base64
import android.view.KeyEvent
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import no.enaasen.tvremote.data.diagnostics.DiagnosticCategory
import no.enaasen.tvremote.data.diagnostics.DiagnosticLog
import no.enaasen.tvremote.di.ApplicationScope
import no.enaasen.tvremote.proto.remote.RemoteDirection
import org.json.JSONObject

/** Why a check of a Samsung TV's address succeeded or failed. */
enum class SamsungProbeResult {
    Found,

    /** The TV answered but turned this phone down. */
    Denied,

    /** The TV took the connection and never answered, typically waiting for someone to allow it on screen. */
    NoReply,

    /** Nothing answered at the address. */
    Unreachable,
}

/**
 * Sends volume and mute straight to a Samsung (Tizen) TV over its network remote
 * control, for a TV whose volume doesn't follow the keys sent to the box in its
 * HDMI port. The TV only has to know this phone; it replies with a token that
 * isn't needed again.
 *
 * The connection is opened on the first key and dropped after a while idle.
 */
@Singleton
class SamsungVolume @Inject constructor(
    private val log: DiagnosticLog,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private var connection: Connection? = null
    private var repeatJob: Job? = null
    private var idleJob: Job? = null

    /** Handles volume and mute keys for the TV at [host]; false for any other key. */
    fun handleKey(host: String, keyCode: Int, direction: RemoteDirection): Boolean {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "KEY_VOLUP"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "KEY_VOLDOWN"
            KeyEvent.KEYCODE_VOLUME_MUTE -> "KEY_MUTE"
            else -> return false
        }
        when (direction) {
            RemoteDirection.START_LONG -> {
                repeatJob?.cancel()
                if (key == "KEY_MUTE") {
                    scope.launch { send(host, key) }
                } else {
                    // Capped, so a lost release can't run the volume to either end.
                    repeatJob = scope.launch {
                        repeat(MAX_HOLD_STEPS) {
                            if (!isActive) return@launch
                            send(host, key)
                            delay(HOLD_REPEAT_MS)
                        }
                    }
                }
            }
            RemoteDirection.END_LONG -> repeatJob?.cancel()
            else -> scope.launch { send(host, key) }
        }
        return true
    }

    /** Checks whether a Samsung TV takes this phone's connection at [host]. */
    suspend fun probe(host: String): SamsungProbeResult = withContext(Dispatchers.IO) {
        try {
            Connection.open(host, PROBE_TIMEOUT_MS).use { connection ->
                when (connection.firstEvent) {
                    EVENT_CONNECT -> SamsungProbeResult.Found
                    "ms.channel.unauthorized" -> SamsungProbeResult.Denied
                    else -> SamsungProbeResult.NoReply
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            // Connected and handshaken, but the TV said nothing in time.
            if (e.message == NO_REPLY) SamsungProbeResult.NoReply else SamsungProbeResult.Unreachable
        } catch (e: IOException) {
            log.warning(DiagnosticCategory.Connection, "Samsung TV at $host did not answer", e)
            SamsungProbeResult.Unreachable
        }
    }

    /** Drops the connection, for when the TV or its address is no longer in use. */
    fun close() {
        repeatJob?.cancel()
        idleJob?.cancel()
        scope.launch { lock.withLock { connection.closeQuietly(); connection = null } }
    }

    private suspend fun send(host: String, key: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            // A dropped connection is only noticed on use, so one retry on a fresh one.
            repeat(2) { attempt ->
                try {
                    val current = connection?.takeIf { it.host == host && it.isOpen }
                        ?: Connection.open(host, CONNECT_TIMEOUT_MS).also {
                            if (it.firstEvent != EVENT_CONNECT) {
                                it.close()
                                throw IOException("The Samsung TV answered ${it.firstEvent}")
                            }
                            connection.closeQuietly()
                            connection = it
                            log.info(DiagnosticCategory.Connection, "Connected to the Samsung TV at $host for volume")
                        }
                    current.sendKey(key)
                    scheduleIdleClose()
                    return@withLock
                } catch (e: IOException) {
                    connection.closeQuietly()
                    connection = null
                    if (attempt == 1) log.warning(DiagnosticCategory.Connection, "Could not send $key to the Samsung TV at $host", e)
                }
            }
        }
    }

    private fun scheduleIdleClose() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_CLOSE_MS)
            lock.withLock { connection.closeQuietly(); connection = null }
        }
    }

    private fun Connection?.closeQuietly() {
        runCatching { this?.close() }
    }

    /** One open WebSocket to the TV's remote control service. */
    private class Connection private constructor(
        val host: String,
        private val socket: SSLSocket,
        private val input: DataInputStream,
        val firstEvent: String?,
    ) : AutoCloseable {
        val isOpen: Boolean get() = !socket.isClosed && socket.isConnected && !readerEnded

        @Volatile
        private var readerEnded = false

        init {
            // Answers the TV's pings and notices when it closes, so a dead connection isn't reused.
            Thread {
                try {
                    while (true) {
                        val frame = readFrame(input)
                        when (frame.opcode) {
                            OPCODE_PING -> runCatching { writeFrame(OPCODE_PONG, frame.payload) }
                            OPCODE_CLOSE -> break
                        }
                    }
                } catch (_: IOException) {
                } finally {
                    readerEnded = true
                    runCatching { socket.close() }
                }
            }.apply { isDaemon = true }.start()
        }

        fun sendKey(key: String) {
            val message = JSONObject()
                .put("method", "ms.remote.control")
                .put(
                    "params",
                    JSONObject()
                        .put("Cmd", "Click")
                        .put("DataOfCmd", key)
                        .put("Option", "false")
                        .put("TypeOfRemote", "SendRemoteKey"),
                )
            writeFrame(OPCODE_TEXT, message.toString().toByteArray())
        }

        @Synchronized
        private fun writeFrame(opcode: Int, payload: ByteArray) {
            val mask = ByteArray(4).also(random::nextBytes)
            val out = socket.getOutputStream()
            val header = when {
                payload.size < 126 -> byteArrayOf((0x80 or opcode).toByte(), (0x80 or payload.size).toByte())
                else -> byteArrayOf(
                    (0x80 or opcode).toByte(),
                    (0x80 or 126).toByte(),
                    (payload.size shr 8).toByte(),
                    payload.size.toByte(),
                )
            }
            val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
            out.write(header + mask + masked)
            out.flush()
        }

        override fun close() {
            runCatching { socket.close() }
        }

        companion object {
            private val random = SecureRandom()

            fun open(host: String, timeoutMs: Int): Connection {
                val socket = tlsContext.socketFactory.createSocket() as SSLSocket
                try {
                    socket.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = timeoutMs
                    socket.startHandshake()
                    val key = Base64.encodeToString(ByteArray(16).also(random::nextBytes), Base64.NO_WRAP)
                    val name = Base64.encodeToString("Klikker".toByteArray(), Base64.NO_WRAP)
                    socket.getOutputStream().apply {
                        write(
                            ("GET /api/v2/channels/samsung.remote.control?name=$name HTTP/1.1\r\n" +
                                "Host: $host:$PORT\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(),
                        )
                        flush()
                    }
                    // One buffered stream for the whole connection, so nothing read ahead is lost.
                    val input = DataInputStream(socket.getInputStream().buffered())
                    val status = readLine(input)
                    if (!status.contains(" 101 ")) throw IOException("Unexpected answer: $status")
                    while (readLine(input).isNotEmpty()) Unit
                    val first = try {
                        val frame = readFrame(input)
                        runCatching { JSONObject(String(frame.payload)).optString("event") }.getOrNull()
                    } catch (e: java.net.SocketTimeoutException) {
                        throw java.net.SocketTimeoutException(NO_REPLY)
                    }
                    socket.soTimeout = 0
                    return Connection(host, socket, input, first)
                } catch (e: IOException) {
                    runCatching { socket.close() }
                    throw e
                }
            }

            private fun readLine(input: InputStream): String {
                val line = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b < 0) throw EOFException()
                    if (b == '\n'.code) return line.toString().trimEnd('\r')
                    line.append(b.toChar())
                }
            }
        }
    }

    private class Frame(val opcode: Int, val payload: ByteArray)

    private companion object {
        const val PORT = 8002
        const val CONNECT_TIMEOUT_MS = 4_000
        const val PROBE_TIMEOUT_MS = 20_000
        const val IDLE_CLOSE_MS = 60_000L
        const val HOLD_REPEAT_MS = 250L
        const val MAX_HOLD_STEPS = 40
        const val OPCODE_TEXT = 0x1
        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
        const val OPCODE_PONG = 0xA
        const val NO_REPLY = "no reply"
        const val EVENT_CONNECT = "ms.channel.connect"

        /**
         * The TV presents its own self-signed certificate, so there is nothing to check it against.
         * Only a volume key is sent, and only to an address the user typed in on their own network.
         */
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        val tlsContext: SSLContext = SSLContext.getInstance("TLS").apply {
            init(
                null,
                arrayOf(
                    object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    },
                ),
                SecureRandom(),
            )
        }

        fun readFrame(input: DataInputStream): Frame {
            val first = input.readUnsignedByte()
            val second = input.readUnsignedByte()
            var length = (second and 0x7F).toLong()
            if (length == 126L) length = input.readUnsignedShort().toLong()
            else if (length == 127L) length = input.readLong()
            if (length > MAX_FRAME_BYTES) throw IOException("Frame too large")
            val mask = if (second and 0x80 != 0) ByteArray(4).also(input::readFully) else null
            val payload = ByteArray(length.toInt()).also(input::readFully)
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            return Frame(first and 0x0F, payload)
        }

        const val MAX_FRAME_BYTES = 64 * 1024
    }
}
