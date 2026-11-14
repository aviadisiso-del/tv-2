package com.glasslauncher.tv.data

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.storage.StorageManager
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

data class CleanerScan(
    /** Our own temporary files (cleanable). */
    val ownCacheBytes: Long,
    /** Other apps' caches, if usage access lets us read it (only the system can clear it). */
    val otherAppsCacheBytes: Long?,
    /** Launchable apps whose background processes can be stopped to free RAM. */
    val backgroundPackages: List<String>,
)

data class CleanerResult(
    val freedCacheBytes: Long,
    val freedRamBytes: Long,
    val stoppedApps: Int,
)

/**
 * What a non-system app is actually allowed to clean on Android 8+:
 *  - its own caches (including Coil's image caches), and
 *  - other apps' *background* processes (KILL_BACKGROUND_PROCESSES), which frees RAM.
 * Other apps' caches can only be measured (with usage access), not deleted.
 */
class CleanerRepository(private val context: Context) {

    private val activityManager = context.getSystemService(ActivityManager::class.java)

    suspend fun scan(protect: Set<String>): CleanerScan = withContext(Dispatchers.IO) {
        val packages = launchablePackages() - protect - context.packageName
        CleanerScan(
            ownCacheBytes = ownCacheDirs().sumOf { dirSize(it) },
            otherAppsCacheBytes = otherAppsCache(packages),
            backgroundPackages = packages.sorted(),
        )
    }

    @OptIn(ExperimentalCoilApi::class)
    suspend fun clean(scan: CleanerScan): CleanerResult = withContext(Dispatchers.IO) {
        val before = availableRam()

        context.imageLoader.let { loader ->
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
        ownCacheDirs().forEach { dir ->
            // Coil's directory was cleared through its own API (keeps its journal valid).
            dir.listFiles()?.forEach { if (it.name != COIL_DIR) it.deleteRecursively() }
        }
        scan.backgroundPackages.forEach { pkg ->
            runCatching { activityManager.killBackgroundProcesses(pkg) }
        }

        delay(800) // give the system a moment to reclaim memory before measuring
        val after = availableRam()
        CleanerResult(
            freedCacheBytes = scan.ownCacheBytes,
            freedRamBytes = (after - before).coerceAtLeast(0L),
            stoppedApps = scan.backgroundPackages.size,
        )
    }

    private fun ownCacheDirs(): List<File> = listOfNotNull(context.cacheDir, context.externalCacheDir)

    private fun dirSize(dir: File): Long =
        runCatching { dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

    private fun availableRam(): Long =
        ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }.availMem

    private fun launchablePackages(): Set<String> {
        val pm = context.packageManager
        return listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMapTo(HashSet()) { category ->
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                .map { it.activityInfo.packageName }
        }
    }

    private fun otherAppsCache(packages: Set<String>): Long? = runCatching {
        val stats = context.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()
        var total = 0L
        var readable = false
        for (pkg in packages) {
            runCatching { stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, user).cacheBytes }
                .onSuccess {
                    total += it
                    readable = true
                }
        }
        if (readable) total else null
    }.getOrNull()

    private companion object {
        const val COIL_DIR = "image_cache"
    }
}
