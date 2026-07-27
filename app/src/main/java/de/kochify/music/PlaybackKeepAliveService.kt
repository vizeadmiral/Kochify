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
    private var currentTitle = "Kochify"
    private var currentArtist = "Musikwiedergabe"
    private var currentIsPlaying = false

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
                override fun onPlay() = dispatchPlay()
                override fun onPause() = dispatchPause()
                override fun onSkipToNext() = dispatchNext()
                override fun onSkipToPrevious() = dispatchPrevious()
                override fun onStop() = dispatchStop()
            })
            isActive = true
        }
        updateSessionState(isPlaying = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> dispatchPlay()
            ACTION_PAUSE -> dispatchPause()
            ACTION_NEXT -> dispatchNext()
            ACTION_PREVIOUS -> dispatchPrevious()
            ACTION_STOP -> dispatchStop()
            else -> {
                currentTitle = intent?.getStringExtra(EXTRA_TITLE)
                    ?.takeIf { it.isNotBlank() }
                    ?: currentTitle
                currentArtist = intent?.getStringExtra(EXTRA_ARTIST)
                    ?.takeIf { it.isNotBlank() }
                    ?: currentArtist
                currentIsPlaying = intent
                    ?.getBooleanExtra(EXTRA_IS_PLAYING, currentIsPlaying)
                    ?: currentIsPlaying
                publishPlaybackNotification()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dispatchPlay() {
        currentIsPlaying = true
        PlaybackCommandBridge.play()
        publishPlaybackNotification()
    }

    private fun dispatchPause() {
        currentIsPlaying = false
        PlaybackCommandBridge.pause()
        publishPlaybackNotification()
    }

    private fun dispatchNext() {
        PlaybackCommandBridge.next()
        publishPlaybackNotification()
    }

    private fun dispatchPrevious() {
        PlaybackCommandBridge.previous()
        publishPlaybackNotification()
    }

    private fun dispatchStop() {
        currentIsPlaying = false
        PlaybackCommandBridge.stop()
        publishPlaybackNotification()
    }

    private fun publishPlaybackNotification() {
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
                .build()
        )
        updateSessionState(currentIsPlaying)
        startForeground(
            NOTIFICATION_ID,
            createNotification(currentTitle, currentArtist, currentIsPlaying)
        )
    }

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

    private fun createNotification(
        title: String,
        artist: String,
        isPlaying: Boolean
    ): Notification {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseLabel = if (isPlaying) "Pause" else "Wiedergabe"
        val playPauseAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText("Kochify")
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_previous,
                    "Vorheriger Titel",
                    actionPendingIntent(ACTION_PREVIOUS, 1)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    playPauseIcon,
                    playPauseLabel,
                    actionPendingIntent(playPauseAction, 2)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_next,
                    "Nächster Titel",
                    actionPendingIntent(ACTION_NEXT, 3)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    actionPendingIntent(ACTION_STOP, 4)
                ).build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackKeepAliveService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val CHANNEL_ID = "kochify_playback"
        private const val NOTIFICATION_ID = 1202
        private const val ACTION_START = "de.kochify.music.action.START_PLAYBACK"
        private const val ACTION_PLAY = "de.kochify.music.action.PLAY"
        private const val ACTION_PAUSE = "de.kochify.music.action.PAUSE"
        private const val ACTION_NEXT = "de.kochify.music.action.NEXT"
        private const val ACTION_PREVIOUS = "de.kochify.music.action.PREVIOUS"
        private const val ACTION_STOP = "de.kochify.music.action.STOP"
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
    private var stopAction: (() -> Unit)? = null

    fun register(
        onPlay: () -> Unit,
        onPause: () -> Unit,
        onNext: () -> Unit,
        onPrevious: () -> Unit,
        onStop: () -> Unit
    ) {
        playAction = onPlay
        pauseAction = onPause
        nextAction = onNext
        previousAction = onPrevious
        stopAction = onStop
    }

    fun unregister() {
        playAction = null
        pauseAction = null
        nextAction = null
        previousAction = null
        stopAction = null
    }

    fun play() = playAction?.invoke() ?: Unit
    fun pause() = pauseAction?.invoke() ?: Unit
    fun next() = nextAction?.invoke() ?: Unit
    fun previous() = previousAction?.invoke() ?: Unit
    fun stop() = stopAction?.invoke() ?: Unit
}
