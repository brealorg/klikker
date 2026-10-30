package no.enaasen.tvremote.data.model

data class Tv(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
)

data class DiscoveredTv(
    val name: String,
    val host: String,
    val port: Int,
)

data class DiscoveredReceiver(
    val name: String,
    val host: String,
)

data class AppShortcut(
    val label: String,
    val packageName: String,
)

data class RemoteSettings(
    /** [MIN_SWIPE_SENSITIVITY] (least sensitive) to [MAX_SWIPE_SENSITIVITY]. */
    val swipeSensitivity: Int = DEFAULT_SWIPE_SENSITIVITY,
    /** Whether resting the finger after a swipe keeps stepping in that direction. */
    val holdToRepeat: Boolean = true,
    /** 1 (calm) to 3 (fast). */
    val holdRepeatSpeed: Int = DEFAULT_HOLD_REPEAT_SPEED,
    /** Whether a fast swipe sends extra steps. */
    val flingEnabled: Boolean = false,
    /** Whether the phone's volume buttons control the TV while connected. */
    val volumeKeysControlTv: Boolean = true,
    /** Whether a foreground service keeps the TV connected while the app is closed. */
    val keepConnectedInBackground: Boolean = true,
    /** Address of a Denon/Marantz receiver whose volume is controlled directly; blank if none. */
    val receiverHost: String = "",
    /** Volume change per press on the receiver, in half-dB steps (see [RECEIVER_VOLUME_STEPS]). */
    val receiverVolumeStep: Int = DEFAULT_RECEIVER_VOLUME_STEP,
    /** Whether app names are shown under the icons in the shortcut row. */
    val showAppNames: Boolean = true,
    /** Vibration while turning the volume ring, [VOLUME_HAPTICS_OFF] to [VOLUME_HAPTICS_FULL]. */
    val volumeHaptics: Int = VOLUME_HAPTICS_FULL,
) {
    companion object {
        const val MIN_SWIPE_SENSITIVITY = 1
        const val MAX_SWIPE_SENSITIVITY = 5
        const val DEFAULT_SWIPE_SENSITIVITY = 3
        const val DEFAULT_HOLD_REPEAT_SPEED = 2
        val HOLD_REPEAT_SPEEDS = 1..3
        const val DEFAULT_RECEIVER_VOLUME_STEP = 2
        val RECEIVER_VOLUME_STEPS = listOf(1, 2, 4)
        const val VOLUME_HAPTICS_OFF = 1
        const val VOLUME_HAPTICS_LIGHT = 2
        const val VOLUME_HAPTICS_FULL = 3
        val VOLUME_HAPTICS = VOLUME_HAPTICS_OFF..VOLUME_HAPTICS_FULL
    }
}

/** Live state of the network-controlled receiver, see [RemoteSettings.receiverHost]. */
data class ReceiverStatus(
    val isConnected: Boolean = false,
    /** In half-dB steps on the receiver's 0–98 scale, so 75 is 37.5; null until reported. */
    val volumeHalfSteps: Int? = null,
    val maxHalfSteps: Int = 196,
    val isMuted: Boolean = false,
)

/** Live state reported by the TV over the control channel. */
data class TvStatus(
    val isOn: Boolean? = null,
    val volumeLevel: Int? = null,
    val volumeMax: Int? = null,
    val isMuted: Boolean = false,
    val currentAppPackage: String? = null,
    /** The text field focused on the TV, if any. */
    val textField: TvTextField? = null,
)

/** A text field focused on the TV, which the phone's keyboard can type into. */
data class TvTextField(
    /** Changes each time a field gains focus, so the phone can tell fields apart. */
    val focusId: Int,
    val label: String,
    val text: String,
    /** The field's android.text.InputType flags. */
    val inputType: Int,
    /** The field's android.view.inputmethod.EditorInfo IME options. */
    val imeOptions: Int,
)

sealed interface ConnectionState {
    data object Loading : ConnectionState
    data object NoTv : ConnectionState
    data class Connecting(val tv: Tv) : ConnectionState
    data class Connected(val tv: Tv, val status: TvStatus) : ConnectionState
    data class Unreachable(val tv: Tv) : ConnectionState
    data class PairingRequired(val tv: Tv) : ConnectionState
}

val ConnectionState.tv: Tv?
    get() = when (this) {
        is ConnectionState.Connecting -> tv
        is ConnectionState.Connected -> tv
        is ConnectionState.Unreachable -> tv
        is ConnectionState.PairingRequired -> tv
        ConnectionState.Loading, ConnectionState.NoTv -> null
    }

/** The selected TV's pinned shortcuts, and apps it has opened that aren't pinned. */
data class TvApps(
    val pinned: List<AppShortcut> = emptyList(),
    val suggested: List<AppShortcut> = emptyList(),
)
