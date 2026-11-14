package com.siso.glasslauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Fetches sunrise / sunset times for a given lat/lon using the free open-meteo API.
 * Returns the results as "minutes since midnight" for the local timezone.
 */
object SunTimes {
    data class Times(val sunriseMins: Int, val sunsetMins: Int)

    suspend fun fetch(lat: Double, lon: Double): Times? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$lat&longitude=$lon" +
                    "&daily=sunrise,sunset&timezone=auto&forecast_days=1"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val daily = JSONObject(body).getJSONObject("daily")
            val sr = daily.getJSONArray("sunrise").getString(0) // "2024-11-14T06:21"
            val ss = daily.getJSONArray("sunset").getString(0)
            Times(toMins(sr), toMins(ss))
        }.getOrNull()
    }

    private fun toMins(iso: String): Int {
        // "YYYY-MM-DDTHH:MM" — take the time part
        val t = iso.substringAfter('T')
        val h = t.substringBefore(':').toIntOrNull() ?: 6
        val m = t.substringAfter(':').toIntOrNull() ?: 0
        return h * 60 + m
    }
}

/** Which lighting phase we are in, based on current clock time + sunrise/sunset. */
enum class SkyPhase {
    NIGHT,          // deep night, stars
    ASTRONOMICAL,   // pre-dawn darkest blue
    NAUTICAL,       // dark blue hint of indigo
    CIVIL,          // horizon starts to orange
    SUNRISE,        // golden bloom on horizon
    MORNING,        // blue sky, sun visible
    MIDDAY,         // pure blue, high sun
    AFTERNOON,      // slightly warmer
    GOLDEN_HOUR,    // rich gold/amber
    SUNSET,         // deep orange/red on horizon
    DUSK,           // mauve/purple after sun
    TWILIGHT,       // fading last light
}

/**
 * Decides the current sky phase given the minute-of-day and sunrise/sunset minutes.
 * Returns a pair (phase, t) where t=0..1 is the blend progress within that phase.
 */
fun skyPhase(nowMins: Int, sunriseMins: Int, sunsetMins: Int): Pair<SkyPhase, Float> {
    val sr = sunriseMins
    val ss = sunsetMins
    // relative minutes from midnight
    fun blend(start: Int, end: Int): Float {
        val span = (end - start).coerceAtLeast(1)
        return ((nowMins - start).toFloat() / span).coerceIn(0f, 1f)
    }
    return when {
        nowMins < sr - 90 || nowMins >= ss + 120 -> SkyPhase.NIGHT to 0f
        nowMins < sr - 60  -> SkyPhase.ASTRONOMICAL to blend(sr - 90, sr - 60)
        nowMins < sr - 30  -> SkyPhase.NAUTICAL     to blend(sr - 60, sr - 30)
        nowMins < sr - 10  -> SkyPhase.CIVIL        to blend(sr - 30, sr - 10)
        nowMins < sr + 10  -> SkyPhase.SUNRISE      to blend(sr - 10, sr + 10)
        nowMins < sr + 90  -> SkyPhase.MORNING      to blend(sr + 10, sr + 90)
        nowMins < ss - 120 -> SkyPhase.MIDDAY       to blend(sr + 90, ss - 120)
        nowMins < ss - 60  -> SkyPhase.AFTERNOON    to blend(ss - 120, ss - 60)
        nowMins < ss - 20  -> SkyPhase.GOLDEN_HOUR  to blend(ss - 60, ss - 20)
        nowMins < ss + 10  -> SkyPhase.SUNSET       to blend(ss - 20, ss + 10)
        nowMins < ss + 40  -> SkyPhase.DUSK         to blend(ss + 10, ss + 40)
        else               -> SkyPhase.TWILIGHT     to blend(ss + 40, ss + 120)
    }
}

/**
 * Procedural sky wallpaper that matches the real time of day at the user's location.
 * Colors are computed on a background thread and the result is a 1920×1080 Bitmap.
 * This replaces / extends Wallpapers — existing named wallpapers still work, this adds
 * the special "LiveSky" entry at index 0.
 */
object SkyWallpaper {

