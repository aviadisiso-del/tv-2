package com.glasslauncher.tv.ui.player

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.ui.components.Text
import com.glasslauncher.tv.media.NowPlaying
import com.glasslauncher.tv.ui.theme.Glass
import com.glasslauncher.tv.ui.theme.tvSpring
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class TransportIcon { PLAY, PAUSE, NEXT, PREVIOUS }

/** tvOS pill transport button: glass at rest, white + lift when focused. */
@Composable
fun TransportButton(
    icon: TransportIcon,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, tvSpring(), label = "btnScale")
    val bg by animateColorAsState(if (focused) Color.White else Glass.GlassFill, tween(150), label = "btnBg")
    val fg by animateColorAsState(if (focused) Color.Black else Color.White, tween(150), label = "btnFg")
    val pill = RoundedCornerShape(percent = 50)

    Box(
        modifier
            .size(width, height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = if (focused) 18.dp.toPx() else 0f
                shape = pill
                clip = true
                alpha = if (enabled) 1f else 0.35f
            }
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(height * 0.38f)) { drawTransportIcon(icon, fg) }
    }
}

private fun DrawScope.drawTransportIcon(icon: TransportIcon, color: Color) {
    val w = size.width
    val h = size.height
    fun triangle(left: Float, right: Float, pointRight: Boolean) = Path().apply {
        if (pointRight) {
            moveTo(left, 0f); lineTo(right, h / 2f); lineTo(left, h)
        } else {
            moveTo(right, 0f); lineTo(left, h / 2f); lineTo(right, h)
        }
        close()
    }
    when (icon) {
        TransportIcon.PLAY -> drawPath(triangle(w * 0.12f, w * 0.98f, pointRight = true), color)
        TransportIcon.PAUSE -> {
            val bar = Size(w * 0.28f, h)
            val r = CornerRadius(w * 0.06f)
            drawRoundRect(color, Offset(w * 0.12f, 0f), bar, r)
            drawRoundRect(color, Offset(w * 0.60f, 0f), bar, r)
        }
        TransportIcon.NEXT -> {
            drawPath(triangle(0f, w * 0.52f, pointRight = true), color)
            drawPath(triangle(w * 0.48f, w, pointRight = true), color)
        }
        TransportIcon.PREVIOUS -> {
            drawPath(triangle(0f, w * 0.52f, pointRight = false), color)
            drawPath(triangle(w * 0.48f, w, pointRight = false), color)
        }
    }
}

/**
 * Progress bar with d-pad scrubbing: focus it, press ←/→ to move (10s steps, 30s when
 * held), the seek is sent after a short pause or on OK. Always laid out LTR.
 */
@Composable
fun ProgressScrubber(
    nowPlaying: NowPlaying,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(nowPlaying)
    val duration = nowPlaying.durationMs
    val canSeek = nowPlaying.canSeek && duration > 0

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }

    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var focused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val commitJob = remember { arrayOfNulls<Job>(1) }

    fun commit(target: Long) {
        commitJob[0]?.cancel()
        commitJob[0] = scope.launch {
            onSeek(target)
            delay(500) // let the new playback state arrive before dropping the preview
            scrubTarget = null
        }
    }

    val shown = scrubTarget ?: nowPlaying.positionAt(now)
    val fraction = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val barHeight by animateDpAsState(if (focused) 10.dp else 6.dp, tvSpring(), label = "barHeight")
    val knobAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "knob")

    Column(modifier) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { event ->
                    if (!canSeek) return@onKeyEvent false
                    val dir = when (event.key) {
                        Key.DirectionRight -> 1
                        Key.DirectionLeft -> -1
                        else -> 0
                    }
                    when {
                        dir != 0 && event.type == KeyEventType.KeyDown -> {
                            val step = if (event.nativeKeyEvent.repeatCount > 4) 30_000L else 10_000L
                            val base = scrubTarget ?: current.positionAt(SystemClock.elapsedRealtime())
                            val target = (base + dir * step).coerceIn(0L, duration)
                            scrubTarget = target
                            commitJob[0]?.cancel()
                            commitJob[0] = scope.launch {
                                delay(700)
                                commit(target)
                            }
                            true
                        }
                        dir != 0 -> true // swallow key-ups too, so focus doesn't leave sideways
                        (event.key == Key.DirectionCenter || event.key == Key.Enter) &&
                            event.type == KeyEventType.KeyUp -> {
                            scrubTarget?.let { commit(it) }
                            true
                        }
                        else -> false
                    }
                }
                .focusable(enabled = canSeek),
            contentAlignment = Alignment.CenterStart,
        ) {
            val knob = 18.dp
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White.copy(alpha = 0.25f))
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(Color.White)
                )
            }
            Box(
                Modifier
                    .offset(x = (maxWidth - knob) * fraction)
                    .size(knob)
                    .graphicsLayer { alpha = knobAlpha }
                    .background(Color.White, CircleShape)
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(formatTime(shown), color = Glass.TextSecondary, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            if (duration > 0) {
                Text("-" + formatTime(duration - shown), color = Glass.TextSecondary, fontSize = 14.sp)
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
