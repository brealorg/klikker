package no.enaasen.tvremote.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import no.enaasen.tvremote.data.TvRepository
import no.enaasen.tvremote.data.model.RemoteSettings

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: TvRepository,
) : ViewModel() {

    val settings: StateFlow<RemoteSettings> = repository.settings

    fun setSwipeSensitivity(sensitivity: Int) = update { it.copy(swipeSensitivity = sensitivity) }

    fun setHoldToRepeat(enabled: Boolean) = update { it.copy(holdToRepeat = enabled) }

    fun setHoldRepeatSpeed(speed: Int) = update { it.copy(holdRepeatSpeed = speed) }

    fun setFlingEnabled(enabled: Boolean) = update { it.copy(flingEnabled = enabled) }

    fun setVolumeKeysControlTv(enabled: Boolean) = update { it.copy(volumeKeysControlTv = enabled) }

    fun setKeepConnectedInBackground(enabled: Boolean) = update { it.copy(keepConnectedInBackground = enabled) }

    private fun update(transform: (RemoteSettings) -> RemoteSettings) {
        viewModelScope.launch { repository.updateSettings(transform) }
    }
}
