package com.siso.glasslauncher

import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)

    var wallpaper: Int
        get() = sp.getInt("wallpaper", 0)
        set(v) { sp.edit().putInt("wallpaper", v).apply() }

    /** File name of the user's own photo used as the wallpaper; empty = a built-in wallpaper. */
    var wallpaperPhoto: String
        get() = sp.getString("wallpaperPhoto", "") ?: ""
        set(v) { sp.edit().putString("wallpaperPhoto", v).apply() }

    /** Slow drift, haze and parallax on the wallpaper. */
    var liveWallpaper: Boolean
        get() = sp.getBoolean("liveWallpaper", true)
        set(v) { sp.edit().putBoolean("liveWallpaper", v).apply() }

    /** Lyrics panel in the full-screen music player. */
    var showLyrics: Boolean
        get() = sp.getBoolean("showLyrics", true)
        set(v) { sp.edit().putBoolean("showLyrics", v).apply() }

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

    var showReco: Boolean
        get() = sp.getBoolean("showReco", true)
        set(v) { sp.edit().putBoolean("showReco", v).apply() }

    var showContinue: Boolean
        get() = sp.getBoolean("showContinue", true)
        set(v) { sp.edit().putBoolean("showContinue", v).apply() }

    /** How many times each app was opened from this launcher (drives the "most used" row). */
    var launches: Map<String, Int>
        get() = (sp.getString("launches", "") ?: "").split("\n").mapNotNull {
            val i = it.lastIndexOf('=')
            if (i <= 0) null else it.substring(0, i) to (it.substring(i + 1).toIntOrNull() ?: 0)
        }.toMap()
        set(v) { sp.edit().putString("launches", v.entries.joinToString("\n") { it.key + "=" + it.value }).apply() }

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

