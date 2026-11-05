package no.enaasen.tvremote.data.receiver

import android.os.SystemClock
import android.view.KeyEvent
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import no.enaasen.tvremote.data.diagnostics.DiagnosticCategory
import no.enaasen.tvremote.data.diagnostics.DiagnosticLog
import no.enaasen.tvremote.data.model.ReceiverStatus
import no.enaasen.tvremote.di.ApplicationScope
import no.enaasen.tvremote.proto.remote.RemoteDirection

/** Why a receiver check at an address succeeded or failed. */
enum class ProbeResult {
    Found,

    /** The address is reachable but refuses port 23, typically because another app holds the only connection. */
    Refused,

    /** Nothing answered: wrong address, other network, VPN, or the receiver is off the network. */
    Unreachable,

    /** Something accepted the connection but did not speak the receiver protocol. */
    NoReply,
}

/**
 * Controls a Denon/Marantz receiver's volume directly over the network. This
 * is much faster than HDMI-CEC through the TV box, allows any step size, and
 * reports the actual volume, which CEC through a SHIELD does not.
 *
 * The receiver accepts only one connection on its control port. So the
 * connection is released after [IDLE_RELEASE_MS] without use, letting other apps
 * (the receiver's own, a second phone) have it, and is reopened on the next
 * volume key. If another controller holds it, the receiver's web interface is
 * used instead; it takes one short request at a time and has no such limit.
 */
