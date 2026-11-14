package com.siso.glasslauncher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A single lyric line with its timestamp (milliseconds from the start of the track).
 * Lines fetched without timing (plain text lyrics) all have startMs = -1.
 */
data class LyricLine(val startMs: Long, val text: String)

/**
 * Fetches synced lyrics from LRCLIB (https://lrclib.net) — free, no API key needed.
 *
 * Strategy:
 * 1. Try LRCLIB /api/get with title + artist (returns synced .lrc when available).
 * 2. Fall back to plain-text if no synced version.
 * 3. Return null on any failure so the UI just hides the lyrics section.
 */
object LyricsRepo {

    suspend fun fetch(title: String, artist: String, durationMs: Long = 0): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = "https://lrclib.net/api/get"
                val params = buildString {
                    append("track_name=").append(URLEncoder.encode(title, "UTF-8"))
                    append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"))
                    if (durationMs > 0) append("&duration=").append(durationMs / 1000)
                }
                val url = URL("$base?$params")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6_000
                    readTimeout = 6_000
                    setRequestProperty("User-Agent", "GlassLauncher/1.0 (contact@example.com)")
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = JSONObject(body)
                val synced = json.optString("syncedLyrics", "")
                if (synced.isNotBlank()) {
                    parseLrc(synced)
                } else {
                    val plain = json.optString("plainLyrics", "")
                    if (plain.isNotBlank()) parsePlain(plain) else null
                }
            }.getOrNull()
        }

    /** Parse standard .lrc format: [mm:ss.xx] Line text */
    private fun parseLrc(lrc: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        val lineRegex = Regex("""^\[(\d+):(\d+)\.(\d+)]\s*(.*)$""")
        for (line in lrc.lines()) {
            val m = lineRegex.matchEntire(line.trim()) ?: continue
            val min = m.groupValues[1].toLongOrNull() ?: continue
            val sec = m.groupValues[2].toLongOrNull() ?: continue
            val cs  = m.groupValues[3].toLongOrNull() ?: 0L
            val text = m.groupValues[4]
            val ms = min * 60_000L + sec * 1_000L + cs * 10L
            result.add(LyricLine(ms, text))
        }
        return result.sortedBy { it.startMs }
    }

    /** Plain lyrics — keep as lines with startMs = -1 so we can still display them statically. */
    private fun parsePlain(text: String): List<LyricLine> =
        text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { LyricLine(-1L, it) }
}
