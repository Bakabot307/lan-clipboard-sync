package com.gnaht.phoneclipboardsync.clipboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gnaht.phoneclipboardsync.LanClipboardApplication
import com.gnaht.phoneclipboardsync.MainActivity
import com.gnaht.phoneclipboardsync.R

class ClipboardMonitorService : Service() {
    private val controller by lazy { (application as LanClipboardApplication).controller }
    private val clipboardManager by lazy {
        getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
    }

    private val clipboardListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        val currentText = clipboardManager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .orEmpty()

        controller.onClipboardChanged(currentText)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (canReadClipboardFromService()) {
            clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        }
        controller.setMonitorRunning(true)
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (canReadClipboardFromService()) {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        }
        controller.setMonitorRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT_APP) {
            controller.shutdownForTaskRemoval()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        controller.shutdownForTaskRemoval()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clipboard)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(getString(R.string.monitor_notification_body))
            .setContentIntent(buildSendPendingIntent())
            .setOngoing(true)
            .addAction(
                R.drawable.ic_stat_clipboard,
                getString(R.string.monitor_notification_exit_app),
                buildExitPendingIntent(),
            )
            .addAction(
                R.drawable.ic_stat_clipboard,
                getString(R.string.monitor_notification_open_app),
                buildOpenAppPendingIntent(),
            )
            .build()
    }

    private fun buildSendPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SEND_CURRENT_CLIPBOARD
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            SEND_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildExitPendingIntent(): PendingIntent {
        val intent = Intent(this, ClipboardMonitorService::class.java).apply {
            action = ACTION_EXIT_APP
        }
        return PendingIntent.getService(
            this,
            EXIT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.monitor_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun canReadClipboardFromService(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    private companion object {
        private const val CHANNEL_ID = "clipboard_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val SEND_REQUEST_CODE = 2000
        private const val OPEN_APP_REQUEST_CODE = 2001
        private const val EXIT_REQUEST_CODE = 2002
        private const val ACTION_EXIT_APP = "com.gnaht.phoneclipboardsync.EXIT_APP"
    }
}
