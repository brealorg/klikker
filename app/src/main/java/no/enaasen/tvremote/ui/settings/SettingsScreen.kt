package no.enaasen.tvremote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
            SettingsGroup(
                title = stringResource(R.string.settings_touchpad),
                { shapes -> SensitivitySetting(settings.swipeSensitivity, shapes, onSwipeSensitivityChange) },
                { shapes ->
                    SwitchSetting(
                        title = stringResource(R.string.settings_hold_repeat),
                        description = stringResource(R.string.settings_hold_repeat_description),
                        checked = settings.holdToRepeat,
                        shapes = shapes,
                        onCheckedChange = onHoldToRepeatChange,
                    )
                },
                { shapes ->
                    HoldRepeatSpeedSetting(
                        speed = settings.holdRepeatSpeed,
                        enabled = settings.holdToRepeat,
                        shapes = shapes,
                        onChange = onHoldRepeatSpeedChange,
                    )
                },
                { shapes ->
                    SwitchSetting(
                        title = stringResource(R.string.settings_fling),
                        description = stringResource(R.string.settings_fling_description),
                        checked = settings.flingEnabled,
                        shapes = shapes,
                        onCheckedChange = onFlingEnabledChange,
                    )
                },
            )
            SettingsGroup(
                title = stringResource(R.string.settings_general),
                { shapes ->
                    SwitchSetting(
                        title = stringResource(R.string.settings_keep_connected),
                        description = stringResource(R.string.settings_keep_connected_description),
                        checked = settings.keepConnectedInBackground,
                        shapes = shapes,
                        onCheckedChange = onKeepConnectedChange,
                    )
                },
                { shapes ->
                    SwitchSetting(
                        title = stringResource(R.string.settings_volume_keys),
                        description = stringResource(R.string.settings_volume_keys_description),
                        checked = settings.volumeKeysControlTv,
                        shapes = shapes,
                        onCheckedChange = onVolumeKeysChange,
                    )
                },
                { shapes ->
                    SwitchSetting(
                        title = stringResource(R.string.settings_show_app_names),
                        description = stringResource(R.string.settings_show_app_names_description),
                        checked = settings.showAppNames,
                        shapes = shapes,
                        onCheckedChange = onShowAppNamesChange,
                    )
                },
            )
            SettingsGroup(
                title = stringResource(R.string.settings_receiver),
                { shapes ->
                    ReceiverHostSetting(
                        host = settings.receiverHost,
                        connected = receiverConnected,
                        check = receiverCheck,
                        discoveredReceivers = discoveredReceivers,
                        shapes = shapes,
                        onSave = onReceiverHostChange,
                        onCheckReset = onReceiverCheckReset,
                    )
                },
                { shapes ->
                    ReceiverVolumeStepSetting(
                        step = settings.receiverVolumeStep,
                        enabled = settings.receiverHost.isNotBlank(),
                        shapes = shapes,
                        onChange = onReceiverVolumeStepChange,
                    )
                },
            )
            SettingsGroup(
                title = stringResource(R.string.settings_troubleshooting),
                { shapes ->
                    SegmentedListItem(
                        onClick = onOpenDiagnostics,
                        shapes = shapes,
        colors = settingsItemColors(),
                        supportingContent = { Text(stringResource(R.string.diagnostics_description)) },
                    ) { Text(stringResource(R.string.diagnostics)) }
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SensitivitySetting(sensitivity: Int, shapes: ListItemShapes, onChange: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val range = RemoteSettings.MIN_SWIPE_SENSITIVITY.toFloat()..RemoteSettings.MAX_SWIPE_SENSITIVITY.toFloat()
    // The thumb is tracked locally while dragging; the setting is saved when released.
    val state = remember {
        SliderState(sensitivity.toFloat(), RemoteSettings.MAX_SWIPE_SENSITIVITY - RemoteSettings.MIN_SWIPE_SENSITIVITY - 1, range)
    }
    LaunchedEffect(sensitivity) { state.value = sensitivity.toFloat() }
    val level = state.value.roundToInt()
    val levelLabel = sensitivityLabel(level)

    SegmentedListItem(
        shapes = shapes,
        colors = settingsItemColors(),
        supportingContent = {
            Column {
                Text(stringResource(R.string.settings_sensitivity_description))
                Slider(
                    state = state,
                    onValueChange = { value ->
                        if (value.roundToInt() != level) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        state.value = value
                    },
                    onValueChangeFinished = { onChange(state.value.roundToInt()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { stateDescription = levelLabel },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = sensitivityLabel(RemoteSettings.MIN_SWIPE_SENSITIVITY),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = sensitivityLabel(RemoteSettings.MAX_SWIPE_SENSITIVITY),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        trailingContent = { Text(levelLabel, style = MaterialTheme.typography.labelLarge) },
        verticalAlignment = Alignment.Top,
    ) { Text(stringResource(R.string.settings_sensitivity)) }
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
private fun HoldRepeatSpeedSetting(speed: Int, enabled: Boolean, shapes: ListItemShapes, onChange: (Int) -> Unit) {
    val labels = listOf(
        R.string.settings_hold_repeat_speed_calm,
        R.string.settings_hold_repeat_speed_medium,
        R.string.settings_hold_repeat_speed_fast,
    )
    SegmentedListItem(
        shapes = shapes,
        colors = settingsItemColors(),
        enabled = enabled,
        supportingContent = {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
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
        },
    ) { Text(stringResource(R.string.settings_hold_repeat_speed)) }
}

@Composable
private fun ReceiverHostSetting(
    host: String,
    connected: Boolean,
    check: ReceiverCheck,
    discoveredReceivers: @Composable () -> List<DiscoveredReceiver>?,
    shapes: ListItemShapes,
    onSave: (String) -> Unit,
    onCheckReset: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    SegmentedListItem(
        onClick = {
            onCheckReset()
            editing = true
        },
        shapes = shapes,
        colors = settingsItemColors(),
        supportingContent = {
            Text(
                when {
                    host.isBlank() -> stringResource(R.string.settings_receiver_not_set)
                    connected -> stringResource(R.string.settings_receiver_connected, host)
                    else -> stringResource(R.string.settings_receiver_not_connected, host)
                },
            )
        },
    ) { Text(stringResource(R.string.settings_receiver_host)) }
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
                                onClick = {
                                    input = receiver.host
                                    onSave(receiver.host)
                                },
                                enabled = !checking,
                                supportingContent = { Text(receiver.host) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            ) { Text(receiver.name) }
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
private fun ReceiverVolumeStepSetting(step: Int, enabled: Boolean, shapes: ListItemShapes, onChange: (Int) -> Unit) {
    val labels = listOf(
        R.string.settings_receiver_volume_step_half,
        R.string.settings_receiver_volume_step_1,
        R.string.settings_receiver_volume_step_2,
    )
    SegmentedListItem(
        shapes = shapes,
        colors = settingsItemColors(),
        enabled = enabled,
        supportingContent = {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
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
        },
    ) { Text(stringResource(R.string.settings_receiver_volume_step)) }
}

@Composable
private fun SwitchSetting(
    title: String,
    description: String,
    checked: Boolean,
    shapes: ListItemShapes,
    onCheckedChange: (Boolean) -> Unit,
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = shapes,
        colors = settingsItemColors(),
        supportingContent = { Text(description) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    ) { Text(title) }
}

/**
 * Every card in a group looks the same whatever its state; by default a
 * switched-on row takes the selected colour, which reads as highlighted.
 */
@Composable
private fun settingsItemColors(): ListItemColors {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    return ListItemDefaults.segmentedColors(
        containerColor = container,
        selectedContainerColor = container,
        disabledContainerColor = container,
    )
}

/** A titled group of settings drawn as one segmented list: rounded at its ends, gaps between. */
@Composable
private fun SettingsGroup(title: String, vararg items: @Composable (ListItemShapes) -> Unit) {
    SectionHeader(title)
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        items.forEachIndexed { index, item -> item(ListItemDefaults.segmentedShapes(index, items.size)) }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 32.dp, top = 24.dp, end = 32.dp, bottom = 8.dp),
    )
}
