package com.glasslauncher.tv.data

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

@Immutable
data class Weather(
    /** The city string the user configured (used to detect a changed setting). */
    val query: String,
    /** Display name from the geocoder, in Hebrew when available. */
    val cityName: String,
    val temperatureC: Int,
    /** WMO weather interpretation code. */
    val code: Int,
    val isDay: Boolean,
    val fetchedAt: Long,
)

/**
 * Open-Meteo (no API key). Geocoding is cached per city so a refresh is one small request.
 * The last result is persisted so the widget shows instantly after a reboot.
 */
class WeatherRepository(context: Context) {

    private val prefs = context.getSharedPreferences("glass_weather", Context.MODE_PRIVATE)

    fun cached(): Weather? {
        val query = prefs.getString("query", null) ?: return null
        return Weather(
            query = query,
            cityName = prefs.getString("city", query) ?: query,
            temperatureC = prefs.getInt("temp", 0),
            code = prefs.getInt("code", 0),
            isDay = prefs.getBoolean("day", true),
            fetchedAt = prefs.getLong("at", 0L),
        )
    }

    suspend fun fetch(query: String): Weather = withContext(Dispatchers.IO) {
        val (lat, lon, name) = geocode(query)
        val json = getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code,is_day&timezone=auto"
        )
        val current = json.getJSONObject("current")
        val weather = Weather(
            query = query,
            cityName = name,
            temperatureC = current.getDouble("temperature_2m").roundToInt(),
            code = current.getInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
            fetchedAt = System.currentTimeMillis(),
        )
        prefs.edit()
            .putString("query", weather.query)
            .putString("city", weather.cityName)
            .putInt("temp", weather.temperatureC)
            .putInt("code", weather.code)
            .putBoolean("day", weather.isDay)
            .putLong("at", weather.fetchedAt)
            .apply()
        weather
    }

    private fun geocode(query: String): Triple<Double, Double, String> {
        if (prefs.getString("geo_query", null) == query) {
            return Triple(
                prefs.getFloat("geo_lat", 0f).toDouble(),
                prefs.getFloat("geo_lon", 0f).toDouble(),
                prefs.getString("geo_name", query) ?: query,
            )
        }
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = getJson(
            "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=he&format=json"
        )
        val hit = json.optJSONArray("results")?.optJSONObject(0)
            ?: throw IllegalArgumentException("City not found: $query")
        val lat = hit.getDouble("latitude")
        val lon = hit.getDouble("longitude")
        val name = hit.optString("name", query)
        prefs.edit()
            .putString("geo_query", query)
            .putFloat("geo_lat", lat.toFloat())
            .putFloat("geo_lon", lon.toFloat())
            .putString("geo_name", name)
            .apply()
        return Triple(lat, lon, name)
    }

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}
