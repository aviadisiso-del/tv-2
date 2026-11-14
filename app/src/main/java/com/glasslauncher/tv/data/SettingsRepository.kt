package com.glasslauncher.tv.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.glasslauncher.tv.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Built-in wallpapers: drawn as gradients in code, so they cost no APK size or bitmap RAM. */
enum class WallpaperPreset(
    val id: String,
    @StringRes val label: Int,
    val base: Color,
    val blobs: List<Color>,
    val accent: Color,
) {
    AURORA(
        "aurora", R.string.wallpaper_aurora, Color(0xFF07080F),
        listOf(Color(0xFF1B3A8C), Color(0xFF5B2A86), Color(0xFF0E6B7A)), Color(0xFF2E8BFF),
    ),
    SUNSET(
        "sunset", R.string.wallpaper_sunset, Color(0xFF0D0709),
        listOf(Color(0xFF8A2B4A), Color(0xFFB5562A), Color(0xFF4A1F6B)), Color(0xFFFF7A45),
    ),
    OCEAN(
        "ocean", R.string.wallpaper_ocean, Color(0xFF050B10),
        listOf(Color(0xFF0B4F6C), Color(0xFF117A8B), Color(0xFF1D2F6F)), Color(0xFF2EC4D6),
    ),
    GRAPHITE(
        "graphite", R.string.wallpaper_graphite, Color(0xFF0A0A0F),
        listOf(Color(0xFF2A2D35), Color(0xFF1C1F26), Color(0xFF3A3D45)), Color(0xFF9AA4B5),
    );

    companion object {
        fun fromId(id: String?): WallpaperPreset = entries.firstOrNull { it.id == id } ?: AURORA
    }
}

/** Preset gradient, optionally replaced by a user image URL. */
@Immutable
data class WallpaperChoice(val preset: WallpaperPreset, val imageUrl: String?)

/** Small key/value settings. SharedPreferences keeps us free of extra dependencies. */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("glass_settings", Context.MODE_PRIVATE)

    private val _wallpaper = MutableStateFlow(readWallpaper())
    val wallpaper: StateFlow<WallpaperChoice> = _wallpaper.asStateFlow()

    private val _weatherCity = MutableStateFlow(prefs.getString(KEY_CITY, null) ?: DEFAULT_CITY)
    val weatherCity: StateFlow<String> = _weatherCity.asStateFlow()

    fun setWallpaper(preset: WallpaperPreset, imageUrl: String? = null) {
        val url = imageUrl?.trim()?.takeIf { it.startsWith("http") }
        prefs.edit().putString(KEY_WALLPAPER, preset.id).putString(KEY_WALLPAPER_URL, url).apply()
        _wallpaper.value = WallpaperChoice(preset, url)
    }

    fun setWeatherCity(city: String) {
        val clean = city.trim().ifEmpty { DEFAULT_CITY }
        prefs.edit().putString(KEY_CITY, clean).apply()
        _weatherCity.value = clean
    }

    // --- Home row order / visibility (the app row is always first) ---

    private val _rowOrder = MutableStateFlow(readRowOrder())
    val rowOrder: StateFlow<List<HomeRow>> = _rowOrder.asStateFlow()

    private val _hiddenRows = MutableStateFlow(
        prefs.getStringSet(KEY_HIDDEN_ROWS, null).orEmpty().mapNotNull { HomeRow.fromId(it) }.toSet()
    )
    val hiddenRows: StateFlow<Set<HomeRow>> = _hiddenRows.asStateFlow()

    fun moveRow(row: HomeRow, delta: Int) {
        val list = _rowOrder.value.toMutableList()
        val from = list.indexOf(row)
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        list.removeAt(from)
        list.add(to, row)
        prefs.edit().putString(KEY_ROW_ORDER, list.joinToString(",") { it.id }).apply()
        _rowOrder.value = list
    }

    fun setRowVisible(row: HomeRow, visible: Boolean) {
        val set = if (visible) _hiddenRows.value - row else _hiddenRows.value + row
        prefs.edit().putStringSet(KEY_HIDDEN_ROWS, set.mapTo(HashSet()) { it.id }).apply()
        _hiddenRows.value = set
    }

    private fun readRowOrder(): List<HomeRow> {
        val saved = prefs.getString(KEY_ROW_ORDER, null)
            ?.split(',')
            ?.mapNotNull { HomeRow.fromId(it) }
            .orEmpty()
        return (saved + HomeRow.entries).distinct() // rows added in future versions get appended
    }

    private fun readWallpaper() = WallpaperChoice(
        preset = WallpaperPreset.fromId(prefs.getString(KEY_WALLPAPER, null)),
        imageUrl = prefs.getString(KEY_WALLPAPER_URL, null),
    )

    private companion object {
        const val KEY_WALLPAPER = "wallpaper"
        const val KEY_WALLPAPER_URL = "wallpaper_url"
        const val KEY_CITY = "weather_city"
        const val KEY_ROW_ORDER = "row_order"
        const val KEY_HIDDEN_ROWS = "hidden_rows"
        const val DEFAULT_CITY = "Tel Aviv"
    }
}

/** Reorderable / hideable rows on Home, below the fixed Top Shelf + app row. */
enum class HomeRow(val id: String, @StringRes val label: Int) {
    NOW_PLAYING("now_playing", R.string.row_now_playing),
    CONTINUE("continue", R.string.row_continue),
    RECOMMENDED("recommended", R.string.row_recommended);

    companion object {
        fun fromId(id: String): HomeRow? = entries.firstOrNull { it.id == id }
    }
}
