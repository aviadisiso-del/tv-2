package com.glasslauncher.tv.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    /** TV apps first (alphabetical), then phone-only apps that are still launchable. */
    suspend fun loadApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val seen = HashSet<String>()
        val result = ArrayList<AppEntry>()
        val candidates = query(Intent.CATEGORY_LEANBACK_LAUNCHER).map { it to true } +
            query(Intent.CATEGORY_LAUNCHER).map { it to false }

        for ((info, isTv) in candidates) {
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName) continue
            if (!seen.add(pkg)) continue
            toEntry(info, isTv)?.let(result::add)
        }
        result.sortedWith(
            compareByDescending<AppEntry> { it.isTvApp }.thenBy { it.label.lowercase() }
        )
    }

    fun launchIntent(app: AppEntry): Intent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(app.packageName, app.activityName)
        addCategory(if (app.isTvApp) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    private fun query(category: String): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        return if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
    }

    private fun toEntry(info: ResolveInfo, isTv: Boolean): AppEntry? = runCatching {
        val activity = info.activityInfo
        val bannerDrawable = activity.loadBanner(pm) ?: activity.applicationInfo.loadBanner(pm)
        val banner = bannerDrawable?.toBitmap(BANNER_W, BANNER_H)
        val icon = activity.loadIcon(pm).toBitmap(ICON_PX, ICON_PX)
        AppEntry(
            packageName = activity.packageName,
            activityName = activity.name,
            label = info.loadLabel(pm).toString(),
            banner = banner?.asImageBitmap(),
            icon = icon.asImageBitmap(),
            isTvApp = isTv,
            // Never let color sampling (e.g. a HARDWARE bitmap) drop the app from the list.
            tint = Color(runCatching { vividColorOf(banner ?: icon, DEFAULT_TINT) }.getOrDefault(DEFAULT_TINT)),
            category = activity.applicationInfo.category,
        )
    }.getOrNull()

    private companion object {
        // Native Android TV banner size; scaled once, never re-decoded.
        const val BANNER_W = 320
        const val BANNER_H = 180
        const val ICON_PX = 144
        const val DEFAULT_TINT = 0xFF2E8BFF.toInt()
    }
}
