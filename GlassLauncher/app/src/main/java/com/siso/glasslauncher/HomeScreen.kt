package com.siso.glasslauncher

import android.content.Context
import android.content.Intent
import android.view.KeyEvent as AKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sin

private val Amber = Color(0xFFFFC857)

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** How far the content is pushed in from the start edge to make room for the side rail. */
private val RailInset = 108.dp

/** A launch zoom: grows [image] from [from] to the whole screen, then runs [action]. */
private class LaunchAnim(
    val from: Rect,
    val image: ImageBitmap?,
    val fillImage: Boolean,
    val action: () -> Boolean,
)

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    settingsOpen: Boolean,
    onOpenSettings: () -> Unit,
    onOpenQuick: () -> Unit,
    gearFocus: FocusRequester,
) {
    val ctx = LocalContext.current
    val track by vm.media.track.collectAsState()
    var weatherOpen by remember { mutableStateOf(false) }
    var musicOpen by remember { mutableStateOf(false) }
    var appsOpen by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<String?>(null) }
    var wasSettingsOpen by remember { mutableStateOf(false) }
    var launching by remember { mutableStateOf<LaunchAnim?>(null) }
    val launchProgress = remember { Animatable(0f) }
    val listState = rememberLazyListState()
    val rowFocus = remember { FocusRequester() }
    val overlayFocus = remember { FocusRequester() }
    val animOn = LocalAnimations.current

    val enabled = !settingsOpen && !weatherOpen && !musicOpen && !appsOpen && launching == null
    val apps = vm.visibleApps
    val currentTrack = track
    val musicShown = vm.showMusic && currentTrack != null
    val continueShown = vm.showContinue && vm.continueItems.isNotEmpty()

    // Back never closes a launcher. Registered first = lowest priority.
    BackHandler(enabled = true) { }
    BackHandler(enabled = moving != null) { moving = null }
    BackHandler(enabled = weatherOpen) { weatherOpen = false }
    BackHandler(enabled = musicOpen) { musicOpen = false }
    BackHandler(enabled = appsOpen) { appsOpen = false }

    // Weather: load now, refresh every 20 minutes, reload when the city changes.
    LaunchedEffect(vm.cityIndex, vm.showWeather) {
        if (vm.showWeather) {
            while (true) {
                vm.refreshWeather()
                delay(20 * 60 * 1000L)
            }
        }
    }

    // HOME pressed: collapse everything and go back to the first app.
    LaunchedEffect(vm.homeTick) {
        weatherOpen = false
        musicOpen = false
        appsOpen = false
        moving = null
        if (vm.homeTick > 0) {
            listState.scrollToItem(0)
            delay(100)
            runCatching { rowFocus.requestFocus() }
        }
    }

    // Start with focus on the first app.
    LaunchedEffect(apps.isNotEmpty()) {
        if (apps.isNotEmpty()) {
            delay(150)
            runCatching { rowFocus.requestFocus() }
        }
    }

    // After Settings closes, land on the gear button.
    LaunchedEffect(settingsOpen) {
        if (settingsOpen) {
            wasSettingsOpen = true
        } else if (wasSettingsOpen) {
            wasSettingsOpen = false
            delay(100)
            runCatching { gearFocus.requestFocus() }
        }
    }

    // Overlays take focus when they open and hand it back to the apps when they close.
    LaunchedEffect(weatherOpen, musicOpen, appsOpen) {
        delay(100)
        if (weatherOpen || musicOpen || appsOpen) {
            runCatching { overlayFocus.requestFocus() }
        } else if (apps.isNotEmpty() && !settingsOpen && launching == null) {
            runCatching { rowFocus.requestFocus() }
        }
    }

    // If the song ends while the big player is open, close it.
    LaunchedEffect(currentTrack == null) {
        if (currentTrack == null) musicOpen = false
    }

    // Launch animation: the tile grows to fill the screen, then the app starts.
    LaunchedEffect(launching) {
        val l = launching ?: return@LaunchedEffect
        launchProgress.snapTo(0f)
        if (animOn) {
            launchProgress.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
        } else {
            launchProgress.snapTo(1f)
        }
        val started = l.action()
        if (!started) {
            if (animOn) launchProgress.animateTo(0f, tween(220, easing = FastOutSlowInEasing)) else launchProgress.snapTo(0f)
            launching = null
        }
    }

    // We came back to the launcher: shrink the app back into its tile.
    LaunchedEffect(vm.resumeTick) {
        if (launching != null) {
            if (animOn) launchProgress.animateTo(0f, tween(280, easing = FastOutSlowInEasing)) else launchProgress.snapTo(0f)
            launching = null
            delay(100)
            runCatching { rowFocus.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize()) {

        // ── Top: clock + weather ──
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = RailInset, end = 40.dp, top = 26.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vm.showClock) {
                    ClockBlock()
                    Spacer(Modifier.width(14.dp))
                }
                if (vm.showWeather) {
                    WeatherChip(vm.weather, CITIES[vm.cityIndex].name, enabled) { weatherOpen = true }
                }
            }
        }

        // ── Side rail: search, all apps, quick menu (display + sound), settings ──
        SideRail(
            enabled = enabled,
            gearFocus = gearFocus,
            onApps = { appsOpen = true },
            onQuick = onOpenQuick,
            onSettings = onOpenSettings,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 40.dp),
        )

        // ── Bottom: continue watching, now playing, apps ──
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = RailInset, end = 40.dp, bottom = 30.dp),
        ) {
            if (continueShown) {
                ContinueRow(vm.continueItems, enabled) { item, bounds ->
                    launching = LaunchAnim(bounds, item.poster, true) { vm.openContinue(item) }
                }
                Spacer(Modifier.height(6.dp))
            }
            if (musicShown && currentTrack != null) {
                MusicStrip(currentTrack, enabled, vm) { musicOpen = true }
                Spacer(Modifier.height(10.dp))
            }
            SectionLabel("אפליקציות")
            AppBar(
                apps = apps,
                moving = moving,
                enabled = enabled,
                listState = listState,
                rowFocus = rowFocus,
                onLaunch = { app, bounds ->
                    launching = LaunchAnim(bounds, app.banner ?: app.icon, app.banner != null) { vm.openApp(app) }
                },
                onStartMove = { moving = it.pkg },
                onMove = { delta -> moving?.let { vm.moveApp(it, delta) } },
                onEndMove = { moving = null },
            )
        }

        // ── Expanded overlays ──
        AnimatedVisibility(
            visible = weatherOpen,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = RailInset, top = 84.dp),
            enter = if (animOn) fadeIn(tween(200, easing = FastOutSlowInEasing)) + scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = 0.92f, transformOrigin = TransformOrigin(1f, 0f)) else EnterTransition.None,
            exit = if (animOn) fadeOut(tween(150)) + scaleOut(tween(180, easing = FastOutSlowInEasing), targetScale = 0.95f, transformOrigin = TransformOrigin(1f, 0f)) else ExitTransition.None,
        ) {
            WeatherPanel(
                w = vm.weather,
                city = CITIES[vm.cityIndex].name,
                focus = overlayFocus,
                onClose = { weatherOpen = false },
            )
        }
        AnimatedVisibility(
            visible = musicOpen && currentTrack != null,
            modifier = Modifier.align(Alignment.Center),
            enter = if (animOn) fadeIn(tween(220, easing = FastOutSlowInEasing)) + scaleIn(tween(260, easing = FastOutSlowInEasing), initialScale = 0.9f) else EnterTransition.None,
            exit = if (animOn) fadeOut(tween(160)) + scaleOut(tween(190, easing = FastOutSlowInEasing), targetScale = 0.95f) else ExitTransition.None,
        ) {
            currentTrack?.let { MusicExpanded(track = it, vm = vm, focus = overlayFocus) }
        }
        AnimatedVisibility(
            visible = appsOpen,
            modifier = Modifier.align(Alignment.Center),
            enter = if (animOn) fadeIn(tween(220, easing = FastOutSlowInEasing)) + scaleIn(tween(260, easing = FastOutSlowInEasing), initialScale = 0.94f) else EnterTransition.None,
            exit = if (animOn) fadeOut(tween(160)) + scaleOut(tween(190, easing = FastOutSlowInEasing), targetScale = 0.97f) else ExitTransition.None,
        ) {
            AllAppsPanel(
                apps = vm.allApps,
                focus = overlayFocus,
                onOpen = { app, bounds ->
                    appsOpen = false
                    launching = LaunchAnim(bounds, app.banner ?: app.icon, app.banner != null) { vm.openApp(app) }
                },
            )
        }

        // ── App launch zoom (always on top) ──
        val l = launching
        if (l != null) {
            LaunchZoom(l, launchProgress)
        }
    }
}

