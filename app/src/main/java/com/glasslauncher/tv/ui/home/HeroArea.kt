package com.glasslauncher.tv.ui.home

import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.ui.components.Text
import com.glasslauncher.tv.R
import com.glasslauncher.tv.data.AppEntry
import com.glasslauncher.tv.data.Weather
import com.glasslauncher.tv.ui.theme.Glass
import com.glasslauncher.tv.ui.theme.tvSpring
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ShelfArtShape = RoundedCornerShape(18.dp)

/**
 * Full-bleed backdrop of the Top Shelf: the focused app's banner, heavily blurred.
 * Real RenderEffect blur on Android 12+; older devices get a much fainter image instead.
 */
@Composable
fun HeroBackdrop(app: AppEntry?, modifier: Modifier = Modifier) {
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(modifier) {
        Crossfade(targetState = app?.banner, animationSpec = tween(500), label = "backdrop") { banner ->
            if (banner != null) {
                Image(
                    bitmap = banner,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(36.dp)
                        .graphicsLayer { alpha = if (canBlur) 0.42f else 0.14f },
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to Glass.Background.copy(alpha = 0.30f),
                        1f to Glass.Background.copy(alpha = 0.92f),
                    )
                )
        )
    }
}

/**
 * Top Shelf content. Changes slide in from the direction focus is travelling
 * (the "near" parallax layer, faster than the wallpaper) and fade over.
 */
@Composable
fun TopShelf(
    app: AppEntry?,
    indexOf: (AppEntry?) -> Int,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = app,
        modifier = modifier,
        contentKey = { it?.packageName },
        transitionSpec = {
            // RTL: the next app sits to the physical left, so new content enters from the left.
            val dir = if (indexOf(targetState) >= indexOf(initialState)) -1 else 1
            (fadeIn(tween(320, delayMillis = 60)) +
                slideInHorizontally(tvSpring()) { width -> dir * width / 10 })
                .togetherWith(
                    fadeOut(tween(180)) +
                        slideOutHorizontally(tvSpring()) { width -> -dir * width / 14 }
                )
                .using(SizeTransform(clip = false))
        },
        label = "topShelf",
    ) { current ->
        if (current == null) WelcomeShelf() else AppShelf(current)
    }
}

@Composable
private fun AppShelf(app: AppEntry) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Text block: first child = right side in RTL.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(categoryLabel(app)),
                color = lerp(app.tint, Color.White, 0.45f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Text(
                text = app.label,
                color = Glass.TextPrimary,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.shelf_open_hint),
                color = Glass.TextSecondary,
                fontSize = 16.sp,
            )
        }
        Spacer(Modifier.width(32.dp))
        // Sharp artwork card: the tvOS "Top Shelf" image.
        Box(
            Modifier
                .size(272.dp, 153.dp)
                .graphicsLayer {
                    shadowElevation = 24.dp.toPx()
                    shape = ShelfArtShape
                    clip = true
                }
                .border(1.dp, Color.White.copy(alpha = 0.18f), ShelfArtShape)
        ) {
            AppArtwork(app, Modifier.fillMaxSize())
        }
    }
}

/** Shown before anything is focused: a tvOS-like greeting. */
@Composable
private fun WelcomeShelf() {
    val hour = remember { LocalDateTime.now().hour }
    val greeting = when (hour) {
        in 5..11 -> R.string.greeting_morning
        in 12..16 -> R.string.greeting_afternoon
        in 17..20 -> R.string.greeting_evening
        else -> R.string.greeting_night
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(greeting), color = Glass.TextPrimary, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.greeting_subtitle),
            color = Glass.TextSecondary,
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@StringRes
private fun categoryLabel(app: AppEntry): Int = when (app.category) {
    ApplicationInfo.CATEGORY_VIDEO -> R.string.cat_video
    ApplicationInfo.CATEGORY_AUDIO -> R.string.cat_audio
    ApplicationInfo.CATEGORY_GAME -> R.string.cat_game
    ApplicationInfo.CATEGORY_IMAGE -> R.string.cat_image
    ApplicationInfo.CATEGORY_NEWS -> R.string.cat_news
    ApplicationInfo.CATEGORY_SOCIAL -> R.string.cat_social
    ApplicationInfo.CATEGORY_MAPS -> R.string.cat_maps
    ApplicationInfo.CATEGORY_PRODUCTIVITY -> R.string.cat_productivity
    else -> if (app.isTvApp) R.string.cat_tv_app else R.string.cat_app
}

/** Time + Hebrew date, tvOS top-right corner. */
@Composable
fun ClockWidget(modifier: Modifier = Modifier) {
    val locale = remember { Locale.forLanguageTag("he") }
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm", locale) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEEE, d 'ב'MMMM", locale) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }

    Column(modifier, horizontalAlignment = AbsoluteAlignment.Right) {
        Text(
            text = now.format(timeFormat),
            color = Glass.TextPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = now.format(dateFormat),
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 15.sp,
        )
    }
}

/** Weather, tvOS top-left corner. Hidden until the first successful fetch. */
@Composable
fun WeatherWidget(weather: Weather?, modifier: Modifier = Modifier) {
    if (weather == null) return
    val (symbol, description) = weatherLook(weather.code, weather.isDay)
    Column(modifier, horizontalAlignment = AbsoluteAlignment.Left) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "${weather.temperatureC}°", color = Glass.TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Text(text = symbol, fontSize = 24.sp)
        }
        Text(
            text = "${weather.cityName} · ${stringResource(description)}",
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 15.sp,
            maxLines = 1,
        )
    }
}

/** WMO weather code -> (symbol, Hebrew description). */
private fun weatherLook(code: Int, isDay: Boolean): Pair<String, Int> = when (code) {
    0 -> (if (isDay) "☀️" else "🌙") to R.string.weather_clear
    1, 2 -> (if (isDay) "⛅" else "☁️") to R.string.weather_partly_cloudy
    3 -> "☁️" to R.string.weather_cloudy
    45, 48 -> "🌫️" to R.string.weather_fog
    in 51..57 -> "🌦️" to R.string.weather_drizzle
    in 61..67 -> "🌧️" to R.string.weather_rain
    in 71..77, 85, 86 -> "❄️" to R.string.weather_snow
    in 80..82 -> "🌦️" to R.string.weather_showers
    in 95..99 -> "⛈️" to R.string.weather_thunder
    else -> "🌡️" to R.string.weather_unknown
}
