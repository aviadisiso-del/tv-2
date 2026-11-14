package com.glasslauncher.tv.data

import android.content.Context
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.provider.BaseColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the Android TV provider (Watch Next + channel preview programs).
 *
 * Most devices only let system/privileged launchers read other apps' rows; there the
 * query throws or comes back empty and the caller falls back to app-based rows.
 * Where READ_EPG_DATA is grantable (install.ps1 tries `pm grant`), real content shows up.
 */
class ContentRepository(private val context: Context) {

    suspend fun watchNext(apps: Map<String, AppEntry>): List<ContentItem> = withContext(Dispatchers.IO) {
        query(TvContract.WatchNextPrograms.CONTENT_URI, "$COL_ENGAGEMENT DESC", apps, isWatchNext = true)
    }

    suspend fun previewPrograms(apps: Map<String, AppEntry>): List<ContentItem> = withContext(Dispatchers.IO) {
        query(TvContract.PreviewPrograms.CONTENT_URI, null, apps, isWatchNext = false)
    }

    private fun query(
        uri: Uri,
        sortOrder: String?,
        apps: Map<String, AppEntry>,
        isWatchNext: Boolean,
    ): List<ContentItem> = runCatching {
        val projection = buildList {
            add(BaseColumns._ID)
            add(COL_PACKAGE)
            add(COL_TITLE)
            add(COL_EPISODE_TITLE)
            add(COL_POSTER)
            add(COL_THUMBNAIL)
            add(COL_INTENT)
            if (isWatchNext) {
                add(COL_POSITION)
                add(COL_DURATION)
                add(COL_ENGAGEMENT)
            }
        }.toTypedArray()

        context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { c ->
            val out = ArrayList<ContentItem>()
            val prefix = if (isWatchNext) "wn" else "pp"
            while (c.moveToNext() && out.size < MAX_ITEMS) {
                val pkg = c.str(COL_PACKAGE) ?: continue
                val app = apps[pkg] ?: continue
                val progress = if (isWatchNext) {
                    val pos = c.long(COL_POSITION)
                    val dur = c.long(COL_DURATION)
                    if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0.02f, 1f) else null
                } else {
                    null
                }
                out += ContentItem(
                    id = "$prefix:${c.long(BaseColumns._ID)}",
                    title = c.str(COL_TITLE) ?: app.label,
                    subtitle = c.str(COL_EPISODE_TITLE) ?: app.label,
                    imageUri = c.str(COL_POSTER) ?: c.str(COL_THUMBNAIL),
                    app = app,
                    progress = progress,
                    intentUri = c.str(COL_INTENT),
                    lastUsedAt = if (isWatchNext) c.long(COL_ENGAGEMENT).takeIf { it > 0 } else null,
                )
            }
            out
        } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun Cursor.str(column: String): String? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getString(i)
    }

    private fun Cursor.long(column: String): Long {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) 0L else getLong(i)
    }

    private companion object {
        const val MAX_ITEMS = 20
        // TvContract column names (string literals: they live on Java interfaces).
        const val COL_PACKAGE = "package_name"
        const val COL_TITLE = "title"
        const val COL_EPISODE_TITLE = "episode_title"
        const val COL_POSTER = "poster_art_uri"
        const val COL_THUMBNAIL = "thumbnail_uri"
        const val COL_INTENT = "intent_uri"
        const val COL_POSITION = "last_playback_position_millis"
        const val COL_DURATION = "duration_millis"
        const val COL_ENGAGEMENT = "last_engagement_time_utc_millis"
    }
}
