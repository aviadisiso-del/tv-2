package com.siso.glasslauncher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent as AKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState

enum class Page { Main, Wallpapers, Widgets, City, Apps, Display, Sound, Remote, Clean }

private fun pageTitle(p: Page) = when (p) {
    Page.Main -> "הגדרות"
    Page.Wallpapers -> "טפטים"
    Page.Widgets -> "ווידג'טים"
    Page.City -> "עיר למזג האוויר"
    Page.Apps -> "אפליקציות במסך הבית"
    Page.Display -> "פרופילי תצוגה"
    Page.Sound -> "אקולייזר"
    Page.Remote -> "לחצני שלט"
    Page.Clean -> "ניקוי ופינוי מקום"
}

fun openSystem(ctx: Context, action: String) {
    try {
        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e2: Exception) {
            // nothing else we can do
        }
    }
}

// ───────────────────────── Building blocks ─────────────────────────

@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    checked: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvClickable(true, { focused = it }, onClick)
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.28f) else Color.Transparent)
            .then(if (focused) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.9f), shape) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        T(title, 14.sp, FontWeight.Medium, modifier = Modifier.weight(1f))
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            T(value, 12.sp, color = Color.White.copy(alpha = 0.7f))
        }
        if (checked) {
            Spacer(Modifier.width(8.dp))
            GlyphIcon(Glyph.Check, 16.dp)
        }
        if (chevron) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.graphicsLayer { scaleX = if (rtl) -1f else 1f }) {
                GlyphIcon(Glyph.Chevron, 16.dp, Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun InfoText(text: String) {
    T(
        text,
        11.sp,
        color = Color.White.copy(alpha = 0.7f),
        maxLines = 5,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** A bar you adjust with the remote's ◀ ▶ while it has focus. Up/Down still move between rows. */
@Composable
fun StepSlider(
    label: String,
    valueText: String,
    fraction: Float,
    onStep: (Int) -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown) {
                    when (ev.nativeKeyEvent.keyCode) {
                        AKeyEvent.KEYCODE_DPAD_LEFT -> { onStep(-1); true }
                        AKeyEvent.KEYCODE_DPAD_RIGHT -> { onStep(1); true }
                        else -> false
                    }
                } else false
            }
            .tvFocusable(true) { focused = it }
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.28f) else Color.Transparent)
            .then(if (focused) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.9f), shape) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T(label, 13.sp, FontWeight.Medium)
            Ltr { T(valueText, 12.sp, color = Color.White.copy(alpha = 0.8f)) }
        }
        Spacer(Modifier.size(6.dp))
        Ltr {
            Box(
                Modifier
                    .fillMaxWidth()
                    .size(width = 10.dp, height = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.25f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color.White),
                )
            }
        }
    }
}

// ───────────────────────── Display profiles ─────────────────────────

fun LazyListScope.displayItems(first: FocusRequester?) {
    if (!DisplayFx.serviceActive) {
        item {
            InfoText("כדי שהפרופיל יחול על כל המסך (גם בתוך נטפליקס ויוטיוב) צריך להפעיל את שירות הלחצנים: הגדרות ← נגישות. ההוראות ב-README.")
        }
    }
    itemsIndexed(DisplayFx.all, key = { _, p -> p.id }) { i, p ->
        SettingRow(
            p.name,
            value = p.note,
            checked = p.id == DisplayFx.selectedId,
            focusRequester = if (i == 0) first else null,
        ) { DisplayFx.select(p.id) }
    }
    item(key = "add_display") {
        SettingRow("➕ הוסף פרופיל חדש (עותק של הנוכחי)") { DisplayFx.newCustom(DisplayFx.current) }
    }
    val cur = DisplayFx.current
    if (cur.custom) {
        item(key = "edit_display_title") { InfoText("עריכת \"${cur.name}\" — ◀ ▶ משנים ערך") }
        item(key = "edit_dim") {
            StepSlider("עמעום", "${cur.dim}%", cur.dim / 70f, { s ->
                DisplayFx.setValues(cur.id, cur.dim + s * 5, cur.warm)
            })
        }
        item(key = "edit_warm") {
            StepSlider("גוון חם", "${cur.warm}%", cur.warm / 60f, { s ->
                DisplayFx.setValues(cur.id, cur.dim, cur.warm + s * 5)
            })
        }
        item(key = "del_display") { SettingRow("🗑 מחק את הפרופיל הזה") { DisplayFx.delete(cur.id) } }
    } else {
        item(key = "display_note") {
            InfoText("הסטרימר לא יכול לשנות ניגודיות או חדות של הטלוויזיה עצמה. הפרופילים כאן מעמעמים ומחממים את התמונה שיוצאת מהסטרימר.")
        }
    }
}

