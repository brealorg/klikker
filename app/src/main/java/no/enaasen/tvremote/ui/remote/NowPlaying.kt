package no.enaasen.tvremote.ui.remote

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.enaasen.tvremote.R
import no.enaasen.tvremote.data.apps.AppCatalog
import no.enaasen.tvremote.ui.theme.MorphShape

private val HeroIconSize = 64.dp

/**
 * What's open on the TV. The protocol reports only the app, not what it
 * plays, so this shows the app big: its icon in the app's own shape (which
 * morphs into the next app's shape when the TV switches app) on a surface
 * tinted with the icon's colour.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NowPlaying(packageName: String, label: String, modifier: Modifier = Modifier) {
    val home = AppCatalog.isHomeScreen(packageName)
    val icon = if (home) null else rememberAppIcon(packageName, HeroIconSize)
    val tint by produceState<Color?>(null, icon) { value = icon?.let { dominantColor(it) } }

    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = tint?.let { lerp(colors.surfaceContainerHigh, it, 0.3f) } ?: colors.surfaceContainerHigh,
        animationSpec = spring(stiffness = Spring.StiffnessVeryLow),
        label = "nowPlayingTint",
    )

    // Morph from the previous app's shape to this one's when the TV switches app.
    val polygon = if (home) MaterialShapes.Circle else appPolygon(packageName)
    var from by remember { mutableStateOf(polygon) }
    var to by remember { mutableStateOf(polygon) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(polygon) {
        if (polygon == to) return@LaunchedEffect
        from = to
        to = polygon
        progress.snapTo(0f)
        progress.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    val morph = remember(from, to) { Morph(from, to) }
    val shape = MorphShape(morph, progress.value)

    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = container) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Crossfade(targetState = packageName, label = "nowPlayingIcon") { shown ->
                if (AppCatalog.isHomeScreen(shown)) {
                    Box(
                        modifier = Modifier
                            .size(HeroIconSize)
                            .clip(shape)
                            .background(colors.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_home), contentDescription = null, tint = colors.onSecondaryContainer)
                    }
                } else {
                    AppIcon(label, shown, shape, HeroIconSize)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.now_open),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = if (home) stringResource(R.string.home_screen) else label,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private suspend fun dominantColor(icon: ImageBitmap): Color? = withContext(Dispatchers.Default) {
    val palette = Palette.from(icon.asAndroidBitmap()).generate()
    (palette.vibrantSwatch ?: palette.dominantSwatch)?.rgb?.let(::Color)
}
