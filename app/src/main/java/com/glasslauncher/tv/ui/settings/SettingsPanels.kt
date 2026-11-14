package com.glasslauncher.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glasslauncher.tv.ui.components.Text
import com.glasslauncher.tv.R
import com.glasslauncher.tv.data.HomeRow
import com.glasslauncher.tv.data.WallpaperChoice
import com.glasslauncher.tv.data.WallpaperPreset
import com.glasslauncher.tv.ui.components.GlassButton
import com.glasslauncher.tv.ui.components.GlassTextField
import com.glasslauncher.tv.ui.home.WallpaperLayer
import com.glasslauncher.tv.ui.theme.Glass
import com.glasslauncher.tv.ui.theme.tvSpring

// ---------------------------------------------------------------- shared bits

@Composable
internal fun PanelHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 20.dp)) {
        Text(title, color = Glass.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Text(subtitle, color = Glass.TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Focusable glass surface with the standard lift; [highlighted] = selected state. */
@Composable
private fun SelectableTile(
    width: Dp,
    height: Dp,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, tvSpring(), label = "tileScale")
    val borderColor by animateColorAsState(
        when {
            focused -> Color.White
            highlighted -> Glass.Accent
            else -> Color.White.copy(alpha = 0.12f)
        },
        tween(150),
        label = "tileBorder",
    )
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .width(width)
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = if (focused) 16.dp.toPx() else 0f
                this.shape = shape
                clip = true
            }
            .border(if (focused || highlighted) 3.dp else 1.dp, borderColor, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// ---------------------------------------------------------------- wallpaper

@Composable
fun WallpaperPanel(vm: SettingsViewModel) {
    val choice by vm.wallpaper.collectAsStateWithLifecycle()
    Column {
        PanelHeader(stringResource(R.string.settings_wallpaper), stringResource(R.string.settings_wallpaper_hint))
        val presets = WallpaperPreset.entries
        presets.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                pair.forEach { preset ->
                    val selected = choice.imageUrl == null && choice.preset == preset
                    SelectableTile(
                        width = 196.dp,
                        height = 100.dp,
                        highlighted = selected,
                        onClick = { vm.selectPreset(preset) },
                    ) {
                        WallpaperLayer(
                            choice = WallpaperChoice(preset, null),
                            tint = { preset.accent },
                            shiftDp = { 0f },
                            modifier = Modifier.fillMaxSize(),
                        )
                        Text(
                            text = (if (selected) "✓ " else "") + stringResource(preset.label),
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        Text(
            stringResource(R.string.settings_wallpaper_url),
            color = Glass.TextSecondary,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassTextField(
                value = choice.imageUrl.orEmpty(),
                placeholder = stringResource(R.string.settings_wallpaper_url_placeholder),
                onSubmit = { url -> if (url.isNotEmpty()) vm.applyWallpaperUrl(url) },
                keyboardType = KeyboardType.Uri,
                forceLtr = true,
                modifier = Modifier.width(300.dp),
            )
            if (choice.imageUrl != null) {
                GlassButton(text = stringResource(R.string.settings_remove), onClick = vm::clearWallpaperUrl)
            }
        }
    }
}

// ---------------------------------------------------------------- rows

@Composable
fun RowsPanel(vm: SettingsViewModel) {
    val order by vm.rowOrder.collectAsStateWithLifecycle()
    val hidden by vm.hiddenRows.collectAsStateWithLifecycle()
    var moving by remember { mutableStateOf<HomeRow?>(null) }

    // Back while moving just drops the row (doesn't leave the panel).
    BackHandler(enabled = moving != null) { moving = null }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelHeader(stringResource(R.string.settings_rows), stringResource(R.string.settings_rows_hint))

        // The app row is fixed, like tvOS.
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.apps_row_title), color = Glass.TextSecondary, fontSize = 19.sp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.settings_rows_fixed), color = Glass.TextSecondary, fontSize = 15.sp)
        }

        order.forEach { row ->
            key(row) {
                val isMoving = moving == row
                val visible = row !in hidden
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SelectableTile(
                        width = 230.dp,
                        height = 56.dp,
                        highlighted = isMoving,
                        onClick = { moving = if (isMoving) null else row },
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (!isMoving || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionUp -> { vm.moveRow(row, -1); true }
                                Key.DirectionDown -> { vm.moveRow(row, 1); true }
                                Key.DirectionLeft, Key.DirectionRight -> true // stay put while moving
                                else -> false
                            }
                        },
                    ) {
                        Row(
                            Modifier
                                .fillMaxSize()
                                .background(if (isMoving) Glass.Accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.1f))
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(row.label),
                                color = if (visible) Glass.TextPrimary else Glass.TextSecondary,
                                fontSize = 19.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text(if (isMoving) "↕" else "≡", color = Glass.TextSecondary, fontSize = 20.sp)
                        }
                    }
                    GlassButton(
                        text = stringResource(if (visible) R.string.settings_row_shown else R.string.settings_row_hidden),
                        onClick = { vm.toggleRow(row) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- weather

private val CityChoices = listOf(
    R.string.city_tel_aviv to "Tel Aviv",
    R.string.city_jerusalem to "Jerusalem",
    R.string.city_haifa to "Haifa",
    R.string.city_beersheba to "Beersheba",
    R.string.city_eilat to "Eilat",
    R.string.city_new_york to "New York",
    R.string.city_london to "London",
    R.string.city_paris to "Paris",
)

@Composable
fun WeatherPanel(vm: SettingsViewModel) {
    val city by vm.weatherCity.collectAsStateWithLifecycle()
    val cached = remember(city) { vm.cachedWeather()?.takeIf { it.query == city } }

    Column {
        PanelHeader(
            stringResource(R.string.settings_weather),
            if (cached != null) stringResource(R.string.settings_weather_current, cached.cityName, cached.temperatureC)
            else stringResource(R.string.settings_weather_hint),
        )
        CityChoices.chunked(4).forEach { rowCities ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                rowCities.forEach { (label, query) ->
                    SelectableTile(
                        width = 100.dp,
                        height = 52.dp,
                        highlighted = city.equals(query, ignoreCase = true),
                        onClick = { vm.setCity(query) },
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.White.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(label), color = Glass.TextPrimary, fontSize = 16.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.settings_weather_custom),
            color = Glass.TextSecondary,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        GlassTextField(
            value = city.takeIf { c -> CityChoices.none { it.second.equals(c, ignoreCase = true) } }.orEmpty(),
            placeholder = stringResource(R.string.settings_weather_placeholder),
            onSubmit = { if (it.isNotEmpty()) vm.setCity(it) },
            modifier = Modifier.width(300.dp),
        )
    }
}

// ---------------------------------------------------------------- about

@Composable
fun AboutPanel(vm: SettingsViewModel) {
    // Re-read every time the panel is shown or "refresh" is pressed (RAM and access change).
    var refreshCount by remember { mutableIntStateOf(0) }
    val info by produceState<AboutInfo?>(null, refreshCount) { value = vm.aboutInfo() }
    val about = info ?: return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelHeader(stringResource(R.string.settings_about), "GlassLauncher ${about.versionName}")
        InfoLine(stringResource(R.string.about_version), "${about.versionName} (${about.versionCode})")
        InfoLine(stringResource(R.string.about_android), about.android)
        InfoLine(stringResource(R.string.about_device), about.device)
        InfoLine(stringResource(R.string.about_app_memory), "${about.appHeapUsedMb} / ${about.appHeapMaxMb} MB")
        InfoLine(stringResource(R.string.about_device_memory), "${about.deviceRamAvailMb} / ${about.deviceRamTotalMb} MB")

        Text(
            stringResource(R.string.about_access),
            color = Glass.TextPrimary,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 14.dp),
        )
        AccessLine(stringResource(R.string.about_access_media), about.mediaAccess)
        AccessLine(stringResource(R.string.about_access_usage), about.usageAccess)
        AccessLine(stringResource(R.string.about_access_tv), about.tvProviderAccess)
        if (!(about.mediaAccess && about.usageAccess && about.tvProviderAccess)) {
            Text(
                stringResource(R.string.about_access_hint),
                color = Glass.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        GlassButton(
            text = stringResource(R.string.about_refresh),
            onClick = { refreshCount++ },
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Glass.TextSecondary, fontSize = 17.sp, modifier = Modifier.width(170.dp))
        Text(value, color = Glass.TextPrimary, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AccessLine(label: String, granted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(50))
                .background(if (granted) Color(0xFF34C759) else Color(0xFFFF453A)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (granted) "✓" else "✕", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, color = Glass.TextPrimary, fontSize = 17.sp, modifier = Modifier.padding(start = 10.dp))
    }
}
