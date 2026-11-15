package com.siso.glasslauncher

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * Heebo (SIL OFL, see /licenses): one variable font file for Hebrew and Latin.
 * Every weight is a real instance on the `wght` axis, not a synthesized bold.
 */
@OptIn(ExperimentalTextApi::class)
val AppFont: FontFamily = FontFamily(
    listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { w ->
        Font(
            resId = R.font.heebo_variable,
            weight = w,
            variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)),
        )
    },
)

/*
 * Liquid Glass look, drawn with plain gradients (no real-time blur) so it stays smooth
 * on a weak TV box: translucent fill + diagonal specular sheen + bright top rim.
 */

private val SheenCool = Color(0xFFBEE1FF)

/** Bright edge that is strongest top-left and bottom-right, like light caught in a glass rim. */
val LiquidRim: Brush = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.95f),
    0.35f to Color.White.copy(alpha = 0.30f),
    0.65f to Color.White.copy(alpha = 0.18f),
    1f to Color.White.copy(alpha = 0.70f),
)

/** The specular layers shared by every glass surface. Draw it on top of the surface's own fill. */
fun DrawScope.drawLiquidSheen(strength: Float = 1f) {
    drawRect(
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.34f * strength),
            0.30f to Color.Transparent,
            0.70f to Color.Transparent,
            1f to SheenCool.copy(alpha = 0.20f * strength),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
        ),
    )
    val rimH = (size.height * 0.20f).coerceAtLeast(1f)
    drawRect(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.30f * strength), Color.Transparent),
            startY = 0f,
            endY = rimH,
        ),
        size = Size(size.width, rimH),
    )
}

/** A glass surface without the blurred-wallpaper backdrop: buttons, tiles, chips. */
fun Modifier.liquidGlass(shape: Shape, fill: Float = 0.18f): Modifier =
    this
        .clip(shape)
        .drawBehind {
            drawRect(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = (fill + 0.10f).coerceAtMost(1f)),
                        Color.White.copy(alpha = fill * 0.45f),
                        Color.White.copy(alpha = fill * 0.75f),
                    ),
                ),
            )
            drawLiquidSheen()
        }
        .border(1.dp, LiquidRim, shape)

/** Sheen + rim laid OVER existing content (app banners, cover art) so it reads as being under glass. */
fun Modifier.glassOverlay(shape: Shape): Modifier =
    this
        .drawWithContent {
            drawContent()
            drawLiquidSheen(0.55f)
        }
        .border(1.dp, LiquidRim, shape)

/**
 * Focus ring that "pours" around the element from the top down, with a soft glow.
 * Put it BEFORE clip() in the chain so it can draw outside the element.
 */
@Composable
fun Modifier.liquidRing(focused: Boolean, corner: Dp, outset: Dp = 4.dp): Modifier {
    val animOn = LocalAnimations.current
    val p by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = when {
            !animOn -> snap()
            focused -> tween(420, easing = FastOutSlowInEasing)
            else -> tween(140)
        },
        label = "liquidRing",
    )
    return this.drawWithContent {
        drawContent()
        if (p > 0f) {
            val o = outset.toPx()
            val r = corner.toPx() + o
            val glow = 5.dp.toPx()
            val pad = o + glow * 2f
            clipRect(
                left = -pad,
                top = -pad,
                right = size.width + pad,
                bottom = -pad + (size.height + pad * 2f) * p,
            ) {
                drawRoundRect(
                    color = SheenCool.copy(alpha = 0.30f),
                    topLeft = Offset(-o, -o),
                    size = Size(size.width + o * 2f, size.height + o * 2f),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = glow * 2f),
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.97f),
                    topLeft = Offset(-o, -o),
                    size = Size(size.width + o * 2f, size.height + o * 2f),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}

/**
 * The wallpaper, alive: a slow drift and zoom, a soft light haze that wanders across it,
 * and a small parallax shift as focus walks along the app row.
 * Everything is read in the draw phase, so it costs a redraw per frame and no recomposition.
 */
@Composable
fun LiveWallpaper(image: ImageBitmap, live: Boolean, focusIndex: Int, modifier: Modifier = Modifier) {
    val animOn = LocalAnimations.current
    val moving = live && animOn
    val shift by animateFloatAsState(
        targetValue = focusIndex.coerceIn(0, 8).toFloat(),
        animationSpec = if (animOn) spring(dampingRatio = 0.85f, stiffness = 60f) else snap(),
        label = "wallpaperShift",
    )
    val phase: State<Float>? = if (moving) {
        rememberInfiniteTransition(label = "wallpaper").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart),
            label = "wallpaperPhase",
        )
    } else {
        null
    }
    Canvas(modifier.fillMaxSize()) {
        val ph = (phase?.value ?: 0f) * 6.2832f
        val zoom = if (moving) 1.08f + 0.02f * sin(ph) else 1.04f
        val par = -(shift - 4f) * size.width * 0.003f
        val dx = (if (moving) cos(ph) * size.width * 0.012f else 0f) + par
        val dy = if (moving) sin(ph * 2f) * size.height * 0.006f else 0f
        val w = size.width * zoom
        val h = size.height * zoom
        drawImage(
            image = image,
            dstOffset = IntOffset(((size.width - w) / 2f + dx).roundToInt(), ((size.height - h) / 2f + dy).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
        if (moving) {
            drawRect(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(size.width * (0.5f + 0.45f * sin(ph + 1.3f)), size.height * 0.62f),
                    radius = size.width * 0.45f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFFFFE2B8).copy(alpha = 0.08f), Color.Transparent),
                    center = Offset(size.width * (0.5f + 0.45f * cos(ph + 0.4f)), size.height * 0.30f),
                    radius = size.width * 0.38f,
                ),
            )
        }
    }
}
