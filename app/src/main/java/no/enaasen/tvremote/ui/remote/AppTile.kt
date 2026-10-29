package no.enaasen.tvremote.ui.remote

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
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
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.graphics.shapes.Morph
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.enaasen.tvremote.data.apps.AppCatalog
import no.enaasen.tvremote.ui.theme.MorphShape

private val IconSize = 48.dp
private val TileWidth = 72.dp

/** An app as a tappable icon with its name below, like on a launcher. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppTile(
    label: String,
    packageName: String,
    showLabel: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Each app keeps its own shape, which springs into a circle while pressed.
    val polygon = TileShapes[packageName.hashCode().absoluteValue % TileShapes.size]
    val morph = remember(polygon) { Morph(polygon, MaterialShapes.Circle) }
    val progress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tileShape",
    )
    Column(
        modifier = modifier
            .width(TileWidth)
            .clip(MaterialTheme.shapes.medium)
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .alpha(if (enabled) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppIcon(label, packageName, MorphShape(morph, progress))
        if (showLabel) TileLabel(label)
    }
}

/** A tile in the same shape as [AppTile] for actions such as editing the row. */
@Composable
internal fun ActionTile(label: String, showLabel: Boolean, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(TileWidth)
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
        if (showLabel) TileLabel(label)
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

/**
 * The app's icon, taken from the same app or its phone edition installed on
 * this phone; there is no way to get it from the TV. Otherwise its initial.
 */
@Composable
private fun AppIcon(label: String, packageName: String, shape: Shape, size: Dp = IconSize) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.toPx().roundToInt() }
    val icon by produceState<ImageBitmap?>(null, packageName, sizePx) {
        value = withContext(Dispatchers.IO) {
            AppCatalog.iconPackages(packageName).firstNotNullOfOrNull { candidate ->
                try {
                    context.packageManager.getApplicationIcon(candidate).toUnmaskedBitmap(sizePx).asImageBitmap()
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
        }
    }
    val current = icon
    if (current != null) {
        // Decorative: the tile's label names the app.
        // A hairline keeps the shape visible on icons as dark as the background.
        Image(
            bitmap = current,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        )
    } else {
        InitialIcon(label, shape, size)
    }
}

@Composable
private fun InitialIcon(label: String, shape: Shape, size: Dp) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = listOf(
        colors.primaryContainer to colors.onPrimaryContainer,
        colors.secondaryContainer to colors.onSecondaryContainer,
        colors.tertiaryContainer to colors.onTertiaryContainer,
    )[label.hashCode().absoluteValue % 3]
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = content)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val TileShapes = listOf(
    MaterialShapes.Cookie9Sided,
    MaterialShapes.Clover4Leaf,
    MaterialShapes.Sunny,
    MaterialShapes.Cookie6Sided,
    MaterialShapes.SoftBurst,
    MaterialShapes.Gem,
    MaterialShapes.Flower,
    MaterialShapes.Puffy,
)

/**
 * The icon without the system's mask, so it can take any shape: adaptive
 * icon layers are 108dp with the visible 72dp in the middle.
 */
private fun Drawable.toUnmaskedBitmap(sizePx: Int): Bitmap {
    if (this !is AdaptiveIconDrawable) return toBitmap(sizePx, sizePx)
    val bitmap = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)
    val bleed = sizePx / 4
    listOfNotNull(background, foreground).forEach { layer ->
        layer.setBounds(-bleed, -bleed, sizePx + bleed, sizePx + bleed)
        layer.draw(canvas)
    }
    return bitmap
}

