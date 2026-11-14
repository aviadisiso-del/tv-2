package com.glasslauncher.tv.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.glasslauncher.tv.ui.LaunchRequest
import com.glasslauncher.tv.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * tvOS-style launch: the card dips (press), then zooms from its spot to full screen
 * with a brief light burst, and fades to black while the app starts underneath.
 */
@Composable
fun LaunchOverlay(request: LaunchRequest, onZoomFinished: (LaunchRequest) -> Unit) {
    val dip = remember(request) { Animatable(1f) }
    val progress = remember(request) { Animatable(0f) }
    LaunchedEffect(request) {
        dip.animateTo(0.94f, tween(Motion.LAUNCH_DIP_MS, easing = FastOutLinearInEasing))
        launch { dip.animateTo(1f, tween(Motion.LAUNCH_ZOOM_MS)) }
        progress.animateTo(1f, tween(Motion.LAUNCH_ZOOM_MS, easing = Motion.EmphasizedDecelerate))
        onZoomFinished(request)
    }
    ZoomFrame(request, progress.value, dip.value, returning = false)
}

/**
 * Coming back from an app: the screen starts black at full size and shrinks back into
 * the card it was launched from, revealing the launcher around it.
 */
@Composable
fun ReturnOverlay(request: LaunchRequest, onFinished: () -> Unit) {
    val progress = remember(request) { Animatable(1f) }
    LaunchedEffect(request) {
        progress.animateTo(0f, tween(Motion.RETURN_ZOOM_MS, easing = Motion.EmphasizedDecelerate))
        onFinished()
    }
    ZoomFrame(request, progress.value, 1f, returning = true)
}

/** [p] = 0 at the card, 1 at full screen. [dip] scales the card rect around its center. */
@Composable
private fun ZoomFrame(request: LaunchRequest, p: Float, dip: Float, returning: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullW = constraints.maxWidth.toFloat()
        val fullH = constraints.maxHeight.toFloat()
        val from = request.fromBounds
        val startW = from.width * dip
        val startH = from.height * dip
        val startL = from.center.x - startW / 2
        val startT = from.center.y - startH / 2

        val left = lerp(startL, 0f, p)
        val top = lerp(startT, 0f, p)
        val width = lerp(startW, fullW, p)
        val height = lerp(startH, fullH, p)
        val density = LocalDensity.current

        // Black cover: launching = last 40% fades in; returning = first 45% fades out.
        val blackCover = if (returning) {
            ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
        } else {
            ((p - 0.6f) / 0.4f).coerceIn(0f, 1f)
        }
        // Returning: the overlay card dissolves into the real card for the last stretch.
        val frameAlpha = if (returning) (p / 0.2f).coerceIn(0f, 1f) else 1f
        // Launch-only light burst, peaking early in the zoom.
        val burst = if (returning) 0f else 0.18f * sin(PI.toFloat() * (p / 0.6f).coerceIn(0f, 1f))

        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f * p)))
        Box(
            Modifier
                .absoluteOffset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { width.toDp() }, with(density) { height.toDp() })
                .graphicsLayer { alpha = frameAlpha }
                .clip(RoundedCornerShape(lerp(16f, 0f, p).dp)),
        ) {
            AppArtwork(request.app, Modifier.fillMaxSize())
            if (burst > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = burst)))
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = blackCover)))
        }
    }
}
