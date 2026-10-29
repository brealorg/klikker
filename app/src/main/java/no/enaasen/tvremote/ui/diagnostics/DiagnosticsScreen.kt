package no.enaasen.tvremote.ui.diagnostics

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.enaasen.tvremote.R
import no.enaasen.tvremote.data.diagnostics.DiagnosticCategory
import no.enaasen.tvremote.data.diagnostics.DiagnosticEvent
import no.enaasen.tvremote.data.diagnostics.DiagnosticLevel

@Composable
fun DiagnosticsRoute(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.diagnostics_share)
    DiagnosticsScreen(
        uiState = uiState,
        onBack = onBack,
        onProblemsOnlyChange = viewModel::setProblemsOnly,
        onShare = {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "TV Remote diagnostic report")
                .putExtra(Intent.EXTRA_TEXT, viewModel.buildReport())
            context.startActivity(Intent.createChooser(send, shareTitle))
        },
        onClear = viewModel::clear,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticsScreen(
    uiState: DiagnosticsUiState,
    onBack: () -> Unit,
    onProblemsOnlyChange: (Boolean) -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.navigate_back))
                    }
                },
                actions = {
                    IconButton(onClick = onShare) {
                        Icon(painterResource(R.drawable.ic_share), stringResource(R.string.diagnostics_share))
                    }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.diagnostics_clear))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !uiState.problemsOnly,
                        onClick = { onProblemsOnlyChange(false) },
                        label = { Text(stringResource(R.string.diagnostics_filter_all)) },
                    )
                    FilterChip(
                        selected = uiState.problemsOnly,
                        onClick = { onProblemsOnlyChange(true) },
                        label = { Text(stringResource(R.string.diagnostics_filter_problems)) },
                    )
                }
            }
            if (uiState.events.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.diagnostics_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                    )
                }
            }
            items(uiState.events) { event -> DiagnosticEventItem(event) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.diagnostics_clear_title)) },
            text = { Text(stringResource(R.string.diagnostics_clear_message)) },
            confirmButton = {
                TextButton(onClick = { onClear(); confirmClear = false }) { Text(stringResource(R.string.diagnostics_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun DiagnosticEventItem(event: DiagnosticEvent) {
    var expanded by rememberSaveable(event.timeMillis, event.message) { mutableStateOf(false) }
    val context = LocalContext.current
    val time = DateUtils.formatDateTime(
        context,
        event.timeMillis,
        DateUtils.FORMAT_SHOW_TIME or (if (DateUtils.isToday(event.timeMillis)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH),
    )
    val seconds = (event.timeMillis / 1000 % 60).toString().padStart(2, '0')
    ListItem(
        overlineContent = { Text("$time:$seconds · ${categoryLabel(event.category)}") },
        headlineContent = { Text(event.message) },
        supportingContent = event.detail?.let { detail ->
            {
                Text(
                    text = detail,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = { LevelIcon(event.level) },
        modifier = Modifier.clickable(enabled = event.detail != null) { expanded = !expanded },
    )
}

@Composable
private fun LevelIcon(level: DiagnosticLevel) {
    val (icon, tint, label) = when (level) {
        DiagnosticLevel.Info -> Triple(R.drawable.ic_info, MaterialTheme.colorScheme.onSurfaceVariant, R.string.diagnostics_level_info)
        DiagnosticLevel.Warning -> Triple(R.drawable.ic_warning, MaterialTheme.colorScheme.tertiary, R.string.diagnostics_level_warning)
        DiagnosticLevel.Error -> Triple(R.drawable.ic_error, MaterialTheme.colorScheme.error, R.string.diagnostics_level_error)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(icon), stringResource(label), tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun categoryLabel(category: DiagnosticCategory): String = stringResource(
    when (category) {
        DiagnosticCategory.App -> R.string.diagnostics_category_app
        DiagnosticCategory.Connection -> R.string.diagnostics_category_connection
        DiagnosticCategory.Pairing -> R.string.diagnostics_category_pairing
        DiagnosticCategory.Discovery -> R.string.diagnostics_category_discovery
        DiagnosticCategory.Service -> R.string.diagnostics_category_service
    },
)
