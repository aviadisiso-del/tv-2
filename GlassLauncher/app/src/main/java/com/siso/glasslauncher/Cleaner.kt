package com.siso.glasslauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import java.io.File
import java.util.Locale

/**
 * Helps free space WITHOUT a computer. An app is not allowed to silently disable system apps,
 * so every action here opens the right Android page where one press does it (disable / uninstall / clear cache).
 * For the one-shot version (disable several apps + clear all caches) use tools/install-tv.ps1.
 */
object Cleaner {

    class Suggest(val pkg: String, val label: String, val why: String)
    class BigApp(val pkg: String, val label: String, val bytes: Long, val system: Boolean)
    class Scan(
        val freeBytes: Long,
        val totalBytes: Long,
        val suggestions: List<Suggest>,
        val biggest: List<BigApp>,
    )

    // Safe to disable on an Android TV box (reversible any time from the same page).
    private val junk = listOf(
        "com.google.android.leanbacklauncher.recommendations" to "המלצות ופרסומות במסך הבית של גוגל",
        "com.google.android.tvrecommendations" to "המלצות של גוגל",
        "com.google.android.backdrop" to "שומר מסך",
        "com.google.android.feedback" to "שליחת משוב",
        "com.google.android.onetimeinitializer" to "אתחול חד-פעמי",
        "com.google.android.partnersetup" to "הגדרת שותפים",
        "com.google.android.play.games" to "Play Games",
        "com.google.android.videos" to "Google Play Movies",
        "com.google.android.music" to "Play Music",
        "com.google.android.youtube.tvmusic" to "YouTube Music",
    )

    fun scan(ctx: Context): Scan {
        val pm = ctx.packageManager
        val stat = StatFs(Environment.getDataDirectory().path)
        val suggestions = ArrayList<Suggest>()
        for ((pkg, why) in junk) {
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                if (!info.enabled) continue
                suggestions.add(Suggest(pkg, pm.getApplicationLabel(info).toString(), why))
            } catch (e: Exception) {
                // not installed
            }
        }
        val big = ArrayList<BigApp>()
        try {
            for (info in pm.getInstalledApplications(0)) {
                if (info.packageName == ctx.packageName) continue
                var size = File(info.sourceDir ?: "").length()
                info.splitSourceDirs?.forEach { size += File(it).length() }
                if (size <= 0L) continue
                val system = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                big.add(BigApp(info.packageName, pm.getApplicationLabel(info).toString(), size, system))
            }
        } catch (e: Exception) {
            // ignore
        }
        big.sortByDescending { it.bytes }
        return Scan(stat.availableBytes, stat.totalBytes, suggestions, big.take(14))
    }

    fun format(bytes: Long): String {
        val mb = bytes / 1_048_576.0
        return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024.0)
        else String.format(Locale.US, "%.0f MB", mb)
    }

    /** Android's own page for the app: Uninstall / Disable / Clear cache / Clear data are all there. */
    fun openAppInfo(ctx: Context, pkg: String) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            openSystem(ctx, Settings.ACTION_APPLICATION_SETTINGS)
        }
    }
}
