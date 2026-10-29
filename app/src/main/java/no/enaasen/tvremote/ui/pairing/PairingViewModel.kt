package no.enaasen.tvremote.ui.pairing

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.enaasen.tvremote.data.TvRepository
import no.enaasen.tvremote.data.diagnostics.DiagnosticCategory
import no.enaasen.tvremote.data.diagnostics.DiagnosticLog
import no.enaasen.tvremote.data.protocol.PairingSession
import no.enaasen.tvremote.data.protocol.WrongPairingCodeException
import no.enaasen.tvremote.ui.navigation.PairingRoute

sealed interface PairingUiState {
    data object Connecting : PairingUiState
    data class EnterCode(val wrongCode: Boolean = false, val verifying: Boolean = false) : PairingUiState
    data object Failed : PairingUiState
    data object Paired : PairingUiState
}

@HiltViewModel(assistedFactory = PairingViewModel.Factory::class)
class PairingViewModel @AssistedInject constructor(
    @Assisted private val route: PairingRoute,
    private val repository: TvRepository,
    private val log: DiagnosticLog,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PairingUiState>(PairingUiState.Connecting)
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    private var session: PairingSession? = null

    init {
        start()
    }

    fun start() {
        session?.close()
        val newSession = repository.newPairingSession(route.host).also { session = it }
        _uiState.value = PairingUiState.Connecting
        log.info(DiagnosticCategory.Pairing, "Pairing with ${route.name} (${route.host})")
        viewModelScope.launch {
            _uiState.value = try {
                newSession.start(clientName = Build.MODEL)
                log.info(DiagnosticCategory.Pairing, "${route.name} is showing a pairing code")
                PairingUiState.EnterCode()
            } catch (e: IOException) {
                log.error(DiagnosticCategory.Pairing, "Could not start pairing with ${route.host}", e)
                PairingUiState.Failed
            }
        }
    }

    fun submitCode(code: String) {
        val current = session ?: return
        _uiState.value = PairingUiState.EnterCode(verifying = true)
        viewModelScope.launch {
            _uiState.value = try {
                val certificate = current.submitCode(code)
                repository.savePairedTv(route.name, route.host, route.port, certificate)
                current.close()
                log.info(DiagnosticCategory.Pairing, "Paired with ${route.name}")
                PairingUiState.Paired
            } catch (_: WrongPairingCodeException) {
                log.warning(DiagnosticCategory.Pairing, "Entered code did not match ${route.name}")
                PairingUiState.EnterCode(wrongCode = true)
            } catch (e: IOException) {
                log.error(DiagnosticCategory.Pairing, "Pairing with ${route.host} failed", e)
                PairingUiState.Failed
            }
        }
    }

    override fun onCleared() {
        session?.close()
    }

    @AssistedFactory
    interface Factory {
        fun create(route: PairingRoute): PairingViewModel
    }
}
