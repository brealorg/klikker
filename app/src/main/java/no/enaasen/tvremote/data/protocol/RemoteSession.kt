package no.enaasen.tvremote.data.protocol

import android.os.SystemClock
import android.view.KeyEvent
import java.util.concurrent.atomic.AtomicInteger
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import no.enaasen.tvremote.data.diagnostics.DiagnosticCategory
import no.enaasen.tvremote.data.diagnostics.DiagnosticLog
import no.enaasen.tvremote.data.model.TvStatus
import no.enaasen.tvremote.data.model.TvTextField
import no.enaasen.tvremote.data.security.ClientIdentity
import no.enaasen.tvremote.data.security.PinningTrustManager
import no.enaasen.tvremote.proto.remote.RemoteDeviceInfo
import no.enaasen.tvremote.proto.remote.RemoteDirection
import no.enaasen.tvremote.proto.remote.RemoteMessage
import no.enaasen.tvremote.proto.remote.remoteAppLinkLaunchRequest
import no.enaasen.tvremote.proto.remote.remoteConfigure
import no.enaasen.tvremote.proto.remote.remoteEditInfo
import no.enaasen.tvremote.proto.remote.remoteImeBatchEdit
import no.enaasen.tvremote.proto.remote.remoteImeObject
import no.enaasen.tvremote.proto.remote.remoteKeyInject
import no.enaasen.tvremote.proto.remote.remoteMessage
import no.enaasen.tvremote.proto.remote.remotePingResponse
import no.enaasen.tvremote.proto.remote.remoteSetActive

/**
 * An open control channel to a paired TV. Call [run] to service the
 * connection; it only returns by throwing, when the connection ends.
 */
