package com.siso.glasslauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN = "open"
    }

    private val vm: HomeViewModel by viewModels()

    /** null = closed, "main" = settings, "quick" = display + sound menu. */
    private val panel = mutableStateOf<String?>(null)

    // Fallback for remotes when the key service is not enabled yet (the service catches the buttons in every app).
    private var gearDownAt = 0L
    private var gearLongHandled = false

    private var lastPackageReload = 0L
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // A changed app icon / label / update arrives here too; reload (throttled so a burst of updates is cheap).
            val now = System.currentTimeMillis()
            if (intent?.action == Intent.ACTION_PACKAGE_CHANGED && now - lastPackageReload < 3000L) return
            lastPackageReload = now
            vm.reloadApps(force = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        setContent {
            val open by panel
            val gearFocus = remember { FocusRequester() }
            val animOn = Fx.animations

            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalAnimations provides animOn,
            ) {
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                ) {
                    val screen = IntSize(constraints.maxWidth, constraints.maxHeight)
                    CompositionLocalProvider(
                        LocalScreenSize provides screen,
                        LocalWallpaperBlur provides vm.wallpaperBlur,
                    ) {
                        // Changing the wallpaper fades smoothly instead of jumping.
                        Crossfade(
                            targetState = vm.wallpaper,
                            animationSpec = if (animOn) tween(500) else tween(0),
                            label = "wallpaper",
                        ) { wallpaper ->
                            if (wallpaper != null) {
                                Canvas(Modifier.fillMaxSize()) {
                                    drawImage(
                                        image = wallpaper,
                                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                                        filterQuality = FilterQuality.Medium,
                                    )
                                }
                            }
                        }
                        // A little darkness toward the bottom keeps white text readable on bright wallpapers.
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Black.copy(alpha = 0.12f), Color.Transparent, Color.Black.copy(alpha = 0.38f)),
                                    ),
                                ),
                        )

                        HomeScreen(
                            vm = vm,
                            settingsOpen = open != null,
                            onOpenSettings = { panel.value = "main" },
                            onOpenQuick = { panel.value = "quick" },
                            gearFocus = gearFocus,
                        )

                        AnimatedVisibility(
                            visible = open == "main",
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 40.dp),
                            enter = if (animOn) fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                                slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 6 } else EnterTransition.None,
                            exit = if (animOn) fadeOut(tween(160)) +
                                slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { it / 6 } else ExitTransition.None,
                        ) {
                            SettingsPanel(vm = vm, onClose = { panel.value = null })
                        }
                        AnimatedVisibility(
                            visible = open == "quick",
                            modifier = Modifier.align(Alignment.Center),
                            enter = if (animOn) fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                                scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = 0.94f) else EnterTransition.None,
                            exit = if (animOn) fadeOut(tween(150)) +
                                scaleOut(tween(180, easing = FastOutSlowInEasing), targetScale = 0.97f) else ExitTransition.None,
                        ) {
                            QuickPanel(tab = 0, onClose = { panel.value = null })
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.media.start()
        vm.reloadApps()
        vm.onResumed()
    }

    override fun onStop() {
        vm.media.stop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // HOME pressed while we're already the foreground launcher.
        panel.value = null
        vm.onHomePressed()
        if (intent.getStringExtra(EXTRA_OPEN) == "apps") vm.requestApps()
    }

    // ── Remote buttons (fallback; the key service does the same in every other app) ──

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (RemoteKeys.learning && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (RemoteKeys.learned(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (RemoteKeys.actionFor(keyCode)) {
            RemoteAction.SETTINGS -> {
                if (event.repeatCount == 0) {
                    gearDownAt = event.eventTime
                    gearLongHandled = false
                } else if (!gearLongHandled && event.eventTime - gearDownAt >= 600L) {
                    gearLongHandled = true
                    panel.value = null
                    openSystem(this, Settings.ACTION_SETTINGS) // long press: the original Android settings
                }
                return true
            }
            RemoteAction.QUICK, RemoteAction.DISPLAY, RemoteAction.SOUND -> return true
            null -> {}
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (RemoteKeys.actionFor(keyCode)) {
            RemoteAction.SETTINGS -> {
                if (!gearLongHandled) panel.value = if (panel.value == "main") null else "main" // short press: our settings
                gearLongHandled = false
                return true
            }
            RemoteAction.QUICK, RemoteAction.DISPLAY, RemoteAction.SOUND -> {
                panel.value = if (panel.value == "quick") null else "quick"
                return true
            }
            null -> {}
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(packageReceiver)
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
