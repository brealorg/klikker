package no.enaasen.tvremote.ui.addtv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import no.enaasen.tvremote.data.TvRepository
import no.enaasen.tvremote.data.diagnostics.DiagnosticCategory
import no.enaasen.tvremote.data.diagnostics.DiagnosticLog
import no.enaasen.tvremote.data.discovery.TvDiscovery
import no.enaasen.tvremote.data.model.DiscoveredTv

data class DiscoveredTvItem(val tv: DiscoveredTv, val alreadyAdded: Boolean)

@HiltViewModel
class AddTvViewModel @Inject constructor(
    discovery: TvDiscovery,
    repository: TvRepository,
    log: DiagnosticLog,
) : ViewModel() {

    val discovered: StateFlow<List<DiscoveredTvItem>> = combine(
        discovery.discover().catch { e ->
            log.warning(DiagnosticCategory.Discovery, "Network discovery failed", e)
            emit(emptyList())
        },
        repository.tvs,
    ) { found, saved ->
        found.map { tv -> DiscoveredTvItem(tv, alreadyAdded = saved.any { it.host == tv.host || it.name == tv.name }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
