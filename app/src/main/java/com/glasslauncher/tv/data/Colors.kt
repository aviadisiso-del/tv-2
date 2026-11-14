package com.glasslauncher.tv.data

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor

/**
 * Saturation-weighted average of an 8x8 downsample, pushed to a vivid, bright-enough
 * color so it reads as a tint on a near-black background. Grayscale art -> [fallback].
 * Shared by app banners and album art.
 */
fun vividColorOf(source: Bitmap, fallback: Int): Int {
    val small = Bitmap.createScaledBitmap(source, 8, 8, true)
    val hsv = FloatArray(3)
    var r = 0f
    var g = 0f
    var b = 0f
    var total = 0f
    for (y in 0 until small.height) {
        for (x in 0 until small.width) {
            val p = small.getPixel(x, y)
            if (AndroidColor.alpha(p) < 128) continue
            AndroidColor.colorToHSV(p, hsv)
            val w = hsv[1] * hsv[2] + 0.04f
            r += AndroidColor.red(p) * w
            g += AndroidColor.green(p) * w
            b += AndroidColor.blue(p) * w
            total += w
        }
    }
    if (small !== source) small.recycle()
    if (total == 0f) return fallback
    val avg = AndroidColor.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt())
    AndroidColor.colorToHSV(avg, hsv)
    if (hsv[1] < 0.12f) return fallback
    hsv[1] = hsv[1].coerceAtLeast(0.5f)
    hsv[2] = 0.85f
    return AndroidColor.HSVToColor(hsv)
}
