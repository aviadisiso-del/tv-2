package com.glasslauncher.tv.media

import android.service.notification.NotificationListenerService

/**
 * Exists only so Android lets us see other apps' media sessions
 * (MediaSessionManager.getActiveSessions requires an enabled notification listener).
 * We never read or post notifications.
 *
 * Enable over ADB (Android TV usually hides the settings screen):
 *   adb shell cmd notification allow_listener com.glasslauncher.tv/com.glasslauncher.tv.media.MediaListenerService
 */
class MediaListenerService : NotificationListenerService()
