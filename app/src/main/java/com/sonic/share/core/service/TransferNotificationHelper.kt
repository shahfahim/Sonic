package com.sonic.share.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Helper utility for creating notification channels and building ongoing
 * transfer progress notifications as well as completed transfer alerts.
 */
object TransferNotificationHelper {

    const val CHANNEL_ID = "sonic_transfers"
    const val CHANNEL_NAME = "File Transfers"
    const val NOTIFICATION_ID = 1001

    /**
     * Formats raw bytes per second into human-readable transfer speed:
     * - < 1 MB/s: "${KB} KB/s"
     * - < 1 GB/s: "%.1f MB/s"
     * - >= 1 GB/s: "%.1f GB/s"
     */
    fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB/s", bytesPerSec / 1_000_000_000.0)
            bytesPerSec >= 1_000_000L -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1_000_000.0)
            else -> "${bytesPerSec / 1024L} KB/s"
        }
    }

    /**
     * Formats remaining transfer time estimate:
     * - speed <= 0: "--"
     * - remainingBytes <= 0: "0s"
     * - totalSeconds < 60: "${totalSeconds}s"
     * - totalSeconds >= 60: "${minutes}m ${seconds}s"
     */
    fun formatEta(remainingBytes: Long, speed: Long): String {
        if (speed <= 0L) return "--"
        if (remainingBytes <= 0L) return "0s"

        val totalSeconds = remainingBytes / speed
        return if (totalSeconds < 60) {
            "${totalSeconds}s"
        } else {
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            "${minutes}m ${seconds}s"
        }
    }

    /**
     * Creates the notification channel on Android 8.0+ (API 26+).
     * Uses [NotificationManager.IMPORTANCE_LOW] to avoid sound/vibration alerts on progress updates.
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live progress and status of active file transfers"
                setShowBadge(false)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Builds an ongoing progress notification for active file transfers.
     */
    fun buildProgressNotification(
        context: Context,
        filename: String,
        progress: Float,
        speedBytesPerSec: Long,
        remainingBytes: Long,
        isIncoming: Boolean,
        cancelIntent: PendingIntent? = null
    ): Notification {
        createNotificationChannel(context)

        val title = if (isIncoming) "Receiving $filename" else "Sending $filename"
        val icon = if (isIncoming) {
            android.R.drawable.stat_sys_download
        } else {
            android.R.drawable.stat_sys_upload
        }
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        val speedStr = formatSpeed(speedBytesPerSec)
        val etaStr = formatEta(remainingBytes, speedBytesPerSec)
        val contentText = "$percent% • $speedStr • ETA: $etaStr"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(contentText)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (cancelIntent != null) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelIntent
            )
        }

        return builder.build()
    }

    /**
     * Builds a non-ongoing completion or failure notification.
     */
    fun buildCompletedNotification(
        context: Context,
        filename: String,
        success: Boolean,
        isIncoming: Boolean
    ): Notification {
        createNotificationChannel(context)

        val title = when {
            success && isIncoming -> "Received $filename"
            success && !isIncoming -> "Sent $filename"
            !success && isIncoming -> "Failed receiving $filename"
            else -> "Failed sending $filename"
        }
        val contentText = if (success) "Transfer completed successfully" else "Transfer failed or was cancelled"
        val icon = if (success) {
            android.R.drawable.stat_sys_download_done
        } else {
            android.R.drawable.stat_notify_error
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(contentText)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }
}
