package com.glasslauncher.tv.ui.settings

import android.app.ActivityManager
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glasslauncher.tv.BuildConfig
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.CleanerRepository
import com.glasslauncher.tv.data.CleanerResult
import com.glasslauncher.tv.data.CleanerScan
import com.glasslauncher.tv.data.HomeRow
import com.glasslauncher.tv.data.UsageRepository
import com.glasslauncher.tv.data.WallpaperChoice
import com.glasslauncher.tv.data.WallpaperPreset
import com.glasslauncher.tv.data.Weather
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SettingsSection { WALLPAPER, ROWS, WEATHER, CLEANER, ABOUT }

sealed interface CleanerState {
    data object Idle : CleanerState
    data object Scanning : CleanerState
    data class Ready(val scan: CleanerScan) : CleanerState
    data class Cleaning(val scan: CleanerScan) : CleanerState
    data class Done(val result: CleanerResult, val otherAppsCacheBytes: Long?) : CleanerState
}

data class AboutInfo(
    val versionName: String,
    val versionCode: Int,
    val android: String,
    val device: String,
    val appHeapUsedMb: Long,
    val appHeapMaxMb: Long,
    val deviceRamAvailMb: Long,
    val deviceRamTotalMb: Long,
    val mediaAccess: Boolean,
    val usageAccess: Boolean,
    val tvProviderAccess: Boolean,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val glass = application as GlassApp
    private val settings = glass.settings
    private val cleanerRepo = CleanerRepository(application)

    val wallpaper: StateFlow<WallpaperChoice> = settings.wallpaper
    val rowOrder: StateFlow<List<HomeRow>> = settings.rowOrder
    val hiddenRows: StateFlow<Set<HomeRow>> = settings.hiddenRows
    val weatherCity: StateFlow<String> = settings.weatherCity

    private val _cleaner = MutableStateFlow<CleanerState>(CleanerState.Idle)
    val cleaner: StateFlow<CleanerState> = _cleaner.asStateFlow()

    // --- Wallpaper ---

    fun selectPreset(preset: WallpaperPreset) = settings.setWallpaper(preset, null)

    fun applyWallpaperUrl(url: String) = settings.setWallpaper(settings.wallpaper.value.preset, url)

    fun clearWallpaperUrl() = settings.setWallpaper(settings.wallpaper.value.preset, null)

    // --- Rows ---

    fun moveRow(row: HomeRow, delta: Int) = settings.moveRow(row, delta)

    fun toggleRow(row: HomeRow) = settings.setRowVisible(row, row in settings.hiddenRows.value)

    // --- Weather ---

    /** Home's ViewModel observes the city and refetches immediately. */
    fun setCity(city: String) = settings.setWeatherCity(city)

    fun cachedWeather(): Weather? = glass.weather.cached()

    // --- About ---

    fun aboutInfo(): AboutInfo {
        val app = getApplication<Application>()
        val runtime = Runtime.getRuntime()
        val memory = ActivityManager.MemoryInfo().also {
            app.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
        }
        return AboutInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            appHeapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB,
            appHeapMaxMb = runtime.maxMemory() / MB,
            deviceRamAvailMb = memory.availMem / MB,
            deviceRamTotalMb = memory.totalMem / MB,
            mediaAccess = NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName),
            usageAccess = UsageRepository(app).hasUsageAccess(),
            tvProviderAccess = ContextCompat.checkSelfPermission(app, EPG_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    // --- System cleaner ---

    fun scan() {
        if (_cleaner.value is CleanerState.Scanning || _cleaner.value is CleanerState.Cleaning) return
        viewModelScope.launch {
            _cleaner.value = CleanerState.Scanning
            val started = System.currentTimeMillis()
            // Never stop the app that's playing music.
            val protect = setOfNotNull(glass.media.nowPlaying.value?.packageName)
            val scan = cleanerRepo.scan(protect)
            delay((MIN_ANIMATION_MS - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            _cleaner.value = CleanerState.Ready(scan)
        }
    }

    fun clean() {
        val scan = (_cleaner.value as? CleanerState.Ready)?.scan ?: return
        viewModelScope.launch {
            _cleaner.value = CleanerState.Cleaning(scan)
            val started = System.currentTimeMillis()
            val result = cleanerRepo.clean(scan)
            delay((MIN_ANIMATION_MS - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            _cleaner.value = CleanerState.Done(result, scan.otherAppsCacheBytes)
        }
    }

    private companion object {
        const val MB = 1024L * 1024L
        const val MIN_ANIMATION_MS = 1600L
        const val EPG_PERMISSION = "com.android.providers.tv.permission.READ_EPG_DATA"
    }
}
