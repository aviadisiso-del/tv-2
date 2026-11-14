package com.siso.glasslauncher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * Our own "continue watching": whatever Netflix / YouTube / Spotify... reported as playing is remembered here,
 * so it is still on the home page after the app closes. (The system's own list is empty on this streamer.)
 */
class WatchHistory(private val ctx: Context) {

    private val sp = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val dir = File(ctx.filesDir, "hist").apply { mkdirs() }

    private class Row(val pkg: String, val title: String, val artist: String, val progress: Float, val time: Long)

    private fun rows(): MutableList<Row> =
        (sp.getString("rows", "") ?: "").split("\n").mapNotNull {
            val p = it.split("\t")
            if (p.size < 5) null else Row(p[0], p[1], p[2], p[3].toFloatOrNull() ?: 0f, p[4].toLongOrNull() ?: 0L)
        }.toMutableList()

    private fun key(pkg: String, title: String) = (pkg + "|" + title).hashCode().toString()

    @Synchronized
    fun remember(t: Track) {
        if (t.title.isBlank()) return
        val progress = if (t.durationMs > 0) (t.currentPositionMs().toFloat() / t.durationMs).coerceIn(0f, 1f) else 0f
        val list = rows()
        list.removeAll { it.pkg == t.appPackage && it.title == t.title }
        list.add(0, Row(t.appPackage, t.title.replace('\t', ' '), t.artist.replace('\t', ' '), progress, System.currentTimeMillis()))
        while (list.size > 12) {
            val gone = list.removeAt(list.size - 1)
            File(dir, key(gone.pkg, gone.title) + ".jpg").delete()
        }
        sp.edit().putString("rows", list.joinToString("\n") { "${it.pkg}\t${it.title}\t${it.artist}\t${it.progress}\t${it.time}" }).apply()
        t.art?.let { art ->
            try {
                val w = 320
                val h = (art.height * w.toFloat() / art.width).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(art, w, h, true)
                File(dir, key(t.appPackage, t.title) + ".jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun load(): List<ContinueItem> {
        val pm = ctx.packageManager
        return rows().mapNotNull { r ->
            val launch = pm.getLeanbackLaunchIntentForPackage(r.pkg) ?: pm.getLaunchIntentForPackage(r.pkg) ?: return@mapNotNull null
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(r.pkg, 0)).toString()
            } catch (e: Exception) {
                ""
            }
            val f = File(dir, key(r.pkg, r.title) + ".jpg")
            val poster = if (f.exists()) BitmapFactory.decodeFile(f.path)?.asImageBitmap() else null
            ContinueItem(
                title = r.title,
                subtitle = r.artist,
                provider = label,
                poster = poster,
                intent = launch,
                progress = if (r.progress in 0.02f..0.97f) r.progress else 0f,
            )
        }
    }
}