/**
 * The zoom is driven entirely by graphicsLayer, which is applied in the DRAW phase:
 * no recomposition and no re-layout per frame, so it stays smooth on a weak TV box.
 */
@Composable
private fun LaunchZoom(l: LaunchAnim, progress: Animatable<Float, AnimationVector1D>) {
    val screen = LocalScreenSize.current
    val sw = screen.width.toFloat().coerceAtLeast(1f)
    val sh = screen.height.toFloat().coerceAtLeast(1f)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = progress.value
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = lerp(l.from.width / sw, 1f, p)
                scaleY = lerp(l.from.height / sh, 1f, p)
                translationX = lerp(l.from.left, 0f, p)
                translationY = lerp(l.from.top, 0f, p)
                shape = RoundedCornerShape(lerp(28f, 0f, p).dp)
                clip = true
            }
            .background(Color(0xFF0E1018)),
        contentAlignment = Alignment.Center,
    ) {
        val image = l.image
        if (image != null) {
            if (l.fillImage) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Image(bitmap = image, contentDescription = null, modifier = Modifier.size(180.dp))
            }
        }
    }
}

// ───────────────────────── Top area ─────────────────────────

@Composable
fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(15_000)
        }
    }
    return now
}

@Composable
fun WeatherChip(w: WeatherData?, city: String, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    GlassPanel(
        modifier = Modifier
            .focusBorder(focused, shape)
            .tvClickable(enabled, { focused = it }, onClick),
        shape = shape,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (w != null) {
                T(weatherEmoji(w.code, w.isDay), 22.sp)
                Spacer(Modifier.width(10.dp))
                T("${w.temp}°", 24.sp, FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp))
                Column {
                    T(city, 12.sp, FontWeight.Medium)
                    T(weatherText(w.code), 11.sp, color = Color.White.copy(alpha = 0.75f))
                }
            } else {
                T("מזג אוויר…", 13.sp, color = Color.White.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
fun ClockBlock() {
    val now = rememberNow()
    val locale = remember { Locale("he", "IL") }
    Column(horizontalAlignment = Alignment.End) {
        T(now.format(DateTimeFormatter.ofPattern("HH:mm", locale)), 30.sp, FontWeight.SemiBold)
        T(now.format(DateTimeFormatter.ofPattern("EEEE, d MMM", locale)), 12.sp, color = Color.White.copy(alpha = 0.75f))
    }
}

@Composable
fun SideRail(
    enabled: Boolean,
    gearFocus: FocusRequester,
    onApps: () -> Unit,
    onQuick: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(modifier.width(52.dp), shape = RoundedCornerShape(26.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoundButton(Glyph.Grid, 36.dp, false, enabled, onApps)
            RoundButton(Glyph.Tv, 36.dp, false, enabled, onQuick)
            RoundButton(Glyph.Gear, 36.dp, false, enabled, onSettings, focusRequester = gearFocus)
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    T(
        text,
        12.sp,
        FontWeight.SemiBold,
        Color.White.copy(alpha = 0.8f),
        Modifier.padding(start = 8.dp, bottom = 4.dp),
    )
}

// ───────────────────────── Continue watching ─────────────────────────

@Composable
fun ContinueRow(items: List<ContinueItem>, enabled: Boolean, onOpen: (ContinueItem, Rect) -> Unit) {
    Column {
        SectionLabel("המשך צפייה")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        ) {
            itemsIndexed(items, key = { index, _ -> index }) { _, item ->
                ContinueCard(item, enabled) { bounds -> onOpen(item, bounds) }
            }
        }
    }
}

@Composable
fun ContinueCard(item: ContinueItem, enabled: Boolean, onClick: (Rect) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val animOn = LocalAnimations.current
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = if (animOn) spring(dampingRatio = 0.75f, stiffness = 450f) else snap(),
        label = "cardScale",
    )
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .tvClickable(enabled, { focused = it }) { onClick(bounds) }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xCC1A2030), Color(0xCC0E121C))))
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), shape)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 112.dp, height = 64.dp)
                .background(Color(0xFF222A3C)),
        ) {
            val poster = item.poster
            if (poster != null) {
                Image(
                    bitmap = poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    T(item.provider.ifEmpty { "▶" }, 12.sp, FontWeight.Bold, maxLines = 2)
                }
            }
            if (item.progress > 0f) {
                Ltr {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(item.progress)
                            .height(3.dp)
                            .background(Color(0xFFFF8A3D)),
                    )
                }
            }
        }
        Column(
            Modifier
                .width(150.dp)
                .padding(horizontal = 10.dp),
        ) {
            T(item.title, 13.sp, FontWeight.SemiBold, maxLines = 2)
            val sub = item.subtitle.ifEmpty { item.provider }
            if (sub.isNotEmpty()) {
                T(sub, 10.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            }
        }
    }
}

