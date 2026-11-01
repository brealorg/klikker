package no.enaasen.tvremote.ui.remote

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import no.enaasen.tvremote.R
import no.enaasen.tvremote.data.apps.AppCatalog
import no.enaasen.tvremote.data.model.MediaSessionInfo
import no.enaasen.tvremote.data.model.MediaState

private val HeroIconSize = 72.dp

/**
 * What's open on the TV: the app's icon (or the picture of what plays) on a
 * surface tinted with its colour, with the transport keys and a seekable
 * progress bar. The protocol reports only the app, so what it plays comes from
 * the companion app on the TV ([media]); without it only the app shows.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NowPlaying(
    packageName: String,
    label: String,
    media: MediaSessionInfo?,
    controlsEnabled: Boolean,
    muted: Boolean,
    muteEnabled: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val home = AppCatalog.isHomeScreen(packageName)
    val icon = if (home) null else rememberAppIcon(packageName, HeroIconSize)
    // The poster or cover, when the session has one, stands in for the app's icon.
    val artwork = remember(media?.art) { media?.art?.asImageBitmap() }
    val tint by produceState<Color?>(null, icon, artwork) { value = (artwork ?: icon)?.let { dominantColor(it) } }

    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = tint?.let { lerp(colors.surfaceContainerHigh, it, 0.3f) } ?: colors.surfaceContainerHigh,
        animationSpec = spring(stiffness = Spring.StiffnessVeryLow),
        label = "nowPlayingTint",
    )

    val subtitle = remember(media?.subtitle, media?.durationMs) {
        // The bar shows the length, so a "• 47m" the app puts in the subtitle is dropped.
        media?.subtitle?.let { if (media.durationMs != null) it.withoutTrailingDuration() else it }?.takeIf { it.isNotBlank() }
    }
    val skipKey = Modifier.size(width = 64.dp, height = 48.dp)

    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = container) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (artwork != null) {
                    Image(
                        bitmap = artwork,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(HeroIconSize).clip(RoundedCornerShape(16.dp)),
                    )
                } else {
                    Crossfade(targetState = packageName, label = "nowPlayingIcon") { shown ->
                        if (AppCatalog.isHomeScreen(shown)) {
                            Box(
                                modifier = Modifier
                                    .size(HeroIconSize)
                                    .clip(CircleShape)
                                    .background(colors.secondaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(painterResource(R.drawable.ic_home), contentDescription = null, tint = colors.onSecondaryContainer)
                            }
                        } else {
                            AppIcon(label, shown, size = HeroIconSize)
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    // Without a playing session the app's name below says it all.
                    if (media != null) {
                        Text(
                            text = stringResource(R.string.now_playing_app_state, label, stringResource(media.state.label())),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = media?.title ?: if (home) stringResource(R.string.home_screen) else label,
                        style = MaterialTheme.typography.headlineSmall,
                        // A title can be long; an app name never is.
                        maxLines = if (media?.title != null) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                FilledTonalIconToggleButton(
                    checked = muted,
                    onCheckedChange = { onMute() },
                    enabled = muteEnabled,
                    modifier = Modifier.align(Alignment.Top).size(40.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_volume_off), stringResource(R.string.mute))
                }
            }
            media?.durationMs?.let { Progress(media, it, controlsEnabled, onSeek) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = onPrevious, enabled = controlsEnabled, modifier = skipKey, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.key_previous))
                }
                FilledIconButton(onClick = onPlayPause, enabled = controlsEnabled, modifier = Modifier.size(width = 88.dp, height = 56.dp), shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_play_pause), stringResource(R.string.key_play_pause), Modifier.size(28.dp))
                }
                FilledTonalIconButton(onClick = onNext, enabled = controlsEnabled, modifier = skipKey, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.key_next))
                }
            }
        }
    }
}

/**
 * How far into what plays. The companion reports the position every few
 * seconds, so between reports it runs on by itself while the media plays.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Progress(media: MediaSessionInfo, durationMs: Long, enabled: Boolean, onSeek: (Long) -> Unit) {
    val reported = media.positionMs ?: 0L
    // Where a seek was sent, shown until the TV's next report says where it really is.
    var sought by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(reported) { sought = null }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val base = sought ?: reported
    val position by produceState(base, base, media.state) {
        value = base
        while (media.state == MediaState.Playing) {
            delay(PROGRESS_TICK_MS)
            value = (value + PROGRESS_TICK_MS).coerceAtMost(durationMs)
        }
    }
    val colors = MaterialTheme.colorScheme
    val fraction = dragging ?: (position.toFloat() / durationMs).coerceIn(0f, 1f)
    val seekLabel = stringResource(R.string.seek)
    val haptics = LocalHapticFeedback.current
    val currentOnSeek by rememberUpdatedState(onSeek)
    // Shows the new position at once and asks the TV to move there.
    fun seekTo(fraction: Float) {
        val target = (fraction * durationMs).toLong()
        sought = target
        currentOnSeek(target)
    }
    Column {
        // A generous touch strip around the thin line, so it can be grabbed with a thumb.
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .semantics { contentDescription = seekLabel }
                .pointerInput(enabled, durationMs) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        seekTo(offset.x.fractionOf(size.width))
                    }
                }
                .pointerInput(enabled, durationMs) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> dragging = offset.x.fractionOf(size.width) },
                        onDragEnd = {
                            dragging?.let(::seekTo)
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                    ) { change, _ ->
                        change.consume()
                        dragging = change.position.x.fractionOf(size.width)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            LinearWavyProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = colors.primary,
                trackColor = colors.onSurface.copy(alpha = 0.12f),
                amplitude = { if (media.state == MediaState.Playing && dragging == null) 1f else 0f },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = if (dragging != null) (fraction * durationMs).toLong() else position
            Text(clock(shown), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Text(clock(durationMs), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}

/** "S6 • E5 • Slow Horses • 47m" without the "• 47m": the bar shows the length. */
private val TrailingDuration = Regex("""\s*[•·|]\s*(?:\d+\s*h)?\s*(?:\d+\s*m(?:in)?)?\s*(?:\d+\s*s)?\s*$""")

private fun String.withoutTrailingDuration(): String {
    val stripped = replace(TrailingDuration, "")
    // Only when it was a duration: a bare bullet or a title that ends in a letter is left alone.
    return if (stripped.length < length && substring(stripped.length).any { it.isDigit() }) stripped else this
}

private const val PROGRESS_TICK_MS = 1_000L

private fun Float.fractionOf(width: Int) = (this / width).coerceIn(0f, 1f)

private fun clock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun MediaState.label() = when (this) {
    MediaState.Playing -> R.string.now_playing_state_playing
    MediaState.Paused -> R.string.now_playing_state_paused
    MediaState.Buffering -> R.string.now_playing_state_loading
    MediaState.Stopped -> R.string.now_playing_state_stopped
}

private suspend fun dominantColor(icon: ImageBitmap): Color? = withContext(Dispatchers.Default) {
    val palette = Palette.from(icon.asAndroidBitmap()).generate()
    (palette.vibrantSwatch ?: palette.dominantSwatch)?.rgb?.let(::Color)
}
