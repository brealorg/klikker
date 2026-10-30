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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import no.enaasen.tvremote.data.apps.AppCatalog
import no.enaasen.tvremote.data.diagnostics.DiagnosticCategory
import no.enaasen.tvremote.data.diagnostics.DiagnosticLog
import no.enaasen.tvremote.data.nowplaying.CompanionClient
import no.enaasen.tvremote.data.diagnostics.formatDuration
import no.enaasen.tvremote.data.discovery.TvDiscovery
import no.enaasen.tvremote.data.model.AppShortcut
import no.enaasen.tvremote.data.model.ConnectionState
import no.enaasen.tvremote.data.model.DiscoveredReceiver
import no.enaasen.tvremote.data.model.MediaSessionInfo
import no.enaasen.tvremote.data.model.ReceiverStatus
import no.enaasen.tvremote.data.model.RemoteSettings
import no.enaasen.tvremote.data.model.Tv
import no.enaasen.tvremote.data.model.TvApps
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
import no.enaasen.tvremote.proto.userdata.seenApp

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
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Loading)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    val isConnected: Boolean get() = _connectionState.value is ConnectionState.Connected

    val tvs: Flow<List<Tv>> = dataStore.data.map { data -> data.tvsList.map { it.toTv() } }

    /** The protocol can't list a TV's apps, so they are learned as the TV opens them. */
    val apps: Flow<TvApps> = dataStore.data.map { data ->
        val tv = data.selectedTv() ?: return@map TvApps()
        val pinned = data.pinnedShortcuts(tv)
        val suggested = tv.seenAppsList
            .filter { seen -> pinned.none { it.packageName == seen.packageName } }
            .take(MAX_SUGGESTED_APPS)
            .map { AppShortcut(AppCatalog.label(it.packageName), it.packageName) }
        TvApps(pinned, suggested)
    }.distinctUntilChanged()

    val settings: StateFlow<RemoteSettings> = dataStore.data
        .map { it.settings.toModel() }
        .stateIn(scope, SharingStarted.Eagerly, RemoteSettings())

    /** True when the TV should stay connected in the background (setting on and a TV saved). */
    val backgroundConnectionWanted: Flow<Boolean> = dataStore.data
        .map { it.tvsCount > 0 && !it.settings.backgroundConnectionDisabled }
        .distinctUntilChanged()

    val receiverStatus: StateFlow<ReceiverStatus> = receiver.status

    /**
     * What the TV's apps are playing, from the companion app on the TV; empty
     * without it. Polled only while someone watches, and rarely while it is missing.
     */
    val nowPlaying: StateFlow<List<MediaSessionInfo>> = _connectionState
        .map { (it as? ConnectionState.Connected)?.tv?.host }
        .distinctUntilChanged()
        .transformLatest { host ->
            if (host == null) {
                emit(emptyList())
                return@transformLatest
            }
            while (true) {
                val sessions = CompanionClient.fetch(host)
                emit(sessions.orEmpty())
                delay(if (sessions != null) NOW_PLAYING_POLL_MS else NOW_PLAYING_RETRY_MS)
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
            .map { it.selectedTv() }
            .distinctUntilChanged { old, new -> old?.withoutApps() == new?.withoutApps() }

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
        val current = settings.value
        if (receiver.handleKey(keyCode, direction, current.receiverVolumeStep, current.receiverMaxHalfSteps)) return
        val session = activeSession ?: return
        val isVolumeStep = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (isVolumeStep && direction == RemoteDirection.SHORT) {
            session.sendVolumeTap(keyCode)
        } else {
            session.sendKey(keyCode, direction)
        }
    }

    /** Makes the text field focused on the TV read [text]. */
    fun setText(text: String) {
        activeSession?.setText(text)
    }

    /**
     * Turns the TV off, or on. A SHIELD treats this remote as a game
     * controller, and while asleep it lets controllers wake it only with the
     * NVIDIA button ("simplified wake"), swallowing Power. So if Power doesn't
     * wake the TV, the NVIDIA button follows; other TVs wake on Power.
     */
    fun togglePower() {
        val session = activeSession ?: return
        val wasOn = session.status.value.isOn == true
        session.sendKey(KeyEvent.KEYCODE_POWER)
        if (wasOn) return
        scope.launch {
            val woke = withTimeoutOrNull(WAKE_TIMEOUT_MS) { session.status.first { it.isOn == true } } != null
            if (!woke && activeSession === session) {
                log.info(DiagnosticCategory.Connection, "Power didn't wake the TV; trying the NVIDIA button")
                session.sendKey(KeyEvent.KEYCODE_BUTTON_MODE)
            }
        }
    }

    fun launchApp(packageName: String) {
        val launch = AppCatalog.launch(packageName)
        val session = activeSession
        log.info(
            DiagnosticCategory.Connection,
            if (session != null) "Opening ${AppCatalog.label(packageName)}" else "Not connected; can't open ${AppCatalog.label(packageName)}",
            detail = "${launch.link} (${launch.packageName ?: "any app"})",
        )
        session?.launchAppLink(launch.link, launch.packageName)
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
            // Re-pairing keeps the TV's shortcuts and learned apps.
            val tv = (if (existing >= 0) data.tvsList[existing] else savedTv { id = UUID.randomUUID().toString() }).copy {
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
                    appNamesHidden = !updated.showAppNames
                    volumeHaptics = updated.volumeHaptics
                    receiverMaxHalfSteps = updated.receiverMaxHalfSteps
                }
            }
        }
    }

    suspend fun addShortcut(label: String, packageName: String) = editShortcuts { current ->
        current.filterNot { it.packageName == packageName } + AppShortcut(label, packageName)
    }

    suspend fun renameShortcut(packageName: String, label: String) = editShortcuts { current ->
        current.map { if (it.packageName == packageName) it.copy(label = label) else it }
    }

    suspend fun removeShortcut(packageName: String) = editShortcuts { current ->
        current.filterNot { it.packageName == packageName }
    }

    private suspend fun editShortcuts(transform: (List<AppShortcut>) -> List<AppShortcut>) {
        dataStore.updateData { data ->
            val tv = data.selectedTv() ?: return@updateData data
            val updated = tv.copy {
                val edited = transform(data.pinnedShortcuts(tv))
                shortcuts.clear()
                shortcuts += edited.map { savedShortcut { label = it.label; packageName = it.packageName } }
                shortcutsInitialized = true
            }
            data.copy { tvs[data.tvsList.indexOf(tv)] = updated }
        }
    }

    private suspend fun recordSeenApp(tvId: String, packageName: String) {
        var isNew = false
        dataStore.updateData { data ->
            val index = data.tvsList.indexOfFirst { it.id == tvId }
            if (index < 0) return@updateData data
            val tv = data.tvsList[index]
            if (!AppCatalog.isSuggestible(packageName)) {
                return@updateData data.copy { tvs[index] = tv.copy { lastAppPackage = packageName } }
            }
            isNew = tv.seenAppsList.none { it.packageName == packageName }
            val seen = seenApp {
                this.packageName = packageName
                lastSeenMillis = System.currentTimeMillis()
            }
            val others = tv.seenAppsList.filter { it.packageName != packageName }
            data.copy {
                tvs[index] = tv.copy {
                    lastAppPackage = packageName
                    seenApps.clear()
                    seenApps += (listOf(seen) + others).take(MAX_SEEN_APPS)
                }
            }
        }
        if (isNew) log.info(DiagnosticCategory.Connection, "Found ${AppCatalog.label(packageName)} ($packageName) on the TV")
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
                            session.status.mapNotNull { it.currentAppPackage }.distinctUntilChanged().collect {
                                recordSeenApp(saved.id, it)
                            }
                        }
                        launch {
                            combine(session.isReady, session.status) { ready, status ->
                                // Until the TV reports a switch, assume the app it last reported.
                                val known = status.copy(currentAppPackage = status.currentAppPackage ?: saved.lastAppPackage.ifEmpty { null })
                                if (ready) ConnectionState.Connected(tv, known) else ConnectionState.Connecting(tv)
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
        const val WAKE_TIMEOUT_MS = 1_500L
        const val NOW_PLAYING_POLL_MS = 3_000L
        const val NOW_PLAYING_RETRY_MS = 30_000L
        const val MAX_SEEN_APPS = 40
        const val MAX_SUGGESTED_APPS = 10
    }
}

