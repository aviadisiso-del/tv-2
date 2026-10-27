package com.siso.glasslauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** A procedurally drawn landscape. No image files, no licences, no downloads. */
class WallpaperSpec(
    val name: String,
    val skyTop: Int,
    val skyMid: Int,
    val skyBottom: Int,
    val sun: Int,
    val sunX: Float,
    val sunY: Float,
    val ridges: List<Int>, // far -> near
    val seed: Int,
    val pixelSky: Boolean = false,
    val dunes: Boolean = false,
)

object Wallpapers {

    val all: List<WallpaperSpec> = listOf(
        WallpaperSpec(
            "דיונות",
            0xFF7F9FBF.toInt(), 0xFFB8BFC4.toInt(), 0xFFE8CFB0.toInt(),
            0xFFFFE0B8.toInt(), 0.5f, 0.55f,
            emptyList(), 7, dunes = true,
        ),
        WallpaperSpec(
            "שמי פיקסל",
            0xFF0B2A52.toInt(), 0xFF2F6F9A.toInt(), 0xFFE9A97B.toInt(),
            0xFFFFD9A8.toInt(), 0.2f, 0.8f,
            emptyList(), 5, pixelSky = true,
        ),
        WallpaperSpec(
            "דמדומי אלפים",
            0xFF2E2A63.toInt(), 0xFFB5679A.toInt(), 0xFFF2A65A.toInt(),
            0xFFFFE2B0.toInt(), 0.74f, 0.50f,
            listOf(0xFF8B5E9E.toInt(), 0xFF5D4A8A.toInt(), 0xFF3A3166.toInt(), 0xFF1F1B3D.toInt()), 11,
        ),
        WallpaperSpec(
            "עמק אדום",
            0xFF1E3A6B.toInt(), 0xFF6C8FC7.toInt(), 0xFFF4C58B.toInt(),
            0xFFFFF1C9.toInt(), 0.28f, 0.42f,
            listOf(0xFFB8503A.toInt(), 0xFF9C3B2E.toInt(), 0xFF70261F.toInt(), 0xFF3F1512.toInt()), 23,
        ),
        WallpaperSpec(
            "זוהר צפוני",
            0xFF04121F.toInt(), 0xFF0E5A5A.toInt(), 0xFF3FD0A2.toInt(),
            0xFF9CFFD6.toInt(), 0.5f, 0.30f,
            listOf(0xFF0E3B46.toInt(), 0xFF0A2B38.toInt(), 0xFF061B28.toInt(), 0xFF020E16.toInt()), 37,
        ),
        WallpaperSpec(
            "אובך ים",
            0xFF1B3A5C.toInt(), 0xFF5A8FB5.toInt(), 0xFFE7C9A9.toInt(),
            0xFFFFF3D6.toInt(), 0.62f, 0.55f,
            listOf(0xFF6F93AE.toInt(), 0xFF4F7694.toInt(), 0xFF335A78.toInt(), 0xFF1D3A52.toInt()), 41,
        ),
        WallpaperSpec(
            "מדבר",
            0xFF5A2A4A.toInt(), 0xFFD9785A.toInt(), 0xFFFFC27A.toInt(),
            0xFFFFE9B8.toInt(), 0.35f, 0.52f,
            listOf(0xFFC9694A.toInt(), 0xFFA64F3A.toInt(), 0xFF7A3A2E.toInt(), 0xFF4A241E.toInt()), 53,
        ),
        WallpaperSpec(
            "חצות",
            0xFF05060F.toInt(), 0xFF141838.toInt(), 0xFF3A3F7A.toInt(),
            0xFFB8C0FF.toInt(), 0.80f, 0.25f,
            listOf(0xFF23284F.toInt(), 0xFF181C3B.toInt(), 0xFF0F1228.toInt(), 0xFF070816.toInt()), 67,
        ),
    )

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    fun render(spec: WallpaperSpec, w: Int = 1920, h: Int = 1080): Bitmap {
        if (spec.pixelSky) return renderPixelSky(spec, w, h)
        if (spec.dunes) return renderDunes(spec, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val wf = w.toFloat()
        val hf = h.toFloat()

        // Sky
        paint.shader = LinearGradient(
            0f, 0f, 0f, hf,
            intArrayOf(spec.skyTop, spec.skyMid, spec.skyBottom),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, wf, hf, paint)

        // Sun / moon glow
        paint.shader = RadialGradient(
            spec.sunX * wf, spec.sunY * hf, hf * 0.6f,
            intArrayOf(withAlpha(spec.sun, 0xD0), withAlpha(spec.sun, 0x44), withAlpha(spec.sun, 0x00)),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, wf, hf, paint)

        // Mountain ridges with haze between them
        val rnd = Random(spec.seed)
        spec.ridges.forEachIndexed { i, color ->
            val base = hf * (0.50f + 0.11f * i)
            val amp = hf * (0.24f - 0.035f * i)
            val f1 = 1.2f + rnd.nextFloat() * 1.6f
            val f2 = 2.6f + rnd.nextFloat() * 2.4f
            val f3 = 5.5f + rnd.nextFloat() * 3.0f
            val p1 = rnd.nextFloat() * 6.2832f
            val p2 = rnd.nextFloat() * 6.2832f
            val p3 = rnd.nextFloat() * 6.2832f

            val path = Path()
            path.moveTo(0f, hf)
            var x = 0
            while (x <= w) {
                val t = x.toFloat() / wf
                val n = 0.55f * sin(t * f1 * 6.2832f + p1) +
                    0.30f * sin(t * f2 * 6.2832f + p2) +
                    0.15f * sin(t * f3 * 6.2832f + p3)
                val peak = 1f - abs(n) // pointed peaks where n crosses 0
                path.lineTo(x.toFloat(), base - amp * peak)
                x += 4
            }
            path.lineTo(wf, hf)
            path.close()

            paint.shader = null
            paint.color = color
            canvas.drawPath(path, paint)

            // Haze drifting up from the foot of this ridge
            paint.shader = LinearGradient(
                0f, base - amp * 0.2f, 0f, base + amp * 1.1f,
                withAlpha(spec.skyBottom, 0x00), withAlpha(spec.skyBottom, 0x55),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, base - amp * 0.2f, wf, hf, paint)
        }
        paint.shader = null
        dither(bmp)
        return bmp
    }

    /** Tiny noise that breaks up color banding, which is very visible in smooth gradients on a big TV. */
    private fun dither(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w)
        var seed = 12345
        for (y in 0 until h) {
            bmp.getPixels(px, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                seed = seed * 1664525 + 1013904223
                val n = ((seed ushr 24) % 3) - 1
                val c = px[x]
                val r = (((c shr 16) and 255) + n).coerceIn(0, 255)
                val g = (((c shr 8) and 255) + n).coerceIn(0, 255)
                val b = ((c and 255) + n).coerceIn(0, 255)
                px[x] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(px, 0, w, 0, y, w, 1)
        }
    }

    private fun ridge(c: Canvas, p: Paint, w: Int, h: Int, base: Float, amp: Float, seed: Int, color: Int, haze: Int) {
        val rnd = Random(seed)
        val f1 = 1.2f + rnd.nextFloat() * 1.4f
        val f2 = 3.0f + rnd.nextFloat() * 2.0f
        val f3 = 7.0f + rnd.nextFloat() * 3.0f
        val p1 = rnd.nextFloat() * 6.2832f
        val p2 = rnd.nextFloat() * 6.2832f
        val p3 = rnd.nextFloat() * 6.2832f
        val path = Path()
        path.moveTo(0f, h.toFloat())
        var x = 0
        while (x <= w) {
            val t = x.toFloat() / w
            val n = 0.6f * sin(t * f1 * 6.2832f + p1) + 0.3f * sin(t * f2 * 6.2832f + p2) + 0.1f * sin(t * f3 * 6.2832f + p3)
            path.lineTo(x.toFloat(), base - amp * (0.5f + 0.5f * n))
            x += 3
        }
        path.lineTo(w.toFloat(), h.toFloat())
        path.close()
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = color
        c.drawPath(path, p)
        p.shader = LinearGradient(0f, base - amp, 0f, base + amp * 0.6f, withAlpha(haze, 0x00), withAlpha(haze, 0x66), Shader.TileMode.CLAMP)
        c.drawRect(0f, base - amp, w.toFloat(), h.toFloat(), p)
        p.shader = null
    }

    private fun dune(c: Canvas, p: Paint, w: Int, h: Int, baseY: Float, amp: Float, f: Float, phase: Float, top: Int, bottom: Int) {
        val path = Path()
        val line = Path()
        path.moveTo(0f, h.toFloat())
        var x = 0
        var first = true
        while (x <= w) {
            val t = x.toFloat() / w
            val v = 0.5f + 0.42f * sin(t * f * 6.2832f + phase) + 0.08f * sin(t * f * 2.7f * 6.2832f + phase * 1.7f)
            val y = baseY - amp * v
            path.lineTo(x.toFloat(), y)
            if (first) { line.moveTo(x.toFloat(), y); first = false } else line.lineTo(x.toFloat(), y)
            x += 3
        }
        path.lineTo(w.toFloat(), h.toFloat())
        path.close()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, baseY - amp, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = withAlpha(0xFFFFF3DC.toInt(), 0x66) // light catching the crest of the dune
        c.drawPath(line, p)
        p.style = Paint.Style.FILL
    }

    /** Soft desert dunes and far mountains under a warm sky (like the lock-screen reference). */
    private fun renderDunes(spec: WallpaperSpec, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val wf = w.toFloat()
        val hf = h.toFloat()
        p.shader = LinearGradient(
            0f, 0f, 0f, hf * 0.62f,
            intArrayOf(spec.skyTop, spec.skyMid, spec.skyBottom),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, wf, hf, p)
        p.shader = RadialGradient(
            wf * 0.5f, hf * 0.56f, hf * 0.7f,
            intArrayOf(withAlpha(spec.sun, 0x66), withAlpha(spec.sun, 0x00)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, wf, hf, p)
        p.shader = null
        ridge(c, p, w, h, hf * 0.56f, hf * 0.15f, 3, 0xFF8C8374.toInt(), 0xFFE8CFB0.toInt())
        ridge(c, p, w, h, hf * 0.60f, hf * 0.12f, 9, 0xFF6F6858.toInt(), 0xFFE8CFB0.toInt())
        ridge(c, p, w, h, hf * 0.64f, hf * 0.09f, 15, 0xFF5B5547.toInt(), 0xFFE2C9A8.toInt())
        dune(c, p, w, h, hf * 0.70f, hf * 0.10f, 1.1f, 0.4f, 0xFFEBDCC3.toInt(), 0xFFCDB894.toInt())
        dune(c, p, w, h, hf * 0.82f, hf * 0.13f, 0.8f, 2.1f, 0xFFE2CFB0.toInt(), 0xFFB89E78.toInt())
        dune(c, p, w, h, hf * 0.94f, hf * 0.11f, 1.3f, 4.0f, 0xFFD6BE98.toInt(), 0xFFA68A64.toInt())
        // a little darkness at the bottom keeps the app row readable
        p.shader = LinearGradient(0f, hf * 0.68f, 0f, hf, 0x00000000, 0x66000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, hf * 0.68f, wf, hf, p)
        p.shader = null
        dither(bmp)
        return bmp
    }

    /** Chunky pixel-art clouds + a telephone pole, drawn tiny and scaled up WITHOUT smoothing. */
    private fun renderPixelSky(spec: WallpaperSpec, w: Int, h: Int): Bitmap {
        val sw = 320
        val sh = 180
        val swf = sw.toFloat()
        val shf = sh.toFloat()
        val small = Bitmap.createBitmap(sw * 2, sh * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(small)
        c.scale(2f, 2f) // draw in 320x180 units on a 640x360 canvas: finer pixels, so it looks sharper on a big TV
        val p = Paint() // no anti-aliasing on purpose: that is what makes it look like pixel art

        p.shader = LinearGradient(
            0f, 0f, 0f, shf,
            intArrayOf(spec.skyTop, spec.skyMid, spec.skyBottom),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, swf, shf, p)
        p.shader = null

        val rnd = Random(spec.seed)
        repeat(7) {
            val cx = rnd.nextFloat() * swf
            val cy = shf * (0.28f + rnd.nextFloat() * 0.45f)
            val size = 16f + rnd.nextFloat() * 26f
            val puffs = 7 + rnd.nextInt(5)
            val list = ArrayList<FloatArray>()
            for (i in 0 until puffs) {
                list.add(
                    floatArrayOf(
                        cx + (rnd.nextFloat() - 0.5f) * size * 2.2f,
                        cy + (rnd.nextFloat() - 0.5f) * size * 0.6f,
                        size * (0.35f + rnd.nextFloat() * 0.5f),
                    ),
                )
            }
            p.color = 0xFF55689A.toInt()
            for (q in list) c.drawCircle(q[0], q[1] + q[2] * 0.25f, q[2], p)
            p.color = 0xFFD7A18E.toInt()
            for (q in list) c.drawCircle(q[0] - q[2] * 0.10f, q[1] - q[2] * 0.12f, q[2] * 0.88f, p)
            p.color = 0xFFF6D5B0.toInt()
            for (q in list) c.drawCircle(q[0] - q[2] * 0.25f, q[1] - q[2] * 0.32f, q[2] * 0.55f, p)
        }

        // Telephone pole with two wires
        p.color = 0xFF0A1220.toInt()
        val poleX = swf * 0.22f
        c.drawRect(poleX, shf * 0.38f, poleX + 2f, shf, p)
        c.drawRect(poleX - 9f, shf * 0.42f, poleX + 11f, shf * 0.42f + 2f, p)
        p.strokeWidth = 1f
        c.drawLine(poleX - 9f, shf * 0.43f, 0f, shf * 0.50f, p)
        c.drawLine(poleX + 11f, shf * 0.43f, swf, shf * 0.56f, p)

        // Darker bottom so the rows of apps stay readable
        p.shader = LinearGradient(
            0f, shf * 0.62f, 0f, shf,
            0x00000000, 0xCC050A14.toInt(),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, shf * 0.62f, swf, shf, p)
        p.shader = null

        return Bitmap.createScaledBitmap(small, w, h, false)
    }

    /**
     * Cheap "blur": shrink by half repeatedly (acts like a box filter). Drawn back
     * stretched to full size with bilinear filtering it looks like frosted glass,
     * and it works on every Android version and weak TV GPUs.
     */
    fun blur(src: Bitmap, targetWidth: Int = 56): Bitmap {
        var cur = src
        while (cur.width > targetWidth * 2) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, (cur.height / 2).coerceAtLeast(2), true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        val h = (cur.height * (targetWidth.toFloat() / cur.width)).toInt().coerceAtLeast(2)
        val out = Bitmap.createScaledBitmap(cur, targetWidth, h, true)
        if (cur !== src && cur !== out) cur.recycle()
        return out
    }
}
