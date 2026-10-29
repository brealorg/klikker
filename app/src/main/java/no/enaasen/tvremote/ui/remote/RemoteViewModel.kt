package no.enaasen.tvremote.ui.remote

import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.enaasen.tvremote.data.TvRepository
import no.enaasen.tvremote.data.model.AppShortcut
import no.enaasen.tvremote.data.model.ConnectionState
import no.enaasen.tvremote.data.model.RemoteSettings
import no.enaasen.tvremote.data.model.Tv
import no.enaasen.tvremote.proto.remote.RemoteDirection

data class RemoteUiState(
    val connection: ConnectionState = ConnectionState.Loading,
    val tvs: List<Tv> = emptyList(),
    val shortcuts: List<AppShortcut> = emptyList(),
    val settings: RemoteSettings = RemoteSettings(),
)

@HiltViewModel
class RemoteViewModel @Inject constructor(
    private val repository: TvRepository,
) : ViewModel() {

    val uiState: StateFlow<RemoteUiState> = combine(
        repository.connectionState,
        repository.tvs,
        repository.shortcuts,
        repository.settings,
        ::RemoteUiState,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteUiState())

    fun sendKey(keyCode: Int) = repository.sendKey(keyCode)

    fun holdKey(keyCode: Int, held: Boolean) =
        repository.sendKey(keyCode, if (held) RemoteDirection.START_LONG else RemoteDirection.END_LONG)

    fun move(direction: SwipeDirection) = sendKey(
        when (direction) {
            SwipeDirection.Up -> KeyEvent.KEYCODE_DPAD_UP
            SwipeDirection.Down -> KeyEvent.KEYCODE_DPAD_DOWN
            SwipeDirection.Left -> KeyEvent.KEYCODE_DPAD_LEFT
            SwipeDirection.Right -> KeyEvent.KEYCODE_DPAD_RIGHT
        },
    )

    fun launch(shortcut: AppShortcut) = repository.launchApp(shortcut.packageName)

    fun retry() = repository.reconnect()

    fun selectTv(tv: Tv) {
        viewModelScope.launch { repository.selectTv(tv.id) }
    }

    fun removeTv(tv: Tv) {
        viewModelScope.launch { repository.removeTv(tv.id) }
    }

    fun addShortcut(label: String, packageName: String) {
        viewModelScope.launch { repository.addShortcut(label, packageName) }
    }

    fun removeShortcut(shortcut: AppShortcut) {
        viewModelScope.launch { repository.removeShortcut(shortcut.packageName) }
    }
}
