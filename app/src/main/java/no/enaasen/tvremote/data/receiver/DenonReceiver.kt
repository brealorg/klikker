package no.enaasen.tvremote.data.receiver

import android.os.SystemClock
import android.view.KeyEvent
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
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
import kotlinx.coroutines.withContext
import no.enaasen.tvremote.data.diagnostics.DiagnosticCategory
import no.enaasen.tvremote.data.diagnostics.DiagnosticLog
import no.enaasen.tvremote.data.model.ReceiverStatus
import no.enaasen.tvremote.di.ApplicationScope
import no.enaasen.tvremote.proto.remote.RemoteDirection

/**
 * Controls a Denon/Marantz receiver's volume directly over the network. This
 * is much faster than HDMI-CEC through the TV box, allows any step size, and
 * reports the actual volume, which CEC through a SHIELD does not.
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

    // The receiver echoes every volume it is set to. Echoes of earlier taps
    // arriving after a newer one would make the shown volume jump back, and
    // the next tap would start from the stale value, so they are ignored briefly.
    @Volatile
    private var lastLocalChangeAt = 0L

    private var repeatJob: Job? = null

    /** Keeps a connection to the receiver at [host] until cancelled. */
    suspend fun maintain(host: String): Nothing {
        var failures = 0
        try {
            while (true) {
                if (failures == 0) log.info(DiagnosticCategory.Receiver, "Connecting to receiver at $host")
                try {
                    session(host) { failures = 0 }
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    failures++
                    if (failures <= 2 || failures % 20 == 0) {
                        log.warning(DiagnosticCategory.Receiver, "Receiver at $host not reachable (attempt $failures)", e)
                    }
                }
                delay(min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS shl min(failures, 5)))
            }
        } finally {
            stopRepeat()
        }
    }

    /**
     * Handles volume and mute keys while connected. Returns false for other
     * keys, or when the receiver isn't connected so the TV should get the key.
     */
    fun handleKey(keyCode: Int, direction: RemoteDirection, stepHalfDb: Int): Boolean {
        if (commands == null) return false
        val up = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                if (direction != RemoteDirection.END_LONG) toggleMute()
                return true
            }
            else -> return false
        }
        when (direction) {
            RemoteDirection.START_LONG -> {
                stopRepeat()
                // Capped, so a lost release can't run the volume to either end.
                repeatJob = scope.launch {
                    repeat(MAX_HOLD_STEPS) {
                        stepVolume(up, stepHalfDb)
                        delay(HOLD_REPEAT_MS)
                    }
                }
            }
            RemoteDirection.END_LONG -> stopRepeat()
            else -> stepVolume(up, stepHalfDb)
        }
        return true
    }

    private fun stopRepeat() {
        repeatJob?.cancel()
        repeatJob = null
    }

    private fun stepVolume(up: Boolean, stepHalfDb: Int) {
        val current = _status.value
        val volume = current.volumeHalfSteps ?: return
        val target = DenonProtocol.stepVolume(volume, stepHalfDb, up, current.maxHalfSteps)
        if (target == volume) return
        lastLocalChangeAt = SystemClock.elapsedRealtime()
        _status.update { it.copy(volumeHalfSteps = target) }
        commands?.trySend(DenonProtocol.setVolumeCommand(target))
    }

    private fun toggleMute() {
        val mute = !_status.value.isMuted
        _status.update { it.copy(isMuted = mute) }
        commands?.trySend(if (mute) "MUON" else "MUOFF")
    }

    private suspend fun session(host: String, onConnected: () -> Unit): Nothing = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, DenonProtocol.PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val outbox = Channel<String>(Channel.UNLIMITED)
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
                    launch {
                        val output = socket.outputStream
                        for (command in outbox) {
                            output.write("$command\r".toByteArray(Charsets.US_ASCII))
                            output.flush()
                            // The receiver may drop commands sent closer together than this.
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
                    readLoop(socket.inputStream, outbox, host, onConnected)
                }
            } finally {
                commands = null
                outbox.close()
                _status.value = ReceiverStatus()
            }
        }
    }

    private fun readLoop(input: InputStream, outbox: Channel<String>, host: String, onConnected: () -> Unit): Nothing {
        val reader = BufferedInputStream(input)
        val line = StringBuilder()
        var connected = false
        while (true) {
            val byte = reader.read()
            if (byte < 0) throw EOFException("Receiver closed the connection")
            if (byte != '\r'.code) {
                line.append(byte.toChar())
                continue
            }
            val text = line.toString()
            line.clear()
            if (handle(text) && !connected) {
                connected = true
                commands = outbox
                _status.update { it.copy(isConnected = true) }
                log.info(DiagnosticCategory.Receiver, "Connected to receiver at $host")
                onConnected()
            }
        }
    }

    /** Returns true once a volume has been reported, i.e. the receiver is usable. */
    private fun handle(line: String): Boolean {
        when {
            line.startsWith("MVMAX") -> DenonProtocol.parseVolume(line.removePrefix("MVMAX").trim())
                ?.let { max -> _status.update { it.copy(maxHalfSteps = max) } }
            line.startsWith("MV") -> DenonProtocol.parseVolume(line.removePrefix("MV")).let { volume ->
                val recentLocalChange = SystemClock.elapsedRealtime() - lastLocalChangeAt < ECHO_IGNORE_MS
                if (volume != null && !(recentLocalChange && _status.value.volumeHalfSteps != null)) {
                    _status.update { it.copy(volumeHalfSteps = volume) }
                }
            }
            line == "MUON" -> _status.update { it.copy(isMuted = true) }
            line == "MUOFF" -> _status.update { it.copy(isMuted = false) }
        }
        return _status.value.volumeHalfSteps != null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val POLL_INTERVAL_MS = 20_000L
        const val READ_TIMEOUT_MS = 45_000
        const val COMMAND_INTERVAL_MS = 60L
        const val HOLD_REPEAT_MS = 200L
        const val MAX_HOLD_STEPS = 20
        const val ECHO_IGNORE_MS = 1_000L
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
