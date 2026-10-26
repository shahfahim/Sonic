package com.sonic.share.core.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Android Foreground Service managing ongoing file transfers with live
 * status notifications and cancelation handlers.
 */
class SonicTransferService : Service() {

    companion object {
        const val ACTION_START_TRANSFER = "com.sonic.share.action.START_TRANSFER"
        const val ACTION_UPDATE_PROGRESS = "com.sonic.share.action.UPDATE_PROGRESS"
        const val ACTION_CANCEL_TRANSFER = "com.sonic.share.action.CANCEL_TRANSFER"
        const val ACTION_STOP_TRANSFER = "com.sonic.share.action.STOP_TRANSFER"
        const val ACTION_COMPLETE_TRANSFER = "com.sonic.share.action.COMPLETE_TRANSFER"

        const val EXTRA_BATCH_ID = "extra_batch_id"
        const val EXTRA_FILENAME = "extra_filename"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_REMAINING_BYTES = "extra_remaining_bytes"
        const val EXTRA_IS_INCOMING = "extra_is_incoming"
        const val EXTRA_SUCCESS = "extra_success"

        /**
         * Optional listener triggered when the user taps "Cancel" in the transfer notification.
         */
        var onCancelRequested: ((batchId: String?) -> Unit)? = null

        /**
         * Starts the foreground transfer service for a new file transfer batch.
         */
        @JvmOverloads
        fun startService(
            context: Context,
            batchId: String,
            filename: String = "File",
            isIncoming: Boolean = true
        ) {
            val intent = Intent(context, SonicTransferService::class.java).apply {
                action = ACTION_START_TRANSFER
                putExtra(EXTRA_BATCH_ID, batchId)
                putExtra(EXTRA_FILENAME, filename)
                putExtra(EXTRA_IS_INCOMING, isIncoming)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Dispatches live progress updates to the active foreground notification.
         */
        fun updateProgress(
            context: Context,
            batchId: String,
            filename: String,
            progress: Float,
            speedBytesPerSec: Long,
            remainingBytes: Long,
            isIncoming: Boolean
        ) {
            val intent = Intent(context, SonicTransferService::class.java).apply {
                action = ACTION_UPDATE_PROGRESS
                putExtra(EXTRA_BATCH_ID, batchId)
                putExtra(EXTRA_FILENAME, filename)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_SPEED, speedBytesPerSec)
                putExtra(EXTRA_REMAINING_BYTES, remainingBytes)
                putExtra(EXTRA_IS_INCOMING, isIncoming)
            }
            context.startService(intent)
        }

        /**
         * Stops the foreground service and dismisses the transfer progress notification.
         */
        fun stopService(context: Context) {
            val intent = Intent(context, SonicTransferService::class.java).apply {
                action = ACTION_STOP_TRANSFER
            }
            context.startService(intent)
        }

        /**
         * Completes the transfer, removing the foreground status and showing
         * a final completion or failure notification.
         */
        fun completeTransfer(
            context: Context,
            filename: String,
            success: Boolean,
            isIncoming: Boolean
        ) {
            val intent = Intent(context, SonicTransferService::class.java).apply {
                action = ACTION_COMPLETE_TRANSFER
                putExtra(EXTRA_FILENAME, filename)
                putExtra(EXTRA_SUCCESS, success)
                putExtra(EXTRA_IS_INCOMING, isIncoming)
            }
            context.startService(intent)
        }
    }

    private var notificationManager: NotificationManager? = null
    private var currentBatchId: String? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        TransferNotificationHelper.createNotificationChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START_TRANSFER -> {
                val batchId = intent.getStringExtra(EXTRA_BATCH_ID) ?: ""
                val filename = intent.getStringExtra(EXTRA_FILENAME) ?: "File"
                val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, true)
                currentBatchId = batchId

                val cancelIntent = createCancelPendingIntent(batchId)
                val notification = TransferNotificationHelper.buildProgressNotification(
                    context = this,
                    filename = filename,
                    progress = 0f,
                    speedBytesPerSec = 0L,
                    remainingBytes = 0L,
                    isIncoming = isIncoming,
                    cancelIntent = cancelIntent
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        TransferNotificationHelper.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(TransferNotificationHelper.NOTIFICATION_ID, notification)
                }
            }

            ACTION_UPDATE_PROGRESS -> {
                val batchId = intent.getStringExtra(EXTRA_BATCH_ID) ?: currentBatchId ?: ""
                val filename = intent.getStringExtra(EXTRA_FILENAME) ?: "File"
                val progress = intent.getFloatExtra(EXTRA_PROGRESS, 0f)
                val speed = intent.getLongExtra(EXTRA_SPEED, 0L)
                val remainingBytes = intent.getLongExtra(EXTRA_REMAINING_BYTES, 0L)
                val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, true)

                val cancelIntent = createCancelPendingIntent(batchId)
                val notification = TransferNotificationHelper.buildProgressNotification(
                    context = this,
                    filename = filename,
                    progress = progress,
                    speedBytesPerSec = speed,
                    remainingBytes = remainingBytes,
                    isIncoming = isIncoming,
                    cancelIntent = cancelIntent
                )

                notificationManager?.notify(TransferNotificationHelper.NOTIFICATION_ID, notification)
            }

            ACTION_CANCEL_TRANSFER -> {
                val batchId = intent.getStringExtra(EXTRA_BATCH_ID) ?: currentBatchId
                onCancelRequested?.invoke(batchId)
                stopForegroundInternal()
                stopSelf()
            }

            ACTION_COMPLETE_TRANSFER -> {
                val filename = intent.getStringExtra(EXTRA_FILENAME) ?: "File"
                val success = intent.getBooleanExtra(EXTRA_SUCCESS, true)
                val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, true)

                val completedNotification = TransferNotificationHelper.buildCompletedNotification(
                    context = this,
                    filename = filename,
                    success = success,
                    isIncoming = isIncoming
                )
                stopForegroundInternal()
                notificationManager?.notify(TransferNotificationHelper.NOTIFICATION_ID + 1, completedNotification)
                stopSelf()
            }

            ACTION_STOP_TRANSFER -> {
                stopForegroundInternal()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForegroundInternal()
        super.onDestroy()
    }

    private fun stopForegroundInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        notificationManager?.cancel(TransferNotificationHelper.NOTIFICATION_ID)
    }

    private fun createCancelPendingIntent(batchId: String): PendingIntent {
        val cancelIntent = Intent(this, SonicTransferService::class.java).apply {
            action = ACTION_CANCEL_TRANSFER
            putExtra(EXTRA_BATCH_ID, batchId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getService(this, batchId.hashCode(), cancelIntent, flags)
    }
}
