package com.siso.glasslauncher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

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

    private const val API = "https://lrclib.net/api"

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    /** GET that returns the body only for a 2xx answer (LRCLIB answers 404 when it has no exact match). */
    private fun httpGet(address: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "GlassLauncher/1.1 (https://github.com/aviadisiso-del/tv-2)")
            }
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().readText() else null
        } catch (e: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Player titles are noisy ("Song (Official Video)", "Song - Remastered 2011", "Artist - Topic").
     * Strip what the lyrics database does not have in its names.
     */
    private fun cleanTitle(t: String): String =
        t.replace(Regex("""[(\[][^)\]]*[)\]]"""), " ")
            .replace(Regex("""(?i)\s+-\s+(remaster(ed)?|live|single|radio edit|mono|stereo|bonus).*$"""), " ")
            .replace(Regex("""(?i)\b(official|lyrics?|video|audio|hd|4k|mv)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun cleanArtist(a: String): String =
        a.replace(Regex("""(?i)\s+-\s+topic$"""), "")
            .replace(Regex("""(?i)\s*(,|&|feat\.?|ft\.?|featuring)\s.*$"""), "")
            .replace(Regex("""(?i)vevo$"""), "")
            .trim()

    private fun linesOf(o: JSONObject): List<LyricLine>? {
        val synced = if (o.isNull("syncedLyrics")) "" else o.optString("syncedLyrics", "")
        if (synced.isNotBlank()) {
            val parsed = parseLrc(synced)
            if (parsed.isNotEmpty()) return parsed
        }
        val plain = if (o.isNull("plainLyrics")) "" else o.optString("plainLyrics", "")
        return if (plain.isNotBlank()) parsePlain(plain) else null
    }

    /** Best hit of a search: synced lyrics first, then the closest track length. */
    private fun search(query: String, durationSec: Long): List<LyricLine>? {
        val body = httpGet("$API/search?$query") ?: return null
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return null
        var best: JSONObject? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val hasSynced = !o.isNull("syncedLyrics") && o.optString("syncedLyrics", "").isNotBlank()
            val hasPlain = !o.isNull("plainLyrics") && o.optString("plainLyrics", "").isNotBlank()
            if (!hasSynced && !hasPlain) continue
            val gap = if (durationSec > 0) abs(o.optDouble("duration", 0.0) - durationSec) else i.toDouble()
            val score = gap + (if (hasSynced) 0.0 else 10_000.0)
            if (score < bestScore) {
                bestScore = score
                best = o
            }
        }
        return best?.let { linesOf(it) }
    }

    /**
     * 1. Exact match (/api/get) with the names as the player reports them.
     * 2. Search by cleaned title + artist.
     * 3. Free-text search (also covers players that put "Artist - Title" in the title and no artist).
     * Returns null when nothing is found, so the player shows "not found".
     */
    suspend fun fetch(title: String, artist: String, durationMs: Long = 0): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val durationSec = if (durationMs > 0) durationMs / 1000 else 0L
                val t = cleanTitle(title).ifBlank { title.trim() }
                val a = cleanArtist(artist)
                var found: List<LyricLine>? = null
                if (artist.isNotBlank() && durationSec > 0) {
                    val exact = httpGet(
                        "$API/get?track_name=" + enc(title) + "&artist_name=" + enc(artist) + "&duration=" + durationSec,
                    )
                    if (exact != null) found = runCatching { linesOf(JSONObject(exact)) }.getOrNull()
                }
                // Plain text only? keep looking for a synced version before settling for it.
                val plainOnly = found?.takeIf { it.none { l -> l.startMs >= 0 } }
                if (found == null || plainOnly != null) {
                    val better = (if (a.isNotBlank()) search("track_name=" + enc(t) + "&artist_name=" + enc(a), durationSec) else null)
                        ?: search("q=" + enc((t + " " + a).trim()), durationSec)
                    if (better != null && (found == null || better.any { l -> l.startMs >= 0 })) found = better
                }
                found
            }.getOrNull()
        }

    /** Parse .lrc: one or more [mm:ss], [mm:ss.xx] or [mm:ss.xxx] stamps, then the line text. */
    private fun parseLrc(lrc: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        val stamp = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?]""")
        for (raw in lrc.lines()) {
            val line = raw.trim()
            val stamps = ArrayList<Long>()
            var rest = line
            while (true) {
                val m = stamp.find(rest)
                if (m == null || m.range.first != 0) break
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val frac = m.groupValues[3]
                val fracMs = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100L
                    2 -> frac.toLong() * 10L
                    else -> frac.take(3).toLong()
                }
                stamps.add(min * 60_000L + sec * 1_000L + fracMs)
                rest = rest.substring(m.range.last + 1).trimStart()
            }
            if (stamps.isEmpty()) continue
            val text = rest.trim()
            if (text.isEmpty()) continue // instrumental gaps are stored as empty lines
            stamps.forEach { result.add(LyricLine(it, text)) }
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
