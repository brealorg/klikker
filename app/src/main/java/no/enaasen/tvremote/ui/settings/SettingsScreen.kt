package no.enaasen.tvremote.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import no.enaasen.tvremote.R
import no.enaasen.tvremote.data.model.RemoteSettings

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsScreen(
        settings = settings,
        onBack = onBack,
        onOpenDiagnostics = onOpenDiagnostics,
        onHoldToRepeatChange = viewModel::setHoldToRepeat,
        onHoldRepeatSpeedChange = viewModel::setHoldRepeatSpeed,
        onSwipeSensitivityChange = viewModel::setSwipeSensitivity,
        onFlingEnabledChange = viewModel::setFlingEnabled,
        onVolumeKeysChange = viewModel::setVolumeKeysControlTv,
        onKeepConnectedChange = viewModel::setKeepConnectedInBackground,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    settings: RemoteSettings,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onHoldToRepeatChange: (Boolean) -> Unit,
    onHoldRepeatSpeedChange: (Int) -> Unit,
    onSwipeSensitivityChange: (Int) -> Unit,
    onFlingEnabledChange: (Boolean) -> Unit,
    onVolumeKeysChange: (Boolean) -> Unit,
    onKeepConnectedChange: (Boolean) -> Unit,
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
