package no.enaasen.tvremote.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.enaasen.tvremote.data.TvRepository
import no.enaasen.tvremote.data.model.DiscoveredReceiver
import no.enaasen.tvremote.data.model.ReceiverStatus
import no.enaasen.tvremote.data.model.RemoteSettings

/** Progress of checking a receiver address before it is saved. */
enum class ReceiverCheck { Idle, Checking, NotFound, Saved }

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: TvRepository,
) : ViewModel() {

    val settings: StateFlow<RemoteSettings> = repository.settings

    val receiverStatus: StateFlow<ReceiverStatus> = repository.receiverStatus

    /** Searches the network only while collected, i.e. while the receiver dialog is open. */
    val discoveredReceivers: StateFlow<List<DiscoveredReceiver>?> = repository.discoverReceivers()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _receiverCheck = MutableStateFlow(ReceiverCheck.Idle)
    val receiverCheck: StateFlow<ReceiverCheck> = _receiverCheck.asStateFlow()

    fun setSwipeSensitivity(sensitivity: Int) = update { it.copy(swipeSensitivity = sensitivity) }

    fun setHoldToRepeat(enabled: Boolean) = update { it.copy(holdToRepeat = enabled) }

    fun setHoldRepeatSpeed(speed: Int) = update { it.copy(holdRepeatSpeed = speed) }

    fun setFlingEnabled(enabled: Boolean) = update { it.copy(flingEnabled = enabled) }

    fun setVolumeKeysControlTv(enabled: Boolean) = update { it.copy(volumeKeysControlTv = enabled) }

    fun setKeepConnectedInBackground(enabled: Boolean) = update { it.copy(keepConnectedInBackground = enabled) }

    fun setShowAppNames(enabled: Boolean) = update { it.copy(showAppNames = enabled) }

    /** Saves [host] if a receiver answers there; a blank host removes the receiver. */
    fun saveReceiverHost(host: String) {
        val trimmed = host.trim()
        viewModelScope.launch {
            if (trimmed.isNotEmpty()) {
                _receiverCheck.value = ReceiverCheck.Checking
                if (!repository.probeReceiver(trimmed)) {
                    _receiverCheck.value = ReceiverCheck.NotFound
                    return@launch
                }
            }
            repository.updateSettings { it.copy(receiverHost = trimmed) }
            _receiverCheck.value = ReceiverCheck.Saved
        }
    }

    fun resetReceiverCheck() {
        _receiverCheck.value = ReceiverCheck.Idle
    }

    fun setReceiverVolumeStep(step: Int) = update { it.copy(receiverVolumeStep = step) }

    private fun update(transform: (RemoteSettings) -> RemoteSettings) {
        viewModelScope.launch { repository.updateSettings(transform) }
    }
}
