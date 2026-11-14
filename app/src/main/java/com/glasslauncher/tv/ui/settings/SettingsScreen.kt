package com.glasslauncher.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glasslauncher.tv.ui.components.Text
import com.glasslauncher.tv.R
import com.glasslauncher.tv.ui.home.WallpaperLayer
import com.glasslauncher.tv.ui.theme.Glass
import com.glasslauncher.tv.ui.theme.tvSpring

@StringRes
private fun SettingsSection.label(): Int = when (this) {
    SettingsSection.WALLPAPER -> R.string.settings_wallpaper
    SettingsSection.ROWS -> R.string.settings_rows
    SettingsSection.WEATHER -> R.string.settings_weather
    SettingsSection.CLEANER -> R.string.settings_cleaner
    SettingsSection.ABOUT -> R.string.settings_about
}

/**
 * tvOS Settings layout: sidebar list + detail panel. In RTL the sidebar sits on the right.
 * Moving through the sidebar previews each section; ←/→ crosses into the panel;
 * Back from the panel returns to the sidebar, Back from the sidebar closes Settings.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    onOpenSystemSettings: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val wallpaper by vm.wallpaper.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf(SettingsSection.WALLPAPER) }
    var detailFocused by remember { mutableStateOf(false) }
    val requesters = remember { SettingsSection.entries.associateWith { FocusRequester() } }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requesters.getValue(section).requestFocus() }
    }
    BackHandler(enabled = detailFocused) {
        runCatching { requesters.getValue(section).requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        // The live wallpaper behind, dimmed: wallpaper changes are visible immediately.
        WallpaperLayer(
            choice = wallpaper,
            tint = { wallpaper.preset.accent },
            shiftDp = { 0f },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))

        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 56.dp, vertical = 36.dp)
        ) {
            // Sidebar (first child = right side in RTL)
            Column(
                Modifier
                    .width(290.dp)
                    .fillMaxHeight()
                    .focusProperties { enter = { requesters.getValue(section) } }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    color = Glass.TextPrimary,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp, bottom = 18.dp),
                )
                SettingsSection.entries.forEach { item ->
                    SidebarItem(
                        label = stringResource(item.label()),
                        selected = item == section,
                        modifier = Modifier.focusRequester(requesters.getValue(item)),
                        onFocused = { section = item },
                        onClick = { section = item },
                    )
                }
                Spacer(Modifier.weight(1f))
                SidebarItem(
                    label = stringResource(R.string.open_android_settings),
                    selected = false,
                    onFocused = {},
                    onClick = onOpenSystemSettings,
                )
            }

            Spacer(Modifier.width(40.dp))

            // Detail panel
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .onFocusChanged { detailFocused = it.hasFocus }
                    .focusGroup()
                    .padding(32.dp),
            ) {
                AnimatedContent(
                    targetState = section,
                    transitionSpec = {
                        (fadeIn(tween(250)) + slideInVertically(tvSpring()) { it / 16 })
                            .togetherWith(fadeOut(tween(150)))
                    },
                    label = "settingsPanel",
                ) { current ->
                    // Scrolls (on focus) when a panel is taller than the screen.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        when (current) {
                            SettingsSection.WALLPAPER -> WallpaperPanel(vm)
                            SettingsSection.ROWS -> RowsPanel(vm)
                            SettingsSection.WEATHER -> WeatherPanel(vm)
                            SettingsSection.CLEANER -> CleanerPanel(vm, onOpenStorageSettings)
                            SettingsSection.ABOUT -> AboutPanel(vm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarItem(
    label: String,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        when {
            focused -> Color.White
            selected -> Color.White.copy(alpha = 0.14f)
            else -> Color.Transparent
        },
        tween(150),
        label = "sidebarBg",
    )
    val fg by animateColorAsState(if (focused) Color.Black else Glass.TextPrimary, tween(150), label = "sidebarFg")
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tvSpring(), label = "sidebarScale")

    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .onFocusChanged {
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained) onFocused()
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 19.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        // Points toward the detail panel (left, in RTL).
        Text(text = "‹", color = fg.copy(alpha = 0.6f), fontSize = 24.sp)
    }
}
