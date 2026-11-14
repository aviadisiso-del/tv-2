package com.glasslauncher.tv.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.glasslauncher.tv.data.WallpaperChoice
import kotlin.math.max

/** Oversized so the parallax drift never reveals an edge (60dp max on a 960dp-wide screen). */
private const val OVERSCAN = 1.16f

/**
 * Full-screen background. It drifts slowly with focus position ([shiftDp]) — the
 * slow "far" layer of the tvOS parallax — and blends toward the focused app's [tint].
 * Both are read only in the draw/layer phase, so focus changes never recompose it.
 */
@Composable
fun WallpaperLayer(
    choice: WallpaperChoice,
    tint: () -> Color,
    shiftDp: () -> Float,
    modifier: Modifier = Modifier,
    /** Vertical drift for page changes (kept within ±40dp of overscan). */
    shiftYDp: () -> Float = { 0f },
) {
    Box(
        modifier
            .background(choice.preset.base)
            .graphicsLayer {
                scaleX = OVERSCAN
                scaleY = OVERSCAN
                translationX = shiftDp() * density
                translationY = shiftYDp() * density
            }
    ) {
        if (choice.imageUrl != null) {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(choice.imageUrl)
                    .size(1280, 720) // never decode a 4K photo into the heap
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color.Black.copy(alpha = 0.45f))
                drawTintGlow(tint(), alpha = 0.28f)
                drawVignette()
            }
        } else {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(choice.preset.base)
                val positions = listOf(
                    Triple(0.18f, 0.12f, 0.70f),
                    Triple(0.88f, 0.28f, 0.55f),
                    Triple(0.55f, 0.95f, 0.65f),
                )
                choice.preset.blobs.zip(positions).forEach { (color, pos) ->
                    drawBlob(color, pos.first, pos.second, pos.third, alpha = 0.55f)
                }
                drawTintGlow(tint(), alpha = 0.38f)
                drawVignette()
            }
        }
    }
}

private fun DrawScope.drawBlob(color: Color, fx: Float, fy: Float, fr: Float, alpha: Float) {
    val center = Offset(size.width * fx, size.height * fy)
    val radius = max(size.width, size.height) * fr
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

private fun DrawScope.drawTintGlow(tint: Color, alpha: Float) =
    drawBlob(tint, fx = 0.5f, fy = 0.3f, fr = 0.6f, alpha = alpha)

private fun DrawScope.drawVignette() {
    drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.6f to Color.Black.copy(alpha = 0.25f),
            1f to Color.Black.copy(alpha = 0.7f),
        )
    )
}