    /**
     * Render a 1920×1080 sky for the given phase and blend factor.
     * [t] 0→1 is how far we are into the given phase.
     */
    fun render(phase: SkyPhase, t: Float, w: Int = 1920, h: Int = 1080): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint  = Paint(Paint.ANTI_ALIAS_FLAG)
        val wf = w.toFloat(); val hf = h.toFloat()

        // ── Sky gradient colors for every phase ──────────────────────────────────
        val (top, mid, bot, sunColor, sunX, sunY, hazeColor) = when (phase) {
            SkyPhase.NIGHT -> SkyColors(
                0xFF020408.toInt(), 0xFF07101E.toInt(), 0xFF0D1A2E.toInt(),
                0xFFBBC8FF.toInt(), 0.5f, 0.15f, 0xFF050C1A.toInt()
            )
            SkyPhase.ASTRONOMICAL -> SkyColors(
                lerpc(0xFF020408.toInt(), 0xFF041020.toInt(), t),
                lerpc(0xFF07101E.toInt(), 0xFF0A1830.toInt(), t),
                lerpc(0xFF0D1A2E.toInt(), 0xFF132240.toInt(), t),
                0xFFD0D8FF.toInt(), 0.5f, 0.10f, 0xFF0A1428.toInt()
            )
            SkyPhase.NAUTICAL -> SkyColors(
                lerpc(0xFF041020.toInt(), 0xFF0B1F3A.toInt(), t),
                lerpc(0xFF0A1830.toInt(), 0xFF1A3A5A.toInt(), t),
                lerpc(0xFF132240.toInt(), 0xFF2A5070.toInt(), t),
                0xFFE0E8FF.toInt(), 0.5f, 0.08f, 0xFF162840.toInt()
            )
            SkyPhase.CIVIL -> SkyColors(
                lerpc(0xFF0B1F3A.toInt(), 0xFF1A2F50.toInt(), t),
                lerpc(0xFF1A3A5A.toInt(), 0xFF4A6080.toInt(), t),
                lerpc(0xFF2A5070.toInt(), 0xFFE8A060.toInt(), t),
                0xFFFFD090.toInt(), 0.5f, lerp(0.08f, 0.88f, t), 0xFF3A5070.toInt()
            )
            SkyPhase.SUNRISE -> SkyColors(
                lerpc(0xFF1A2F50.toInt(), 0xFF2F4A70.toInt(), t),
                lerpc(0xFFE8701A.toInt(), 0xFF4A80C0.toInt(), t),
                lerpc(0xFFFFD090.toInt(), 0xFFFFA040.toInt(), t),
                0xFFFFE08A.toInt(), 0.5f, lerp(0.88f, 0.72f, t), 0xFF4A5A70.toInt()
            )
            SkyPhase.MORNING -> SkyColors(
                lerpc(0xFF2F4A70.toInt(), 0xFF4080C0.toInt(), t),
                lerpc(0xFF4A80C0.toInt(), 0xFF6AAAE0.toInt(), t),
                lerpc(0xFFFFA040.toInt(), 0xFFB0D8F8.toInt(), t),
                0xFFFFEAAA.toInt(), 0.5f, lerp(0.72f, 0.55f, t), 0xFF5080A0.toInt()
            )
            SkyPhase.MIDDAY -> SkyColors(
                0xFF3A7AC8.toInt(), 0xFF5A9AE0.toInt(), 0xFF90C8F8.toInt(),
                0xFFFFF8D0.toInt(), 0.5f, 0.22f, 0xFF78AACC.toInt()
            )
            SkyPhase.AFTERNOON -> SkyColors(
                lerpc(0xFF3A7AC8.toInt(), 0xFF4870B8.toInt(), t),
                lerpc(0xFF5A9AE0.toInt(), 0xFF7090C0.toInt(), t),
                lerpc(0xFF90C8F8.toInt(), 0xFFE8C880.toInt(), t),
                0xFFFFF0A0.toInt(), 0.5f, lerp(0.22f, 0.42f, t), 0xFF607090.toInt()
            )
            SkyPhase.GOLDEN_HOUR -> SkyColors(
                lerpc(0xFF4870B8.toInt(), 0xFF7A4820.toInt(), t),
                lerpc(0xFF7090C0.toInt(), 0xFFD07030.toInt(), t),
                lerpc(0xFFE8C880.toInt(), 0xFFFF9040.toInt(), t),
                0xFFFFD060.toInt(), 0.5f, lerp(0.42f, 0.65f, t), 0xFF804030.toInt()
            )
            SkyPhase.SUNSET -> SkyColors(
                lerpc(0xFF7A4820.toInt(), 0xFF4A1A30.toInt(), t),
                lerpc(0xFFD07030.toInt(), 0xFFE04040.toInt(), t),
                lerpc(0xFFFF9040.toInt(), 0xFFFF6020.toInt(), t),
                0xFFFF8040.toInt(), 0.5f, lerp(0.65f, 0.88f, t), 0xFF502030.toInt()
            )
            SkyPhase.DUSK -> SkyColors(
                lerpc(0xFF4A1A30.toInt(), 0xFF200A30.toInt(), t),
                lerpc(0xFFE04040.toInt(), 0xFF702060.toInt(), t),
                lerpc(0xFFFF6020.toInt(), 0xFFB04040.toInt(), t),
                0xFFFF6080.toInt(), 0.5f, lerp(0.88f, 0.96f, t), 0xFF301030.toInt()
            )
            SkyPhase.TWILIGHT -> SkyColors(
                lerpc(0xFF200A30.toInt(), 0xFF080412.toInt(), t),
                lerpc(0xFF702060.toInt(), 0xFF200830.toInt(), t),
                lerpc(0xFFB04040.toInt(), 0xFF280C28.toInt(), t),
                0xFFBBC8FF.toInt(), 0.5f, lerp(0.96f, 0.15f, t), 0xFF180820.toInt()
            )
        }

