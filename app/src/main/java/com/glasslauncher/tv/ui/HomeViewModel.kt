package com.glasslauncher.tv.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.R
import com.glasslauncher.tv.data.AppEntry
import com.glasslauncher.tv.data.AppRepository
import com.glasslauncher.tv.data.ContentItem
import com.glasslauncher.tv.data.ContentRepository
import com.glasslauncher.tv.data.ContentRowState
import com.glasslauncher.tv.data.HomeRow
import com.glasslauncher.tv.data.UsageRepository
import com.glasslauncher.tv.data.WallpaperChoice
import com.glasslauncher.tv.data.Weather
import com.glasslauncher.tv.media.NowPlaying
import com.glasslauncher.tv.ui.home.MenuTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Player : Screen
}

/**
 * A launch in progress: where its card was (so the zoom starts there), and an optional
 * provider deep link (Watch Next item) that is tried before plain app launch.
 */
@Immutable
data class LaunchRequest(
    val app: AppEntry,
    val fromBounds: Rect,
    val intentUri: String? = null,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AppRepository(application)
    private val contentRepo = ContentRepository(application)
    private val usage = UsageRepository(application)
    private val settings = (application as GlassApp).settings
    private val weatherRepo = (application as GlassApp).weather
    private val media = (application as GlassApp).media

    val nowPlaying: StateFlow<NowPlaying?> = media.nowPlaying

    val wallpaper: StateFlow<WallpaperChoice> = settings.wallpaper
    val rowOrder: StateFlow<List<HomeRow>> = settings.rowOrder
    val hiddenRows: StateFlow<Set<HomeRow>> = settings.hiddenRows

    /** True between onStart/onStop. */
    private var visible = false

    private val _weather = MutableStateFlow(weatherRepo.cached())
    val weather: StateFlow<Weather?> = _weather.asStateFlow()
    private var weatherJob: Job? = null

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _focusedApp = MutableStateFlow<AppEntry?>(null)
    val focusedApp: StateFlow<AppEntry?> = _focusedApp.asStateFlow()

    /** "Continue Watching" (system Watch Next) or "Recently opened" (fallback); null = hidden. */
    private val _continueRow = MutableStateFlow<ContentRowState?>(null)
    val continueRow: StateFlow<ContentRowState?> = _continueRow.asStateFlow()

    private val _recommendedRow = MutableStateFlow<ContentRowState?>(null)
    val recommendedRow: StateFlow<ContentRowState?> = _recommendedRow.asStateFlow()

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set

    var selectedTab by mutableStateOf(MenuTab.HOME)
        private set

    var launch by mutableStateOf<LaunchRequest?>(null)
        private set

    /** Set when we come back from an app we launched: plays the shrink-back animation. */
    var returnFrom by mutableStateOf<LaunchRequest?>(null)
        private set
    private var lastLaunch: LaunchRequest? = null

    /** Bumped when the Home key is pressed: scroll back to the first app and focus it. */
    var homeSignal by mutableIntStateOf(0)
        private set

    private var loadJob: Job? = null
    private var contentJob: Job? = null

    init {
        refresh()
        // City changed in Settings -> fetch right away instead of waiting 30 minutes.
        viewModelScope.launch {
            settings.weatherCity.drop(1).collect { if (visible) startWeather() }
        }
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val list = repo.loadApps()
            _apps.value = list
            _focusedApp.value?.let { current ->
                _focusedApp.value = list.firstOrNull { it.packageName == current.packageName }
            }
            refreshContent()
        }
    }

    /** Rebuilds the content rows: system provider first, app-based fallback otherwise. */
    fun refreshContent() {
        val apps = _apps.value
        if (apps.isEmpty()) return
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            val byPackage = apps.associateBy { it.packageName }

            val watchNext = contentRepo.watchNext(byPackage)
            val (lastUsed, counts) = withContext(Dispatchers.IO) { usage.lastUsed() to usage.launchCounts() }
            _continueRow.value = if (watchNext.isNotEmpty()) {
                ContentRowState(R.string.row_continue, watchNext)
            } else {
                lastUsed.entries
                    .sortedByDescending { it.value }
                    .mapNotNull { (pkg, time) -> byPackage[pkg]?.let { appItem("recent", it, time) } }
                    .take(MAX_RECENT)
                    .takeIf { it.isNotEmpty() }
                    ?.let { ContentRowState(R.string.row_recent, it) }
            }

            val shown = _continueRow.value?.items.orEmpty().mapTo(HashSet()) { it.app.packageName }
            val previews = contentRepo.previewPrograms(byPackage)
            val recommended = previews.ifEmpty {
                apps.asSequence()
                    .filter { it.packageName !in shown }
                    .sortedWith(
                        compareByDescending<AppEntry> { counts[it.packageName] ?: 0 }
                            .thenByDescending { it.category in MEDIA_CATEGORIES }
                            .thenByDescending { it.isTvApp }
                    )
                    .take(MAX_RECOMMENDED)
                    .map { appItem("rec", it, null) }
                    .toList()
            }
            _recommendedRow.value = recommended.takeIf { it.isNotEmpty() }
                ?.let { ContentRowState(R.string.row_recommended, it) }
        }
    }

    private fun appItem(prefix: String, app: AppEntry, lastUsedAt: Long?) = ContentItem(
        id = "$prefix:${app.packageName}",
        title = app.label,
        subtitle = null,
        imageUri = null,
        app = app,
        progress = null,
        intentUri = null,
        lastUsedAt = lastUsedAt,
    )

    /** Weather + content refresh while the launcher is visible: on start, weather every 30 min. */
    fun onStart() {
        visible = true
        lastLaunch?.let { returnFrom = it }
        lastLaunch = null
        refreshContent()
        media.start()
        startWeather()
    }

    private fun startWeather() {
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            while (isActive) {
                val city = settings.weatherCity.value
                val current = _weather.value
                val stale = current == null || current.query != city ||
                    System.currentTimeMillis() - current.fetchedAt > WEATHER_TTL_MS
                if (stale) {
                    // Offline / unknown city: keep showing the last good value.
                    runCatching { weatherRepo.fetch(city) }.onSuccess { _weather.value = it }
                }
                delay(WEATHER_TTL_MS)
            }
        }
    }

    fun onStop() {
        visible = false
        media.stop()
        weatherJob?.cancel()
        weatherJob = null
        // We're being covered by the app we just launched: remember it for the way back.
        if (launch != null) lastLaunch = launch
        launch = null
    }

    fun clearReturn() {
        returnFrom = null
    }

    fun onAppFocused(app: AppEntry) {
        _focusedApp.value = app
    }

    fun onContentFocused(item: ContentItem) {
        _focusedApp.value = item.app
    }

    fun selectTab(tab: MenuTab) {
        selectedTab = tab
    }

    fun onTabClicked(tab: MenuTab) {
        selectedTab = tab
        if (tab == MenuTab.SETTINGS) screen = Screen.Settings
    }

    fun openSettings() {
        screen = Screen.Settings
    }

    /** Back from any sub-screen (settings, player). */
    fun closeSubScreen() {
        screen = Screen.Home
        // Coming back from Settings opened via the menu tab: land on Home, not the tab's hint.
        if (selectedTab == MenuTab.SETTINGS) selectedTab = MenuTab.HOME
    }

    // --- Music ---

    fun openPlayer() {
        screen = Screen.Player
    }

    fun onNowPlayingFocused() {
        val pkg = media.nowPlaying.value?.packageName ?: return
        _apps.value.firstOrNull { it.packageName == pkg }?.let { _focusedApp.value = it }
    }

    fun playPause() = media.playPause()
    fun skipNext() = media.next()
    fun skipPrevious() = media.previous()
    fun seekTo(positionMs: Long) = media.seekTo(positionMs)

    fun appFor(packageName: String?): AppEntry? =
        packageName?.let { pkg -> _apps.value.firstOrNull { it.packageName == pkg } }

    fun onHomePressed() {
        screen = Screen.Home
        selectedTab = MenuTab.HOME
        launch = null
        // Home key resets the layout, so the old card position would be wrong: no shrink-back.
        returnFrom = null
        lastLaunch = null
        homeSignal++
    }

    fun requestLaunch(app: AppEntry, fromBounds: Rect) {
        if (launch == null) launch = LaunchRequest(app, fromBounds)
    }

    fun requestContentLaunch(item: ContentItem, fromBounds: Rect) {
        if (launch == null) launch = LaunchRequest(item.app, fromBounds, item.intentUri)
    }

    fun recordLaunch(packageName: String) {
        usage.recordLaunch(packageName)
    }

    fun clearLaunch() {
        launch = null
    }

    fun launchIntent(app: AppEntry): Intent = repo.launchIntent(app)

    private companion object {
        const val WEATHER_TTL_MS = 30L * 60 * 1000
        const val MAX_RECENT = 10
        const val MAX_RECOMMENDED = 12
        val MEDIA_CATEGORIES = setOf(
            ApplicationInfo.CATEGORY_VIDEO,
            ApplicationInfo.CATEGORY_AUDIO,
            ApplicationInfo.CATEGORY_GAME,
        )
    }
}
