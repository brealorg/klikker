package no.enaasen.tvremote.ui.remote

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DecimalFormat
import kotlinx.coroutines.delay
import no.enaasen.tvremote.R
import no.enaasen.tvremote.data.apps.AppCatalog
import no.enaasen.tvremote.data.model.AppShortcut
import no.enaasen.tvremote.data.model.ConnectionState
import no.enaasen.tvremote.data.model.ReceiverStatus
import no.enaasen.tvremote.data.model.Tv
import no.enaasen.tvremote.data.model.TvApps
import no.enaasen.tvremote.data.model.TvStatus
import no.enaasen.tvremote.data.model.tv

@Composable
fun RemoteRoute(
    onAddTv: () -> Unit,
    onPairTv: (Tv) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: RemoteViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    RemoteScreen(
        uiState = uiState,
        actions = RemoteActions(
            onAddTv = onAddTv,
            onPairTv = onPairTv,
            onOpenSettings = onOpenSettings,
            onOpenDiagnostics = onOpenDiagnostics,
            onSelectTv = viewModel::selectTv,
            onRemoveTv = viewModel::removeTv,
            onRetry = viewModel::retry,
            onKey = viewModel::sendKey,
            onHoldKey = viewModel::holdKey,
            onMove = viewModel::move,
            onLaunch = viewModel::launch,
            onAddShortcut = viewModel::addShortcut,
            onRemoveShortcut = viewModel::removeShortcut,
            onTextChange = viewModel::setText,
            onSubmitText = viewModel::submitText,
        ),
    )
}

class RemoteActions(
    val onAddTv: () -> Unit,
    val onPairTv: (Tv) -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenDiagnostics: () -> Unit,
    val onSelectTv: (Tv) -> Unit,
    val onRemoveTv: (Tv) -> Unit,
    val onRetry: () -> Unit,
    val onKey: (Int) -> Unit,
    val onHoldKey: (Int, Boolean) -> Unit,
    val onMove: (SwipeDirection) -> Unit,
    val onLaunch: (AppShortcut) -> Unit,
    val onAddShortcut: (label: String, packageName: String) -> Unit,
    val onRemoveShortcut: (AppShortcut) -> Unit,
    val onTextChange: (String) -> Unit,
    val onSubmitText: () -> Unit,
)

