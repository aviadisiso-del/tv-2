package com.glasslauncher.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.derivedStateOf
import com.glasslauncher.tv.ui.theme.Motion
import kotlinx.coroutines.delay
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glasslauncher.tv.ui.components.Text
import com.glasslauncher.tv.R
import com.glasslauncher.tv.data.AppEntry
import com.glasslauncher.tv.data.HomeRow
import com.glasslauncher.tv.ui.HomeViewModel
import com.glasslauncher.tv.ui.components.FocusTravel
import com.glasslauncher.tv.ui.theme.Glass
import com.glasslauncher.tv.ui.theme.TV_DAMPING_RATIO
import com.glasslauncher.tv.ui.theme.TV_STIFFNESS
import com.glasslauncher.tv.ui.theme.tvSpring
import kotlinx.coroutines.launch

/** Max wallpaper drift (dp) — must stay inside the wallpaper's overscan. */
private const val MAX_WALLPAPER_SHIFT_DP = 60f
private const val WALLPAPER_SHIFT_PER_ITEM_DP = 5f

/** Page 2: the wallpaper moves up only this much while the content moves a whole page. */
private const val PAGE2_WALLPAPER_SHIFT_DP = -40f

/** Top Shelf height: leaves a peek of the next row's title under the app row, like tvOS. */
private val ShelfHeight = 190.dp

/**
 * Focus scrolling for every list on Home: scroll only as far as needed to show the
 * focused item, and do it on the tvOS spring instead of the default tween.
 */
@OptIn(ExperimentalFoundationApi::class)
private val SpringBringIntoView = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> =
        spring(dampingRatio = TV_DAMPING_RATIO, stiffness = TV_STIFFNESS)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailingEdge = offset + size
        return when {
            offset >= 0f && trailingEdge <= containerSize -> 0f
            offset < 0f -> offset
            size > containerSize -> offset
            else -> trailingEdge - containerSize
        }
    }
}

/**
 * Scroll an item to the top on the tvOS spring. If it's on screen we know the exact
 * distance; otherwise fall back to the list's own animated jump.
 */
private suspend fun LazyListState.springScrollToItem(index: Int) {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item != null) {
        animateScrollBy(item.offset.toFloat(), tvSpring())
    } else {
        animateScrollToItem(index)
    }
}

