package de.kochify.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat

class DownloadKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Musikdownloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Zeigt laufende Kochify-Downloads im Hintergrund an."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val status = intent?.getStringExtra(EXTRA_STATUS)
            ?.takeIf { it.isNotBlank() }
            ?: "Download läuft …"
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0)?.coerceIn(0, 100) ?: 0
        startForeground(NOTIFICATION_ID, notification(status, progress))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(status: String, progress: Int): Notification {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Kochify lädt Musik herunter")
            .setContentText(status.replace('\n', ' ').take(120))
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "kochify_downloads"
        private const val NOTIFICATION_ID = 1203
        private const val ACTION_START = "de.kochify.music.action.START_DOWNLOAD"
        private const val ACTION_UPDATE = "de.kochify.music.action.UPDATE_DOWNLOAD"
        private const val ACTION_STOP = "de.kochify.music.action.STOP_DOWNLOAD"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_PROGRESS = "progress"

        fun start(context: Context, status: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadKeepAliveService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_STATUS, status)
                    putExtra(EXTRA_PROGRESS, 0)
                }
            )
        }

        fun update(context: Context, status: String, progress: Float) {
            runCatching {
                context.startService(
                    Intent(context, DownloadKeepAliveService::class.java).apply {
                        action = ACTION_UPDATE
                        putExtra(EXTRA_STATUS, status)
                        putExtra(EXTRA_PROGRESS, (progress * 100f).toInt().coerceIn(0, 100))
                    }
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, DownloadKeepAliveService::class.java).apply {
                        action = ACTION_STOP
                    }
                )
            }.onFailure {
                context.stopService(Intent(context, DownloadKeepAliveService::class.java))
            }
        }
    }
}