@Singleton
class DenonReceiver @Inject constructor(
    private val log: DiagnosticLog,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(ReceiverStatus())
    val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

    @Volatile
    private var commands: Channel<String>? = null

    @Volatile
    private var connectedHost: String? = null

    // Set while [maintain] runs, even when the control connection is released or busy.
    @Volatile
    private var maintainedHost: String? = null

    // The last attempt to open the control connection failed, so keys go over HTTP without waiting for it.
    @Volatile
    private var telnetBusy = false

    @Volatile
    private var lastActivityAt = 0L

    @Volatile
    private var lastHttpRefreshAt = 0L

    // The receiver echoes every volume it is set to. Echoes of earlier taps
    // arriving after a newer one would make the shown volume jump back, and
    // the next tap would start from the stale value, so they are ignored briefly.
    @Volatile
    private var lastLocalChangeAt = 0L

    // Only the latest wanted volume is sent. Queueing every step made the receiver
    // replay a fast turn up and down for seconds after the finger had stopped.
    private val volumeTargets = Channel<Int>(Channel.CONFLATED)

    // The same, for the web interface while the control connection isn't open.
    private val httpVolumeTargets = Channel<Int>(Channel.CONFLATED)
    private val httpCommands = Channel<String>(Channel.UNLIMITED)

    // Asks [maintain] to open the control connection now instead of waiting.
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var repeatJob: Job? = null

    /** Keeps the receiver at [host] controllable until cancelled. */
    suspend fun maintain(host: String): Nothing = coroutineScope<Nothing> {
        launch {
            for (target in httpVolumeTargets) {
                httpSend(host, DenonProtocol.setVolumeCommand(target))
                delay(COMMAND_INTERVAL_MS)
            }
        }
        launch {
            for (command in httpCommands) {
                httpSend(host, command)
                delay(COMMAND_INTERVAL_MS)
            }
        }
        maintainConnection(host)
    }

    private suspend fun maintainConnection(host: String): Nothing {
        maintainedHost = host
        var failures = 0
        try {
            while (true) {
                if (failures == 0) log.info(DiagnosticCategory.Receiver, "Connecting to receiver at $host")
                try {
                    session(host) {
                        failures = 0
                        telnetBusy = false
                    }
                    // Released after sitting idle. Stay off the connection until a key needs it.
                    log.info(DiagnosticCategory.Receiver, "Released the receiver connection after ${IDLE_RELEASE_MS / 1000}s idle")
                    while (wake.tryReceive().isSuccess) Unit
                    wake.receive()
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    failures++
                    val wasBusy = telnetBusy
                    telnetBusy = true
                    // Keeps the volume shown and controllable while the connection is taken.
                    val viaHttp = refreshViaHttp(host)
                    if (failures <= 2 || failures % 20 == 0) {
                        log.warning(
                            DiagnosticCategory.Receiver,
                            "Receiver at $host not reachable (attempt $failures)" +
                                if (viaHttp && !wasBusy) ", using its web interface instead" else "",
                            e,
                        )
                    }
                    // A key press can cut the wait short to try again at once.
                    withTimeoutOrNull(min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS shl min(failures, 5))) { wake.receive() }
                }
            }
        } finally {
            stopRepeat()
            maintainedHost = null
            telnetBusy = false
        }
    }

    /** Checks whether a Denon/Marantz receiver answers at [host], and if not, why. */
    suspend fun probe(host: String): ProbeResult {
        // The receiver accepts only one control connection, so don't open a second.
        if (connectedHost == host) return ProbeResult.Found
        val result = withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    try {
                        socket.connect(InetSocketAddress(host, DenonProtocol.PORT), CONNECT_TIMEOUT_MS)
                    } catch (e: IOException) {
                        return@withContext if (e.message?.contains("ECONNREFUSED", ignoreCase = true) == true ||
                            e.message?.contains("refused", ignoreCase = true) == true
                        ) {
                            // Another controller holds the only connection; the web interface still works.
                            if (httpAnswers(host)) {
                                log.info(DiagnosticCategory.Receiver, "Receiver at $host is busy, but its web interface answers")
                                ProbeResult.Found
                            } else {
                                log.warning(DiagnosticCategory.Receiver, "Receiver at $host refused the connection; another app may hold it", e)
                                ProbeResult.Refused
                            }
                        } else {
                            log.warning(DiagnosticCategory.Receiver, "Receiver at $host not reachable", e)
                            ProbeResult.Unreachable
                        }
                    }
                    socket.soTimeout = PROBE_TIMEOUT_MS
                    try {
                        socket.outputStream.write("MV?\r".toByteArray(Charsets.US_ASCII))
                        val input = BufferedInputStream(socket.inputStream)
                        // Other status lines may arrive first; a timeout ends the wait.
                        val found = generateSequence { input.readLine() }.any { line ->
                            line.startsWith("MV") && DenonProtocol.parseVolume(line.removePrefix("MV")) != null
                        }
                        if (found) ProbeResult.Found else ProbeResult.NoReply
                    } catch (e: IOException) {
                        log.warning(DiagnosticCategory.Receiver, "Connected to $host but it did not answer like a receiver", e)
                        ProbeResult.NoReply
                    }
                }
            } catch (e: IOException) {
                log.warning(DiagnosticCategory.Receiver, "Probing receiver at $host failed", e)
                ProbeResult.Unreachable
            }
        }
        if (result == ProbeResult.Found) log.info(DiagnosticCategory.Receiver, "Receiver answered at $host")
        return result
    }

    private suspend fun httpAnswers(host: String): Boolean = try {
        DenonProtocol.parseHttpVolume(httpGet(host, DenonProtocol.HTTP_STATUS_PATH)) != null
    } catch (_: IOException) {
        false
    }

    /**
     * Handles volume and mute keys while the receiver is usable. Returns false for
     * other keys, or when the receiver isn't known to be there so the TV should get the key.
     */
    fun handleKey(keyCode: Int, direction: RemoteDirection, stepHalfDb: Int, limitHalfSteps: Int): Boolean {
        if (maintainedHost == null || _status.value.volumeHalfSteps == null) return false
        val up = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                if (direction != RemoteDirection.END_LONG) whenReady { toggleMute() }
                return true
            }
            else -> return false
        }
        when (direction) {
            RemoteDirection.START_LONG -> {
                stopRepeat()
                // Capped, so a lost release can't run the volume to either end.
                repeatJob = scope.launch {
                    prepare()
                    repeat(MAX_HOLD_STEPS) {
                        stepVolume(up, stepHalfDb, limitHalfSteps)
                        delay(HOLD_REPEAT_MS)
                    }
                }
            }
            RemoteDirection.END_LONG -> stopRepeat()
            else -> whenReady { stepVolume(up, stepHalfDb, limitHalfSteps) }
        }
        return true
    }

    /** Sets the volume outright, within the receiver's range and [limitHalfSteps]. */
    fun setVolumeTo(halfSteps: Int, limitHalfSteps: Int) {
        if (maintainedHost == null) return
        whenReady {
            val current = _status.value
            val volume = current.volumeHalfSteps ?: return@whenReady
            val target = halfSteps.coerceIn(0, minOf(current.maxHalfSteps, limitHalfSteps))
            if (target == volume) return@whenReady
            lastLocalChangeAt = SystemClock.elapsedRealtime()
            lastActivityAt = lastLocalChangeAt
            _status.update { it.copy(volumeHalfSteps = target) }
            sendVolume(target)
        }
    }

    private fun stopRepeat() {
        repeatJob?.cancel()
        repeatJob = null
    }

    /** Runs [action] at once on an open connection, otherwise once [prepare] has made the volume current. */
    private fun whenReady(action: () -> Unit) {
        if (commands != null) action() else scope.launch {
            prepare()
            action()
        }
    }

    /**
     * Makes the volume current before it is stepped from. After the connection was
     * released that means reopening it; if the receiver is busy with another
     * controller it means reading the volume over HTTP, so the step isn't taken from a stale value.
     */
    private suspend fun prepare() {
        if (commands != null) return
        val host = maintainedHost ?: return
        wake.trySend(Unit)
        if (!telnetBusy) {
            // Gives up early if the attempt this wake triggers is refused.
            withTimeoutOrNull(WAKE_WAIT_MS) { while (commands == null && !telnetBusy) delay(20) }
            if (commands != null) return
        }
        if (SystemClock.elapsedRealtime() - lastHttpRefreshAt > HTTP_FRESH_MS) refreshViaHttp(host)
    }

    private fun sendVolume(target: Int) {
        if (commands != null) volumeTargets.trySend(target) else httpVolumeTargets.trySend(target)
    }

    private fun stepVolume(up: Boolean, stepHalfDb: Int, limitHalfSteps: Int) {
        val current = _status.value
        val volume = current.volumeHalfSteps ?: return
        // Up never goes past the limit. If the receiver is already above it (set
        // elsewhere), going up does nothing and going down steps normally.
        val max = if (up) minOf(current.maxHalfSteps, limitHalfSteps) else current.maxHalfSteps
        if (up && volume >= max) return
        val target = DenonProtocol.stepVolume(volume, stepHalfDb, up, max)
        if (target == volume) return
        lastLocalChangeAt = SystemClock.elapsedRealtime()
        lastActivityAt = lastLocalChangeAt
        _status.update { it.copy(volumeHalfSteps = target) }
        sendVolume(target)
    }

    private fun toggleMute() {
        val mute = !_status.value.isMuted
        lastActivityAt = SystemClock.elapsedRealtime()
        _status.update { it.copy(isMuted = mute) }
        val command = if (mute) "MUON" else "MUOFF"
        val open = commands
        if (open != null) open.trySend(command) else httpCommands.trySend(command)
    }

    /** Reads volume and mute from the web interface. Returns whether it answered. */
    private suspend fun refreshViaHttp(host: String): Boolean {
        val xml = try {
            httpGet(host, DenonProtocol.HTTP_STATUS_PATH)
        } catch (_: IOException) {
            return false
        }
        val volume = DenonProtocol.parseHttpVolume(xml) ?: return false
        val mute = DenonProtocol.parseHttpMute(xml)
        lastHttpRefreshAt = SystemClock.elapsedRealtime()
        val recentLocalChange = lastHttpRefreshAt - lastLocalChangeAt < ECHO_IGNORE_MS
        _status.update {
            it.copy(
                isConnected = true,
                volumeHalfSteps = if (recentLocalChange && it.volumeHalfSteps != null) it.volumeHalfSteps else volume,
                isMuted = mute ?: it.isMuted,
            )
        }
        return true
    }

    private suspend fun httpSend(host: String, command: String) {
        try {
            httpGet(host, DenonProtocol.httpCommandPath(command))
        } catch (e: IOException) {
            log.warning(DiagnosticCategory.Receiver, "Could not send $command to $host over HTTP", e)
        }
    }

    /**
     * A plain HTTP GET over a raw socket: Android blocks cleartext HTTP in its own
     * clients unless the whole app opts in, and this is only ever a local address.
     */
    private suspend fun httpGet(host: String, path: String): String = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, DenonProtocol.HTTP_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HTTP_TIMEOUT_MS
            socket.outputStream.write(
                "GET $path HTTP/1.0\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII),
            )
            socket.outputStream.flush()
            val body = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (body.size() < MAX_HTTP_BYTES) {
                val read = socket.inputStream.read(buffer)
                if (read < 0) break
                body.write(buffer, 0, read)
            }
            val response = body.toString(Charsets.UTF_8.name())
            if (!response.startsWith("HTTP/") || response.substringBefore('\r').split(' ').getOrNull(1) != "200") {
                throw IOException("Unexpected HTTP reply: ${response.substringBefore('\r').take(60)}")
            }
            response.substringAfter("\r\n\r\n", "")
        }
    }

    /**
     * Holds the control connection until it fails, which throws, or until it has
     * sat idle for [IDLE_RELEASE_MS], which returns normally.
     */
    private suspend fun session(host: String, onConnected: () -> Unit) = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, DenonProtocol.PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val outbox = Channel<String>(Channel.UNLIMITED)
            // A target left from before a reconnect is stale.
            while (volumeTargets.tryReceive().isSuccess) Unit
            val idleReleased = AtomicBoolean(false)
            lastActivityAt = SystemClock.elapsedRealtime()
            try {
                coroutineScope {
                    // Blocking socket reads ignore cancellation; closing the socket unblocks them.
                    launch {
                        try {
                            awaitCancellation()
                        } finally {
                            socket.close()
                        }
                    }
                    val output = socket.outputStream
                    val writeLock = Mutex()
                    suspend fun write(command: String) = writeLock.withLock {
                        output.write("$command\r".toByteArray(Charsets.US_ASCII))
                        output.flush()
                    }
                    launch {
                        for (command in outbox) {
                            write(command)
                            // The receiver may drop commands sent closer together than this.
                            delay(COMMAND_INTERVAL_MS)
                        }
                    }
                    launch {
                        for (target in volumeTargets) {
                            write(DenonProtocol.setVolumeCommand(target))
                            delay(COMMAND_INTERVAL_MS)
                        }
                    }
                    // Polling keeps the read timeout from firing on a quiet link and
                    // catches changes made while the receiver didn't report them.
                    launch {
                        while (true) {
                            outbox.send("MV?")
                            outbox.send("MU?")
                            delay(POLL_INTERVAL_MS)
                        }
                    }
                    // Closing the socket ends the read loop with an exception, which is caught below.
                    launch {
                        while (true) {
                            val remaining = IDLE_RELEASE_MS - (SystemClock.elapsedRealtime() - lastActivityAt)
                            if (remaining <= 0) break
                            delay(remaining)
                        }
                        idleReleased.set(true)
                        socket.close()
                    }
                    readLoop(socket.inputStream, outbox, host, onConnected)
                }
            } catch (e: IOException) {
                if (!idleReleased.get()) throw e
            } finally {
                commands = null
                connectedHost = null
                outbox.close()
                // An idle release keeps the last volume, so it is still shown and stepped from after a refresh.
                if (!idleReleased.get()) _status.value = ReceiverStatus()
            }
        }
    }

    private fun readLoop(input: InputStream, outbox: Channel<String>, host: String, onConnected: () -> Unit): Nothing {
        val reader = BufferedInputStream(input)
        var connected = false
        while (true) {
            if (handle(reader.readLine()) && !connected) {
                connected = true
                commands = outbox
                connectedHost = host
                _status.update { it.copy(isConnected = true) }
                log.info(DiagnosticCategory.Receiver, "Connected to receiver at $host")
                onConnected()
            }
        }
    }

    /**
     * Returns true when [line] reported the volume, i.e. the receiver is usable.
     * It must be this connection's own report: the volume kept from before an idle
     * release could be stale.
     */
    private fun handle(line: String): Boolean {
        var reportedVolume = false
        when {
            line.startsWith("MVMAX") -> DenonProtocol.parseVolume(line.removePrefix("MVMAX").trim())
                ?.let { max -> _status.update { it.copy(maxHalfSteps = max) } }
            line.startsWith("MV") -> DenonProtocol.parseVolume(line.removePrefix("MV")).let { volume ->
                reportedVolume = volume != null
                val recentLocalChange = SystemClock.elapsedRealtime() - lastLocalChangeAt < ECHO_IGNORE_MS
                if (volume != null && !(recentLocalChange && _status.value.volumeHalfSteps != null)) {
                    _status.update { it.copy(volumeHalfSteps = volume) }
                }
            }
            line == "MUON" -> _status.update { it.copy(isMuted = true) }
            line == "MUOFF" -> _status.update { it.copy(isMuted = false) }
        }
        return reportedVolume
    }

    private fun BufferedInputStream.readLine(): String {
        val line = StringBuilder()
        while (true) {
            val byte = read()
            if (byte < 0) throw EOFException("Receiver closed the connection")
            if (byte == '\r'.code) return line.toString()
            line.append(byte.toChar())
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val PROBE_TIMEOUT_MS = 2_000
        const val POLL_INTERVAL_MS = 20_000L
        const val READ_TIMEOUT_MS = 45_000
        const val HTTP_TIMEOUT_MS = 2_000
        const val MAX_HTTP_BYTES = 64 * 1024
        const val HTTP_FRESH_MS = 3_000L
        const val IDLE_RELEASE_MS = 60_000L
        const val WAKE_WAIT_MS = 2_500L
        const val COMMAND_INTERVAL_MS = 60L
        const val HOLD_REPEAT_MS = 200L
        const val MAX_HOLD_STEPS = 20
        const val ECHO_IGNORE_MS = 1_000L
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