/** Special index meaning "use the live sky wallpaper". Stored as -1 in prefs. */
const val LIVE_SKY_INDEX = -1

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val repo = AppsRepo(app)
    private val watchNext = WatchNextRepo(app)
    private val history = WatchHistory(app)
    val media = MediaWatcher(app)

    var allApps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var order by mutableStateOf(prefs.order)
        private set
    var hidden by mutableStateOf(prefs.hidden)
        private set

    // ── Live sky ──────────────────────────────────────────────────────────────
    /** -1 means live sky; ≥0 means a named Wallpapers.all entry */
    var wallpaperIndex by mutableStateOf(prefs.wallpaper.coerceIn(-1, Wallpapers.all.size - 1))
        private set
    var wallpaper by mutableStateOf<ImageBitmap?>(null)
        private set
    var wallpaperBlur by mutableStateOf<ImageBitmap?>(null)
        private set

    /** The user's photo currently used as wallpaper ("" = built-in). */
    var wallpaperPhoto by mutableStateOf(prefs.wallpaperPhoto)
        private set
    var liveWallpaper by mutableStateOf(prefs.liveWallpaper)
        private set
    var showLyrics by mutableStateOf(prefs.showLyrics)
        private set
    /** Index of the focused app on the home row: drives the wallpaper parallax. */
    var homeFocus by mutableStateOf(0)
    /** File names of the user's own wallpaper photos, oldest first. */
    var photos by mutableStateOf<List<String>>(emptyList())
        private set
    val photoThumbs = mutableStateMapOf<String, ImageBitmap>()

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

    // ── Live sky state ────────────────────────────────────────────────────────
    /** Current sky phase (recomputed every minute when live sky is active). */
    var skyPhaseState by mutableStateOf(SkyPhase.MIDDAY)
        private set
    var skyPhaseT by mutableStateOf(0f)
        private set
    private var sunTimes: SunTimes.Times? = null
    private var lastSkyRenderMinute = -1

    // ── Lyrics state ──────────────────────────────────────────────────────────
    var lyrics by mutableStateOf<List<LyricLine>?>(null)
        private set
    var lyricsLoading by mutableStateOf(false)
        private set
    private var lastLyricsTrack: String? = null

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
    var showReco by mutableStateOf(prefs.showReco)
        private set
    var recoItems by mutableStateOf<List<ContinueItem>>(emptyList())
        private set
    private var launchCounts by mutableStateOf(prefs.launches)

    var sleepTimerOn by mutableStateOf(false)
        private set

    /** Bumped when the HOME button is pressed so the UI can reset itself. */
    var homeTick by mutableStateOf(0)
        private set

    /** Bumped when a remote button asks for the "all apps" list. */
    var appsTick by mutableStateOf(0)
        private set
    fun requestApps() { appsTick++ }

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
        // Remember what is playing, so it can be offered as "continue" later.
        viewModelScope.launch {
            var last: Track? = null
            media.track.collect { t ->
                if (t != null && (last == null || last?.title != t.title || last?.appPackage != t.appPackage)) {
                    withContext(Dispatchers.IO) { history.remember(t) }
                    reloadContinue()
                } else if (t == null && last != null) {
                    // playback ended or paused out: store the final position
                    withContext(Dispatchers.IO) { history.remember(last!!) }
                    reloadContinue()
                }
                last = t
            }
        }
        val savedPhoto = prefs.wallpaperPhoto
        if (savedPhoto.isNotEmpty() && PhotoStore.file(app, savedPhoto).exists()) usePhoto(savedPhoto)
        else setWallpaper(wallpaperIndex)
        reloadPhotos()
        reloadApps()
        reloadContinue()
        // Live sky: fetch sun times once and re-render every minute.
        viewModelScope.launch {
            fetchSunTimesAndLocation()
            while (true) {
                tickSky()
                delay(60_000L)
            }
        }
        // Lyrics: watch for track changes
        viewModelScope.launch {
            media.track.collect { t ->
                val key = if (t != null) "${t.title}|${t.artist}" else null
                if (key != lastLyricsTrack) {
                    lastLyricsTrack = key
                    if (t != null && t.title.isNotBlank()) {
                        fetchLyrics(t.title, t.artist, t.durationMs)
                    } else {
                        lyrics = null
                    }
                }
            }
        }
    }

    // ── Live sky ──────────────────────────────────────────────────────────────
    private fun fetchSunTimesAndLocation() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            var lat = 32.0853  // default: Tel Aviv
            var lon = 34.7818
            try {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                // Try GPS first, then network
                for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    @Suppress("MissingPermission")
                    val loc = try { lm.getLastKnownLocation(provider) } catch (e: Exception) { null }
                    if (loc != null) { lat = loc.latitude; lon = loc.longitude; break }
                }
            } catch (e: Exception) { /* use default */ }
            val times = SunTimes.fetch(lat, lon)
            withContext(Dispatchers.Main) {
                sunTimes = times
                tickSky()
            }
        }
    }

    private fun tickSky() {
        if (wallpaperIndex != LIVE_SKY_INDEX || wallpaperPhoto.isNotEmpty()) return
        val cal = Calendar.getInstance()
        val nowMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (nowMins == lastSkyRenderMinute) return
        lastSkyRenderMinute = nowMins
        val sr = sunTimes?.sunriseMins ?: 360   // 06:00 default
        val ss = sunTimes?.sunsetMins ?: 1140   // 19:00 default
        val (phase, t) = skyPhase(nowMins, sr, ss)
        skyPhaseState = phase
        skyPhaseT = t
        viewModelScope.launch(Dispatchers.Default) {
            val bmp = SkyWallpaper.render(phase, t)
            val soft = Wallpapers.blur(bmp)
            withContext(Dispatchers.Main) {
                if (wallpaperPhoto.isEmpty() && wallpaperIndex == LIVE_SKY_INDEX) {
                    wallpaper = bmp.asImageBitmap()
                    wallpaperBlur = soft.asImageBitmap()
                }
            }
        }
    }

    // ── Lyrics ────────────────────────────────────────────────────────────────
    private fun fetchLyrics(title: String, artist: String, durationMs: Long) {
        viewModelScope.launch {
            lyricsLoading = true
            lyrics = null
            val result = LyricsRepo.fetch(title, artist, durationMs)
            lyrics = result
            lyricsLoading = false
        }
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

    fun openApp(app: AppEntry): Boolean {
        val ok = repo.launch(app)
        if (ok) {
            val m = launchCounts.toMutableMap()
            m[app.pkg] = (m[app.pkg] ?: 0) + 1
            launchCounts = m
            prefs.launches = m
        }
        return ok
    }

    /** Apps opened most often, for the "recommended" row when the system gives no real recommendations. */
    val topUsed: List<AppEntry>
        get() = visibleApps
            .filter { (launchCounts[it.pkg] ?: 0) > 0 }
            .sortedByDescending { launchCounts[it.pkg] ?: 0 }
            .take(10)

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
        showReco = prefs.showReco
        val c = prefs.city.coerceIn(0, CITIES.size - 1)
        if (c != cityIndex) {
            cityIndex = c
            weather = null
        }
        val w = prefs.wallpaper.coerceIn(-1, Wallpapers.all.size - 1)
        if (wallpaperPhoto.isEmpty() && w != wallpaperIndex) setWallpaper(w)
    }

    fun setWallpaper(index: Int) {
        val i = index.coerceIn(-1, Wallpapers.all.size - 1)
        if (wallpaperPhoto.isNotEmpty()) {
            prefs.wallpaperPhoto = ""
            wallpaperPhoto = ""
        }
        prefs.wallpaper = i
        wallpaperIndex = i
        if (i == LIVE_SKY_INDEX) {
            lastSkyRenderMinute = -1 // force immediate re-render
            tickSky()
        } else {
            viewModelScope.launch(Dispatchers.Default) {
                val sharp = Wallpapers.render(Wallpapers.all[i])
                val soft = Wallpapers.blur(sharp)
                withContext(Dispatchers.Main) {
                    if (wallpaperPhoto.isEmpty() && wallpaperIndex == i) {
                        wallpaper = sharp.asImageBitmap()
                        wallpaperBlur = soft.asImageBitmap()
                    }
                }
            }
        }
    }

    // ── The user's own photo wallpapers ───────────────────────────────────────

    fun reloadPhotos() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val names = PhotoStore.list(ctx)
            val fresh = names.filter { it !in photoThumbs }.mapNotNull { n ->
                PhotoStore.thumb(ctx, n)?.let { n to it.asImageBitmap() }
            }
            withContext(Dispatchers.Main) {
                fresh.forEach { photoThumbs[it.first] = it.second }
                photos = names
            }
        }
    }

    fun usePhoto(name: String) {
        prefs.wallpaperPhoto = name
        wallpaperPhoto = name
        viewModelScope.launch(Dispatchers.IO) {
            val sharp = PhotoStore.load(getApplication<Application>(), name)
            val soft = if (sharp != null) Wallpapers.blur(sharp) else null
            withContext(Dispatchers.Main) {
                if (wallpaperPhoto == name) {
                    if (sharp != null && soft != null) {
                        wallpaper = sharp.asImageBitmap()
                        wallpaperBlur = soft.asImageBitmap()
                    } else {
                        setWallpaper(wallpaperIndex) // the file is gone or unreadable: back to the built-in one
                    }
                }
            }
        }
    }

    fun deletePhoto(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            PhotoStore.delete(getApplication<Application>(), name)
            withContext(Dispatchers.Main) {
                photoThumbs.remove(name)
                if (wallpaperPhoto == name) setWallpaper(wallpaperIndex)
                reloadPhotos()
            }
        }
    }

    fun toggleLiveWallpaper() {
        liveWallpaper = !liveWallpaper
        prefs.liveWallpaper = liveWallpaper
    }

    fun toggleLyrics() {
        showLyrics = !showLyrics
        prefs.showLyrics = showLyrics
    }

    @JvmName("doShowWeather")

    fun setShowWeather(v: Boolean) { showWeather = v; prefs.showWeather = v }
    @JvmName("doShowMusic")
    fun setShowMusic(v: Boolean) { showMusic = v; prefs.showMusic = v }
    @JvmName("doShowClock")
    fun setShowClock(v: Boolean) { showClock = v; prefs.showClock = v }
    @JvmName("doShowReco")
    fun setShowReco(v: Boolean) { showReco = v; prefs.showReco = v }
    @JvmName("doShowContinue")
    fun setShowContinue(v: Boolean) { showContinue = v; prefs.showContinue = v }
    @JvmName("doUserName")
    fun setUserName(v: String) { userName = v; prefs.userName = v }
    @JvmName("doHeroMode")
    fun setHeroMode(v: Int) { heroMode = v; prefs.heroMode = v }
    fun setTagline(v: Int) { taglineIndex = v.coerceIn(0, TAGLINES.size - 1); prefs.tagline = taglineIndex }

    fun reloadContinue() {
        viewModelScope.launch(Dispatchers.IO) {
            val cont = if (showContinue) (watchNext.load() + history.load()).distinctBy { it.title + "|" + it.provider } else emptyList()
            val reco = if (showReco) watchNext.load("content://android.media.tv/preview_program", limit = 16, perPackage = 4) else emptyList()
            withContext(Dispatchers.Main) {
                continueItems = cont
                recoItems = reco
            }
        }
    }

    fun epgPermission(): Boolean = watchNext.hasPermission()

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