/** Tags a list item with its page: whenever focus is inside it, that page is current. */
private fun Modifier.pageMarker(page: Int, onPage: (Int) -> Unit): Modifier =
    onFocusChanged { if (it.hasFocus) onPage(page) }.focusGroup()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    menuRequesters: Map<MenuTab, FocusRequester>,
    firstAppRequester: FocusRequester,
    onQuickSetting: (QuickSetting) -> Unit,
) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val focusedApp by vm.focusedApp.collectAsStateWithLifecycle()
    val wallpaper by vm.wallpaper.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val continueRow by vm.continueRow.collectAsStateWithLifecycle()
    val recommendedRow by vm.recommendedRow.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val rowOrder by vm.rowOrder.collectAsStateWithLifecycle()
    val hiddenRows by vm.hiddenRows.collectAsStateWithLifecycle()
    val tab = vm.selectedTab
    var contentFocused by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    val travel = remember { FocusTravel() }
    val columnState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val page2 = remember(apps) { buildPage2(apps) }

    val shownApp: AppEntry? = if (tab == MenuTab.HOME) focusedApp else null
    // Debounced copy for the Top Shelf, backdrop and wallpaper: while focus is flying
    // along a row they wait until it settles (calmer, and cheaper on weak streamers).
    var shelfApp by remember { mutableStateOf(shownApp) }
    LaunchedEffect(shownApp) {
        if (shownApp != null && shelfApp != null) delay(Motion.SHELF_DEBOUNCE_MS)
        shelfApp = shownApp
    }
    val currentApps by rememberUpdatedState(apps)
    val indexOf: (AppEntry?) -> Int = remember {
        { app: AppEntry? -> if (app == null) -1 else currentApps.indexOfFirst { it.packageName == app.packageName } }
    }

    // Page-1 rows that actually have something to show, in the user's order.
    val page1Rows = rowOrder.filter { row ->
        row !in hiddenRows && when (row) {
            HomeRow.NOW_PLAYING -> nowPlaying != null
            HomeRow.CONTINUE -> continueRow != null
            HomeRow.RECOMMENDED -> recommendedRow != null
        }
    }
    // shelf + apps + page-1 rows + "more" hint come before the Page 2 header.
    val page2HeaderIndex = 2 + page1Rows.size + 1

    // Far parallax layer: wallpaper drifts a little per item as focus walks the row,
    // and a little upward (much less than a full page) on Page 2.
    val shift by animateFloatAsState(
        targetValue = (indexOf(shelfApp).coerceAtLeast(0) * WALLPAPER_SHIFT_PER_ITEM_DP)
            .coerceAtMost(MAX_WALLPAPER_SHIFT_DP),
        animationSpec = tvSpring(),
        label = "wallpaperShift",
    )
    val shiftY by animateFloatAsState(
        targetValue = if (page == 1) PAGE2_WALLPAPER_SHIFT_DP else 0f,
        animationSpec = tvSpring(),
        label = "wallpaperShiftY",
    )
    val backdropAlpha by animateFloatAsState(if (page == 1) 0.25f else 1f, tween(Motion.FADE), label = "backdropAlpha")
    val tint by animateColorAsState(
        targetValue = shelfApp?.tint ?: wallpaper.preset.accent,
        animationSpec = tween(Motion.FADE_SLOW),
        label = "wallpaperTint",
    )
    // Deep in the rows the menu bar slides away (tvOS); heading up brings it back.
    val scrolledDeep by remember { derivedStateOf { columnState.firstVisibleItemIndex >= 2 } }
    val menuHidden = contentFocused && (page == 1 || scrolledDeep)

    fun scrollHomeToTop() {
        if (columnState.firstVisibleItemIndex != 0 || columnState.firstVisibleItemScrollOffset != 0) {
            scope.launch { columnState.springScrollToItem(0) }
        }
    }

    // Back on the menu bar (or Home pressed) -> Top Shelf slides back into view.
    LaunchedEffect(contentFocused) {
        if (!contentFocused) {
            page = 0
            scrollHomeToTop()
        }
    }
    LaunchedEffect(vm.homeSignal) {
        page = 0
        columnState.scrollToItem(0)
    }
    // Entering Page 2 snaps it to the top as a whole page (page-1 rows leave together).
    LaunchedEffect(page) {
        if (page == 1) columnState.springScrollToItem(page2HeaderIndex)
    }
    // Back on Page 2 -> return to Page 1's app row.
    BackHandler(enabled = page == 1 && contentFocused) {
        scope.launch {
            columnState.scrollToItem(0)
            withFrameNanos { }
            runCatching { firstAppRequester.requestFocus() }
        }
    }

    val onPage: (Int) -> Unit = { page = it }

    Box(Modifier.fillMaxSize()) {
        WallpaperLayer(
            choice = wallpaper,
            tint = { tint },
            shiftDp = { shift },
            shiftYDp = { shiftY },
            modifier = Modifier.fillMaxSize(),
        )

        HeroBackdrop(
            app = shelfApp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .graphicsLayer { alpha = backdropAlpha },
        )

        // Weather fades in once the first fetch lands instead of popping in.
        AnimatedVisibility(
            visible = weather != null,
            enter = fadeIn(tween(Motion.FADE_SLOW)),
            modifier = Modifier
                .align(AbsoluteAlignment.TopLeft)
                .absolutePadding(top = 28.dp, left = 56.dp),
        ) {
            WeatherWidget(weather = weather)
        }
        ClockWidget(
            Modifier
                .align(AbsoluteAlignment.TopRight)
                .absolutePadding(top = 28.dp, right = 56.dp)
        )

        Column(Modifier.fillMaxSize()) {
            MenuBar(
                selected = tab,
                compact = contentFocused,
                hidden = menuHidden,
                requesters = menuRequesters,
                onTabFocused = vm::selectTab,
                onTabClicked = vm::onTabClicked,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 28.dp),
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onFocusChanged { contentFocused = it.hasFocus }
                    .focusGroup(),
            ) {
                if (tab == MenuTab.HOME) {
                    CompositionLocalProvider(LocalBringIntoViewSpec provides SpringBringIntoView) {
                        LazyColumn(
                            state = columnState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 40.dp),
                        ) {
                            // ============================== Page 1
                            item(key = "shelf") {
                                TopShelf(
                                    app = shelfApp,
                                    indexOf = indexOf,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(ShelfHeight),
                                )
                            }
                            item(key = "apps") {
                                Box(Modifier.pageMarker(0, onPage)) {
                                    if (apps.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.no_apps),
                                            color = Glass.TextSecondary,
                                            fontSize = 20.sp,
                                            modifier = Modifier.padding(horizontal = 64.dp, vertical = 40.dp),
                                        )
                                    } else {
                                        AppRow(
                                            apps = apps,
                                            travel = travel,
                                            firstItemRequester = firstAppRequester,
                                            resetSignal = vm.homeSignal,
                                            onFocused = { app ->
                                                vm.onAppFocused(app)
                                                // Back on the app row -> show the full Top Shelf again.
                                                scrollHomeToTop()
                                            },
                                            onLaunch = vm::requestLaunch,
                                        )
                                    }
                                }
                            }
                            page1Rows.forEach { homeRow ->
                                item(key = homeRow.id) {
                                    Box(Modifier.pageMarker(0, onPage)) {
                                        Page1Row(homeRow, vm, nowPlaying, continueRow, recommendedRow, travel)
                                    }
                                }
                            }
                            item(key = "moreHint") {
                                Text(
                                    text = stringResource(R.string.page2_hint),
                                    color = Glass.TextSecondary,
                                    fontSize = 15.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp, bottom = 24.dp)
                                        .graphicsLayer { alpha = if (page == 0) 1f else 0f },
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }

                            // ============================== Page 2
                            page2Items(
                                page2 = page2,
                                vm = vm,
                                nowPlaying = nowPlaying,
                                travel = travel,
                                onPage = onPage,
                                onQuickSetting = onQuickSetting,
                            )
                        }
                    }
                } else {
                    TabPlaceholder(tab, Modifier.fillMaxSize())
                }
            }
        }
    }

    // Nothing focusable in the content area -> keep focus on the menu.
    LaunchedEffect(apps.isEmpty(), tab) {
        if (apps.isEmpty() || tab != MenuTab.HOME) {
            withFrameNanos { }
            runCatching { menuRequesters.getValue(tab).requestFocus() }
        }
    }
}

