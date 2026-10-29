package no.enaasen.tvremote.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import no.enaasen.tvremote.R
import no.enaasen.tvremote.data.model.DiscoveredReceiver
import no.enaasen.tvremote.data.model.RemoteSettings

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val receiverStatus by viewModel.receiverStatus.collectAsStateWithLifecycle()
    val receiverCheck by viewModel.receiverCheck.collectAsStateWithLifecycle()
    SettingsScreen(
        settings = settings,
        receiverConnected = receiverStatus.isConnected,
        receiverCheck = receiverCheck,
        discoveredReceivers = { viewModel.discoveredReceivers.collectAsStateWithLifecycle().value },
        onBack = onBack,
        onOpenDiagnostics = onOpenDiagnostics,
        onHoldToRepeatChange = viewModel::setHoldToRepeat,
        onHoldRepeatSpeedChange = viewModel::setHoldRepeatSpeed,
        onSwipeSensitivityChange = viewModel::setSwipeSensitivity,
        onFlingEnabledChange = viewModel::setFlingEnabled,
        onVolumeKeysChange = viewModel::setVolumeKeysControlTv,
        onKeepConnectedChange = viewModel::setKeepConnectedInBackground,
        onShowAppNamesChange = viewModel::setShowAppNames,
        onReceiverHostChange = viewModel::saveReceiverHost,
        onReceiverCheckReset = viewModel::resetReceiverCheck,
        onReceiverVolumeStepChange = viewModel::setReceiverVolumeStep,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    settings: RemoteSettings,
    receiverConnected: Boolean,
    receiverCheck: ReceiverCheck,
    /** Read only while the receiver dialog is shown, so the network is searched only then. */
    discoveredReceivers: @Composable () -> List<DiscoveredReceiver>?,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onHoldToRepeatChange: (Boolean) -> Unit,
    onHoldRepeatSpeedChange: (Int) -> Unit,
    onSwipeSensitivityChange: (Int) -> Unit,
    onFlingEnabledChange: (Boolean) -> Unit,
    onVolumeKeysChange: (Boolean) -> Unit,
    onKeepConnectedChange: (Boolean) -> Unit,
    onShowAppNamesChange: (Boolean) -> Unit,
    onReceiverHostChange: (String) -> Unit,
    onReceiverCheckReset: () -> Unit,
    onReceiverVolumeStepChange: (Int) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.navigate_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_touchpad))
            SensitivitySetting(settings.swipeSensitivity, onSwipeSensitivityChange)
            SwitchSetting(
                title = stringResource(R.string.settings_hold_repeat),
                description = stringResource(R.string.settings_hold_repeat_description),
                checked = settings.holdToRepeat,
                onCheckedChange = onHoldToRepeatChange,
            )
            HoldRepeatSpeedSetting(
                speed = settings.holdRepeatSpeed,
                enabled = settings.holdToRepeat,
                onChange = onHoldRepeatSpeedChange,
            )
            SwitchSetting(
                title = stringResource(R.string.settings_fling),
                description = stringResource(R.string.settings_fling_description),
                checked = settings.flingEnabled,
                onCheckedChange = onFlingEnabledChange,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.settings_general))
            SwitchSetting(
                title = stringResource(R.string.settings_keep_connected),
                description = stringResource(R.string.settings_keep_connected_description),
                checked = settings.keepConnectedInBackground,
                onCheckedChange = onKeepConnectedChange,
            )
            SwitchSetting(
                title = stringResource(R.string.settings_volume_keys),
                description = stringResource(R.string.settings_volume_keys_description),
                checked = settings.volumeKeysControlTv,
                onCheckedChange = onVolumeKeysChange,
            )
            SwitchSetting(
                title = stringResource(R.string.settings_show_app_names),
                description = stringResource(R.string.settings_show_app_names_description),
                checked = settings.showAppNames,
                onCheckedChange = onShowAppNamesChange,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.settings_receiver))
            ReceiverHostSetting(
                host = settings.receiverHost,
                connected = receiverConnected,
                check = receiverCheck,
                discoveredReceivers = discoveredReceivers,
                onSave = onReceiverHostChange,
                onCheckReset = onReceiverCheckReset,
            )
            ReceiverVolumeStepSetting(
                step = settings.receiverVolumeStep,
                enabled = settings.receiverHost.isNotBlank(),
                onChange = onReceiverVolumeStepChange,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.settings_troubleshooting))
            ListItem(
                headlineContent = { Text(stringResource(R.string.diagnostics)) },
                supportingContent = { Text(stringResource(R.string.diagnostics_description)) },
                modifier = Modifier.clickable(onClick = onOpenDiagnostics),
            )
        }
    }
}

