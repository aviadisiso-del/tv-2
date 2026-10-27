package com.siso.glasslauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

// ───────────────────────── Apps ─────────────────────────

class AppEntry(
    val pkg: String,
    val component: ComponentName,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    val isLeanback: Boolean,
)

class AppsRepo(private val ctx: Context) {

    fun load(): List<AppEntry> {
        val pm = ctx.packageManager
        val seen = HashSet<String>()
        val leanback = ArrayList<AppEntry>()
        val other = ArrayList<AppEntry>()

        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val isLeanback = category == Intent.CATEGORY_LEANBACK_LAUNCHER
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            val infos = try {
                pm.queryIntentActivities(query, 0)
            } catch (e: Exception) {
                emptyList()
            }
            for (ri in infos) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName) continue
                if (!seen.add(pkg)) continue
                val label = ri.loadLabel(pm)?.toString() ?: pkg
                val banner = if (isLeanback) {
                    try {
                        ri.activityInfo.loadBanner(pm)?.toBitmap(320, 180)?.asImageBitmap()
                    } catch (e: Exception) {
                        null
                    }
                } else null
                val icon = try {
                    ri.loadIcon(pm)?.toBitmap(160, 160)?.asImageBitmap()
                } catch (e: Exception) {
                    null
                }
                val entry = AppEntry(
                    pkg = pkg,
                    component = ComponentName(pkg, ri.activityInfo.name),
                    label = label,
                    banner = banner,
                    icon = icon,
                    isLeanback = isLeanback,
                )
                if (isLeanback) leanback.add(entry) else other.add(entry)
            }
        }
        return leanback.sortedBy { it.label.lowercase() } + other.sortedBy { it.label.lowercase() }
    }

    /** Returns false if the app could not be started (so the UI can undo its launch animation). */
    fun launch(app: AppEntry): Boolean {
        val category = if (app.isLeanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(category)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            launchPackage(app.pkg)
        }
    }

    fun launchPackage(pkg: String): Boolean {
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}

// ───────────────────────── Weather (Open-Meteo, no API key) ─────────────────────────

class City(val name: String, val lat: Double, val lon: Double)

val CITIES = listOf(
    City("ירושלים", 31.7683, 35.2137),
    City("תל אביב", 32.0853, 34.7818),
    City("חיפה", 32.7940, 34.9896),
    City("באר שבע", 31.2530, 34.7915),
    City("אילת", 29.5577, 34.9519),
    City("נתניה", 32.3215, 34.8532),
    City("קופנהגן", 55.6761, 12.5683),
    City("לונדון", 51.5072, -0.1276),
    City("ניו יורק", 40.7128, -74.0060),
)

class HourPoint(val label: String, val temp: Int, val code: Int, val rain: Int)

class WeatherData(
    val temp: Int,
    val feels: Int,
    val code: Int,
    val wind: Int,
    val isDay: Boolean,
    val high: Int,
    val low: Int,
    val hours: List<HourPoint>,
)

fun weatherEmoji(code: Int, isDay: Boolean = true): String = when (code) {
    0 -> if (isDay) "☀️" else "🌙"
    1 -> if (isDay) "🌤️" else "🌙"
    2 -> "⛅"
    3 -> "☁️"
    45, 48 -> "🌫️"
    in 51..57 -> "🌦️"
    in 61..67 -> "🌧️"
    in 71..77 -> "❄️"
    in 80..82 -> "🌧️"
    85, 86 -> "❄️"
    in 95..99 -> "⛈️"
    else -> "🌡️"
}

fun weatherText(code: Int): String = when (code) {
    0 -> "שמיים בהירים"
    1 -> "בהיר ברובו"
    2 -> "מעונן חלקית"
    3 -> "מעונן"
    45, 48 -> "ערפל"
    in 51..57 -> "טפטוף"
    in 61..67 -> "גשם"
    in 71..77 -> "שלג"
    in 80..82 -> "ממטרים"
    85, 86 -> "ממטרי שלג"
    in 95..99 -> "סופת רעמים"
    else -> "—"
}

object WeatherRepo {
    /** Blocking. Call from a background dispatcher. */
    fun fetch(city: City): WeatherData {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${city.lat}&longitude=${city.lon}" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,is_day" +
            "&hourly=temperature_2m,weather_code,precipitation_probability" +
            "&daily=temperature_2m_max,temperature_2m_min" +
            "&forecast_days=2&timezone=auto"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val cur = root.getJSONObject("current")
            val nowIso = cur.getString("time") // e.g. 2026-10-25T15:45

            val hourly = root.getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val temps = hourly.getJSONArray("temperature_2m")
            val codes = hourly.getJSONArray("weather_code")
            val rain = hourly.getJSONArray("precipitation_probability")
            val hours = ArrayList<HourPoint>()
            for (i in 0 until times.length()) {
                val t = times.getString(i)
                if (t > nowIso && hours.size < 8) {
                    hours.add(
                        HourPoint(
                            label = t.substring(11, 16),
                            temp = temps.optDouble(i, 0.0).toInt(),
                            code = codes.optInt(i, 0),
                            rain = if (rain.isNull(i)) 0 else rain.optInt(i, 0),
                        ),
                    )
                }
            }
            val daily = root.getJSONObject("daily")
            return WeatherData(
                temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
                feels = Math.round(cur.getDouble("apparent_temperature")).toInt(),
                code = cur.getInt("weather_code"),
                wind = Math.round(cur.getDouble("wind_speed_10m")).toInt(),
                isDay = cur.optInt("is_day", 1) == 1,
                high = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
                low = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
                hours = hours,
            )
        } finally {
            conn.disconnect()
        }
    }
}

// ───────────────────────── Now playing ─────────────────────────