// ───────────────────────── App bar ─────────────────────────

private class PressState {
    var down = false
    var long = false
}

@Composable
fun AppBar(
    apps: List<AppEntry>,
    moving: String?,
    enabled: Boolean,
    listState: LazyListState,
    rowFocus: FocusRequester,
    onLaunch: (AppEntry, Rect) -> Unit,
    onStartMove: (AppEntry) -> Unit,
    onMove: (Int) -> Unit,
    onEndMove: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp),
        shape = RoundedCornerShape(30.dp),
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(apps, key = { _, a -> a.pkg }) { index, app ->
                AppTile(
                    app = app,
                    isMoving = moving == app.pkg,
                    enabled = enabled,
                    focusRequester = if (index == 0) rowFocus else null,
                    onLaunch = { bounds -> onLaunch(app, bounds) },
                    onLongPress = { onStartMove(app) },
                    onMove = onMove,
                    onEndMove = onEndMove,
                )
            }
        }
    }
}

@Composable
fun AppTile(
    app: AppEntry,
    isMoving: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester?,
    onLaunch: (Rect) -> Unit,
    onLongPress: () -> Unit,
    onMove: (Int) -> Unit,
    onEndMove: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val press = remember { PressState() }
    val animOn = LocalAnimations.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // A spring feels alive and never "snaps" when focus moves quickly; it is read inside graphicsLayer
    // so the animation costs a redraw, not a recomposition.
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = if (animOn) spring(dampingRatio = 0.72f, stiffness = 500f) else snap(),
        label = "tileScale",
    )
    val shape = RoundedCornerShape(18.dp)

    Box(
        Modifier
            .size(width = 150.dp, height = 84.dp)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onKeyEvent { ev ->
                val k = ev.nativeKeyEvent.keyCode
                val ok = k == AKeyEvent.KEYCODE_DPAD_CENTER ||
                    k == AKeyEvent.KEYCODE_ENTER ||
                    k == AKeyEvent.KEYCODE_NUMPAD_ENTER
                val down = ev.type == KeyEventType.KeyDown
                val up = ev.type == KeyEventType.KeyUp
                if (isMoving) {
                    // Left/Right are PHYSICAL directions; in a Hebrew (RTL) row "left" means later in the list.
                    val towardLeft = if (rtl) 1 else -1
                    when {
                        down && k == AKeyEvent.KEYCODE_DPAD_LEFT -> { onMove(towardLeft); true }
                        down && k == AKeyEvent.KEYCODE_DPAD_RIGHT -> { onMove(-towardLeft); true }
                        down && (k == AKeyEvent.KEYCODE_DPAD_UP || k == AKeyEvent.KEYCODE_DPAD_DOWN) -> true
                        down && ok && ev.nativeKeyEvent.repeatCount == 0 -> { onEndMove(); true }
                        ok -> true // swallow repeats and the key-up that ended a long press
                        else -> false
                    }
                } else if (ok && down) {
                    if (ev.nativeKeyEvent.repeatCount == 0) {
                        press.down = true
                        press.long = false
                    } else if (!press.long) {
                        press.long = true
                        onLongPress()
                    }
                    true
                } else if (ok && up) {
                    val fire = press.down && !press.long
                    press.down = false
                    press.long = false
                    if (fire) onLaunch(bounds)
                    true
                } else {
                    false
                }
            }
            .tvFocusable(enabled) { focused = it }
            .pointerInput(app.pkg) {
                detectTapGestures(onTap = { onLaunch(bounds) }, onLongPress = { onLongPress() })
            }
            .clip(shape)
            .background(
                if (app.banner != null) Brush.verticalGradient(listOf(Color.White, Color.White))
                else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.12f))),
            )
            .then(
                if (isMoving) Modifier.border(3.dp, Amber, shape)
                else if (focused) Modifier.border(2.dp, Color.White, shape)
                else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val banner = app.banner
        val icon = app.icon
        if (banner != null) {
            Image(
                bitmap = banner,
                contentDescription = app.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Row(
                Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                T(app.label, 13.sp, FontWeight.Medium, maxLines = 2, modifier = Modifier.weight(1f))
            }
        }
        if (isMoving) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Amber)
                    .padding(horizontal = 8.dp, vertical = 1.dp),
            ) {
                T("◀  הזז  ▶", 10.sp, FontWeight.Bold, Color.Black)
            }
        }
    }
}

