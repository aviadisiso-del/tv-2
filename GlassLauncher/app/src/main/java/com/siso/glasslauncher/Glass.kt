package com.siso.glasslauncher

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Heavily shrunk copy of the wallpaper that panels paint as their "frosted" backdrop. */
val LocalWallpaperBlur = compositionLocalOf<ImageBitmap?> { null }

/** Master switch for animations (Settings → אנימציות). */
val LocalAnimations = compositionLocalOf { true }

/** Media controls and sliders keep their natural left-to-right direction even in a Hebrew (RTL) UI. */
@Composable
fun Ltr(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

/** Size of the root layout in px, so panels can line the backdrop up with the real wallpaper. */
val LocalScreenSize = compositionLocalOf { IntSize(1920, 1080) }

/**
 * Frosted-glass panel. It paints the matching slice of the blurred wallpaper,
 * adds a soft white sheen and a thin bright border.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    tint: Color = Color.Black.copy(alpha = 0.18f),
    borderAlpha: Float = 0.30f,
    content: @Composable BoxScope.() -> Unit,
) {
    val blur = LocalWallpaperBlur.current
    val screen = LocalScreenSize.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .onGloballyPositioned { pos = it.positionInRoot() }
            .clip(shape)
            .drawBehind {
                if (blur != null) {
                    drawImage(
                        image = blur,
                        dstOffset = IntOffset(-pos.x.roundToInt(), -pos.y.roundToInt()),
                        dstSize = screen,
                        filterQuality = FilterQuality.Low,
                    )
                } else {
                    // No wallpaper behind us (menu floating over another app): dark glass.
                    drawRect(Color(0xF0151822))
                }
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.16f), tint, Color.Black.copy(alpha = 0.16f)),
                    ),
                )
            }
            .border(1.dp, Color.White.copy(alpha = borderAlpha), shape),
        content = content,
    )
}

/**
 * Focusable with an on/off switch. Put this BEFORE clickable/focusable in a chain:
 * focusProperties and onFocusChanged both apply to the focus target that follows them.
 */
fun Modifier.tvFocusable(enabled: Boolean = true, onFocus: (Boolean) -> Unit): Modifier =
    this
        .focusProperties { canFocus = enabled }
        .onFocusChanged { onFocus(it.isFocused) }
        .focusable()

/** Clickable (OK / Enter / tap) that can be switched off for D-pad focus, with a focus callback. */
@Composable
fun Modifier.tvClickable(
    enabled: Boolean = true,
    onFocus: (Boolean) -> Unit = {},
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .focusProperties { canFocus = enabled }
        .onFocusChanged { onFocus(it.isFocused) }
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}

fun Modifier.focusBorder(focused: Boolean, shape: Shape, width: Dp = 2.dp): Modifier =
    if (focused) this.border(width, Color.White, shape) else this

@Composable
fun T(
    text: String,
    size: TextUnit = 14.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Color.White,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

enum class Glyph { Play, Pause, Next, Prev, Gear, Chevron, Check, Replay15, Forward15, Search, Grid, Tv }

@Composable
fun GlyphIcon(glyph: Glyph, size: Dp, color: Color = Color.White) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        when (glyph) {
            Glyph.Play -> {
                val p = Path().apply {
                    moveTo(w * 0.28f, h * 0.15f)
                    lineTo(w * 0.86f, h * 0.50f)
                    lineTo(w * 0.28f, h * 0.85f)
                    close()
                }
                drawPath(p, color)
            }
            Glyph.Pause -> {
                drawRect(color, Offset(w * 0.22f, h * 0.15f), Size(w * 0.20f, h * 0.70f))
                drawRect(color, Offset(w * 0.58f, h * 0.15f), Size(w * 0.20f, h * 0.70f))
            }
            Glyph.Next -> {
                val p = Path().apply {
                    moveTo(w * 0.15f, h * 0.20f)
                    lineTo(w * 0.65f, h * 0.50f)
                    lineTo(w * 0.15f, h * 0.80f)
                    close()
                }
                drawPath(p, color)
                drawRect(color, Offset(w * 0.70f, h * 0.20f), Size(w * 0.14f, h * 0.60f))
            }
            Glyph.Prev -> {
                val p = Path().apply {
                    moveTo(w * 0.85f, h * 0.20f)
                    lineTo(w * 0.35f, h * 0.50f)
                    lineTo(w * 0.85f, h * 0.80f)
                    close()
                }
                drawPath(p, color)
                drawRect(color, Offset(w * 0.16f, h * 0.20f), Size(w * 0.14f, h * 0.60f))
            }
            Glyph.Gear -> {
                val stroke = w * 0.09f
                drawCircle(color, radius = w * 0.20f, style = Stroke(width = stroke))
                for (i in 0 until 8) {
                    rotate(degrees = i * 45f) {
                        drawLine(
                            color,
                            Offset(w / 2f, h * 0.10f),
                            Offset(w / 2f, h * 0.24f),
                            strokeWidth = stroke * 1.2f,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
            Glyph.Search -> {
                val stroke = w * 0.10f
                drawCircle(color, radius = w * 0.24f, center = Offset(w * 0.44f, h * 0.44f), style = Stroke(width = stroke))
                drawLine(color, Offset(w * 0.62f, h * 0.62f), Offset(w * 0.85f, h * 0.85f), stroke, StrokeCap.Round)
            }
            Glyph.Grid -> {
                val s = w * 0.28f
                val gap = w * 0.10f
                val x0 = (w - 2 * s - gap) / 2f
                val y0 = (h - 2 * s - gap) / 2f
                for (i in 0..1) for (j in 0..1) {
                    drawRect(color, Offset(x0 + i * (s + gap), y0 + j * (s + gap)), Size(s, s))
                }
            }
            Glyph.Tv -> {
                val stroke = w * 0.09f
                drawRect(color, Offset(w * 0.14f, h * 0.30f), Size(w * 0.72f, h * 0.50f), style = Stroke(width = stroke))
                drawLine(color, Offset(w * 0.50f, h * 0.30f), Offset(w * 0.34f, h * 0.14f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.50f, h * 0.30f), Offset(w * 0.66f, h * 0.14f), stroke, StrokeCap.Round)
            }
            Glyph.Chevron -> {
                val stroke = w * 0.12f
                drawLine(color, Offset(w * 0.35f, h * 0.20f), Offset(w * 0.65f, h * 0.50f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.65f, h * 0.50f), Offset(w * 0.35f, h * 0.80f), stroke, StrokeCap.Round)
            }
            Glyph.Check -> {
                val stroke = w * 0.12f
                drawLine(color, Offset(w * 0.18f, h * 0.52f), Offset(w * 0.40f, h * 0.74f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.40f, h * 0.74f), Offset(w * 0.84f, h * 0.28f), stroke, StrokeCap.Round)
            }
            Glyph.Replay15, Glyph.Forward15 -> {
                val stroke = w * 0.08f
                drawArc(
                    color = color,
                    startAngle = if (glyph == Glyph.Replay15) 120f else -300f,
                    sweepAngle = if (glyph == Glyph.Replay15) 280f else 280f,
                    useCenter = false,
                    topLeft = Offset(w * 0.15f, h * 0.15f),
                    size = Size(w * 0.70f, h * 0.70f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}