@Composable
private fun SensitivitySetting(sensitivity: Int, onChange: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    // Track the thumb locally while dragging; persist when released.
    var position by remember(sensitivity) { mutableFloatStateOf(sensitivity.toFloat()) }
    val level = position.roundToInt()
    val levelLabel = sensitivityLabel(level)

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_sensitivity)) },
        supportingContent = { Text(stringResource(R.string.settings_sensitivity_description)) },
        trailingContent = { Text(levelLabel, style = MaterialTheme.typography.labelLarge) },
    )
    Slider(
        value = position,
        onValueChange = { value ->
            if (value.roundToInt() != level) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            position = value
        },
        onValueChangeFinished = { onChange(position.roundToInt()) },
        valueRange = RemoteSettings.MIN_SWIPE_SENSITIVITY.toFloat()..RemoteSettings.MAX_SWIPE_SENSITIVITY.toFloat(),
        steps = RemoteSettings.MAX_SWIPE_SENSITIVITY - RemoteSettings.MIN_SWIPE_SENSITIVITY - 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics { stateDescription = levelLabel },
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = sensitivityLabel(RemoteSettings.MIN_SWIPE_SENSITIVITY),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = sensitivityLabel(RemoteSettings.MAX_SWIPE_SENSITIVITY),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun sensitivityLabel(level: Int): String = stringResource(
    when (level) {
        1 -> R.string.settings_sensitivity_1
        2 -> R.string.settings_sensitivity_2
        4 -> R.string.settings_sensitivity_4
        5 -> R.string.settings_sensitivity_5
        else -> R.string.settings_sensitivity_3
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HoldRepeatSpeedSetting(speed: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val labels = listOf(
        R.string.settings_hold_repeat_speed_calm,
        R.string.settings_hold_repeat_speed_medium,
        R.string.settings_hold_repeat_speed_fast,
    )
    ListItem(headlineContent = { Text(stringResource(R.string.settings_hold_repeat_speed)) })
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    ) {
        RemoteSettings.HOLD_REPEAT_SPEEDS.forEachIndexed { index, value ->
            SegmentedButton(
                selected = speed == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
                enabled = enabled,
            ) {
                Text(stringResource(labels[index]))
            }
        }
    }
}

@Composable
private fun ReceiverHostSetting(
    host: String,
    connected: Boolean,
    check: ReceiverCheck,
    discoveredReceivers: @Composable () -> List<DiscoveredReceiver>?,
    onSave: (String) -> Unit,
    onCheckReset: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_receiver_host)) },
        supportingContent = {
            Text(
                when {
                    host.isBlank() -> stringResource(R.string.settings_receiver_not_set)
                    connected -> stringResource(R.string.settings_receiver_connected, host)
                    else -> stringResource(R.string.settings_receiver_not_connected, host)
                },
            )
        },
        modifier = Modifier.clickable {
            onCheckReset()
            editing = true
        },
    )
    if (editing) {
        LaunchedEffect(check) {
            if (check == ReceiverCheck.Saved) {
                editing = false
                onCheckReset()
            }
        }
        ReceiverDialog(
            host = host,
            check = check,
            discovered = discoveredReceivers(),
            onSave = onSave,
            onCheckReset = onCheckReset,
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun ReceiverDialog(
    host: String,
    check: ReceiverCheck,
    discovered: List<DiscoveredReceiver>?,
    onSave: (String) -> Unit,
    onCheckReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(host) }
    val checking = check == ReceiverCheck.Checking
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_receiver_host)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.settings_receiver_dialog_message))
                if (discovered.isNullOrEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.settings_receiver_searching),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Column {
                        Text(
                            stringResource(R.string.settings_receiver_found),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        discovered.forEach { receiver ->
                            ListItem(
                                headlineContent = { Text(receiver.name) },
                                supportingContent = { Text(receiver.host) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable(enabled = !checking) {
                                    input = receiver.host
                                    onSave(receiver.host)
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        if (check == ReceiverCheck.NotFound) onCheckReset()
                    },
                    label = { Text(stringResource(R.string.ip_address)) },
                    singleLine = true,
                    enabled = !checking,
                    isError = check == ReceiverCheck.NotFound,
                    supportingText = when (check) {
                        ReceiverCheck.Checking -> {
                            { Text(stringResource(R.string.settings_receiver_checking)) }
                        }
                        ReceiverCheck.NotFound -> {
                            { Text(stringResource(R.string.settings_receiver_not_found)) }
                        }
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) onSave(input) }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(input) }, enabled = !checking && input.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            if (host.isNotBlank()) {
                TextButton(onClick = { onSave("") }, enabled = !checking) { Text(stringResource(R.string.remove)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiverVolumeStepSetting(step: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val labels = listOf(
        R.string.settings_receiver_volume_step_half,
        R.string.settings_receiver_volume_step_1,
        R.string.settings_receiver_volume_step_2,
    )
    ListItem(headlineContent = { Text(stringResource(R.string.settings_receiver_volume_step)) })
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    ) {
        RemoteSettings.RECEIVER_VOLUME_STEPS.forEachIndexed { index, value ->
            SegmentedButton(
                selected = step == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
                enabled = enabled,
            ) {
                Text(stringResource(labels[index]))
            }
        }
    }
}

@Composable
private fun SwitchSetting(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}
