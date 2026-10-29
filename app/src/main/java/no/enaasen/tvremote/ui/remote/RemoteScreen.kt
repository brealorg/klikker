package no.enaasen.tvremote.ui.remote

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DecimalFormat
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
            onRenameShortcut = viewModel::renameShortcut,
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
    val onRenameShortcut: (AppShortcut, String) -> Unit,
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
                        statusText = connection.statusText(),
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
            status?.currentAppPackage?.let { packageName ->
                NowPlaying(
                    packageName = packageName,
                    label = uiState.apps.pinned.find { it.packageName == packageName }?.label ?: AppCatalog.label(packageName),
                )
            }
            val volume = uiState.receiver.takeIf { it.isConnected }?.toVolumeDisplay() ?: status?.toVolumeDisplay()
            VolumeKnob(
                volume = volume,
                padEnabled = connected,
                onVolumeStep = { up -> actions.onKey(if (up) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN) },
                pad = { padModifier, onNudge ->
                    SwipePad(
                        enabled = connected,
                        sensitivity = uiState.settings.swipeSensitivity,
                        flingEnabled = uiState.settings.flingEnabled,
                        holdToRepeat = uiState.settings.holdToRepeat,
                        holdRepeatSpeed = uiState.settings.holdRepeatSpeed,
                        onDirection = { direction ->
                            onNudge(direction)
                            actions.onMove(direction)
                        },
                        onSelect = { actions.onKey(KeyEvent.KEYCODE_DPAD_CENTER) },
                        onSelectHeld = { held -> actions.onHoldKey(KeyEvent.KEYCODE_DPAD_CENTER, held) },
                        shape = CircleShape,
                        containerColor = Color.Transparent,
                        modifier = padModifier,
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            RemoteToolbar(
                enabled = connected,
                muted = volume?.isMuted == true,
                volumeEnabled = volume != null,
                onKey = key,
                modifier = Modifier.align(Alignment.CenterHorizontally),
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
            selectedStatus = connection.statusText(),
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
            onPin = actions.onAddShortcut,
            onRename = actions.onRenameShortcut,
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
private fun ConnectionState.statusText(): String = when (this) {
    // The open app is shown big below, see NowPlaying.
    is ConnectionState.Connected -> stringResource(R.string.status_connected)
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

/** Back, Home, Play/Pause and Mute, floating below the knob. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RemoteToolbar(
    enabled: Boolean,
    muted: Boolean,
    volumeEnabled: Boolean,
    onKey: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(expanded = true, modifier = modifier) {
        IconButton(onClick = { onKey(KeyEvent.KEYCODE_BACK) }, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.key_back))
        }
        IconButton(onClick = { onKey(KeyEvent.KEYCODE_HOME) }, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_home), stringResource(R.string.key_home))
        }
        FilledIconButton(onClick = { onKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_play_pause), stringResource(R.string.key_play_pause))
        }
        IconToggleButton(
            checked = muted,
            onCheckedChange = { onKey(KeyEvent.KEYCODE_VOLUME_MUTE) },
            enabled = volumeEnabled,
        ) {
            Icon(painterResource(R.drawable.ic_volume_off), stringResource(R.string.mute))
        }
    }
}

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

