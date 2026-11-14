package com.glasslauncher.tv.ui.home

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.ui.components.Text
import coil.compose.AsyncImage
import com.glasslauncher.tv.R
import com.glasslauncher.tv.media.NowPlaying
import com.glasslauncher.tv.ui.components.FocusCard
import com.glasslauncher.tv.ui.components.FocusTravel
import com.glasslauncher.tv.ui.theme.Glass
import kotlinx.coroutines.delay

private val NowPlayingCardWidth = 460.dp
private val NowPlayingCardHeight = 169.dp

/** "מתנגן עכשיו": one wide card for the active media session; OK opens the full player. */
@Composable
fun NowPlayingRow(
    nowPlaying: NowPlaying,
    appLabel: String?,
    travel: FocusTravel,
    onFocused: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var rowHasFocus by remember { mutableStateOf(false) }
    val titleColor by animateColorAsState(
        if (rowHasFocus) Glass.TextPrimary else Glass.TextSecondary,
        tween(200),
        label = "nowPlayingTitle",
    )
    val status = stringResource(if (nowPlaying.isPlaying) R.string.player_playing else R.string.player_paused)

    Column(modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.row_now_playing),
            color = titleColor,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 64.dp, top = 8.dp),
        )
        Box(
            Modifier
                .padding(horizontal = 64.dp, vertical = 24.dp)
                .onFocusChanged { rowHasFocus = it.hasFocus }
        ) {
            FocusCard(
                width = NowPlayingCardWidth,
                height = NowPlayingCardHeight,
                label = appLabel ?: nowPlaying.packageName,
                sublabel = status,
                rowHasFocus = rowHasFocus,
                travel = travel,
                edgeScale = { 1f },
                onFocused = onFocused,
                onClick = { onOpen() },
                overlay = { NowPlayingCardContent(nowPlaying) },
            ) { artModifier ->
                NowPlayingCardBackdrop(nowPlaying, artModifier)
            }
        }
    }
}

@Composable
internal fun NowPlayingCardBackdrop(nowPlaying: NowPlaying, modifier: Modifier) {
    Box(
        modifier.background(
            Brush.linearGradient(listOf(nowPlaying.tint.copy(alpha = 0.6f), Color(0xFF101018)))
        )
    ) {
        val art = nowPlaying.art
        if (art != null) {
            val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            Image(
                bitmap = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(30.dp)
                    .graphicsLayer { alpha = if (canBlur) 0.55f else 0.2f },
            )
        }
    }
}

@Composable
internal fun NowPlayingCardContent(nowPlaying: NowPlaying) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(nowPlaying.isPlaying) {
        while (nowPlaying.isPlaying) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }

    // Cover first = right side in RTL, text beside it.
    Row(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .background(Color.Black.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center,
        ) {
            val art = nowPlaying.art
            when {
                art != null -> Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                nowPlaying.artUri != null -> AsyncImage(
                    model = nowPlaying.artUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Text("♪", color = Color.White.copy(alpha = 0.8f), fontSize = 54.sp)
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Text(
                text = nowPlaying.title,
                color = Glass.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            nowPlaying.artist?.let {
                Text(it, color = Glass.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            if (nowPlaying.durationMs > 0) {
                val fraction = (nowPlaying.positionAt(now).toFloat() / nowPlaying.durationMs).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.3f))
                ) {
                    Box(
                        Modifier
                            .align(AbsoluteAlignment.CenterLeft)
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .background(Color.White)
                    )
                }
            }
        }
    }
}
