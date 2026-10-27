package com.siso.glasslauncher

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)

    var wallpaper: Int
        get() = sp.getInt("wallpaper", 0)
        set(v) { sp.edit().putInt("wallpaper", v).apply() }

    var city: Int
        get() = sp.getInt("city", 0)
        set(v) { sp.edit().putInt("city", v).apply() }

    var showWeather: Boolean
        get() = sp.getBoolean("showWeather", true)
        set(v) { sp.edit().putBoolean("showWeather", v).apply() }

    var showMusic: Boolean
        get() = sp.getBoolean("showMusic", true)
        set(v) { sp.edit().putBoolean("showMusic", v).apply() }

    var showClock: Boolean
        get() = sp.getBoolean("showClock", true)
        set(v) { sp.edit().putBoolean("showClock", v).apply() }

    var userName: String
        get() = sp.getString("userName", "סיסו") ?: ""
        set(v) { sp.edit().putString("userName", v).apply() }

    /** 0 = off, 1 = greeting only, 2 = greeting + sentence */
    var heroMode: Int
        get() = sp.getInt("heroMode", 2)
        set(v) { sp.edit().putInt("heroMode", v).apply() }

    var tagline: Int
        get() = sp.getInt("tagline", 0)
        set(v) { sp.edit().putInt("tagline", v).apply() }

    var showContinue: Boolean
        get() = sp.getBoolean("showContinue", true)
        set(v) { sp.edit().putBoolean("showContinue", v).apply() }

    var order: List<String>
        get() = (sp.getString("order", "") ?: "").split("\n").filter { it.isNotBlank() }
        set(v) { sp.edit().putString("order", v.joinToString("\n")).apply() }

    var hidden: Set<String>
        get() = (sp.getStringSet("hidden", emptySet()) ?: emptySet()).toSet()
        set(v) { sp.edit().putStringSet("hidden", v).apply() }
}