class RemoteSession private constructor(
    private val socket: SSLSocket,
    private val deviceInfo: RemoteDeviceInfo,
    private val log: DiagnosticLog,
) : Closeable {

    private val outbox = Channel<RemoteMessage>(Channel.UNLIMITED)

    // Volume often travels on to the TV over HDMI-CEC, which drops presses that
    // arrive faster than it can handle them, so volume taps are queued and paced.
    private val volumeTaps = Channel<Int>(MAX_QUEUED_VOLUME_TAPS, BufferOverflow.DROP_LATEST)

    private val _isReady = MutableStateFlow(false)
    /** True once the TV has accepted our configuration and will act on keys. */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _status = MutableStateFlow(TvStatus())
    val status: StateFlow<TvStatus> = _status.asStateFlow()

    fun sendKey(keyCode: Int, direction: RemoteDirection = RemoteDirection.SHORT) = send(
        remoteMessage {
            remoteKeyInject = remoteKeyInject {
                this.keyCode = keyCode
                this.direction = direction
            }
        },
    )

    /** Queues a single volume key press; see [volumeTaps]. */
    fun sendVolumeTap(keyCode: Int) {
        volumeTaps.trySend(keyCode)
    }

    // Typing: the TV accepts an insertion only with the counters it last
    // reported, and reports new ones (and its text) after each insertion.
    // Deletions are backspace key presses, which need no counters.
    private val wantedText = MutableStateFlow<String?>(null)
    private val textReports = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var imeCounter = 0

    @Volatile
    private var imeFieldCounter = 0

    /** The TV's text as last reported, adjusted for edits sent since. */
    @Volatile
    private var tvText = ""


    /** Makes the TV's focused text field read [text]. */
    fun setText(text: String) {
        wantedText.value = text
    }

    fun launchAppLink(link: String, packageName: String?) = send(
        remoteMessage {
            remoteAppLinkLaunchRequest = remoteAppLinkLaunchRequest {
                appLink = link
                packageName?.let { this.packageName = it }
            }
        },
    )

    suspend fun run(): Nothing = coroutineScope {
        // Blocking socket reads ignore cancellation; closing the socket unblocks them.
        launch {
            try {
                awaitCancellation()
            } finally {
                close()
            }
        }
        launch {
            // Collecting a StateFlow skips text typed while an earlier sync ran.
            wantedText.collect { text -> if (text != null) syncText(text) }
        }
        launch {
            for (keyCode in volumeTaps) {
                sendKey(keyCode)
                delay(VOLUME_TAP_INTERVAL_MS)
            }
        }
        launch(Dispatchers.IO) {
            val output = socket.outputStream
            for (message in outbox) message.writeFramedTo(output)
        }
        withContext(Dispatchers.IO) { readLoop() }
    }

    override fun close() {
        outbox.close()
        volumeTaps.close()
        runCatching { socket.close() }
    }

    private suspend fun syncText(wanted: String) {
        repeat(MAX_TEXT_SYNC_ROUNDS) {
            val current = tvText
            if (current == wanted) return
            // Assumes the TV's cursor is at the end, as it is while typing.
            val edit = textEdit(current, wanted)
            repeat(edit.backspaces) { sendKey(KeyEvent.KEYCODE_DEL) }
            tvText = current.take(edit.kept)
            if (edit.insert.isEmpty()) return
            textReports.tryReceive()
            send(
                remoteMessage {
                    remoteImeBatchEdit = remoteImeBatchEdit {
                        imeCounter = this@RemoteSession.imeCounter
                        fieldCounter = imeFieldCounter
                        editInfo += remoteEditInfo {
                            insert = 1
                            textFieldStatus = remoteImeObject {
                                start = wanted.length - 1
                                end = wanted.length - 1
                                value = edit.insert
                            }
                        }
                    }
                },
            )
            tvText = wanted
            // The next insertion needs the counters the TV reports for this one;
            // the report also corrects tvText if the TV changed the text.
            withTimeoutOrNull(TEXT_REPORT_TIMEOUT_MS) { textReports.receive() }
        }
    }

    private fun send(message: RemoteMessage) {
        outbox.trySend(message)
    }

    private fun readLoop(): Nothing {
        val input = socket.inputStream
        var receivedAny = false
        var lastMessageAt = SystemClock.elapsedRealtime()
        while (true) {
            // With TLS 1.3 the TV rejects an unknown client certificate only
            // after the handshake, so a failure before the first message
            // means we are not paired.
            val message = try {
                RemoteMessage.parseDelimitedFrom(input)
            } catch (e: IOException) {
                // Protobuf wraps the TLS failure in an InvalidProtocolBufferException.
                val tlsFailure = generateSequence<Throwable>(e) { it.cause }.any { it is SSLException }
                throw if (receivedAny || !tlsFailure) e else PairingRequiredException(e)
            } ?: throw if (receivedAny) EOFException("TV closed the connection") else PairingRequiredException()
            receivedAny = true
            val now = SystemClock.elapsedRealtime()
            // The TV pings every ~5 s; a long gap explains later timeouts.
            if (now - lastMessageAt > SILENCE_WARNING_MS) {
                log.warning(DiagnosticCategory.Connection, "TV was silent for ${(now - lastMessageAt) / 1000} s")
            }
            lastMessageAt = now
            handle(message)
        }
    }

    private fun handle(message: RemoteMessage) {
        when {
            message.hasRemoteConfigure() -> send(
                remoteMessage {
                    remoteConfigure = remoteConfigure {
                        code1 = FEATURES
                        deviceInfo = this@RemoteSession.deviceInfo
                    }
                },
            )
            message.hasRemoteSetActive() -> {
                send(remoteMessage { remoteSetActive = remoteSetActive { active = FEATURES } })
                _isReady.value = true
            }
            message.hasRemotePingRequest() -> send(
                remoteMessage { remotePingResponse = remotePingResponse { val1 = message.remotePingRequest.val1 } },
            )
            message.hasRemoteStart() -> _status.update { it.copy(isOn = message.remoteStart.started) }
            message.hasRemoteSetVolumeLevel() -> with(message.remoteSetVolumeLevel) {
                // With HDMI-CEC volume passthrough (e.g. SHIELD) the device reports a fixed 0
                // rather than the TV's volume; treat that as unknown instead of showing it.
                val known = volumeLevel > 0 || volumeMuted
                _status.update {
                    it.copy(
                        volumeLevel = volumeLevel.takeIf { known },
                        volumeMax = volumeMax.takeIf { known && volumeMax > 0 },
                        isMuted = volumeMuted,
                    )
                }
            }
            // The TV's answer to each insertion, with the counters for the next one.
            message.hasRemoteImeShowRequest() -> with(message.remoteImeShowRequest.remoteTextFieldStatus) {
                imeFieldCounter = counterField
                tvText = value
                _status.update { it.copy(textField = it.textField?.copy(label = label, text = value)) }
                textReports.trySend(Unit)
            }
            message.hasRemoteImeKeyInject() -> with(message.remoteImeKeyInject) {
                val appPackage = appInfo.appPackage
                if (appPackage.isNotEmpty()) _status.update { it.copy(currentAppPackage = appPackage) }
                if (hasTextFieldStatus()) {
                    val focusId = focusIds.incrementAndGet()
                    // Never the text itself: the field may hold a password.
                    log.info(
                        DiagnosticCategory.Connection,
                        "TV text field focused in $appPackage (inputType ${appInfo.int2}, imeOptions ${appInfo.int3})",
                    )
                    imeCounter = appInfo.counter
                    imeFieldCounter = textFieldStatus.counterField
                    tvText = textFieldStatus.value
                    _status.update {
                        it.copy(
                            textField = TvTextField(
                                // The TV sends this on each focus (its edit reports come
                                // as show requests), so every one is a new field.
                                focusId = focusId,
                                label = textFieldStatus.label,
                                text = textFieldStatus.value,
                                inputType = appInfo.int2,
                                imeOptions = appInfo.int3,
                            ),
                        )
                    }
                    textReports.trySend(Unit)
                } else {
                    if (_status.value.textField != null) {
                        log.info(DiagnosticCategory.Connection, "TV text field lost focus in $appPackage")
                    }
                    wantedText.value = null
                    _status.update { it.copy(textField = null) }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 6466
        // Shared across connections so a field after a reconnect is never mistaken for an earlier one.
        private val focusIds = AtomicInteger()
        private const val MAX_TEXT_SYNC_ROUNDS = 3
        private const val TEXT_REPORT_TIMEOUT_MS = 1_000L
        private const val SILENCE_WARNING_MS = 12_000L
        private const val VOLUME_TAP_INTERVAL_MS = 400L
        private const val MAX_QUEUED_VOLUME_TAPS = 10

        // Bit flags: ping(1) | key(2) | ime(4) | power(32) | volume(64) | app link(512).
        private const val FEATURES = 1 or 2 or 4 or 32 or 64 or 512

        suspend fun open(
            identity: ClientIdentity,
            host: String,
            port: Int,
            pinnedCertificateSha256: ByteArray,
            deviceInfo: RemoteDeviceInfo,
            log: DiagnosticLog,
        ): RemoteSession = withContext(Dispatchers.IO) {
            val socket = try {
                identity.sslContext(PinningTrustManager(pinnedCertificateSha256)).openSocket(host, port)
            } catch (e: SSLHandshakeException) {
                throw PairingRequiredException(e)
            }
            RemoteSession(socket, deviceInfo, log)
        }
    }
}
