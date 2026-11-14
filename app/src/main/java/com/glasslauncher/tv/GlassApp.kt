package com.glasslauncher.tv

import android.app.Application
import android.content.ComponentCallbacks2
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import com.glasslauncher.tv.data.SettingsRepository
import com.glasslauncher.tv.data.WeatherRepository
import com.glasslauncher.tv.media.MediaRepository

class GlassApp : Application(), ImageLoaderFactory {

    /** App-wide singletons (tiny service locator; no DI framework needed at this size). */
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val weather: WeatherRepository by lazy { WeatherRepository(this) }
    val media: MediaRepository by lazy { MediaRepository(this) }

    /**
     * Streamers are RAM-constrained, so the Coil memory cache is capped explicitly
     * (10% of the app's heap class) and the disk cache at 48 MB.
     */
    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.10)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(48L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The launcher went to the background (an app is open): give memory back.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            imageLoader.memoryCache?.clear()
        }
    }
}
