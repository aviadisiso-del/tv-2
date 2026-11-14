package com.glasslauncher.tv.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.ui.components.Text
import com.glasslauncher.tv.data.AppEntry
import com.glasslauncher.tv.ui.components.FocusCard
import com.glasslauncher.tv.ui.components.FocusTravel
import com.glasslauncher.tv.ui.components.edgeScaleFor
import com.glasslauncher.tv.ui.theme.Glass

val AppCardWidth = 240.dp
val AppCardHeight = 135.dp

/** The tvOS app row: same-size, same-radius cards with the full focus treatment. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppRow(
    apps: List<AppEntry>,
    travel: FocusTravel,
    firstItemRequester: FocusRequester,
    resetSignal: Int,
    onFocused: (AppEntry) -> Unit,
    onLaunch: (AppEntry, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var rowHasFocus by remember { mutableStateOf(false) }

    // Initial focus, and "Home pressed": jump back to the first app.
    LaunchedEffect(resetSignal, apps.isNotEmpty()) {
        if (apps.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(0)
        withFrameNanos { }
        runCatching { firstItemRequester.requestFocus() }
    }

    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { rowHasFocus = it.hasFocus }
            .focusRestorer(),
        // Vertical room so the 1.08x lift and its shadow aren't clipped.
        contentPadding = PaddingValues(horizontal = 64.dp, vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
            FocusCard(
                width = AppCardWidth,
                height = AppCardHeight,
                label = app.label,
                rowHasFocus = rowHasFocus,
                travel = travel,
                edgeScale = { edgeScaleFor(listState, index) },
                onFocused = { onFocused(app) },
                onClick = { bounds -> onLaunch(app, bounds) },
                focusRequester = if (index == 0) firstItemRequester else null,
            ) { artModifier ->
                AppArtwork(app, artModifier)
            }
        }
    }
}

/** Banner if the app has one; otherwise a glass tile with icon + name. */
@Composable
fun AppArtwork(app: AppEntry, modifier: Modifier = Modifier) {
    val banner = app.banner
    if (banner != null) {
        Image(
            bitmap = banner,
            contentDescription = app.label,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(
            modifier.background(
                Brush.linearGradient(listOf(Color(0xFF1E2638), Color(0xFF0E121C)))
            ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(56.dp))
                Text(
                    text = app.label,
                    color = Glass.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
