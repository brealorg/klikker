package no.enaasen.tvremote.ui.remote

import android.content.pm.PackageManager
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.enaasen.tvremote.data.apps.AppCatalog

private val IconSize = 48.dp
private val TileWidth = 72.dp

/** An app as a tappable icon with its name below, like on the phone's launcher. */
@Composable
internal fun AppTile(
    label: String,
    packageName: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(TileWidth),
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tilePress",
    )
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .alpha(if (enabled) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppIcon(label, packageName, size = IconSize, modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
        TileLabel(label)
    }
}

/** A tile in the same shape as [AppTile] for actions such as editing the row. */
@Composable
internal fun ActionTile(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(TileWidth),
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(IconSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { icon() }
        TileLabel(label)
    }
}

@Composable
private fun TileLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // The tile already announces the name.
        modifier = Modifier.clearAndSetSemantics { },
    )
}

/** The app's icon as the phone's launcher shows it, or its initial in a circle when the phone has no icon for it. */
@Composable
internal fun AppIcon(label: String, packageName: String, size: Dp = IconSize, modifier: Modifier = Modifier) {
    val current = rememberAppIcon(packageName, size)
    if (current != null) {
        // Decorative: the tile's label names the app.
        Image(bitmap = current, contentDescription = null, modifier = modifier.size(size))
    } else {
        InitialIcon(label, CircleShape, size, modifier)
    }
}

/**
 * The app's icon as the phone's launcher shows it (with the shape the phone has chosen), taken
 * from the same app or its phone edition installed on this phone; there is no way to get it
 * from the TV.
 */
@Composable
internal fun rememberAppIcon(packageName: String, size: Dp): ImageBitmap? {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.toPx().roundToInt() }
    val icon by produceState<ImageBitmap?>(null, packageName, sizePx) {
        value = withContext(Dispatchers.IO) {
            AppCatalog.iconPackages(packageName).firstNotNullOfOrNull { candidate ->
                try {
                    context.packageManager.getApplicationIcon(candidate).toBitmap(sizePx, sizePx).asImageBitmap()
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
        }
    }
    return icon
}

@Composable
private fun InitialIcon(label: String, shape: Shape, size: Dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = listOf(
        colors.primaryContainer to colors.onPrimaryContainer,
        colors.secondaryContainer to colors.onSecondaryContainer,
        colors.tertiaryContainer to colors.onTertiaryContainer,
    )[label.hashCode().absoluteValue % 3]
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = content)
    }
}