// ───────────────────────── All apps ─────────────────────────

@Composable
fun AllAppsPanel(
    apps: List<AppEntry>,
    focus: FocusRequester,
    onOpen: (AppEntry, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(
        modifier = modifier
            .width(720.dp)
            .height(400.dp),
        shape = RoundedCornerShape(30.dp),
        tint = Color.Black.copy(alpha = 0.30f),
    ) {
        Column(Modifier.padding(20.dp)) {
            T("כל האפליקציות", 16.sp, FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 136.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(6.dp),
            ) {
                gridItemsIndexed(apps, key = { _, a -> a.pkg }) { index, app ->
                    GridApp(app, if (index == 0) focus else null) { bounds -> onOpen(app, bounds) }
                }
            }
        }
    }
}

@Composable
private fun GridApp(app: AppEntry, focusRequester: FocusRequester?, onClick: (Rect) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val animOn = LocalAnimations.current
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = if (animOn) spring(dampingRatio = 0.75f, stiffness = 500f) else snap(),
        label = "gridScale",
    )
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .size(width = 136.dp, height = 76.dp)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvClickable(true, { focused = it }) { onClick(bounds) }
            .clip(shape)
            .background(
                if (app.banner != null) Brush.verticalGradient(listOf(Color.White, Color.White))
                else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.10f))),
            )
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val banner = app.banner
        val icon = app.icon
        if (banner != null) {
            Image(bitmap = banner, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(6.dp))
                }
                T(app.label, 12.sp, FontWeight.Medium, maxLines = 2, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ───────────────────────── Weather overlay ─────────────────────────

@Composable
fun WeatherPanel(
    w: WeatherData?,
    city: String,
    focus: FocusRequester,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(28.dp)
    GlassPanel(
        modifier = modifier
            .width(560.dp)
            .focusBorder(focused, shape)
            .focusRequester(focus)
            .tvClickable(true, { focused = it }, onClose),
        shape = shape,
    ) {
        Column(Modifier.padding(24.dp)) {
            T("מזג אוויר", 12.sp, color = Color.White.copy(alpha = 0.7f))
            T(city, 20.sp, FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (w == null) {
                T("טוען…", 14.sp, color = Color.White.copy(alpha = 0.75f))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(weatherEmoji(w.code, w.isDay), 40.sp)
                    Spacer(Modifier.width(12.dp))
                    Ltr { T("${w.temp}°", 54.sp, FontWeight.Light) }
                    Spacer(Modifier.width(18.dp))
                    Column {
                        T(weatherText(w.code), 18.sp, FontWeight.Medium)
                        T(
                            "מרגיש כמו ${w.feels}°  ·  מקס׳ ${w.high}°  ·  מינ׳ ${w.low}°",
                            12.sp,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                        T("רוח ${w.wind} קמ״ש", 12.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                }
                Spacer(Modifier.height(16.dp))
                T("השעות הקרובות", 12.sp, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (h in w.hours) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            T(h.label, 11.sp, color = Color.White.copy(alpha = 0.75f))
                            T(weatherEmoji(h.code), 20.sp)
                            T("${h.temp}°", 14.sp, FontWeight.Medium)
                            T("${h.rain}%", 10.sp, color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Music ─────────────────────────

@Composable
fun RoundButton(
    glyph: Glyph,
    diameter: Dp,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    badge: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(diameter)
            .focusBorder(focused, CircleShape, 3.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvClickable(enabled, { focused = it }, onClick)
            .clip(CircleShape)
            .background(
                if (filled) Color.White
                else Color.White.copy(alpha = if (focused) 0.35f else 0.18f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, diameter * 0.5f, if (filled) Color(0xFF1A1A1A) else Color.White)
        if (badge != null) {
            T(badge, 9.sp, FontWeight.Bold, if (filled) Color(0xFF1A1A1A) else Color.White)
        }
    }
}

@Composable
fun PillButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .focusBorder(focused, shape)
            .tvClickable(enabled, { focused = it }, onClick)
            .clip(shape)
            .background(Color.White.copy(alpha = if (focused) 0.35f else 0.18f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        T(text, 12.sp, FontWeight.Medium)
    }
}

@Composable
fun WaveLine(progress: Float, playing: Boolean, modifier: Modifier = Modifier) {
    val animOn = LocalAnimations.current
    // The State itself is read INSIDE the draw block, so the wave animates without recomposing.
    val phaseState = rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = 6.2832f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "wavePhase",
    )
    Canvas(modifier) {
        val phase = if (animOn && playing) phaseState.value else 0f
        val w = size.width
        val mid = size.height / 2f
        val px = w * progress.coerceIn(0f, 1f)
        val amp = if (playing) 4.dp.toPx() else 0f
        val period = 26.dp.toPx()

        val path = Path()
        path.moveTo(0f, mid)
        var x = 0f
        while (x <= px) {
            path.lineTo(x, mid + amp * sin(x / period * 6.2832f + phase))
            x += 4f
        }
        drawPath(
            path,
            Brush.horizontalGradient(listOf(Color(0xFFFF8A5B), Color(0xFFFF5E9C))),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
        drawLine(
            Color.White.copy(alpha = 0.35f),
            Offset(px, mid),
            Offset(w, mid),
            strokeWidth = 2.dp.toPx(),
        )
        drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(px, mid))
    }
}

@Composable
fun MusicStrip(track: Track, enabled: Boolean, vm: HomeViewModel, onExpand: () -> Unit) {
    var position by remember(track) { mutableStateOf(track.currentPositionMs()) }
    LaunchedEffect(track) {
        while (true) {
            position = track.currentPositionMs()
            delay(500)
        }
    }
    val art = remember(track.art) { track.art?.asImageBitmap() }
    val fraction = if (track.durationMs > 0) position.toFloat() / track.durationMs.toFloat() else 0f
    var titleFocused by remember { mutableStateOf(false) }
    val titleShape = RoundedCornerShape(14.dp)

    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Column(
                    Modifier
                        .focusBorder(titleFocused, titleShape)
                        .tvClickable(enabled, { titleFocused = it }, onExpand)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    T(track.title, 28.sp, FontWeight.Bold)
                    if (track.artist.isNotEmpty()) {
                        T(track.artist, 15.sp, color = Color.White.copy(alpha = 0.85f))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Ltr {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RoundButton(Glyph.Prev, 34.dp, false, enabled, { track.controller.transportControls.skipToPrevious() })
                            RoundButton(
                                if (track.playing) Glyph.Pause else Glyph.Play,
                                42.dp, true, enabled,
                                { if (track.playing) track.controller.transportControls.pause() else track.controller.transportControls.play() },
                            )
                            RoundButton(Glyph.Next, 34.dp, false, enabled, { track.controller.transportControls.skipToNext() })
                        }
                    }
                    PillButton("פתח אפליקציה", enabled, { vm.openPackage(track.appPackage) })
                }
            }
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        WaveLine(fraction, track.playing, Modifier.fillMaxWidth().height(16.dp))
    }
}

@Composable
fun MusicExpanded(track: Track, vm: HomeViewModel, focus: FocusRequester, modifier: Modifier = Modifier) {
    var position by remember(track) { mutableStateOf(track.currentPositionMs()) }
    LaunchedEffect(track) {
        while (true) {
            position = track.currentPositionMs()
            delay(500)
        }
    }
    val art = remember(track.art) { track.art?.asImageBitmap() }
    // Blurring a bitmap on the main thread would stutter the opening animation, so it is done in the background.
    val artBlur by produceState<ImageBitmap?>(initialValue = null, track.art) {
        val src = track.art
        value = if (src == null) null else withContext(Dispatchers.Default) {
            Wallpapers.blur(src, 24).asImageBitmap()
        }
    }
    val tc = track.controller.transportControls
    val fraction = if (track.durationMs > 0) (position.toFloat() / track.durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    GlassPanel(
        modifier = modifier
            .width(760.dp)
            .height(340.dp),
        shape = RoundedCornerShape(32.dp),
    ) {
        val blurred = artBlur
        if (blurred != null) {
            Canvas(Modifier.matchParentSize()) {
                drawImage(
                    image = blurred,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    alpha = 0.9f,
                    filterQuality = FilterQuality.Low,
                )
            }
        }
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.40f), Color.Black.copy(alpha = 0.05f)))),
        )
        Row(
            Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    T(track.title, 30.sp, FontWeight.Bold, maxLines = 2)
                    if (track.artist.isNotEmpty()) {
                        T(track.artist, 16.sp, color = Color.White.copy(alpha = 0.85f))
                    }
                }
                Column {
                    Ltr {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RoundButton(
                                Glyph.Replay15, 38.dp, false, true,
                                { tc.seekTo((track.currentPositionMs() - 15_000).coerceAtLeast(0L)) },
                                badge = "15",
                            )
                            RoundButton(Glyph.Prev, 38.dp, false, true, { tc.skipToPrevious() })
                            RoundButton(
                                if (track.playing) Glyph.Pause else Glyph.Play,
                                54.dp, true, true,
                                { if (track.playing) tc.pause() else tc.play() },
                                focusRequester = focus,
                            )
                            RoundButton(Glyph.Next, 38.dp, false, true, { tc.skipToNext() })
                            RoundButton(
                                Glyph.Forward15, 38.dp, false, true,
                                { tc.seekTo(track.currentPositionMs() + 15_000) },
                                badge = "15",
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("פתח אפליקציה", true, { vm.openPackage(track.appPackage) })
                        PillButton(
                            if (vm.sleepTimerOn) "יושהה בעוד 30 דקות ✓" else "השהה בעוד 30 דקות",
                            true,
                            { vm.toggleSleepTimer() },
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Ltr {
                        Column {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.3f)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(fraction)
                                        .fillMaxHeight()
                                        .background(Color.White),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                T(formatMs(position), 11.sp, color = Color.White.copy(alpha = 0.8f))
                                if (track.durationMs > 0) {
                                    T("-" + formatMs(track.durationMs - position), 11.sp, color = Color.White.copy(alpha = 0.8f))
                                }
                            }
                        }
                    }
                }
            }
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(250.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .align(Alignment.CenterVertically),
                )
            }
        }
    }
}
