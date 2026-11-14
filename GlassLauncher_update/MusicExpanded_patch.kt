// ═══════════════════════════════════════════════════════════════
// DROP-IN REPLACEMENT for the MusicExpanded composable in HomeScreen.kt
// Copy everything between the two === markers and replace the existing
// MusicExpanded function (lines ~1227–1358) with this.
// ═══════════════════════════════════════════════════════════════

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
    val tones by produceState<List<Color>?>(initialValue = null, track.art) {
        val src = track.art
        value = if (src == null) null else withContext(Dispatchers.Default) { coverTones(src) }
    }
    val tc = track.controller.transportControls
    val fraction = if (track.durationMs > 0) (position.toFloat() / track.durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    // Lyrics from ViewModel
    val lyrics = vm.lyrics
    val lyricsLoading = vm.lyricsLoading

    // Lyrics scroll state: find the active line index
    val activeLine = remember(lyrics, position) {
        val synced = lyrics?.firstOrNull { it.startMs >= 0 }
        if (synced == null) -1
        else {
            var idx = -1
            lyrics?.forEachIndexed { i, line ->
                if (line.startMs >= 0 && line.startMs <= position) idx = i
            }
            idx
        }
    }
    val lyricsListState = rememberLazyListState()
    LaunchedEffect(activeLine) {
        if (activeLine >= 0) {
            lyricsListState.animateScrollToItem(
                (activeLine - 1).coerceAtLeast(0),
                scrollOffset = 0,
            )
        }
    }

    // Background gradient
    Box(modifier.fillMaxSize().background(Color(0xFF0B0D14))) {
        val t = tones
        val c1 by animateColorAsState(t?.getOrNull(0) ?: Color(0xFF1A2233), tween(700), label = "c1")
        val c2 by animateColorAsState(t?.getOrNull(1) ?: Color(0xFF111827), tween(700), label = "c2")
        val c3 by animateColorAsState(t?.getOrNull(2) ?: Color(0xFF0B0D14), tween(700), label = "c3")
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.linearGradient(listOf(c1, c2, c3), start = Offset(0f, 0f), end = Offset(size.width, size.height)))
            drawRect(
                Brush.radialGradient(
                    listOf(c2.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(size.width * 0.28f, size.height * 0.5f),
                    radius = size.height * 0.9f,
                ),
            )
        }

        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 90.dp, vertical = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(50.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── Left column: controls ─────────────────────────────────────────
            Column(
                Modifier.weight(0.95f).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Track title + artist
                Column {
                    T("מתנגן עכשיו", 13.sp, color = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.height(8.dp))
                    T(track.title, 40.sp, FontWeight.Bold, maxLines = 2)
                    if (track.artist.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        T(track.artist, 20.sp, color = Color.White.copy(alpha = 0.85f))
                    }
                }

                // Playback controls + progress
                Column {
                    Ltr {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            RoundButton(Glyph.Replay15, 48.dp, false, true,
                                { tc.seekTo((track.currentPositionMs() - 15_000).coerceAtLeast(0L)) }, badge = "15")
                            RoundButton(Glyph.Prev, 48.dp, false, true, { tc.skipToPrevious() })
                            RoundButton(
                                if (track.playing) Glyph.Pause else Glyph.Play,
                                70.dp, true, true,
                                { if (track.playing) tc.pause() else tc.play() },
                                focusRequester = focus,
                            )
                            RoundButton(Glyph.Next, 48.dp, false, true, { tc.skipToNext() })
                            RoundButton(Glyph.Forward15, 48.dp, false, true,
                                { tc.seekTo(track.currentPositionMs() + 15_000) }, badge = "15")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("פתח אפליקציה", true, { vm.openPackage(track.appPackage) })
                        PillButton(
                            if (vm.sleepTimerOn) "יושהה בעוד 30 דקות ✓" else "השהה בעוד 30 דקות",
                            true, { vm.toggleSleepTimer() },
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    WaveLine(fraction, track.playing, Modifier.fillMaxWidth().height(18.dp))
                    Spacer(Modifier.height(4.dp))
                    Ltr {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            T(formatMs(position), 12.sp, color = Color.White.copy(alpha = 0.8f))
                            if (track.durationMs > 0)
                                T("-" + formatMs(track.durationMs - position), 12.sp, color = Color.White.copy(alpha = 0.8f))
                        }
                    }
                }
            }

            // ── Right column: cover art + lyrics ─────────────────────────────
            Column(
                Modifier.weight(1.05f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Cover art
                if (art != null) {
                    Image(
                        bitmap = art,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(260.dp)
                            .clip(RoundedCornerShape(24.dp)),
                    )
                }
                Spacer(Modifier.height(24.dp))

                // ── Lyrics panel ─────────────────────────────────────────────
                val hasSynced = lyrics?.any { it.startMs >= 0 } == true
                when {
                    lyricsLoading -> {
                        T("טוען מילים…", 14.sp, color = Color.White.copy(alpha = 0.5f))
                    }
                    lyrics.isNullOrEmpty() -> {
                        T("מילים לא נמצאו", 13.sp, color = Color.White.copy(alpha = 0.35f))
                    }
                    else -> {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                        ) {
                            androidx.compose.foundation.lazy.LazyColumn(
                                state = lyricsListState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 80.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                itemsIndexed(lyrics, key = { i, _ -> i }) { i, line ->
                                    val isActive = hasSynced && i == activeLine
                                    val isPast   = hasSynced && i < activeLine
                                    val animOn   = LocalAnimations.current
                                    val lineScale by animateFloatAsState(
                                        if (isActive) 1.07f else 1f,
                                        if (animOn) spring(dampingRatio = 0.72f, stiffness = 350f) else snap(),
                                        label = "lyricScale$i",
                                    )
                                    val alpha = when {
                                        isActive -> 1f
                                        isPast   -> 0.38f
                                        else     -> 0.62f
                                    }
                                    androidx.compose.foundation.text.BasicText(
                                        text = line.text,
                                        style = androidx.compose.ui.text.TextStyle(
                                            color = Color.White.copy(alpha = alpha),
                                            fontSize = if (isActive) 20.sp else 17.sp,
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer { scaleX = lineScale; scaleY = lineScale },
                                    )
                                }
                            }
                            // fade top + bottom edges so text fades out gracefully
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(80.dp)
                                    .align(Alignment.TopCenter)
                                    .background(
                                        Brush.verticalGradient(listOf(c3.copy(alpha = 0.95f), Color.Transparent)),
                                    ),
                            )
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(80.dp)
                                    .align(Alignment.BottomCenter)
                                    .background(
                                        Brush.verticalGradient(listOf(Color.Transparent, c3.copy(alpha = 0.95f))),
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Picks three rich, darkened colors from a cover: top-left, center, bottom-right. */
private fun coverTones(src: android.graphics.Bitmap): List<Color> {
    val small = android.graphics.Bitmap.createScaledBitmap(src, 3, 3, true)
    fun tone(x: Int, y: Int): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(small.getPixel(x, y), hsv)
        hsv[1] = (hsv[1] * 1.15f).coerceIn(0.15f, 0.9f)
        hsv[2] = (hsv[2] * 0.6f).coerceIn(0.16f, 0.5f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
    return listOf(tone(0, 0), tone(1, 1), tone(2, 2))
}
