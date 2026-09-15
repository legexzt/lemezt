package com.lemezt.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lemezt.app.R

object NotificationHelper {

    const val CHANNEL_ID = "lemezt_downloads_v2"
    const val CHANNEL_UPDATES_ID = "lemezt_updates_channel"
    const val NOTIFICATION_ID = 1001
    const val COMPLETE_NOTIFICATION_ID = 1002
    const val UPDATE_NOTIFICATION_ID = 1003

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)

            val downloadChannel = NotificationChannel(
                CHANNEL_ID,
                "lemezt Downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Active media downloads progress and live speed"
                setShowBadge(true)
                enableVibration(false)
            }
            manager.createNotificationChannel(downloadChannel)

            val updateChannel = NotificationChannel(
                CHANNEL_UPDATES_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when newer lemezt versions are released"
                setShowBadge(true)
                enableVibration(true)
            }
            manager.createNotificationChannel(updateChannel)
        }
    }

    fun buildProgressNotification(
        context: Context,
        title: String,
        progress: Int,
        speedText: String = "",
        statusText: String = "",
        indeterminate: Boolean = false
    ): NotificationCompat.Builder {
        val contentText = when {
            indeterminate -> "Preparing fast multi-stream download..."
            speedText.isNotEmpty() -> "$progress% • $speedText • $statusText"
            else -> "$progress% completed • $statusText"
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("lemezt: $title")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_download)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    fun showCompleteNotification(
        context: Context,
        title: String,
        savedUri: Uri?,
        mimeType: String
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (savedUri != null) {
                setDataAndType(savedUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val pendingIntent = if (savedUri != null) {
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Download Complete! 🎉")
            .setContentText("$title has been saved to Downloads/lemezt")
            .setSmallIcon(R.drawable.ic_download)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        manager.notify(COMPLETE_NOTIFICATION_ID, builder.build())
    }

    fun showUpdateNotification(
        context: Context,
        versionName: String,
        notes: String,
        downloadUrl: String
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val intent = Intent(context, com.lemezt.app.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_TRIGGER_UPDATE", true)
            putExtra("EXTRA_UPDATE_VERSION", versionName)
            putExtra("EXTRA_UPDATE_NOTES", notes)
            putExtra("EXTRA_UPDATE_URL", downloadUrl)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            2001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_UPDATES_ID)
            .setContentTitle("🚀 lemezt Update Available: v$versionName")
            .setContentText("A new version is ready to install. Tap to update!")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Version $versionName is available.\n$notes"))
            .setSmallIcon(R.drawable.ic_download)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)

        manager.notify(UPDATE_NOTIFICATION_ID, builder.build())
    }
}
