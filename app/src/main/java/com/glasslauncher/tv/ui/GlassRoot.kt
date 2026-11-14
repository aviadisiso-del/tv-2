package com.glasslauncher.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glasslauncher.tv.ui.home.HomeScreen
import com.glasslauncher.tv.ui.home.LaunchOverlay
import com.glasslauncher.tv.ui.home.MenuTab
import com.glasslauncher.tv.ui.home.ReturnOverlay
import com.glasslauncher.tv.ui.player.PlayerScreen
import com.glasslauncher.tv.ui.settings.SettingsScreen
import com.glasslauncher.tv.ui.theme.Glass
import com.glasslauncher.tv.ui.theme.Motion
import com.glasslauncher.tv.ui.theme.tvSpring

@Composable
fun GlassRoot(
    vm: HomeViewModel,
    onStartApp: (LaunchRequest) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    onOpenPackage: (String) -> Unit,
    onOpenSettingsAction: (String) -> Unit,
) {
    val menuRequesters = remember { MenuTab.entries.associateWith { FocusRequester() } }
    val firstAppRequester = remember { FocusRequester() }

    // A launcher never exits on Back: close sub-screens, otherwise go up to the menu bar.
    BackHandler {
        when {
            vm.launch != null -> Unit
            vm.screen != Screen.Home -> vm.closeSubScreen()
            else -> runCatching { menuRequesters.getValue(vm.selectedTab).requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Glass.Background)
    ) {
        // tvOS depth transition: sub-screens rise toward you from slightly behind;
        // going back, they recede while Home settles into place.
        AnimatedContent(
            targetState = vm.screen,
            transitionSpec = {
                val toHome = targetState == Screen.Home
                (fadeIn(tween(Motion.FADE)) +
                    scaleIn(tvSpring(), initialScale = if (toHome) 1.06f else 0.94f))
                    .togetherWith(
                        fadeOut(tween(Motion.FADE_FAST)) +
                            scaleOut(tween(Motion.FADE), targetScale = if (toHome) 0.94f else 1.06f)
                    )
            },
            label = "screens",
        ) { screen ->
            when (screen) {
                Screen.Home -> HomeScreen(
                    vm = vm,
                    menuRequesters = menuRequesters,
                    firstAppRequester = firstAppRequester,
                    onQuickSetting = { qs ->
                        val action = qs.action
                        if (action == null) vm.openSettings() else onOpenSettingsAction(action)
                    },
                )
                Screen.Settings -> SettingsScreen(onOpenSystemSettings, onOpenStorageSettings)
                Screen.Player -> {
                    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
                    val pkg = nowPlaying?.packageName
                    PlayerScreen(
                        nowPlaying = nowPlaying,
                        appLabel = vm.appFor(pkg)?.label,
                        onPlayPause = vm::playPause,
                        onNext = vm::skipNext,
                        onPrevious = vm::skipPrevious,
                        onSeek = vm::seekTo,
                        onOpenApp = { if (pkg != null) onOpenPackage(pkg) },
                    )
                }
            }
        }
        vm.launch?.let { request -> LaunchOverlay(request, onStartApp) }
        vm.returnFrom?.let { request -> ReturnOverlay(request, vm::clearReturn) }
    }
}
