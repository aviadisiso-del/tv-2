package com.glasslauncher.tv.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import com.glasslauncher.tv.data.vividColorOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.roundToInt

/** Snapshot of the active media session. Position is extrapolated in the UI. */
@Immutable
data class NowPlaying(
    val packageName: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val art: ImageBitmap?,
    /** Used when the session gives a URI instead of a bitmap. */
    val artUri: String?,
    val tint: Color,
    val durationMs: Long,
    val positionMs: Long,
    /** SystemClock.elapsedRealtime() of [positionMs]. */
    val positionUpdatedAt: Long,
    val speed: Float,
    val isPlaying: Boolean,
    val canSkipNext: Boolean,
    val canSkipPrevious: Boolean,
    val canSeek: Boolean,
) {
    fun positionAt(elapsedRealtime: Long): Long {
        if (!isPlaying) return positionMs.coerceAtLeast(0)
        val live = positionMs + ((elapsedRealtime - positionUpdatedAt) * speed).toLong()
        return if (durationMs > 0) live.coerceIn(0, durationMs) else live.coerceAtLeast(0)
    }
}

/**
 * Follows the "best" active media session (playing first, else any with metadata) and
 * exposes it as [nowPlaying]. Only runs between start()/stop() — i.e. while the launcher
 * is visible — so it costs nothing while you're watching something.
 */
class MediaRepository(private val context: Context) {

    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, MediaListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _hasAccess = MutableStateFlow(false)
    val hasAccess: StateFlow<Boolean> = _hasAccess.asStateFlow()

    private var controller: MediaController? = null
    private var started = false

    // Art cache: scaling a 1000px cover on every position update would churn memory.
    private var artSource: Bitmap? = null
    private var artScaled: ImageBitmap? = null
    private var artTint: Color = DEFAULT_TINT

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publish()
            // Paused here? Another app may have started playing.
            if (state?.state != PlaybackState.STATE_PLAYING) refreshSessions()
        }

        override fun onSessionDestroyed() = refreshSessions()
    }

    fun start() {
        _hasAccess.value = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        if (started || !_hasAccess.value) return
        runCatching {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            started = true
            refreshSessions()
        }
    }

    fun stop() {
        if (!started) return
        runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionsListener) }
        setController(null)
        started = false
    }

    // --- Transport controls ---

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.transportControls?.seekTo(positionMs)
    }

    // --- Session tracking ---

    private fun refreshSessions() {
        runCatching { pick(sessionManager.getActiveSessions(listenerComponent)) }
    }

    private fun pick(sessions: List<MediaController>) {
        val best = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.metadata != null }
        if (best?.sessionToken != controller?.sessionToken) setController(best) else publish()
    }

    private fun setController(next: MediaController?) {
        controller?.unregisterCallback(callback)
        controller = next
        next?.registerCallback(callback, handler)
        publish()
    }

    private fun publish() {
        val c = controller
        val metadata = c?.metadata
        if (c == null || metadata == null) {
            _nowPlaying.value = null
            return
        }
        val state = c.playbackState
        val actions = state?.actions ?: 0L
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: metadata.description?.title?.toString()
            ?: ""

        updateArt(
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        )

        _nowPlaying.value = NowPlaying(
            packageName = c.packageName,
            title = title,
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            art = artScaled,
            artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
            tint = artTint,
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
            positionMs = state?.position ?: 0L,
            positionUpdatedAt = state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            isPlaying = state?.state == PlaybackState.STATE_PLAYING ||
                state?.state == PlaybackState.STATE_BUFFERING,
            canSkipNext = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L,
            canSkipPrevious = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
            canSeek = (actions and PlaybackState.ACTION_SEEK_TO) != 0L,
        )
    }

    private fun updateArt(source: Bitmap?) {
        if (source === artSource) return
        artSource = source
        if (source == null) {
            artScaled = null
            artTint = DEFAULT_TINT
            return
        }
        val scaled = runCatching {
            val longest = max(source.width, source.height)
            if (longest <= MAX_ART_PX) {
                source
            } else {
                val f = MAX_ART_PX.toFloat() / longest
                Bitmap.createScaledBitmap(source, (source.width * f).roundToInt(), (source.height * f).roundToInt(), true)
            }
        }.getOrNull()
        artScaled = scaled?.asImageBitmap()
        artTint = Color(runCatching { vividColorOf(scaled ?: source, DEFAULT_TINT_ARGB) }.getOrDefault(DEFAULT_TINT_ARGB))
    }

    private companion object {
        const val MAX_ART_PX = 512
        const val DEFAULT_TINT_ARGB = 0xFF2E8BFF.toInt()
        val DEFAULT_TINT = Color(DEFAULT_TINT_ARGB)
    }
}
