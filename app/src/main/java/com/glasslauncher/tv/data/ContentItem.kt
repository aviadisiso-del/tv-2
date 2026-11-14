package com.glasslauncher.tv.data

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

/** One card in a content row: a system Watch Next / preview program, or an app fallback. */
@Immutable
data class ContentItem(
    val id: String,
    val title: String,
    val subtitle: String?,
    /** Poster/thumbnail from the provider (content:// or https), loaded with Coil. */
    val imageUri: String?,
    /** Owning app: artwork fallback, badge, tint and launch target. */
    val app: AppEntry,
    /** Playback progress 0..1 for Watch Next items. */
    val progress: Float?,
    /** Deep link supplied by the provider; falls back to launching [app]. */
    val intentUri: String?,
    val lastUsedAt: Long?,
)

@Immutable
data class ContentRowState(
    @StringRes val title: Int,
    val items: List<ContentItem>,
)
