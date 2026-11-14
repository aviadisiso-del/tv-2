package com.glasslauncher.tv.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.ui.components.Text
import coil.compose.AsyncImage
import com.glasslauncher.tv.R
import com.glasslauncher.tv.data.ContentItem
import com.glasslauncher.tv.ui.components.FocusCard
import com.glasslauncher.tv.ui.components.FocusTravel
import com.glasslauncher.tv.ui.components.edgeScaleFor
import com.glasslauncher.tv.ui.theme.Glass

val ContentCardWidth = 300.dp
val ContentCardHeight = 169.dp

/** A titled row of large 16:9 cards ("Continue Watching", "Recommended"). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ContentRow(
    title: String,
    items: List<ContentItem>,
    travel: FocusTravel,
    onFocused: (ContentItem) -> Unit,
    onLaunch: (ContentItem, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var rowHasFocus by remember { mutableStateOf(false) }
    val titleColor by animateColorAsState(
        if (rowHasFocus) Glass.TextPrimary else Glass.TextSecondary,
        tween(200),
        label = "rowTitle",
    )

    Column(modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = titleColor,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 64.dp, top = 8.dp),
        )
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { rowHasFocus = it.hasFocus }
                .focusRestorer(),
            contentPadding = PaddingValues(horizontal = 64.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                ContentCard(
                    item = item,
                    rowHasFocus = rowHasFocus,
                    travel = travel,
                    edgeScale = { edgeScaleFor(listState, index) },
                    onFocused = { onFocused(item) },
                    onClick = { bounds -> onLaunch(item, bounds) },
                )
            }
        }
    }
}

@Composable
private fun ContentCard(
    item: ContentItem,
    rowHasFocus: Boolean,
    travel: FocusTravel,
    edgeScale: () -> Float,
    onFocused: () -> Unit,
    onClick: (Rect) -> Unit,
) {
    val sublabel = item.subtitle ?: item.lastUsedAt?.let { relativeTime(it) }
    val progress = item.progress

    FocusCard(
        width = ContentCardWidth,
        height = ContentCardHeight,
        label = item.title,
        sublabel = sublabel,
        rowHasFocus = rowHasFocus,
        travel = travel,
        edgeScale = edgeScale,
        onFocused = onFocused,
        onClick = onClick,
        overlay = {
            // App badge on real content, so you know which app it opens in.
            if (item.imageUri != null) {
                Image(
                    bitmap = item.app.icon,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .size(28.dp)
                        .clip(RoundedCornerShape(7.dp)),
                )
            }
            if (progress != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                )
                // Media progress is never mirrored (Material bidi guidance): fills left -> right.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.3f))
                ) {
                    Box(
                        Modifier
                            .align(AbsoluteAlignment.CenterLeft)
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(Color.White)
                    )
                }
            }
        },
    ) { artModifier ->
        Box(artModifier) {
            // App art underneath: shows while the poster loads, or if it fails.
            AppArtwork(item.app, Modifier.fillMaxSize())
            if (item.imageUri != null) {
                AsyncImage(
                    model = item.imageUri,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Hebrew relative time: "לפני 5 דקות", "לפני שעתיים", "אתמול"… */
@Composable
private fun relativeTime(at: Long): String {
    val minutes = ((System.currentTimeMillis() - at).coerceAtLeast(0L)) / 60_000L
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> stringResource(R.string.time_just_now)
        minutes == 1L -> stringResource(R.string.time_minute_one)
        minutes < 60 -> stringResource(R.string.time_minutes, minutes)
        hours == 1L -> stringResource(R.string.time_hour_one)
        hours == 2L -> stringResource(R.string.time_hours_two)
        hours < 24 -> stringResource(R.string.time_hours, hours)
        days == 1L -> stringResource(R.string.time_yesterday)
        else -> stringResource(R.string.time_days, days)
    }
}
