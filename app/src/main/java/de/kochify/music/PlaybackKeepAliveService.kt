package de.kochify.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder

class PlaybackKeepAliveService : Service() {
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Musikwiedergabe",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Hält Kochify während der Musikwiedergabe aktiv."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)

        mediaSession = MediaSession(this, "KochifyPlayback").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = PlaybackCommandBridge.play()
                override fun onPause() = PlaybackCommandBridge.pause()
                override fun onSkipToNext() = PlaybackCommandBridge.next()
                override fun onSkipToPrevious() = PlaybackCommandBridge.previous()
                override fun onStop() = PlaybackCommandBridge.pause()
            })
            isActive = true
        }
        updateSessionState(isPlaying = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: "Kochify"
        val artist = intent?.getStringExtra(EXTRA_ARTIST)
            ?.takeIf { it.isNotBlank() }
            ?: "Musikwiedergabe läuft"
        val isPlaying = intent?.getBooleanExtra(EXTRA_IS_PLAYING, false) == true
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .build()
        )
        updateSessionState(isPlaying)
        startForeground(
            NOTIFICATION_ID,
            createNotification(title, artist)
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateSessionState(isPlaying: Boolean) {
        val state = if (isPlaying) {
            PlaybackState.STATE_PLAYING
        } else {
            PlaybackState.STATE_PAUSED
        }
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_STOP
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    state,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (isPlaying) 1f else 0f
                )
                .build()
        )
    }

    private fun createNotification(title: String, artist: String): Notification {
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
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText("Kochify")
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "kochify_playback"
        private const val NOTIFICATION_ID = 1202
        private const val ACTION_START = "de.kochify.music.action.START_PLAYBACK"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ARTIST = "artist"
        private const val EXTRA_IS_PLAYING = "isPlaying"

        fun start(
            context: Context,
            title: String,
            artist: String,
            isPlaying: Boolean = true
        ) {
            val intent = Intent(context, PlaybackKeepAliveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackKeepAliveService::class.java))
        }
    }
}

internal object PlaybackCommandBridge {
    private var playAction: (() -> Unit)? = null
    private var pauseAction: (() -> Unit)? = null
    private var nextAction: (() -> Unit)? = null
    private var previousAction: (() -> Unit)? = null

    fun register(
        onPlay: () -> Unit,
        onPause: () -> Unit,
        onNext: () -> Unit,
        onPrevious: () -> Unit
    ) {
        playAction = onPlay
        pauseAction = onPause
        nextAction = onNext
        previousAction = onPrevious
    }

    fun unregister() {
        playAction = null
        pauseAction = null
        nextAction = null
        previousAction = null
    }

    fun play() = playAction?.invoke() ?: Unit
    fun pause() = pauseAction?.invoke() ?: Unit
    fun next() = nextAction?.invoke() ?: Unit
    fun previous() = previousAction?.invoke() ?: Unit
}
