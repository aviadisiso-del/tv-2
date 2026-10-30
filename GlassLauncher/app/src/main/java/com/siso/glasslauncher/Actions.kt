package com.siso.glasslauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * Entry points for button-remapping apps (Button Mapper, Key Mapper...). In such an app choose, for the button you want:
 *   "Launch app / shortcut / activity"  ->  one of the "מסך בית זכוכית: ..." items below.
 * They also answer to these intent actions:
 *   com.siso.glasslauncher.action.SETTINGS | QUICK | SYSTEM_SETTINGS | HOME
 */
abstract class ActionActivity(private val title: String) : Activity() {

    abstract fun run()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_CREATE_SHORTCUT) {
            // Remapping apps ask "which shortcut?"; answer with this very action.
            val launch = Intent(this, this::class.java).setAction(Intent.ACTION_MAIN)
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch)
                    .putExtra(Intent.EXTRA_SHORTCUT_NAME, title),
            )
        } else {
            run()
        }
        finish()
    }

    protected fun overlay(page: String) {
        startActivity(
            Intent(this, OverlayActivity::class.java)
                .putExtra(KeyService.EXTRA_PAGE, page)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

class ActionSettingsActivity : ActionActivity("מסך בית זכוכית: הגדרות") {
    override fun run() = overlay("main")
}

class ActionQuickActivity : ActionActivity("מסך בית זכוכית: תפריט מהיר (תצוגה וסאונד)") {
    override fun run() = overlay("quick")
}

class ActionSystemSettingsActivity : ActionActivity("מסך בית זכוכית: הגדרות אנדרואיד") {
    override fun run() = openSystem(this, Settings.ACTION_SETTINGS)
}

class ActionHomeActivity : ActionActivity("מסך בית זכוכית: מסך הבית") {
    override fun run() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }
}

class ActionAppsActivity : ActionActivity("מסך בית זכוכית: כל האפליקציות") {
    override fun run() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, "apps")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }
}
