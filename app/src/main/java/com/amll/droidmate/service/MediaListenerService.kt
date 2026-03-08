package com.amll.droidmate.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import timber.log.Timber

/**
 * Notification listener service.
 * After user grants notification access in system settings,
 * this service can observe media notifications from music apps.
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Timber.d("MediaListenerService connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        Timber.d("Notification posted from package: ${sbn.packageName}")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Timber.d("MediaListenerService disconnected")
    }
}
