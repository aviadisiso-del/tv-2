package com.glasslauncher.tv.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** tvOS-like surface language: near-black base, frosted glass, white + electric blue. */
object Glass {
    val Background = Color(0xFF0A0A0F)
    val GlassFill = Color.White.copy(alpha = 0.15f)
    val GlassFillFocused = Color.White.copy(alpha = 0.28f)
    val MenuFill = Color.White.copy(alpha = 0.10f)
    val Accent = Color(0xFF2E8BFF)
    val TextPrimary = Color.White
    val TextSecondary = Color.White.copy(alpha = 0.7f)
}

/**
 * The spec asks for stiffness ~400 / damping ~28. Compose springs take a damping
 * *ratio*: with mass 1, ratio = damping / (2 * sqrt(stiffness)) = 28 / 40 = 0.7.
 */
const val TV_STIFFNESS = 400f
const val TV_DAMPING_RATIO = 0.7f

fun <T> tvSpring(): SpringSpec<T> = spring(dampingRatio = TV_DAMPING_RATIO, stiffness = TV_STIFFNESS)

/** Shared timing tokens so every transition in the app feels like one system. */
object Motion {
    /** Small state changes: colors, dimming, tab highlights. */
    const val FADE_FAST = 150
    /** Screen and panel cross-fades. */
    const val FADE = 260
    /** Backdrops and wallpaper tint. */
    const val FADE_SLOW = 600

    /** App launch: the card dips under your thumb, then zooms to full screen. */
    const val LAUNCH_DIP_MS = 90
    const val LAUNCH_ZOOM_MS = 400
    /** Coming back from an app: full screen shrinks back into the card. */
    const val RETURN_ZOOM_MS = 380

    /** Top Shelf waits this long while focus is still moving fast. */
    const val SHELF_DEBOUNCE_MS = 140L

    /** Quick start, long gentle settle (zooms that land). */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
}

@Composable
fun GlassTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Glass.Accent,
            background = Glass.Background,
            surface = Glass.Background,
            onBackground = Glass.TextPrimary,
            onSurface = Glass.TextPrimary,
        ),
        content = content,
    )
}
