package com.reptar.fishreel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/** Notification channel used for comment/reply pushes - see FishReelMessagingService. */
const val COMMENTS_NOTIFICATION_CHANNEL_ID = "comments"

/**
 * Creates the notification channel used for comment/reply pushes as soon as
 * the process starts, rather than lazily inside FishReelMessagingService.
 * Android requires a channel to exist (API 26+) before a notification can
 * be shown - if it were only created on first foreground receipt, the very
 * first notification arriving while the app is backgrounded or not yet
 * launched (the common case) would have nothing to post to. Application.
 * onCreate runs before any component - Activity, Service, or otherwise -
 * so this guarantees the channel is ready either way.
 */
class FishReelApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                COMMENTS_NOTIFICATION_CHANNEL_ID,
                "Comments & Replies",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies you when someone comments on your post or replies to your comment."
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
