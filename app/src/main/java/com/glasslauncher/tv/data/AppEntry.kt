package com.glasslauncher.tv.data

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

/** A launchable app on the device. Bitmaps are pre-scaled once to keep memory flat. */
@Immutable
data class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    /** 16:9 TV banner if the app ships one (most TV apps do). */
    val banner: ImageBitmap?,
    val icon: ImageBitmap,
    /** True if the app declares LEANBACK_LAUNCHER (a real TV app). */
    val isTvApp: Boolean,
    /** Vivid dominant color of the artwork — tints the wallpaper while focused. */
    val tint: Color,
    /** ApplicationInfo.CATEGORY_* (or CATEGORY_UNDEFINED). */
    val category: Int,
)