val TAGLINES = listOf(
    "השלט שלך. תשתמש בו בחוכמה.",
    "מה נראה היום?",
    "הכול במרחק לחיצה אחת.",
    "זמן להירגע.",
    "סרט, מוזיקה או סתם שקט?",
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val repo = AppsRepo(app)
    private val watchNext = WatchNextRepo(app)
    val media = MediaWatcher(app)

    var allApps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var order by mutableStateOf(prefs.order)
        private set
    var hidden by mutableStateOf(prefs.hidden)
        private set

    var wallpaperIndex by mutableStateOf(prefs.wallpaper.coerceIn(0, Wallpapers.all.size - 1))
        private set
    var wallpaper by mutableStateOf<ImageBitmap?>(null)
        private set
    var wallpaperBlur by mutableStateOf<ImageBitmap?>(null)
        private set

    var showWeather by mutableStateOf(prefs.showWeather)
        private set
    var showMusic by mutableStateOf(prefs.showMusic)
        private set
    var showClock by mutableStateOf(prefs.showClock)
        private set
    var cityIndex by mutableStateOf(prefs.city.coerceIn(0, CITIES.size - 1))
        private set

    var weather by mutableStateOf<WeatherData?>(null)
        private set

    var userName by mutableStateOf(prefs.userName)
        private set
    var heroMode by mutableStateOf(prefs.heroMode)
        private set
    var taglineIndex by mutableStateOf(prefs.tagline.coerceIn(0, TAGLINES.size - 1))
        private set
    var showContinue by mutableStateOf(prefs.showContinue)
        private set
    var continueItems by mutableStateOf<List<ContinueItem>>(emptyList())
        private set

    var sleepTimerOn by mutableStateOf(false)
        private set

    /** Bumped when the HOME button is pressed so the UI can reset itself. */
    var homeTick by mutableStateOf(0)
        private set

    /** Bumped every time the launcher comes back to the front (to play the "come back" animation). */
    var resumeTick by mutableStateOf(0)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var lastReload = 0L
    private val sleepRunnable = Runnable {
        try {
            media.track.value?.controller?.transportControls?.pause()
        } catch (e: Exception) {
            // ignore
        }
        sleepTimerOn = false
    }

    /** Apps in the user's order, minus the ones they hid. */
    val visibleApps: List<AppEntry>
        get() {
            val index = HashMap<String, Int>()
            order.forEachIndexed { i, p -> index[p] = i }
            return allApps
                .filter { it.pkg !in hidden }
                .sortedBy { index[it.pkg] ?: Int.MAX_VALUE } // stable: unknown apps keep default order at the end
        }

    init {
        setWallpaper(wallpaperIndex)
        reloadApps()
        reloadContinue()
    }

    fun reloadApps(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && allApps.isNotEmpty() && now - lastReload < 60_000L) return
        lastReload = now
        viewModelScope.launch(Dispatchers.Default) {
            val list = repo.load()
            withContext(Dispatchers.Main) { allApps = list }
        }
    }

    fun openApp(app: AppEntry) = repo.launch(app)
    fun openPackage(pkg: String) = repo.launchPackage(pkg)

    fun onHomePressed() {
        homeTick++
    }

    fun onResumed() {
        resumeTick++
        reloadContinue()
        // The menu overlay (opened from the remote) may have changed settings in its own copy of this class.
        reloadFromPrefs()
    }

    private fun reloadFromPrefs() {
        order = prefs.order
        hidden = prefs.hidden
        showWeather = prefs.showWeather
        showMusic = prefs.showMusic
        showClock = prefs.showClock
        userName = prefs.userName
        heroMode = prefs.heroMode
        taglineIndex = prefs.tagline.coerceIn(0, TAGLINES.size - 1)
        showContinue = prefs.showContinue
        val c = prefs.city.coerceIn(0, CITIES.size - 1)
        if (c != cityIndex) {
            cityIndex = c
            weather = null
        }
        val w = prefs.wallpaper.coerceIn(0, Wallpapers.all.size - 1)
        if (w != wallpaperIndex) setWallpaper(w)
    }

    fun setWallpaper(index: Int) {
        val i = index.coerceIn(0, Wallpapers.all.size - 1)
        prefs.wallpaper = i
        wallpaperIndex = i
        viewModelScope.launch(Dispatchers.Default) {
            val sharp = Wallpapers.render(Wallpapers.all[i])
            val soft = Wallpapers.blur(sharp)
            withContext(Dispatchers.Main) {
                wallpaper = sharp.asImageBitmap()
                wallpaperBlur = soft.asImageBitmap()
            }
        }
    }

    fun setShowWeather(v: Boolean) { showWeather = v; prefs.showWeather = v }
    fun setShowMusic(v: Boolean) { showMusic = v; prefs.showMusic = v }
    fun setShowClock(v: Boolean) { showClock = v; prefs.showClock = v }
    fun setShowContinue(v: Boolean) { showContinue = v; prefs.showContinue = v }
    fun setUserName(v: String) { userName = v; prefs.userName = v }
    fun setHeroMode(v: Int) { heroMode = v; prefs.heroMode = v }
    fun setTagline(v: Int) { taglineIndex = v.coerceIn(0, TAGLINES.size - 1); prefs.tagline = taglineIndex }

    fun reloadContinue() {
        if (!showContinue) {
            continueItems = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val list = watchNext.load()
            withContext(Dispatchers.Main) { continueItems = list }
        }
    }

    fun openContinue(item: ContinueItem): Boolean = watchNext.open(item)

    fun setCity(i: Int) {
        val c = i.coerceIn(0, CITIES.size - 1)
        cityIndex = c
        prefs.city = c
        weather = null
    }

    fun refreshWeather() {
        val city = CITIES[cityIndex]
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { WeatherRepo.fetch(city) } }
            val data = result.getOrNull()
            // Ignore a late answer for a city the user has already switched away from.
            if (data != null && CITIES[cityIndex] === city) weather = data
        }
    }

    fun toggleHidden(pkg: String) {
        hidden = if (pkg in hidden) hidden - pkg else hidden + pkg
        prefs.hidden = hidden
    }

    fun moveApp(pkg: String, delta: Int) {
        val list = visibleApps.map { it.pkg }.toMutableList()
        val i = list.indexOf(pkg)
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return
        list.removeAt(i)
        list.add(j, pkg)
        val rest = allApps.map { it.pkg }.filter { it !in list }
        order = list + rest
        prefs.order = order
    }

    fun toggleSleepTimer() {
        if (sleepTimerOn) {
            handler.removeCallbacks(sleepRunnable)
            sleepTimerOn = false
        } else {
            handler.postDelayed(sleepRunnable, 30 * 60 * 1000L)
            sleepTimerOn = true
        }
    }

    override fun onCleared() {
        handler.removeCallbacks(sleepRunnable)
        media.stop()
    }
}
