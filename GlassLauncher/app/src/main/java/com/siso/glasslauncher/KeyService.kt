package com.siso.glasslauncher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.media.audiofx.AudioEffect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * One always-on service that:
 *  1. hears the remote's buttons in EVERY app (settings ⚙ and the TV button),
 *  2. draws the display profile (dim / warm tint) over everything,
 *  3. keeps the equalizer alive.
 * Turn it on once: Settings → Accessibility, or with the setup script.
 */
class KeyService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: KeyService? = null
        const val EXTRA_PAGE = "page"
        private const val LONG_PRESS_MS = 600L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var longRunnable: Runnable? = null
    private var longFired = false

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> AudioFx.onSessionOpen(session)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> AudioFx.onSessionClose(session)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        DisplayFx.serviceActive = true
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        try {
            ContextCompat.registerReceiver(this, sessionReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            // equalizer still works through the global session
        }
        AudioFx.apply()
        DisplayFx.apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // not used
    }

    override fun onInterrupt() {
        // not used
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (instance === this) instance = null
        DisplayFx.serviceActive = false
        removeOverlay()
        AudioFx.release()
        try {
            unregisterReceiver(sessionReceiver)
        } catch (e: Exception) {
            // not registered
        }
    }

    // ───────────── Remote buttons ─────────────

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // While the "learn a button" screen is waiting, grab whatever is pressed.
        if (RemoteKeys.learning) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                return RemoteKeys.learned(event.keyCode)
            }
            return false
        }

        val action = RemoteKeys.actionFor(event.keyCode) ?: return false

        if (action == RemoteAction.SETTINGS) {
            // Short press = our settings. Long press = the original Android settings.
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    longFired = false
                    val r = Runnable {
                        longFired = true
                        closeOverlayIfOpen()
                        openSystemSettings()
                    }
                    longRunnable = r
                    handler.postDelayed(r, LONG_PRESS_MS)
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                longRunnable?.let { handler.removeCallbacks(it) }
                longRunnable = null
                if (!longFired) toggleOverlay("main")
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val page = when (action) {
                RemoteAction.QUICK -> "quick"
                RemoteAction.DISPLAY -> "display"
                RemoteAction.SOUND -> "sound"
                RemoteAction.SETTINGS -> "main"
            }
            toggleOverlay(page)
        }
        return true // we own this button; swallow down, repeats and up
    }

    private fun toggleOverlay(page: String) {
        val cur = OverlayActivity.current
        if (cur != null) {
            if (cur.page.value == page) cur.finish() else cur.page.value = page
            return
        }
        val i = Intent(this, OverlayActivity::class.java)
            .putExtra(EXTRA_PAGE, page)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            startActivity(i)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun closeOverlayIfOpen() {
        OverlayActivity.current?.finish()
    }

    private fun openSystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // ignore
        }
    }

    // ───────────── Display profile overlay ─────────────

    fun setTint(dim: Int, warm: Int) {
        handler.post {
            if (dim <= 0 && warm <= 0) {
                removeOverlay()
                return@post
            }
            val dimColor = Color.argb((dim.coerceIn(0, 70) * 255 / 100), 0, 0, 0)
            val warmColor = Color.argb((warm.coerceIn(0, 60) * 255 / 100), 255, 140, 40)
            val bg = LayerDrawable(arrayOf(ColorDrawable(warmColor), ColorDrawable(dimColor)))
            val existing = overlay
            if (existing != null) {
                existing.background = bg
                return@post
            }
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val v = View(this)
                v.background = bg
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                )
                wm.addView(v, lp)
                overlay = v
            } catch (e: Exception) {
                overlay = null
            }
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(v)
        } catch (e: Exception) {
            // already gone
        }
    }
}
