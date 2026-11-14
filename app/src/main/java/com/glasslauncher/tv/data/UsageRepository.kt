package com.glasslauncher.tv.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * "What did I use recently?" — always from our own launch history, plus Android's
 * usage stats when the user granted Usage Access (install.ps1 does it via appops).
 */
class UsageRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("glass_usage", Context.MODE_PRIVATE)

    fun recordLaunch(packageName: String) {
        prefs.edit()
            .putLong("$TIME$packageName", System.currentTimeMillis())
            .putInt("$COUNT$packageName", prefs.getInt("$COUNT$packageName", 0) + 1)
            .apply()
    }

    fun launchCounts(): Map<String, Int> =
        prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(COUNT) && value is Int) key.removePrefix(COUNT) to value else null
        }.toMap()

    /** packageName -> last time used (epoch millis). Call off the main thread. */
    fun lastUsed(): Map<String, Long> {
        val result = HashMap<String, Long>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(TIME) && value is Long) result[key.removePrefix(TIME)] = value
        }
        if (hasUsageAccess()) {
            runCatching {
                val usm = context.getSystemService(UsageStatsManager::class.java)
                val end = System.currentTimeMillis()
                usm.queryAndAggregateUsageStats(end - LOOKBACK_MS, end).forEach { (pkg, stats) ->
                    if (stats.totalTimeInForeground > 0 && pkg != context.packageName) {
                        val previous = result[pkg] ?: 0L
                        result[pkg] = maxOf(previous, stats.lastTimeUsed)
                    }
                }
            }
        }
        return result
    }

    fun hasUsageAccess(): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private companion object {
        const val TIME = "t:"
        const val COUNT = "c:"
        const val LOOKBACK_MS = 14L * 24 * 60 * 60 * 1000
    }
}
