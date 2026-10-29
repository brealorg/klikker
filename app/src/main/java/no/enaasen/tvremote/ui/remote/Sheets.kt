package no.enaasen.tvremote.ui.remote

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import no.enaasen.tvremote.R
import no.enaasen.tvremote.data.apps.AppCatalog
import no.enaasen.tvremote.data.model.AppShortcut
import no.enaasen.tvremote.data.model.Tv
import no.enaasen.tvremote.data.model.TvApps
import no.enaasen.tvremote.ui.theme.MorphShape

private val SheetIconSize = 40.dp

/** The TVs as a segmented list; the selected one is highlighted and its icon takes a scalloped shape. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TvSheet(
    tvs: List<Tv>,
    selected: Tv,
    selectedStatus: String,
    onSelect: (Tv) -> Unit,
    onRemove: (Tv) -> Unit,
    onAddTv: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingRemoval by remember { mutableStateOf<Tv?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SheetTitle(stringResource(R.string.your_tvs))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                tvs.forEachIndexed { index, tv ->
                    val isSelected = tv.id == selected.id
                    SegmentedListItem(
                        selected = isSelected,
                        onClick = { onSelect(tv) },
                        shapes = ListItemDefaults.segmentedShapes(index, tvs.size),
                        leadingContent = { TvIcon(isSelected) },
                        supportingContent = {
                            Text(if (isSelected) "${tv.host} · $selectedStatus" else tv.host)
                        },
                        trailingContent = {
                            IconButton(onClick = { pendingRemoval = tv }) {
                                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.remove_shortcut, tv.name))
                            }
                        },
                    ) { Text(tv.name) }
                }
            }
            FilledTonalButton(
                onClick = onAddTv,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.add_tv))
            }
            Spacer(Modifier.height(24.dp))
        }
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

/** A TV icon whose container springs from a circle into a scalloped shape when selected. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TvIcon(selected: Boolean) {
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val progress = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected) {
        progress.animateTo(if (selected) 1f else 0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(SheetIconSize)
            .clip(MorphShape(morph, progress.value))
            .background(if (selected) colors.primary else colors.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (selected) R.drawable.ic_tv_fill1 else R.drawable.ic_tv),
            contentDescription = null,
            tint = if (selected) colors.onPrimary else colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The app open on the TV on top, ready to pin; then the pinned apps (tap to
 * rename) and the apps the TV has opened, each with its shaped icon.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ShortcutSheet(
    apps: TvApps,
    currentAppPackage: String?,
    onPin: (label: String, packageName: String) -> Unit,
    onRename: (AppShortcut, label: String) -> Unit,
    onRemove: (AppShortcut) -> Unit,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf<AppShortcut?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SheetTitle(stringResource(R.string.shortcuts))
            val current = currentAppPackage?.takeIf { AppCatalog.isSuggestible(it) }
            if (current != null) {
                CurrentAppCard(
                    packageName = current,
                    label = apps.pinned.find { it.packageName == current }?.label ?: AppCatalog.label(current),
                    pinned = apps.pinned.any { it.packageName == current },
                    onPin = onPin,
                )
            }
            if (apps.pinned.isNotEmpty()) {
                SheetSectionHeader(stringResource(R.string.shortcuts_pinned))
                AppList(apps.pinned, onClick = { renaming = it }) { shortcut ->
                    Row {
                        IconButton(onClick = { renaming = shortcut }) {
                            Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.rename_shortcut_named, shortcut.label))
                        }
                        IconButton(onClick = { onRemove(shortcut) }) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.remove_shortcut, shortcut.label))
                        }
                    }
                }
            }
            SheetSectionHeader(stringResource(R.string.shortcuts_seen))
            if (apps.suggested.isEmpty()) {
                Text(
                    text = stringResource(R.string.shortcuts_seen_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                AppList(apps.suggested, onClick = { onPin(it.label, it.packageName) }) { app ->
                    IconButton(onClick = { onPin(app.label, app.packageName) }) {
                        Icon(painterResource(R.drawable.ic_add), stringResource(R.string.pin_shortcut, app.label))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    renaming?.let { shortcut ->
        var label by rememberSaveable(shortcut.packageName) { mutableStateOf(shortcut.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.rename_shortcut)) },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.shortcut_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onRename(shortcut, label.trim()); renaming = null },
                    enabled = label.isNotBlank(),
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CurrentAppCard(packageName: String, label: String, pinned: Boolean, onPin: (String, String) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppIcon(label, packageName, appPolygon(packageName).toShape(), 48.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.now_open), style = MaterialTheme.typography.labelMedium)
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (pinned) {
                Text(stringResource(R.string.shortcut_pinned_state), style = MaterialTheme.typography.labelLarge)
            } else {
                FilledTonalButton(onClick = { onPin(label, packageName) }) { Text(stringResource(R.string.pin)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppList(
    apps: List<AppShortcut>,
    onClick: (AppShortcut) -> Unit,
    trailing: @Composable (AppShortcut) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        apps.forEachIndexed { index, app ->
            SegmentedListItem(
                onClick = { onClick(app) },
                shapes = ListItemDefaults.segmentedShapes(index, apps.size),
                leadingContent = { AppIcon(app.label, app.packageName, appPolygon(app.packageName).toShape(), SheetIconSize) },
                trailingContent = { trailing(app) },
            ) { Text(app.label) }
        }
    }
}

@Composable
private fun SheetSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 16.dp),
    )
}