@Composable
private fun Page1Row(
    homeRow: HomeRow,
    vm: HomeViewModel,
    nowPlaying: com.glasslauncher.tv.media.NowPlaying?,
    continueRow: com.glasslauncher.tv.data.ContentRowState?,
    recommendedRow: com.glasslauncher.tv.data.ContentRowState?,
    travel: FocusTravel,
) {
    when (homeRow) {
        HomeRow.NOW_PLAYING -> nowPlaying?.let { playing ->
            NowPlayingRow(
                nowPlaying = playing,
                appLabel = vm.appFor(playing.packageName)?.label,
                travel = travel,
                onFocused = vm::onNowPlayingFocused,
                onOpen = vm::openPlayer,
            )
        }
        HomeRow.CONTINUE, HomeRow.RECOMMENDED -> {
            val row = if (homeRow == HomeRow.CONTINUE) continueRow else recommendedRow
            row?.let {
                ContentRow(
                    title = stringResource(it.title),
                    items = it.items,
                    travel = travel,
                    onFocused = vm::onContentFocused,
                    onLaunch = vm::requestContentLaunch,
                )
            }
        }
    }
}

private fun LazyListScope.page2Items(
    page2: Page2Content,
    vm: HomeViewModel,
    nowPlaying: com.glasslauncher.tv.media.NowPlaying?,
    travel: FocusTravel,
    onPage: (Int) -> Unit,
    onQuickSetting: (QuickSetting) -> Unit,
) {
    item(key = "page2Header") {
        Text(
            text = stringResource(R.string.page2_title),
            color = Glass.TextPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 64.dp, top = 12.dp, bottom = 4.dp),
        )
    }
    if (nowPlaying != null || page2.musicApps.isNotEmpty()) {
        item(key = "page2Music") {
            Box(Modifier.pageMarker(1, onPage)) {
                MusicRow(
                    nowPlaying = nowPlaying,
                    nowPlayingAppLabel = nowPlaying?.let { vm.appFor(it.packageName)?.label },
                    musicApps = page2.musicApps,
                    travel = travel,
                    onAppFocused = vm::onAppFocused,
                    onNowPlayingFocused = vm::onNowPlayingFocused,
                    onOpenPlayer = vm::openPlayer,
                    onLaunch = vm::requestLaunch,
                )
            }
        }
    }
    listOf(
        Triple("page2Video", R.string.row_video, page2.videoApps),
        Triple("page2Games", R.string.row_games, page2.games),
        Triple("page2All", R.string.row_all_apps, page2.allApps),
    ).forEach { (key, title, list) ->
        if (list.isNotEmpty()) {
            item(key = key) {
                Box(Modifier.pageMarker(1, onPage)) {
                    AppsShelfRow(
                        title = stringResource(title),
                        apps = list,
                        travel = travel,
                        onFocused = vm::onAppFocused,
                        onLaunch = vm::requestLaunch,
                    )
                }
            }
        }
    }
    item(key = "page2QuickSettings") {
        Box(Modifier.pageMarker(1, onPage)) {
            QuickSettingsRow(travel = travel, onOpen = onQuickSetting)
        }
    }
    // Room so the Page 2 header can always snap to the top.
    item(key = "page2Filler") { Spacer(Modifier.height(260.dp)) }
}

@Composable
private fun TabPlaceholder(tab: MenuTab, modifier: Modifier = Modifier) {
    val isSettings = tab == MenuTab.SETTINGS
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(if (isSettings) R.string.settings_title else R.string.coming_soon),
            color = Glass.TextPrimary,
            fontSize = 34.sp,
        )
        Text(
            stringResource(if (isSettings) R.string.settings_tab_hint else R.string.coming_soon_detail),
            color = Glass.TextSecondary,
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.weight(1.3f))
    }
}