// ───────────────────────── Equalizer ─────────────────────────

private fun signed(v: Float): String {
    val i = Math.round(v)
    return if (i > 0) "+$i dB" else "$i dB"
}

fun LazyListScope.soundItems(first: FocusRequester?) {
    item(key = "eq_switch") {
        SettingRow(
            "אקולייזר",
            value = if (AudioFx.enabled) "פועל" else "כבוי",
            focusRequester = first,
        ) { AudioFx.setEnabled(!AudioFx.enabled) }
    }
    if (AudioFx.status.isNotEmpty()) {
        item(key = "eq_status") { InfoText(AudioFx.status) }
    }
    itemsIndexed(AudioFx.all, key = { _, p -> p.id }) { _, p ->
        SettingRow(p.name, checked = p.id == AudioFx.selectedId) { AudioFx.select(p.id) }
    }
    item(key = "add_eq") {
        SettingRow("➕ הוסף אקולייזר חדש (עותק של הנוכחי)") { AudioFx.newCustom(AudioFx.current) }
    }
    val cur = AudioFx.current
    if (cur.custom) {
        item(key = "edit_eq_title") { InfoText("עריכת \"${cur.name}\" — ◀ ▶ משנים ערך") }
        for (b in 0 until 5) {
            item(key = "band_$b") {
                StepSlider(
                    label = AudioFx.bandLabels[b] + " Hz",
                    valueText = signed(cur.gains[b]),
                    fraction = (cur.gains[b] - AudioFx.MIN_DB) / (AudioFx.MAX_DB - AudioFx.MIN_DB),
                    onStep = { s -> AudioFx.setGain(cur.id, b, cur.gains[b] + s) },
                )
            }
        }
        item(key = "del_eq") { SettingRow("🗑 מחק את האקולייזר הזה") { AudioFx.delete(cur.id) } }
    } else {
        item(key = "eq_note") {
            InfoText("רוצה לכוון תדרים בעצמך? בחר \"הוסף אקולייזר חדש\" ואז שנה כל תדר בנפרד.")
        }
    }
}

// ───────────────────────── Remote buttons ─────────────────────────

fun LazyListScope.remoteItems(first: FocusRequester?, ctx: Context) {
    item(key = "remote_info") {
        InfoText("אם לחצן בשלט לא פותח את מה שצריך: לחץ על השורה שלו ואז על הלחצן הרצוי בשלט (יש 10 שניות). אפשר לחזור אחורה עם OK.")
    }
    RemoteAction.values().forEachIndexed { i, a ->
        item(key = "remote_" + a.name) {
            Column {
                SettingRow(
                    a.label,
                    value = if (RemoteKeys.learning && RemoteKeys.learnTarget == a) "לחץ על לחצן בשלט…"
                    else RemoteKeys.keysFor(a).joinToString { RemoteKeys.nameOf(it) }.ifEmpty { "—" },
                    focusRequester = if (i == 0) first else null,
                ) { RemoteKeys.startLearning(a) }
                InfoText(a.hint)
            }
        }
    }
    if (RemoteKeys.message.isNotEmpty()) {
        item(key = "remote_msg") { InfoText(RemoteKeys.message) }
    }
    item(key = "remote_reset") { SettingRow("אפס לברירת מחדל") { RemoteKeys.reset() } }
    item(key = "remote_service") {
        SettingRow(
            "שירות הלחצנים",
            value = if (DisplayFx.serviceActive) "פועל" else "כבוי — לחץ להפעלה",
        ) { openSystem(ctx, Settings.ACTION_ACCESSIBILITY_SETTINGS) }
    }
}

// ───────────────────────── Cleaning ─────────────────────────

