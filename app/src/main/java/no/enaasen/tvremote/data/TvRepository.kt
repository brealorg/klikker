package no.enaasen.tvremote.data

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.datastore.core.DataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import no.enaasen.tvremote.data.diagnostics.DiagnosticCategory
import no.enaasen.tvremote.data.diagnostics.DiagnosticLog
import no.enaasen.tvremote.data.diagnostics.formatDuration
import no.enaasen.tvremote.data.discovery.TvDiscovery
import no.enaasen.tvremote.data.model.AppShortcut
import no.enaasen.tvremote.data.model.ConnectionState
import no.enaasen.tvremote.data.model.DefaultAppShortcuts
import no.enaasen.tvremote.data.model.DiscoveredReceiver
import no.enaasen.tvremote.data.model.ReceiverStatus
import no.enaasen.tvremote.data.model.RemoteSettings
import no.enaasen.tvremote.data.model.Tv
import no.enaasen.tvremote.data.protocol.PairingRequiredException
import no.enaasen.tvremote.data.protocol.PairingSession
import no.enaasen.tvremote.data.protocol.RemoteSession
import no.enaasen.tvremote.data.receiver.DenonReceiver
import no.enaasen.tvremote.data.security.ClientIdentity
import no.enaasen.tvremote.di.ApplicationScope
import no.enaasen.tvremote.proto.remote.RemoteDirection
import no.enaasen.tvremote.proto.remote.remoteDeviceInfo
import no.enaasen.tvremote.proto.userdata.SavedSettings
import no.enaasen.tvremote.proto.userdata.SavedTv
import no.enaasen.tvremote.proto.userdata.UserData
import no.enaasen.tvremote.proto.userdata.copy
import no.enaasen.tvremote.proto.userdata.savedSettings
import no.enaasen.tvremote.proto.userdata.savedShortcut
import no.enaasen.tvremote.proto.userdata.savedTv

