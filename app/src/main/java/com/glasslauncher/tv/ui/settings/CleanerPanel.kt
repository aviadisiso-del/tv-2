package com.glasslauncher.tv.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glasslauncher.tv.ui.components.Text
import com.glasslauncher.tv.R
import com.glasslauncher.tv.ui.components.GlassButton
import com.glasslauncher.tv.ui.theme.Glass
import com.glasslauncher.tv.ui.theme.tvSpring
import java.util.Locale

private val CleanGreen = Color(0xFF34C759)

/** Scan -> show MB found -> clean with animation -> result. */
@Composable
fun CleanerPanel(vm: SettingsViewModel, onOpenStorageSettings: () -> Unit) {
    val state by vm.cleaner.collectAsStateWithLifecycle()

    // Big number: MB found (ready) or freed (done); counts up/down with a tween.
    val targetBytes = when (val s = state) {
        is CleanerState.Ready -> s.scan.ownCacheBytes
        is CleanerState.Done -> s.result.freedCacheBytes + s.result.freedRamBytes
        else -> 0L
    }
    val shownMb by animateFloatAsState(
        targetValue = targetBytes / MB,
        animationSpec = tween(if (state is CleanerState.Done) 1100 else 900),
        label = "cleanerNumber",
    )
    val ringColor by animateColorAsState(
        if (state is CleanerState.Done) CleanGreen else Glass.Accent,
        tween(400),
        label = "ringColor",
    )
    val busy = state is CleanerState.Scanning || state is CleanerState.Cleaning
    val ringFill by animateFloatAsState(
        targetValue = when (state) {
            is CleanerState.Ready, is CleanerState.Done -> 1f
            else -> 0f
        },
        animationSpec = tvSpring(),
        label = "ringFill",
    )

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PanelHeader(stringResource(R.string.settings_cleaner), stringResource(R.string.cleaner_subtitle))

        Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
            if (busy) SpinningRing(Glass.Accent) else StaticRing(ringColor, ringFill)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (state is CleanerState.Done) {
                    Text("✓", color = CleanGreen, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = if (busy || state is CleanerState.Idle) "—" else formatMb(shownMb),
                    color = Glass.TextPrimary,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("MB", color = Glass.TextSecondary, fontSize = 15.sp)
            }
        }

        Text(
            text = statusText(state),
            color = Glass.TextPrimary,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 18.dp, bottom = 16.dp),
        )

        // One button whose action follows the state, so focus never jumps.
        GlassButton(
            text = stringResource(
                when (state) {
                    CleanerState.Idle -> R.string.cleaner_scan
                    CleanerState.Scanning -> R.string.cleaner_scanning
                    is CleanerState.Ready -> R.string.cleaner_clean
                    is CleanerState.Cleaning -> R.string.cleaner_cleaning
                    is CleanerState.Done -> R.string.cleaner_scan_again
                }
            ),
            onClick = {
                when (state) {
                    is CleanerState.Ready -> vm.clean()
                    CleanerState.Idle, is CleanerState.Done -> vm.scan()
                    else -> Unit
                }
            },
        )

        // Other apps' caches: measurable with usage access, but only the system may delete them.
        val otherCache = when (val s = state) {
            is CleanerState.Ready -> s.scan.otherAppsCacheBytes
            is CleanerState.Done -> s.otherAppsCacheBytes
            else -> null
        }
        if (otherCache != null && otherCache > 0) {
            Text(
                text = stringResource(R.string.cleaner_other_apps, formatMb(otherCache / MB)),
                color = Glass.TextSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp, bottom = 10.dp),
            )
            GlassButton(text = stringResource(R.string.cleaner_open_storage), onClick = onOpenStorageSettings)
        }
    }
}

@Composable
private fun statusText(state: CleanerState): String = when (state) {
    CleanerState.Idle -> stringResource(R.string.cleaner_idle)
    CleanerState.Scanning -> stringResource(R.string.cleaner_scanning)
    is CleanerState.Ready -> stringResource(
        R.string.cleaner_found,
        formatMb(state.scan.ownCacheBytes / MB),
        state.scan.backgroundPackages.size,
    )
    is CleanerState.Cleaning -> stringResource(R.string.cleaner_cleaning)
    is CleanerState.Done -> stringResource(
        R.string.cleaner_done,
        formatMb(state.result.freedCacheBytes / MB),
        formatMb(state.result.freedRamBytes / MB),
    )
}

/** Rotating arc while scanning/cleaning. Only composed while busy, so it costs nothing idle. */
@Composable
private fun SpinningRing(color: Color) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "spinAngle",
    )
    Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle }) {
        ring(Color.White.copy(alpha = 0.12f), 360f)
        ring(color, 100f)
    }
}

@Composable
private fun StaticRing(color: Color, fill: Float) {
    Canvas(Modifier.fillMaxSize()) {
        ring(Color.White.copy(alpha = 0.12f), 360f)
        ring(color, 360f * fill.coerceIn(0f, 1f))
    }
}

/** Arc inset by half the stroke so it isn't clipped at the canvas edge. */
private fun DrawScope.ring(color: Color, sweep: Float) {
    val stroke = 14.dp.toPx()
    drawArc(
        color = color,
        startAngle = -90f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        style = Stroke(stroke, cap = StrokeCap.Round),
    )
}

private const val MB = 1024f * 1024f

private fun formatMb(mb: Float): String = String.format(Locale.US, "%.1f", mb)
