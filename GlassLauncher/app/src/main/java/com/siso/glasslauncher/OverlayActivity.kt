package com.siso.glasslauncher

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Translucent menu that appears ON TOP of whatever is playing, so a video keeps going behind it.
 * Opened by the remote's ⚙ button (page "main") or TV button (page "quick" / "display" / "sound").
 */
class OverlayActivity : ComponentActivity() {

    companion object {
        @Volatile
        var current: OverlayActivity? = null
    }

    val page = mutableStateOf("main")
    private val vm: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        page.value = intent?.getStringExtra(KeyService.EXTRA_PAGE) ?: "main"

        setContent {
            val p by page
            val appear = remember { Animatable(if (Fx.animations) 0f else 1f) }
            LaunchedEffect(Unit) {
                if (Fx.animations) appear.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
            }
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalAnimations provides Fx.animations,
            ) {
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Read inside the draw phase: smooth, no recomposition per frame.
                            val v = appear.value
                            alpha = v
                            scaleX = 0.97f + 0.03f * v
                            scaleY = 0.97f + 0.03f * v
                        }
                        .background(Color.Black.copy(alpha = 0.40f))
                        .pointerInput(Unit) { detectTapGestures { finish() } },
                ) {
                    CompositionLocalProvider(
                        LocalScreenSize provides IntSize(constraints.maxWidth, constraints.maxHeight),
                    ) {
                        if (p == "main") {
                            SettingsPanel(
                                vm = vm,
                                onClose = { finish() },
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 40.dp),
                            )
                        } else {
                            QuickPanel(
                                tab = if (p == "sound") 1 else 0,
                                onClose = { finish() },
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        page.value = intent.getStringExtra(KeyService.EXTRA_PAGE) ?: "main"
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (RemoteKeys.learning && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (RemoteKeys.learned(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
}
