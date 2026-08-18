package com.reptar.fishreel.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.reptar.fishreel.COMMENTS_NOTIFICATION_CHANNEL_ID
import com.reptar.fishreel.MainActivity
import com.reptar.fishreel.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Receives FCM tokens and messages for the comment/reply notifications sent
 * by the shared onCommentCreated Cloud Function - mirrors iOS's AppDelegate
 * + PushNotificationManager (PushNotifications.swift). A fresh token is
 * saved to this device's signed-in user's users/{uid}.fcmToken doc, the
 * same field iOS writes and the same field the Cloud Function reads.
 *
 * Unlike iOS, Android only auto-displays a system notification when the app
 * is backgrounded or not running - while it's in the foreground,
 * onMessageReceived fires instead and the app is responsible for building
 * and showing the notification itself.
 */
class FishReelMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        saveToken(token)
    }

    private fun saveToken(token: String) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                FirebaseFirestore.getInstance().collection("users").document(userId)
                    .set(mapOf("fcmToken" to token), SetOptions.merge())
                    .await()
            } catch (_: Exception) {
                // Best-effort - a stale/unsaved token just means this device
                // misses the next notification, not worth surfacing to the user.
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title ?: return
        val body = message.notification?.body.orEmpty()
        val postId = message.data["postID"]

        showNotification(title, body, postId)
    }

    private fun showNotification(title: String, body: String, postId: String?) {
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        // Same "postID" key the Cloud Function sends and MainActivity reads
        // out of a cold-launch intent's extras when the system handles
        // displaying/launching this automatically (backgrounded case) -
        // keeping one key means MainActivity only needs one extraction path.
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (postId != null) {
                putExtra("postID", postId)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            postId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, COMMENTS_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(this).notify(postId?.hashCode() ?: 0, notification)
    }
}
