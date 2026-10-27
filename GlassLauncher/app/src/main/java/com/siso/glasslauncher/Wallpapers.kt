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
)

object Wallpapers {

    val all: List<WallpaperSpec> = listOf(
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

    fun render(spec: WallpaperSpec, w: Int = 960, h: Int = 540): Bitmap {
        if (spec.pixelSky) return renderPixelSky(spec, w, h)
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
        return bmp
    }

    /** Chunky pixel-art clouds + a telephone pole, drawn tiny and scaled up WITHOUT smoothing. */
    private fun renderPixelSky(spec: WallpaperSpec, w: Int, h: Int): Bitmap {
        val sw = 320
        val sh = 180
        val swf = sw.toFloat()
        val shf = sh.toFloat()
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val c = Canvas(small)
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
