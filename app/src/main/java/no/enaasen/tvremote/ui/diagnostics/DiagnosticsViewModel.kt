package no.enaasen.tvremote.ui.diagnostics

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import no.enaasen.tvremote.data.TvRepository
import no.enaasen.tvremote.data.diagnostics.DiagnosticEvent
import no.enaasen.tvremote.data.diagnostics.DiagnosticLevel
import no.enaasen.tvremote.data.diagnostics.DiagnosticLog
import no.enaasen.tvremote.data.model.ConnectionState
import no.enaasen.tvremote.data.model.tv

data class DiagnosticsUiState(
    /** Newest first. */
    val events: List<DiagnosticEvent> = emptyList(),
    val problemsOnly: Boolean = false,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val log: DiagnosticLog,
    private val repository: TvRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val problemsOnly = MutableStateFlow(false)

    val uiState: StateFlow<DiagnosticsUiState> = combine(log.events, problemsOnly) { events, onlyProblems ->
        DiagnosticsUiState(
            events = events.asReversed().filter { !onlyProblems || it.level != DiagnosticLevel.Info },
            problemsOnly = onlyProblems,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    fun setProblemsOnly(enabled: Boolean) {
        problemsOnly.value = enabled
    }

    fun clear() = log.clear()

    /** A plain-text report to share, newest events first. */
    fun buildReport(): String = buildString {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val connection = repository.connectionState.value
        val settings = repository.settings.value
        appendLine("Klikker diagnostic report")
        appendLine("Generated: ${timestamp.format(Date())}")
        appendLine("App: ${packageInfo.versionName} (${packageInfo.longVersionCode})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Connection: ${connection::class.simpleName}${connection.tv?.let { " — ${it.name} (${it.host}:${it.port})" }.orEmpty()}")
        if (connection is ConnectionState.Connected) {
            appendLine("TV status: ${connection.status}")
        }
        appendLine("Settings: $settings")
        appendLine()
        appendLine("Events, newest first:")
        log.events.value.asReversed().forEach { event ->
            append(timestamp.format(Date(event.timeMillis)))
            append("  ").append(event.level.name.uppercase().padEnd(7))
            append(" ").append(event.category.name.padEnd(10))
            append(" ").appendLine(event.message)
            event.detail?.lineSequence()?.forEach { appendLine("        $it") }
        }
    }
}
