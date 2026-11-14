package com.glasslauncher.tv.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.ui.components.Text
import com.glasslauncher.tv.ui.theme.Glass
import com.glasslauncher.tv.ui.theme.tvSpring
import kotlinx.coroutines.launch

private val CardShape = RoundedCornerShape(16.dp)

private const val FOCUS_SCALE = 1.08f
private const val UNFOCUSED_ALPHA = 0.7f
private const val ENTER_TILT_DEG = 7f
private const val NUDGE_TILT_DEG = 5f
private const val PARALLAX_DP_PER_DEG = 1.4f

/**
 * Remembers which way the d-pad last moved, so the card that *receives* focus can
 * tilt in from that direction (our d-pad stand-in for the tvOS touch-surface parallax).
 * Shared by every row on a screen.
 */
@Stable
class FocusTravel {
    var dx = 0f
        private set
    var dy = 0f
        private set
    private var at = 0L

    fun record(dx: Float, dy: Float) {
        this.dx = dx
        this.dy = dy
        at = SystemClock.uptimeMillis()
    }

    fun isRecent(): Boolean = SystemClock.uptimeMillis() - at < 250
}

/** Items shrink slightly as they slide out past either edge of a row (tvOS feel). */
fun edgeScaleFor(state: LazyListState, index: Int): Float {
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return 1f
    val center = item.offset + item.size / 2f
    val distance = minOf(center - info.viewportStartOffset, info.viewportEndOffset - center)
    val t = (distance / (item.size * 0.9f)).coerceIn(0f, 1f)
    return 0.86f + 0.14f * t
}

/**
 * The tvOS focus card used by every row:
 * spring lift (1.08x), white border glow + drop shadow, dimming of unfocused siblings,
 * directional tilt with a parallax artwork layer, a specular sheen, and a caption
 * that appears only while focused.
 *
 * [artwork] receives the parallax modifier and must apply it; [overlay] is drawn on
 * top without parallax (badges, progress bars).
 */
@Composable
fun FocusCard(
    width: Dp,
    height: Dp,
    label: String,
    rowHasFocus: Boolean,
    travel: FocusTravel,
    edgeScale: () -> Float,
    onFocused: () -> Unit,
    onClick: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    sublabel: String? = null,
    focusRequester: FocusRequester? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    artwork: @Composable (Modifier) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            focused && pressed -> 1.02f
            focused -> FOCUS_SCALE
            else -> 1f
        },
        animationSpec = tvSpring(),
        label = "cardScale",
    )
    val glow by animateFloatAsState(if (focused) 1f else 0f, tvSpring(), label = "cardGlow")
    val dim by animateFloatAsState(
        targetValue = if (focused || !rowHasFocus) 1f else UNFOCUSED_ALPHA,
        animationSpec = tween(220),
        label = "cardDim",
    )

    val rotY = remember { Animatable(0f) } // tilt around the vertical axis (left/right)
    val rotX = remember { Animatable(0f) } // tilt around the horizontal axis (up/down)
    val scope = rememberCoroutineScope()
    val bounds = remember { arrayOf(Rect.Zero) } // not state: no recomposition on scroll

    fun tilt(dx: Float, dy: Float, entering: Boolean) {
        scope.launch {
            if (entering) rotY.snapTo(-dx * ENTER_TILT_DEG)
            else rotY.animateTo(-dx * NUDGE_TILT_DEG, spring(stiffness = Spring.StiffnessHigh))
            rotY.animateTo(0f, tvSpring())
        }
        scope.launch {
            if (entering) rotX.snapTo(dy * ENTER_TILT_DEG)
            else rotX.animateTo(dy * NUDGE_TILT_DEG, spring(stiffness = Spring.StiffnessHigh))
            rotX.animateTo(0f, tvSpring())
        }
    }

    Column(
        modifier = modifier.width(width),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width, height)
                .onGloballyPositioned { bounds[0] = it.boundsInRoot() }
                .graphicsLayer {
                    val s = scale * edgeScale()
                    scaleX = s
                    scaleY = s
                    rotationY = rotY.value
                    rotationX = rotX.value
                    cameraDistance = 14f * density
                    shadowElevation = 22.dp.toPx() * glow.coerceIn(0f, 1f)
                    shape = CardShape
                    clip = true
                    alpha = dim
                }
                .background(Glass.GlassFill)
                .border(2.dp, Color.White.copy(alpha = 0.9f * glow.coerceIn(0f, 1f)), CardShape)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onPreviewKeyEvent { event ->
                    if (focused && event.type == KeyEventType.KeyDown) {
                        event.key.toDirection()?.let { (dx, dy) ->
                            travel.record(dx, dy)
                            // Runs after focus search: if focus couldn't move (row edge), nudge.
                            scope.launch { if (focused) tilt(dx, dy, entering = false) }
                        }
                    }
                    false
                }
                .onFocusChanged { state ->
                    val gained = state.isFocused && !focused
                    focused = state.isFocused
                    if (gained) {
                        onFocused()
                        if (travel.isRecent()) tilt(travel.dx, travel.dy, entering = true)
                    }
                }
                .clickable(interactionSource = interaction, indication = null) {
                    onClick(bounds[0])
                },
        ) {
            // Artwork layer drifts opposite to the tilt -> depth / parallax.
            artwork(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.07f
                        scaleY = 1.07f
                        translationX = rotY.value * PARALLAX_DP_PER_DEG * density
                        translationY = -rotX.value * PARALLAX_DP_PER_DEG * density
                    }
            )
            overlay()
            // Specular sheen that slides with the tilt.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = glow.coerceIn(0f, 1f) }
                    .drawBehind {
                        val shift = rotY.value / ENTER_TILT_DEG
                        drawRect(
                            Brush.linearGradient(
                                colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                                start = Offset(size.width * (0.15f - 0.3f * shift), 0f),
                                end = Offset(size.width * (0.65f - 0.3f * shift), size.height),
                            )
                        )
                    }
            )
        }

        // Caption appears only on the focused card (space reserved so rows don't jump).
        Column(
            modifier = Modifier
                .padding(top = 14.dp)
                .graphicsLayer { alpha = glow.coerceIn(0f, 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                color = Glass.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sublabel != null) {
                Text(
                    text = sublabel,
                    color = Glass.TextSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun Key.toDirection(): Pair<Float, Float>? = when (this) {
    Key.DirectionLeft -> -1f to 0f
    Key.DirectionRight -> 1f to 0f
    Key.DirectionUp -> 0f to -1f
    Key.DirectionDown -> 0f to 1f
    else -> null
}