fun LazyListScope.cleanItems(first: FocusRequester?, ctx: Context, scan: Cleaner.Scan?) {
    item(key = "clean_space") {
        InfoText(
            if (scan == null) "בודק…"
            else "מקום פנוי: ${Cleaner.format(scan.freeBytes)} מתוך ${Cleaner.format(scan.totalBytes)}",
        )
    }
    item(key = "clean_cache") {
        SettingRow("ניקוי מטמון (דף האחסון)", chevron = true, focusRequester = first) {
            openSystem(ctx, Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        }
    }
    item(key = "clean_cache_hint") {
        InfoText("בדף שנפתח בחר \"נתונים שנשמרו במטמון\" ← אישור. לניקוי כל המטמון בבת אחת ולהשבתה של כמה אפליקציות יחד יש את הסקריפט במחשב.")
    }
    if (scan != null && scan.suggestions.isNotEmpty()) {
        item(key = "clean_sug_title") { InfoText("מומלץ להשבית (הפיך בכל עת): לחץ ← בדף האפליקציה לחץ \"השבת\"") }
        itemsIndexed(scan.suggestions, key = { _, a -> "sug_" + a.pkg }) { _, a ->
            SettingRow(a.label, value = a.why, chevron = true) { Cleaner.openAppInfo(ctx, a.pkg) }
        }
    }
    if (scan != null && scan.biggest.isNotEmpty()) {
        item(key = "clean_big_title") { InfoText("האפליקציות הגדולות ביותר: לחץ ← הסר / נקה נתונים / השבת") }
        itemsIndexed(scan.biggest, key = { _, a -> "big_" + a.pkg }) { _, a ->
            SettingRow(
                a.label + if (a.system) "  (מערכת)" else "",
                value = Cleaner.format(a.bytes),
                chevron = true,
            ) { Cleaner.openAppInfo(ctx, a.pkg) }
        }
    }
}

// ───────────────────────── Main settings panel ─────────────────────────

@Composable
fun SettingsPanel(vm: HomeViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var page by remember { mutableStateOf(Page.Main) }
    val firstFocus = remember { FocusRequester() }
    val mediaAccess by vm.media.hasAccess.collectAsState()
    val cleanScan by produceState<Cleaner.Scan?>(null, page) {
        value = if (page == Page.Clean) withContext(Dispatchers.IO) { Cleaner.scan(ctx) } else null
    }

    BackHandler(enabled = true) {
        if (RemoteKeys.learning) RemoteKeys.cancelLearning()
        else if (page != Page.Main) page = Page.Main
        else onClose()
    }
    LaunchedEffect(page) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    // Learning a button times out so the remote can never get "stuck".
    LaunchedEffect(RemoteKeys.learning) {
        if (RemoteKeys.learning) {
            delay(10_000)
            RemoteKeys.cancelLearning()
        }
    }

    GlassPanel(
        modifier = modifier
            .width(360.dp)
            .heightIn(max = 480.dp),
        shape = RoundedCornerShape(28.dp),
        tint = Color.Black.copy(alpha = 0.30f),
    ) {
        Column(Modifier.padding(14.dp)) {
            T(
                pageTitle(page),
                18.sp,
                FontWeight.SemiBold,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
            )
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                when (page) {
                    Page.Main -> {
                        item {
                            SettingRow(
                                "טפטים",
                                value = Wallpapers.all[vm.wallpaperIndex].name,
                                chevron = true,
                                focusRequester = firstFocus,
                            ) { page = Page.Wallpapers }
                        }
                        item { SettingRow("תצוגה (פרופילים)", value = DisplayFx.current.name, chevron = true) { page = Page.Display } }
                        item { SettingRow("סאונד (אקולייזר)", value = AudioFx.current.name, chevron = true) { page = Page.Sound } }
                        item { SettingRow("ווידג'טים", chevron = true) { page = Page.Widgets } }
                        item {
                            SettingRow("עיר למזג האוויר", value = CITIES[vm.cityIndex].name, chevron = true) {
                                page = Page.City
                            }
                        }
                        item {
                            SettingRow(
                                "אפליקציות במסך הבית",
                                value = "${vm.visibleApps.size} מתוך ${vm.allApps.size}",
                                chevron = true,
                            ) { page = Page.Apps }
                        }
                        item { SettingRow("ניקוי ופינוי מקום", chevron = true) { page = Page.Clean } }
                        item { SettingRow("לחצני שלט", chevron = true) { page = Page.Remote } }
                        item {
                            SettingRow("אנימציות", value = if (Fx.animations) "פועל" else "כבוי") {
                                Fx.setAnimations(!Fx.animations)
                            }
                        }
                        item {
                            SettingRow("Wi-Fi ורשת", chevron = true) { openSystem(ctx, Settings.ACTION_WIFI_SETTINGS) }
                        }
                        item {
                            SettingRow("Bluetooth", chevron = true) { openSystem(ctx, Settings.ACTION_BLUETOOTH_SETTINGS) }
                        }
                        item {
                            SettingRow(
                                "גישה לנגן המוזיקה",
                                value = if (mediaAccess) "פועל" else "כבוי",
                                chevron = true,
                            ) { openSystem(ctx, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
                        }
                        item {
                            SettingRow("הגדרות אנדרואיד המקוריות", chevron = true) {
                                openSystem(ctx, Settings.ACTION_SETTINGS)
                            }
                        }
                        item {
                            InfoText("טיפ: לחיצה ארוכה על OK באפליקציה ואז ◀ ▶ מזיזה אותה. לחיצה ארוכה על ⚙ בשלט פותחת את הגדרות אנדרואיד.")
                        }
                    }

                    Page.Wallpapers -> {
                        itemsIndexed(Wallpapers.all) { i, spec ->
                            SettingRow(
                                spec.name,
                                checked = i == vm.wallpaperIndex,
                                focusRequester = if (i == 0) firstFocus else null,
                                leading = {
                                    Box(
                                        Modifier
                                            .size(width = 34.dp, height = 20.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color(spec.skyTop), Color(spec.skyMid), Color(spec.skyBottom)),
                                                ),
                                            ),
                                    )
                                },
                            ) { vm.setWallpaper(i) }
                        }
                    }

                    Page.Widgets -> {
                        item {
                            SettingRow(
                                "מזג אוויר",
                                value = if (vm.showWeather) "פועל" else "כבוי",
                                focusRequester = firstFocus,
                            ) { vm.setShowWeather(!vm.showWeather) }
                        }
                        item {
                            SettingRow("מה מתנגן עכשיו", value = if (vm.showMusic) "פועל" else "כבוי") {
                                vm.setShowMusic(!vm.showMusic)
                            }
                        }
                        item {
                            SettingRow("שעון", value = if (vm.showClock) "פועל" else "כבוי") {
                                vm.setShowClock(!vm.showClock)
                            }
                        }
                        item {
                            SettingRow("המשך צפייה", value = if (vm.showContinue) "פועל" else "כבוי") {
                                vm.setShowContinue(!vm.showContinue)
                                vm.reloadContinue()
                            }
                        }
                    }

                    Page.City -> {
                        itemsIndexed(CITIES) { i, c ->
                            SettingRow(
                                c.name,
                                checked = i == vm.cityIndex,
                                focusRequester = if (i == 0) firstFocus else null,
                            ) { vm.setCity(i) }
                        }
                    }

                    Page.Apps -> {
                        itemsIndexed(vm.allApps, key = { _, a -> a.pkg }) { i, app ->
                            SettingRow(
                                app.label,
                                checked = app.pkg !in vm.hidden,
                                focusRequester = if (i == 0) firstFocus else null,
                                leading = {
                                    val icon = app.icon
                                    if (icon != null) {
                                        Image(
                                            bitmap = icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    } else {
                                        Spacer(Modifier.size(24.dp))
                                    }
                                },
                            ) { vm.toggleHidden(app.pkg) }
                        }
                    }

                    Page.Display -> displayItems(firstFocus)
                    Page.Sound -> soundItems(firstFocus)
                    Page.Remote -> remoteItems(firstFocus, ctx)
                    Page.Clean -> cleanItems(firstFocus, ctx, cleanScan)
                }
            }
        }
    }
}

// ───────────────────────── Quick menu (the remote's TV button) ─────────────────────────

@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .tvClickable(true, { focused = it }, onClick)
            .clip(shape)
            .background(
                if (selected) Color.White.copy(alpha = 0.32f)
                else Color.White.copy(alpha = if (focused) 0.2f else 0.08f),
            )
            .then(if (focused) Modifier.border(1.5.dp, Color.White, shape) else Modifier)
            .padding(horizontal = 22.dp, vertical = 8.dp),
    ) {
        T(text, 14.sp, if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

/** Display profiles + equalizer in one quick menu, like the picture/sound menu on a TV remote. */
@Composable
fun QuickPanel(tab: Int, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var current by remember(tab) { mutableStateOf(tab) }
    val first = remember { FocusRequester() }
    BackHandler(enabled = true) { onClose() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { first.requestFocus() }
    }
    GlassPanel(
        modifier = modifier
            .width(460.dp)
            .heightIn(max = 480.dp),
        shape = RoundedCornerShape(28.dp),
        tint = Color.Black.copy(alpha = 0.30f),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabChip("תצוגה", current == 0) { current = 0 }
                TabChip("סאונד", current == 1) { current = 1 }
            }
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (current == 0) displayItems(first) else soundItems(first)
            }
        }
    }
}