/**
 * Single source of truth for saved TVs, shortcuts and the live connection.
 * The selected TV is kept connected while the app is in the foreground (plus
 * a short grace period, so switching apps briefly doesn't drop the link).
 * A configured receiver is kept connected alongside it and gets volume keys.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TvRepository @Inject constructor(
    private val dataStore: DataStore<UserData>,
    private val identity: ClientIdentity,
    private val discovery: TvDiscovery,
    private val log: DiagnosticLog,
    private val receiver: DenonReceiver,
    @ApplicationContext private val context: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Loading)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    val isConnected: Boolean get() = _connectionState.value is ConnectionState.Connected

    val tvs: Flow<List<Tv>> = dataStore.data.map { data -> data.tvsList.map { it.toTv() } }

    val shortcuts: Flow<List<AppShortcut>> = dataStore.data.map { data ->
        if (data.shortcutsInitialized) {
            data.shortcutsList.map { AppShortcut(it.label, it.packageName) }
        } else {
            DefaultAppShortcuts
        }
    }

    val settings: StateFlow<RemoteSettings> = dataStore.data
        .map { it.settings.toModel() }
        .stateIn(scope, SharingStarted.Eagerly, RemoteSettings())

    /** True when the TV should stay connected in the background (setting on and a TV saved). */
    val backgroundConnectionWanted: Flow<Boolean> = dataStore.data
        .map { it.tvsCount > 0 && !it.settings.backgroundConnectionDisabled }
        .distinctUntilChanged()

    val receiverStatus: StateFlow<ReceiverStatus> = receiver.status

    /** Set by [no.enaasen.tvremote.service.RemoteConnectionService] while it runs. */
    private val backgroundServiceRunning = MutableStateFlow(false)

    @Volatile
    private var activeSession: RemoteSession? = null

    private val reconnectRequests = MutableStateFlow(0)

    private val deviceInfo = remoteDeviceInfo {
        model = Build.MODEL
        vendor = Build.MANUFACTURER
        unknown1 = 1
        unknown2 = "1"
        packageName = context.packageName
        appVersion = "1.0.0"
    }

    init {
        val keepConnected = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
            .distinctUntilChanged()
            .transformLatest { foreground ->
                if (!foreground) delay(BACKGROUND_GRACE_MS)
                emit(foreground)
            }
        val selectedTv = dataStore.data
            .map { data -> data.tvsList.find { it.id == data.selectedId } ?: data.tvsList.firstOrNull() }
            .distinctUntilChanged()

        scope.launch {
            val receiverHost = settings.map { it.receiverHost }.distinctUntilChanged()
            combine(keepConnected, backgroundServiceRunning, receiverHost) { foreground, service, host ->
                host.takeIf { (foreground || service) && it.isNotBlank() }
            }.distinctUntilChanged().collectLatest { host ->
                if (host != null) receiver.maintain(host)
            }
        }
        scope.launch {
            combine(keepConnected, backgroundServiceRunning, selectedTv, reconnectRequests) { foreground, service, tv, generation ->
                Target(tv, foreground || service, generation)
            }.distinctUntilChanged().collectLatest { target ->
                when {
                    target.tv == null -> _connectionState.value = ConnectionState.NoTv
                    !target.foreground -> _connectionState.value = ConnectionState.Connecting(target.tv.toTv())
                    else -> maintainConnection(target.tv)
                }
            }
        }
    }

    fun sendKey(keyCode: Int, direction: RemoteDirection = RemoteDirection.SHORT) {
        if (receiver.handleKey(keyCode, direction, settings.value.receiverVolumeStep)) return
        val session = activeSession ?: return
        val isVolumeStep = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (isVolumeStep && direction == RemoteDirection.SHORT) {
            session.sendVolumeTap(keyCode)
        } else {
            session.sendKey(keyCode, direction)
        }
    }

    fun launchApp(packageName: String) {
        activeSession?.launchAppLink("market://launch?id=$packageName")
    }

    fun setBackgroundServiceRunning(running: Boolean) {
        backgroundServiceRunning.value = running
    }

    fun reconnect() {
        reconnectRequests.value++
    }

    fun discoverReceivers(): Flow<List<DiscoveredReceiver>> = discovery.discoverReceivers()

    suspend fun probeReceiver(host: String): Boolean = receiver.probe(host)

    fun newPairingSession(host: String): PairingSession = PairingSession(identity, host)

    suspend fun selectTv(id: String) {
        dataStore.updateData { it.copy { selectedId = id } }
    }

    suspend fun removeTv(id: String) {
        dataStore.updateData { data ->
            val remaining = data.tvsList.filter { it.id != id }
            data.copy {
                tvs.clear()
                tvs += remaining
                if (selectedId == id) selectedId = remaining.firstOrNull()?.id.orEmpty()
            }
        }
    }

    /** Saves a freshly paired TV (replacing an earlier pairing of the same TV) and selects it. */
    suspend fun savePairedTv(name: String, host: String, port: Int, certificateSha256: ByteArray) {
        dataStore.updateData { data ->
            val existing = data.tvsList.indexOfFirst { it.host == host || it.name == name }
            val tv = savedTv {
                id = if (existing >= 0) data.tvsList[existing].id else UUID.randomUUID().toString()
                this.name = name
                this.host = host
                this.port = port
                this.certificateSha256 = ByteString.copyFrom(certificateSha256)
            }
            data.copy {
                if (existing >= 0) tvs[existing] = tv else tvs += tv
                selectedId = tv.id
            }
        }
        reconnect()
    }

    suspend fun updateSettings(transform: (RemoteSettings) -> RemoteSettings) {
        dataStore.updateData { data ->
            val updated = transform(data.settings.toModel())
            data.copy {
                settings = savedSettings {
                    swipeSensitivity = updated.swipeSensitivity
                    flingEnabled = updated.flingEnabled
                    holdRepeatDisabled = !updated.holdToRepeat
                    holdRepeatSpeed = updated.holdRepeatSpeed
                    volumeKeysDisabled = !updated.volumeKeysControlTv
                    backgroundConnectionDisabled = !updated.keepConnectedInBackground
                    receiverHost = updated.receiverHost.trim()
                    receiverVolumeStep = updated.receiverVolumeStep
                }
            }
        }
    }

    suspend fun addShortcut(label: String, packageName: String) = editShortcuts { current ->
        current.filterNot { it.packageName == packageName } + AppShortcut(label, packageName)
    }

    suspend fun removeShortcut(packageName: String) = editShortcuts { current ->
        current.filterNot { it.packageName == packageName }
    }

    private suspend fun editShortcuts(transform: (List<AppShortcut>) -> List<AppShortcut>) {
        dataStore.updateData { data ->
            val current = if (data.shortcutsInitialized) {
                data.shortcutsList.map { AppShortcut(it.label, it.packageName) }
            } else {
                DefaultAppShortcuts
            }
            data.copy {
                shortcuts.clear()
                shortcuts += transform(current).map { savedShortcut { label = it.label; packageName = it.packageName } }
                shortcutsInitialized = true
            }
        }
    }

    private suspend fun maintainConnection(saved: SavedTv) {
        val tv = saved.toTv()
        val address = "${saved.host}:${saved.port}"
        var failures = 0
        while (true) {
            _connectionState.value = ConnectionState.Connecting(tv)
            if (failures == 0) log.info(DiagnosticCategory.Connection, "Connecting to ${tv.name} ($address)")
            val startedAt = SystemClock.elapsedRealtime()
            var connectedAt = 0L
            try {
                RemoteSession.open(
                    identity = identity,
                    host = saved.host,
                    port = saved.port,
                    pinnedCertificateSha256 = saved.certificateSha256.toByteArray(),
                    deviceInfo = deviceInfo,
                    log = log,
                ).use { session ->
                    activeSession = session
                    coroutineScope {
                        launch {
                            combine(session.isReady, session.status) { ready, status ->
                                if (ready) ConnectionState.Connected(tv, status) else ConnectionState.Connecting(tv)
                            }.collect { state ->
                                if (state is ConnectionState.Connected && connectedAt == 0L) {
                                    connectedAt = SystemClock.elapsedRealtime()
                                    log.info(
                                        DiagnosticCategory.Connection,
                                        "Connected to ${tv.name} in ${connectedAt - startedAt} ms",
                                        detail = if (failures > 0) "After $failures failed attempt(s)" else null,
                                    )
                                    failures = 0
                                }
                                _connectionState.value = state
                            }
                        }
                        session.run()
                    }
                }
            } catch (e: PairingRequiredException) {
                log.error(DiagnosticCategory.Connection, "${tv.name} rejected this phone; pairing required", e)
                _connectionState.value = ConnectionState.PairingRequired(tv)
                return
            } catch (e: IOException) {
                // Closing the socket on cancellation surfaces as an IOException; that's not a failure.
                currentCoroutineContext().ensureActive()
                if (connectedAt > 0L) {
                    val duration = formatDuration(SystemClock.elapsedRealtime() - connectedAt)
                    log.warning(DiagnosticCategory.Connection, "Connection to ${tv.name} lost after $duration", e)
                }
                failures++
                // Log the start of a failure streak, then only occasionally, so a TV that's
                // off overnight doesn't push everything else out of the log.
                if (failures <= 2 || failures % 20 == 0) {
                    log.warning(DiagnosticCategory.Connection, "Could not connect to $address (attempt $failures)", e)
                }
                // A single drop is retried quietly; only report it if the retry fails too.
                _connectionState.value = if (failures >= 2) ConnectionState.Unreachable(tv) else ConnectionState.Connecting(tv)
                // The TV may have a new DHCP address; saving it restarts the connection.
                if (failures == RELOCATE_AFTER_FAILURES && relocate(saved)) return
            } finally {
                activeSession = null
                if (connectedAt > 0L && !currentCoroutineContext().isActive) {
                    log.info(DiagnosticCategory.Connection, "Disconnected from ${tv.name}")
                }
            }
            delay(min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS shl min(failures, 4)))
        }
    }

    private suspend fun relocate(saved: SavedTv): Boolean {
        val found = discovery.find(saved.name, RELOCATE_TIMEOUT_MS) ?: return false
        if (found.host == saved.host && found.port == saved.port) return false
        log.info(DiagnosticCategory.Discovery, "${saved.name} moved to ${found.host}:${found.port}")
        dataStore.updateData { data ->
            val index = data.tvsList.indexOfFirst { it.id == saved.id }
            if (index < 0) return@updateData data
            data.copy { tvs[index] = saved.toBuilder().setHost(found.host).setPort(found.port).build() }
        }
        return true
    }

    private data class Target(val tv: SavedTv?, val foreground: Boolean, val generation: Int)

    private companion object {
        const val BACKGROUND_GRACE_MS = 30_000L
        const val INITIAL_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 8_000L
        const val RELOCATE_AFTER_FAILURES = 2
        const val RELOCATE_TIMEOUT_MS = 5_000L
    }
}

private fun SavedTv.toTv() = Tv(id = id, name = name, host = host, port = port)

private fun SavedSettings.toModel() = RemoteSettings(
    swipeSensitivity = swipeSensitivity
        .takeIf { it in RemoteSettings.MIN_SWIPE_SENSITIVITY..RemoteSettings.MAX_SWIPE_SENSITIVITY }
        ?: RemoteSettings.DEFAULT_SWIPE_SENSITIVITY,
    holdToRepeat = !holdRepeatDisabled,
    holdRepeatSpeed = holdRepeatSpeed.takeIf { it in RemoteSettings.HOLD_REPEAT_SPEEDS }
        ?: RemoteSettings.DEFAULT_HOLD_REPEAT_SPEED,
    flingEnabled = flingEnabled,
    volumeKeysControlTv = !volumeKeysDisabled,
    keepConnectedInBackground = !backgroundConnectionDisabled,
    receiverHost = receiverHost,
    receiverVolumeStep = receiverVolumeStep.takeIf { it in RemoteSettings.RECEIVER_VOLUME_STEPS }
        ?: RemoteSettings.DEFAULT_RECEIVER_VOLUME_STEP,
)