@Composable
internal fun RemoteScreen(uiState: RemoteUiState, actions: RemoteActions) {
    val tv = uiState.connection.tv
    when {
        uiState.connection == ConnectionState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }
        tv == null -> NoTvScreen(onAddTv = actions.onAddTv)
        else -> RemoteControlScreen(tv, uiState, actions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoTvScreen(onAddTv: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_tv_fill1),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.no_tv_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.no_tv_message),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onAddTv) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.add_tv))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteControlScreen(tv: Tv, uiState: RemoteUiState, actions: RemoteActions) {
    val connection = uiState.connection
    val status = (connection as? ConnectionState.Connected)?.status
    val connected = status != null
    val haptics = LocalHapticFeedback.current
    var showTvSheet by rememberSaveable { mutableStateOf(false) }
    var showShortcutSheet by rememberSaveable { mutableStateOf(false) }
    // The keyboard opens by itself when a TV text field gains focus, unless hidden for that field.
    val textField = status?.textField
    var hiddenTextFocusId by rememberSaveable { mutableStateOf<Int?>(null) }
    val typing = textField != null && textField.focusId != hiddenTextFocusId
    BackHandler(enabled = typing) { hiddenTextFocusId = textField?.focusId }

    val key: (Int) -> Unit = { keyCode ->
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        actions.onKey(keyCode)
    }

    Scaffold(
        bottomBar = {
            if (typing) {
                TvTextInputBar(
                    field = textField,
                    onTextChange = actions.onTextChange,
                    onSubmit = {
                        actions.onSubmitText()
                        hiddenTextFocusId = textField.focusId
                    },
                    onHide = { hiddenTextFocusId = textField.focusId },
                )
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    TvTitle(
                        tv = tv,
                        statusText = connection.statusText(uiState.apps.pinned),
                        onClick = { showTvSheet = true },
                    )
                },
                actions = {
                    if (textField != null && !typing) {
                        IconButton(onClick = { hiddenTextFocusId = null }) {
                            Icon(painterResource(R.drawable.ic_keyboard), stringResource(R.string.type_on_tv))
                        }
                    }
                    IconButton(onClick = actions.onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
                    }
                    FilledIconToggleButton(
                        checked = status?.isOn == true,
                        onCheckedChange = { on ->
                            haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                            actions.onKey(KeyEvent.KEYCODE_POWER)
                        },
                        enabled = connected,
                    ) {
                        Icon(painterResource(R.drawable.ic_power_settings_new), stringResource(R.string.power))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionMessage(
                connection = connection,
                onRetry = actions.onRetry,
                onPair = { actions.onPairTv(tv) },
                onDetails = actions.onOpenDiagnostics,
            )
            SwipePad(
                enabled = connected,
                sensitivity = uiState.settings.swipeSensitivity,
                flingEnabled = uiState.settings.flingEnabled,
                holdToRepeat = uiState.settings.holdToRepeat,
                holdRepeatSpeed = uiState.settings.holdRepeatSpeed,
                onDirection = actions.onMove,
                onSelect = { actions.onKey(KeyEvent.KEYCODE_DPAD_CENTER) },
                onSelectHeld = { held -> actions.onHoldKey(KeyEvent.KEYCODE_DPAD_CENTER, held) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            NavigationKeys(enabled = connected, onKey = key)
            VolumeControls(
                volume = uiState.receiver.takeIf { it.isConnected }?.toVolumeDisplay() ?: status?.toVolumeDisplay(),
                onKey = key,
                onHoldKey = actions.onHoldKey,
            )
            ShortcutRow(
                apps = uiState.apps,
                showNames = uiState.settings.showAppNames,
                enabled = connected,
                onLaunch = { shortcut ->
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    actions.onLaunch(shortcut)
                },
                onEdit = { showShortcutSheet = true },
            )
        }
    }

    if (showTvSheet) {
        TvSheet(
            tvs = uiState.tvs,
            selected = tv,
            onSelect = { actions.onSelectTv(it); showTvSheet = false },
            onRemove = actions.onRemoveTv,
            onAddTv = { showTvSheet = false; actions.onAddTv() },
            onDismiss = { showTvSheet = false },
        )
    }
    if (showShortcutSheet) {
        ShortcutSheet(
            apps = uiState.apps,
            currentAppPackage = status?.currentAppPackage,
            onAdd = actions.onAddShortcut,
            onRemove = actions.onRemoveShortcut,
            onDismiss = { showShortcutSheet = false },
        )
    }
}

@Composable
private fun TvTitle(tv: Tv, statusText: String, onClick: () -> Unit) {
    val switchLabel = stringResource(R.string.switch_tv)
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = switchLabel, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tv.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(painterResource(R.drawable.ic_keyboard_arrow_down), contentDescription = null)
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ConnectionState.statusText(shortcuts: List<AppShortcut>): String = when (this) {
    is ConnectionState.Connected -> status.currentAppPackage
        ?.let { pkg -> shortcuts.find { it.packageName == pkg }?.label ?: AppCatalog.label(pkg) }
        ?: stringResource(R.string.status_connected)
    is ConnectionState.Unreachable -> stringResource(R.string.status_unreachable)
    is ConnectionState.PairingRequired -> stringResource(R.string.status_pairing_required)
    else -> stringResource(R.string.status_connecting)
}

@Composable
private fun ConnectionMessage(
    connection: ConnectionState,
    onRetry: () -> Unit,
    onPair: () -> Unit,
    onDetails: () -> Unit,
) {
    AnimatedVisibility(visible = connection is ConnectionState.Connecting) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    val message = when (connection) {
        is ConnectionState.Unreachable -> stringResource(R.string.unreachable_message, connection.tv.name)
        is ConnectionState.PairingRequired -> stringResource(R.string.pairing_required_message, connection.tv.name)
        else -> null
    }
    AnimatedVisibility(visible = message != null) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp)) {
                Text(message.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onClick = onDetails) { Text(stringResource(R.string.details)) }
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
                    if (connection is ConnectionState.PairingRequired) {
                        TextButton(onClick = onPair) { Text(stringResource(R.string.pair_again)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationKeys(enabled: Boolean, onKey: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RemoteKey(R.drawable.ic_arrow_back, R.string.key_back, enabled) { onKey(KeyEvent.KEYCODE_BACK) }
        RemoteKey(R.drawable.ic_home, R.string.key_home, enabled) { onKey(KeyEvent.KEYCODE_HOME) }
        RemoteKey(R.drawable.ic_play_pause, R.string.key_play_pause, enabled) { onKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
    }
}

@Composable
private fun RemoteKey(icon: Int, label: Int, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(64.dp)) {
        Icon(painterResource(icon), stringResource(label), Modifier.size(28.dp))
    }
}

/** What the volume row shows, from the receiver when it is connected and otherwise the TV. */
private data class VolumeDisplay(val level: String?, val fraction: Float?, val isMuted: Boolean)

private fun TvStatus.toVolumeDisplay() = VolumeDisplay(
    level = volumeLevel?.toString(),
    fraction = volumeLevel?.let { level -> volumeMax?.let { max -> level.toFloat() / max } },
    isMuted = isMuted,
)

private fun ReceiverStatus.toVolumeDisplay() = VolumeDisplay(
    // As on the receiver's own display: 0 on its scale is -80 dB.
    level = volumeHalfSteps?.let { DecimalFormat("0.# dB").format(it / 2.0 - 80) },
    fraction = volumeHalfSteps?.let { it.toFloat() / maxHalfSteps },
    isMuted = isMuted,
)

@Composable
private fun VolumeControls(volume: VolumeDisplay?, onKey: (Int) -> Unit, onHoldKey: (Int, Boolean) -> Unit) {
    val enabled = volume != null
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HoldableKeyButton(R.drawable.ic_volume_down, R.string.volume_down, KeyEvent.KEYCODE_VOLUME_DOWN, enabled, onKey, onHoldKey)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val level = volume?.level
            val fraction = volume?.fraction
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = if (level != null) stringResource(R.string.volume_level_text, level) else stringResource(R.string.volume),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        HoldableKeyButton(R.drawable.ic_volume_up, R.string.volume_up, KeyEvent.KEYCODE_VOLUME_UP, enabled, onKey, onHoldKey)
        IconToggleButton(
            checked = volume?.isMuted == true,
            onCheckedChange = { onKey(KeyEvent.KEYCODE_VOLUME_MUTE) },
            enabled = enabled,
        ) {
            Icon(painterResource(R.drawable.ic_volume_off), stringResource(R.string.mute))
        }
    }
}

/**
 * Tap sends one key press. Holding sends a long press that the TV repeats at
 * its own pace, like holding a button on a physical remote — sending our own
 * repeats outruns slow paths such as HDMI-CEC volume.
 */
@Composable
private fun HoldableKeyButton(
    icon: Int,
    label: Int,
    keyCode: Int,
    enabled: Boolean,
    onKey: (Int) -> Unit,
    onHoldKey: (Int, Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val currentOnHoldKey by rememberUpdatedState(onHoldKey)
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) {
            delay(longPressTimeout)
            holding = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            currentOnHoldKey(keyCode, true)
        } else if (holding) {
            holding = false
            currentOnHoldKey(keyCode, false)
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (holding) currentOnHoldKey(keyCode, false) }
    }
    FilledTonalIconButton(
        onClick = { if (!holding) onKey(keyCode) },
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = Modifier.size(56.dp),
    ) {
        Icon(painterResource(icon), stringResource(label))
    }
}

/** Pinned shortcuts first, then apps the TV has opened recently as suggestions. */
@Composable
private fun ShortcutRow(
    apps: TvApps,
    showNames: Boolean,
    enabled: Boolean,
    onLaunch: (AppShortcut) -> Unit,
    onEdit: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(apps.pinned, key = { "pinned:${it.packageName}" }) { shortcut ->
            AppTile(shortcut.label, shortcut.packageName, showNames, enabled, onClick = { onLaunch(shortcut) })
        }
        if (apps.pinned.isNotEmpty() && apps.suggested.isNotEmpty()) {
            item(key = "divider") {
                VerticalDivider(Modifier.height(48.dp).padding(horizontal = 4.dp))
            }
        }
        items(apps.suggested, key = { "suggested:${it.packageName}" }) { app ->
            AppTile(app.label, app.packageName, showNames, enabled, onClick = { onLaunch(app) })
        }
        item(key = "edit") {
            ActionTile(
                label = stringResource(R.string.edit_shortcuts),
                showLabel = showNames,
                icon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                onClick = onEdit,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvSheet(
    tvs: List<Tv>,
    selected: Tv,
    onSelect: (Tv) -> Unit,
    onRemove: (Tv) -> Unit,
    onAddTv: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingRemoval by remember { mutableStateOf<Tv?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle(stringResource(R.string.your_tvs))
        tvs.forEach { tv ->
            val isSelected = tv.id == selected.id
            ListItem(
                headlineContent = { Text(tv.name) },
                supportingContent = { Text(tv.host) },
                leadingContent = {
                    Icon(painterResource(if (isSelected) R.drawable.ic_tv_fill1 else R.drawable.ic_tv), contentDescription = null)
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSelected) {
                            Icon(painterResource(R.drawable.ic_check), stringResource(R.string.tv_selected), tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { pendingRemoval = tv }) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.remove))
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.clickable { onSelect(tv) },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ListItem(
            headlineContent = { Text(stringResource(R.string.add_tv)) },
            leadingContent = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.clickable(onClick = onAddTv),
        )
        Spacer(Modifier.height(16.dp))
    }

    pendingRemoval?.let { tv ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.remove_tv_title, tv.name)) },
            text = { Text(stringResource(R.string.remove_tv_message)) },
            confirmButton = {
                TextButton(onClick = { onRemove(tv); pendingRemoval = null }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortcutSheet(
    apps: TvApps,
    currentAppPackage: String?,
    onAdd: (label: String, packageName: String) -> Unit,
    onRemove: (AppShortcut) -> Unit,
    onDismiss: () -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    val canAddCurrent = currentAppPackage != null && apps.pinned.none { it.packageName == currentAppPackage }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetTitle(stringResource(R.string.shortcuts))
            ListItem(
                headlineContent = { Text(stringResource(R.string.add_current_app)) },
                supportingContent = {
                    Text(currentAppPackage?.let(AppCatalog::label) ?: stringResource(R.string.add_current_app_hint))
                },
                leadingContent = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.clickable(enabled = canAddCurrent) { adding = true },
            )
            if (apps.pinned.isNotEmpty()) {
                SheetSectionHeader(stringResource(R.string.shortcuts_pinned))
                apps.pinned.forEach { shortcut ->
                    ListItem(
                        headlineContent = { Text(shortcut.label) },
                        supportingContent = { Text(shortcut.packageName) },
                        trailingContent = {
                            IconButton(onClick = { onRemove(shortcut) }) {
                                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.remove_shortcut, shortcut.label))
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    )
                }
            }
            SheetSectionHeader(stringResource(R.string.shortcuts_seen))
            if (apps.suggested.isEmpty()) {
                Text(
                    text = stringResource(R.string.shortcuts_seen_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            apps.suggested.forEach { app ->
                ListItem(
                    headlineContent = { Text(app.label) },
                    supportingContent = { Text(app.packageName) },
                    trailingContent = {
                        IconButton(onClick = { onAdd(app.label, app.packageName) }) {
                            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.pin_shortcut, app.label))
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (adding && currentAppPackage != null) {
        var label by rememberSaveable { mutableStateOf(AppCatalog.label(currentAppPackage)) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.add_current_app)) },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.shortcut_name)) },
                    supportingText = { Text(currentAppPackage) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onAdd(label.trim(), currentAppPackage); adding = false },
                    enabled = label.isNotBlank(),
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SheetSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
