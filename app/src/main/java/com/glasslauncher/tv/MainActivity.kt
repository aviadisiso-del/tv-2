package com.glasslauncher.tv

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import com.glasslauncher.tv.data.AppEntry
import com.glasslauncher.tv.ui.GlassRoot
import com.glasslauncher.tv.ui.HomeViewModel
import com.glasslauncher.tv.ui.LaunchRequest
import com.glasslauncher.tv.ui.theme.GlassTheme

class MainActivity : ComponentActivity() {

    private val vm: HomeViewModel by viewModels()

    /** Set when the gear key's long-press fired, so its key-up doesn't also open our settings. */
    private var gearLongPressed = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = vm.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlassTheme {
                // Whole UI is Hebrew / RTL regardless of the device language.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    GlassRoot(
                        vm = vm,
                        onStartApp = ::startApp,
                        onOpenSystemSettings = ::openSystemSettings,
                        onOpenStorageSettings = ::openStorageSettings,
                        onOpenPackage = ::openPackage,
                        onOpenSettingsAction = ::openSettingsAction,
                    )
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    /** Home button while we're already in front: reset to the first app. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Intent.ACTION_MAIN == intent.action) vm.onHomePressed()
    }

    override fun onStart() {
        super.onStart()
        vm.onStart()
    }

    override fun onStop() {
        super.onStop()
        vm.onStop()
    }

    // --- Gear key: short press = our settings, long press = Android settings ---

    private fun isGearKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_MENU

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isGearKey(keyCode)) {
            if (event.repeatCount == 0) {
                gearLongPressed = false
                event.startTracking()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (isGearKey(keyCode)) {
            gearLongPressed = true
            openSystemSettings()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isGearKey(keyCode)) {
            if (!gearLongPressed && event.isTracking && !event.isCanceled) vm.openSettings()
            gearLongPressed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // --- Launching ---

    /** Deep link from the TV provider first (Watch Next item), then the app itself. */
    private fun startApp(request: LaunchRequest) {
        val options = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, 0).toBundle()
        val deepLink = request.intentUri?.let { uri -> deepLinkIntent(uri, request.app) }
        val started = (deepLink != null && runCatching { startActivity(deepLink, options) }.isSuccess) ||
            runCatching { startActivity(vm.launchIntent(request.app), options) }.isSuccess

        if (started) {
            vm.recordLaunch(request.app.packageName)
        } else {
            vm.clearLaunch()
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Parses a provider intent URI, keeping it pinned to the app that published it. */
    private fun deepLinkIntent(uri: String, app: AppEntry): Intent? = runCatching {
        Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).apply {
            selector = null
            if (component == null && `package` == null) setPackage(app.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }.getOrNull()

    /** Brings an app (e.g. the music app behind the player) to the front. */
    private fun openPackage(packageName: String) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null || runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Storage settings (where other apps' caches can be cleared); falls back to main settings. */
    private fun openStorageSettings() {
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!opened) openSystemSettings()
    }

    /** Quick-settings shortcuts; TV builds lack some screens, so fall back to main settings. */
    private fun openSettingsAction(action: String) {
        val opened = runCatching {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!opened) openSystemSettings()
    }

    private fun openSystemSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