private fun SavedTv.toTv() = Tv(id = id, name = name, host = host, port = port)

private fun UserData.selectedTv(): SavedTv? = tvsList.find { it.id == selectedId } ?: tvsList.firstOrNull()

/** Shortcuts made before they were kept per TV apply until the TV gets its own. */
private fun UserData.pinnedShortcuts(tv: SavedTv): List<AppShortcut> = when {
    tv.shortcutsInitialized -> tv.shortcutsList
    shortcutsInitialized -> shortcutsList
    else -> emptyList()
}.map { AppShortcut(it.label, it.packageName) }

/** Changes to shortcuts and learned apps must not restart the connection. */
private fun SavedTv.withoutApps(): SavedTv = copy {
    shortcuts.clear()
    seenApps.clear()
    clearLastAppPackage()
}

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
    showAppNames = !appNamesHidden,
    volumeHaptics = volumeHaptics.takeIf { it in RemoteSettings.VOLUME_HAPTICS } ?: RemoteSettings.VOLUME_HAPTICS_FULL,
    receiverMaxHalfSteps = receiverMaxHalfSteps
        .takeIf { it in RemoteSettings.MIN_RECEIVER_MAX_HALF_STEPS..RemoteSettings.NO_RECEIVER_MAX_HALF_STEPS }
        ?: RemoteSettings.DEFAULT_RECEIVER_MAX_HALF_STEPS,
)