class Track(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val durationMs: Long,
    val positionMs: Long,
    val updatedAtElapsed: Long,
    val speed: Float,
    val playing: Boolean,
    val appPackage: String,
    val controller: MediaController,
) {
    fun currentPositionMs(): Long {
        if (!playing) return positionMs
        val delta = ((SystemClock.elapsedRealtime() - updatedAtElapsed) * speed).toLong()
        val p = positionMs + delta
        return if (durationMs > 0) p.coerceIn(0L, durationMs) else p.coerceAtLeast(0L)
    }
}

/** Needed only so Android lets us read active media sessions once the user grants notification access. */
class MediaNotificationListener : NotificationListenerService()

class MediaWatcher(private val ctx: Context) {

    val track = MutableStateFlow<Track?>(null)
    val hasAccess = MutableStateFlow(false)

    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(ctx, MediaNotificationListener::class.java)
    private var controllers: List<MediaController> = emptyList()
    private var started = false

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() = refresh()
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list ->
            setControllers(list ?: emptyList())
        }

    fun start() {
        val granted = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        hasAccess.value = granted
        if (!granted) {
            stop()
            track.value = null
            return
        }
        try {
            if (!started) {
                msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
                started = true
            }
            setControllers(msm.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            hasAccess.value = false
            track.value = null
        }
    }

    fun stop() {
        if (started) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (e: Exception) {
                // ignore
            }
            started = false
        }
        for (c in controllers) {
            try {
                c.unregisterCallback(callback)
            } catch (e: Exception) {
                // ignore
            }
        }
        controllers = emptyList()
    }

    private fun setControllers(list: List<MediaController>) {
        for (c in controllers) {
            try {
                c.unregisterCallback(callback)
            } catch (e: Exception) {
                // ignore
            }
        }
        controllers = list
        for (c in controllers) {
            try {
                c.registerCallback(callback)
            } catch (e: Exception) {
                // ignore
            }
        }
        refresh()
    }

    private fun refresh() {
        val chosen = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull {
                val s = it.playbackState?.state
                it.metadata != null && (s == PlaybackState.STATE_PAUSED || s == PlaybackState.STATE_BUFFERING)
            }
        if (chosen == null) {
            track.value = null
            return
        }
        val md = chosen.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (md == null || title == null) {
            track.value = null
            return
        }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val ps = chosen.playbackState
        track.value = Track(
            title = title,
            artist = artist,
            art = art,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = ps?.position ?: 0L,
            updatedAtElapsed = ps?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = ps?.playbackSpeed ?: 1f,
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            appPackage = chosen.packageName,
            controller = chosen,
        )
    }
}

fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}


// ───────────────────────── "Continue watching" (Android TV Watch Next) ─────────────────────────

class ContinueItem(
    val title: String,
    val subtitle: String,
    val provider: String,
    val poster: ImageBitmap?,
    val intent: Intent?,
    val progress: Float,
)

/**
 * Reads the system "Watch Next" list that Netflix, YouTube, Disney+ … fill in.
 * Needs the READ_EPG_DATA permission (the setup script grants it). Without it the row simply stays empty.
 */
class WatchNextRepo(private val ctx: Context) {

    /** [uriText] is the Watch Next table by default; pass the preview_program table for channel recommendations. */
    fun load(
        uriText: String = "content://android.media.tv/watch_next_program",
        limit: Int = 12,
        perPackage: Int = 99,
    ): List<ContinueItem> {
        val out = ArrayList<ContinueItem>()
        val perPkg = HashMap<String, Int>()
        try {
            val uri = Uri.parse(uriText)
            val projection = arrayOf(
                "title", "short_description", "poster_art_uri", "thumbnail_uri",
                "intent_uri", "last_playback_position_millis", "duration_millis", "package_name",
            )
            ctx.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val title = c.getString(0)
                    if (title.isNullOrBlank()) continue
                    val intentUri = c.getString(4)
                    val intent = try {
                        if (intentUri.isNullOrBlank()) null
                        else Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    } catch (e: Exception) {
                        null
                    }
                    if (intent == null) continue
                    val pkg = c.getString(7) ?: ""
                    val used = perPkg[pkg] ?: 0
                    if (used >= perPackage) continue
                    perPkg[pkg] = used + 1
                    val label = try {
                        val pm = ctx.packageManager
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (e: Exception) {
                        ""
                    }
                    val pos = c.getLong(5)
                    val dur = c.getLong(6)
                    out.add(
                        ContinueItem(
                            title = title,
                            subtitle = c.getString(1) ?: "",
                            provider = label,
                            poster = loadImage(c.getString(2) ?: c.getString(3)),
                            intent = intent,
                            progress = if (dur > 0) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            // no permission / provider missing: the row just stays hidden
        }
        return out
    }

    private fun loadImage(uriText: String?): ImageBitmap? {
        if (uriText.isNullOrBlank()) return null
        return try {
            val stream = if (uriText.startsWith("http")) {
                val conn = URL(uriText).openConnection() as HttpURLConnection
                conn.connectTimeout = 6_000
                conn.readTimeout = 6_000
                conn.inputStream
            } else {
                ctx.contentResolver.openInputStream(Uri.parse(uriText))
            }
            stream?.use { s ->
                val bmp = BitmapFactory.decodeStream(s) ?: return null
                val targetW = 320
                val scaled = if (bmp.width > targetW) {
                    Bitmap.createScaledBitmap(bmp, targetW, (bmp.height * targetW.toFloat() / bmp.width).toInt().coerceAtLeast(1), true)
                } else bmp
                scaled.asImageBitmap()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun open(item: ContinueItem): Boolean {
        val i = item.intent ?: return false
        return try {
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }
}