        // ── Sky ──────────────────────────────────────────────────────────────────
        paint.shader = LinearGradient(
            0f, 0f, 0f, hf,
            intArrayOf(top, mid, bot),
            floatArrayOf(0f, 0.50f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, wf, hf, paint)

        // ── Stars (night + twilight) ──────────────────────────────────────────────
        val starAlpha = when (phase) {
            SkyPhase.NIGHT -> 1f
            SkyPhase.ASTRONOMICAL -> 1f - t * 0.4f
            SkyPhase.TWILIGHT -> t * 0.8f
            SkyPhase.NAUTICAL -> (1f - t) * 0.5f
            else -> 0f
        }
        if (starAlpha > 0.01f) {
            paint.shader = null
            var seed = 0x1234ABCD.toInt()
            repeat(180) {
                seed = seed * 1664525 + 1013904223
                val sx = ((seed ushr 8) and 0xFFF).toFloat() / 0xFFF.toFloat() * wf
                seed = seed * 1664525 + 1013904223
                val sy = ((seed ushr 8) and 0xFFF).toFloat() / 0xFFF.toFloat() * hf * 0.65f
                seed = seed * 1664525 + 1013904223
                val radius = 1.2f + ((seed ushr 28) and 3).toFloat() * 0.5f
                val brightness = 0.5f + ((seed ushr 24) and 15).toFloat() / 30f
                paint.color = android.graphics.Color.argb(
                    (starAlpha * brightness * 240).toInt().coerceIn(0, 255),
                    240, 240, 255
                )
                canvas.drawCircle(sx, sy, radius, paint)
            }
        }

        // ── Sun / moon glow ───────────────────────────────────────────────────────
        val sunAlpha = when (phase) {
            SkyPhase.NIGHT, SkyPhase.ASTRONOMICAL -> 0.4f
            SkyPhase.TWILIGHT -> 0.3f + t * 0.1f
            else -> 0.9f
        }
        paint.shader = RadialGradient(
            sunX * wf, sunY * hf, hf * 0.65f,
            intArrayOf(
                withAlpha(sunColor, (sunAlpha * 0xCC).toInt()),
                withAlpha(sunColor, (sunAlpha * 0x44).toInt()),
                withAlpha(sunColor, 0x00),
            ),
            floatArrayOf(0f, 0.30f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, wf, hf, paint)

        // ── Ground/horizon haze ───────────────────────────────────────────────────
        paint.shader = LinearGradient(
            0f, hf * 0.55f, 0f, hf * 0.80f,
            withAlpha(hazeColor, 0x00),
            withAlpha(hazeColor, 0x88),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, hf * 0.55f, wf, hf * 0.80f, paint)

        // ── Mountains (silhouette, vary by time-of-day lighting) ─────────────────
        val ridgeColor1 = when (phase) {
            SkyPhase.SUNRISE, SkyPhase.SUNSET -> lerpc(0xFF2A1A30.toInt(), 0xFF4A3050.toInt(), t)
            SkyPhase.GOLDEN_HOUR -> 0xFF5A3A28.toInt()
            SkyPhase.MIDDAY, SkyPhase.MORNING -> 0xFF3A5878.toInt()
            SkyPhase.AFTERNOON -> 0xFF4A5070.toInt()
            SkyPhase.NIGHT -> 0xFF0A0C14.toInt()
            else -> 0xFF1A2030.toInt()
        }
        val ridgeColor2 = when (phase) {
            SkyPhase.SUNRISE, SkyPhase.SUNSET -> lerpc(0xFF1A0A20.toInt(), 0xFF301828.toInt(), t)
            SkyPhase.GOLDEN_HOUR -> 0xFF3A2818.toInt()
            SkyPhase.MIDDAY, SkyPhase.MORNING -> 0xFF284868.toInt()
            SkyPhase.AFTERNOON -> 0xFF384060.toInt()
            SkyPhase.NIGHT -> 0xFF060810.toInt()
            else -> 0xFF101820.toInt()
        }
        paint.shader = null
        drawRidge(canvas, paint, w, h, hf * 0.52f, hf * 0.22f, 11, ridgeColor1, bot)
        drawRidge(canvas, paint, w, h, hf * 0.63f, hf * 0.16f, 23, ridgeColor2, bot)

        // ── Bottom darkening so the app bar stays readable ────────────────────────
        paint.shader = LinearGradient(0f, hf * 0.70f, 0f, hf, 0x00000000, 0x88000000, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, hf * 0.70f, wf, hf, paint)
        paint.shader = null

        Wallpapers.dither2(bmp)
        return bmp
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private data class SkyColors(
        val top: Int, val mid: Int, val bot: Int,
        val sunColor: Int, val sunX: Float, val sunY: Float,
        val hazeColor: Int,
    )

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
    private fun lerpc(a: Int, b: Int, t: Float): Int {
        val tc = t.coerceIn(0f, 1f)
        val ar = (a shr 16) and 255; val ag = (a shr 8) and 255; val ab = a and 255
        val br = (b shr 16) and 255; val bg = (b shr 8) and 255; val bb = b and 255
        val r = (ar + (br - ar) * tc).toInt()
        val g = (ag + (bg - ag) * tc).toInt()
        val bl2 = (ab + (bb - ab) * tc).toInt()
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or bl2
    }
    private fun withAlpha(color: Int, alpha: Int) =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    private fun drawRidge(
        c: Canvas, p: Paint,
        w: Int, h: Int,
        base: Float, amp: Float,
        seed: Int, color: Int, haze: Int,
    ) {
        val rnd = kotlin.random.Random(seed)
        val f1 = 1.1f + rnd.nextFloat() * 1.4f
        val f2 = 2.8f + rnd.nextFloat() * 2.0f
        val f3 = 6.0f + rnd.nextFloat() * 3.0f
        val p1 = rnd.nextFloat() * 6.2832f
        val p2 = rnd.nextFloat() * 6.2832f
        val p3 = rnd.nextFloat() * 6.2832f
        val path = Path()
        path.moveTo(0f, h.toFloat())
        var x = 0
        while (x <= w) {
            val t2 = x.toFloat() / w
            val n = 0.55f * sin(t2 * f1 * 6.2832f + p1) +
                    0.30f * sin(t2 * f2 * 6.2832f + p2) +
                    0.15f * sin(t2 * f3 * 6.2832f + p3)
            path.lineTo(x.toFloat(), base - amp * (0.5f + 0.5f * abs(n)))
            x += 4
        }
        path.lineTo(w.toFloat(), h.toFloat())
        path.close()
        p.shader = null
        p.color = color
        c.drawPath(path, p)
        p.shader = LinearGradient(
            0f, base - amp * 0.2f, 0f, base + amp * 1.2f,
            withAlpha(haze, 0x00), withAlpha(haze, 0x55), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, base - amp * 0.2f, w.toFloat(), h.toFloat(), p)
        p.shader = null
    }
}
