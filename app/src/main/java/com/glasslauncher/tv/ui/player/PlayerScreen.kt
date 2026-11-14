package com.glasslauncher.tv.ui.player

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.ui.components.Text
import coil.compose.AsyncImage
import com.glasslauncher.tv.R
import com.glasslauncher.tv.media.NowPlaying
import com.glasslauncher.tv.ui.components.GlassButton
import com.glasslauncher.tv.ui.theme.Glass
import com.glasslauncher.tv.ui.theme.tvSpring

private val ArtShape = RoundedCornerShape(18.dp)

/**
 * Full-screen player in the Apple Music on tvOS style: blurred album art fills the screen,
 * big art in the middle (shrinks a little when paused), title/artist, scrubber, pill controls.
 */
@Composable
fun PlayerScreen(
    nowPlaying: NowPlaying?,
    appLabel: String?,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenApp: () -> Unit,
) {
    val playRequester = remember { FocusRequester() }
    LaunchedEffect(nowPlaying != null) {
        if (nowPlaying != null) {
            withFrameNanos { }
            runCatching { playRequester.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Glass.Background)
    ) {
        PlayerBackdrop(nowPlaying)

        if (nowPlaying == null) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.player_nothing), color = Glass.TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.player_nothing_hint),
                    color = Glass.TextSecondary,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            return@Box
        }

        if (appLabel != null) {
            GlassButton(
                text = stringResource(R.string.player_open_app, appLabel),
                onClick = onOpenApp,
                modifier = Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    .absolutePadding(left = 48.dp, top = 32.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AlbumArt(nowPlaying, Modifier.size(220.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                text = nowPlaying.title,
                color = Glass.TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(640.dp),
            )
            val subtitle = listOfNotNull(nowPlaying.artist, nowPlaying.album).joinToString(" — ")
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = Glass.TextSecondary,
                    fontSize = 19.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(640.dp),
                )
            }
            Spacer(Modifier.height(16.dp))

            // Media controls and progress are never mirrored, even in Hebrew.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ProgressScrubber(nowPlaying, onSeek, Modifier.width(560.dp))
                    Spacer(Modifier.height(14.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TransportButton(
                            icon = TransportIcon.PREVIOUS,
                            width = 84.dp,
                            height = 56.dp,
                            enabled = nowPlaying.canSkipPrevious,
                            onClick = onPrevious,
                        )
                        TransportButton(
                            icon = if (nowPlaying.isPlaying) TransportIcon.PAUSE else TransportIcon.PLAY,
                            width = 112.dp,
                            height = 66.dp,
                            onClick = onPlayPause,
                            modifier = Modifier.focusRequester(playRequester),
                        )
                        TransportButton(
                            icon = TransportIcon.NEXT,
                            width = 84.dp,
                            height = 56.dp,
                            enabled = nowPlaying.canSkipNext,
                            onClick = onNext,
                        )
                    }
                }
            }
        }
    }
}

/** Large cover; springs down slightly while paused, like Apple Music. */
@Composable
private fun AlbumArt(nowPlaying: NowPlaying, modifier: Modifier = Modifier) {
    val scale by animateFloatAsState(if (nowPlaying.isPlaying) 1f else 0.86f, tvSpring(), label = "artScale")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = 30.dp.toPx()
                shape = ArtShape
                clip = true
            }
            .background(Brush.linearGradient(listOf(nowPlaying.tint.copy(alpha = 0.7f), Color(0xFF15151F)))),
        contentAlignment = Alignment.Center,
    ) {
        val art: ImageBitmap? = nowPlaying.art
        when {
            art != null -> Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            nowPlaying.artUri != null -> AsyncImage(
                model = nowPlaying.artUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Text("♪", color = Color.White.copy(alpha = 0.8f), fontSize = 80.sp)
        }
    }
}

/** Blurred cover filling the screen (real blur on Android 12+), tinted and darkened. */
@Composable
private fun PlayerBackdrop(nowPlaying: NowPlaying?) {
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val tint = nowPlaying?.tint ?: Glass.Accent
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = nowPlaying?.art, animationSpec = tween(600), label = "playerBackdrop") { art ->
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(80.dp)
                        .graphicsLayer { alpha = if (canBlur) 0.75f else 0.25f },
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(tint.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.55f))
                    )
                )
        )
    }
}
