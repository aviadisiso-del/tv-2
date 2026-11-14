package com.glasslauncher.tv.ui.home

import android.content.pm.ApplicationInfo
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.ui.components.Text
import com.glasslauncher.tv.R
import com.glasslauncher.tv.data.AppEntry
import com.glasslauncher.tv.media.NowPlaying
import com.glasslauncher.tv.ui.components.FocusCard
import com.glasslauncher.tv.ui.components.FocusTravel
import com.glasslauncher.tv.ui.components.edgeScaleFor
import com.glasslauncher.tv.ui.theme.Glass

// ---------------------------------------------------------------- data

/** Page 2 groupings, derived from the installed apps. */
@Immutable
data class Page2Content(
    val musicApps: List<AppEntry>,
    val videoApps: List<AppEntry>,
    val games: List<AppEntry>,
    val allApps: List<AppEntry>,
)

/** Popular music apps that don't declare CATEGORY_AUDIO. */
private val KnownMusicPackages = setOf(
    "com.spotify.tv.android",
    "com.spotify.music",
    "com.google.android.apps.youtube.music",
    "com.google.android.youtube.tvmusic",
    "com.apple.android.music",
    "deezer.android.tv",
    "deezer.android.app",
    "com.aspiro.tidal",
    "com.amazon.mp3",
    "com.soundcloud.android",
    "tunein.player",
    "radiotime.player",
)

fun buildPage2(apps: List<AppEntry>): Page2Content = Page2Content(
    musicApps = apps.filter { it.category == ApplicationInfo.CATEGORY_AUDIO || it.packageName in KnownMusicPackages },
    videoApps = apps.filter { it.category == ApplicationInfo.CATEGORY_VIDEO },
    games = apps.filter { it.category == ApplicationInfo.CATEGORY_GAME },
    allApps = apps.sortedBy { it.label.lowercase() },
)

/** Shortcuts into Android settings (and our own). */
enum class QuickSetting(@StringRes val label: Int, val symbol: String, val action: String?) {
    WIFI(R.string.qs_wifi, "📶", Settings.ACTION_WIFI_SETTINGS),
    BLUETOOTH(R.string.qs_bluetooth, "🎧", Settings.ACTION_BLUETOOTH_SETTINGS),
    DISPLAY(R.string.qs_display, "🖥️", Settings.ACTION_DISPLAY_SETTINGS),
    SOUND(R.string.qs_sound, "🔊", Settings.ACTION_SOUND_SETTINGS),
    APPS(R.string.qs_apps, "📦", Settings.ACTION_APPLICATION_SETTINGS),
    STORAGE(R.string.qs_storage, "💾", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
    DATE(R.string.qs_date, "🕒", Settings.ACTION_DATE_SETTINGS),
    LAUNCHER(R.string.qs_launcher, "✨", null), // null = GlassLauncher's own settings
}

// ---------------------------------------------------------------- rows

@Composable
private fun RowTitle(title: String, rowHasFocus: Boolean) {
    val color by animateColorAsState(
        if (rowHasFocus) Glass.TextPrimary else Glass.TextSecondary,
        tween(200),
        label = "rowTitle",
    )
    Text(
        text = title,
        color = color,
        fontSize = 21.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 64.dp, top = 8.dp),
    )
}

/** A titled row of standard app cards, optionally led by a custom card (e.g. Now Playing). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppsShelfRow(
    title: String,
    apps: List<AppEntry>,
    travel: FocusTravel,
    onFocused: (AppEntry) -> Unit,
    onLaunch: (AppEntry, Rect) -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable (rowHasFocus: Boolean) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    var rowHasFocus by remember { mutableStateOf(false) }
    val offset = if (leading != null) 1 else 0

    Column(modifier.fillMaxWidth()) {
        RowTitle(title, rowHasFocus)
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { rowHasFocus = it.hasFocus }
                .focusRestorer(),
            contentPadding = PaddingValues(horizontal = 64.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            if (leading != null) {
                item(key = "leading") { leading(rowHasFocus) }
            }
            itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                FocusCard(
                    width = AppCardWidth,
                    height = AppCardHeight,
                    label = app.label,
                    rowHasFocus = rowHasFocus,
                    travel = travel,
                    edgeScale = { edgeScaleFor(listState, index + offset) },
                    onFocused = { onFocused(app) },
                    onClick = { bounds -> onLaunch(app, bounds) },
                ) { artModifier ->
                    AppArtwork(app, artModifier)
                }
            }
        }
    }
}

/** Music row: compact Now Playing card first (if anything is playing), then music apps. */
@Composable
fun MusicRow(
    nowPlaying: NowPlaying?,
    nowPlayingAppLabel: String?,
    musicApps: List<AppEntry>,
    travel: FocusTravel,
    onAppFocused: (AppEntry) -> Unit,
    onNowPlayingFocused: () -> Unit,
    onOpenPlayer: () -> Unit,
    onLaunch: (AppEntry, Rect) -> Unit,
) {
    // Explicit type so the lambda is inferred as @Composable.
    val leading: (@Composable (Boolean) -> Unit)? = if (nowPlaying == null) {
        null
    } else {
        { rowHasFocus ->
            FocusCard(
                width = 300.dp,
                height = AppCardHeight,
                label = nowPlayingAppLabel ?: nowPlaying.packageName,
                sublabel = stringResource(if (nowPlaying.isPlaying) R.string.player_playing else R.string.player_paused),
                rowHasFocus = rowHasFocus,
                travel = travel,
                edgeScale = { 1f },
                onFocused = onNowPlayingFocused,
                onClick = { onOpenPlayer() },
                overlay = { NowPlayingCardContent(nowPlaying) },
            ) { artModifier ->
                NowPlayingCardBackdrop(nowPlaying, artModifier)
            }
        }
    }
    AppsShelfRow(
        title = stringResource(R.string.row_music),
        apps = musicApps,
        travel = travel,
        onFocused = onAppFocused,
        onLaunch = onLaunch,
        leading = leading,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun QuickSettingsRow(
    travel: FocusTravel,
    onOpen: (QuickSetting) -> Unit,
) {
    val listState = rememberLazyListState()
    var rowHasFocus by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        RowTitle(stringResource(R.string.row_quick_settings), rowHasFocus)
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { rowHasFocus = it.hasFocus }
                .focusRestorer(),
            contentPadding = PaddingValues(horizontal = 64.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            itemsIndexed(QuickSetting.entries, key = { _, qs -> qs.name }) { index, qs ->
                val label = stringResource(qs.label)
                FocusCard(
                    width = 180.dp,
                    height = 101.dp,
                    label = label,
                    rowHasFocus = rowHasFocus,
                    travel = travel,
                    edgeScale = { edgeScaleFor(listState, index) },
                    onFocused = {},
                    onClick = { onOpen(qs) },
                    overlay = {
                        Column(
                            Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(qs.symbol, fontSize = 30.sp)
                            Text(label, color = Glass.TextPrimary, fontSize = 15.sp, maxLines = 1)
                        }
                    },
                ) { artModifier ->
                    Box(
                        artModifier.background(
                            Brush.linearGradient(listOf(Color(0xFF26304A), Color(0xFF12151F)))
                        )
                    )
                }
            }
        }
    }
}
