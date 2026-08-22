package de.kochify.music

import android.app.Application
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.util.Base64
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

data class AudioTrack(
    val id: String,
    val title: String,
    val artist: String,
    val path: String,
    val coverPath: String? = null,
    val favorite: Boolean = false,
    val bookmarked: Boolean = false,
    val playlists: Set<String> = emptySet()
)

data class PendingSpotifyTrack(
    val playlist: String,
    val title: String,
    val artist: String
)

data class SpotifyPlaylistImport(
    val name: String,
    val spotifyUrl: String,
    val tracks: List<PendingSpotifyTrack>
)

data class PlaybackHistoryEntry(
    val trackId: String,
    val title: String,
    val artist: String,
    val playedAt: Long
)

data class PlaybackStats(
    val totalListeningMs: Long,
    val totalPlays: Int,
    val uniqueTracks: Int,
    val mostPlayed: List<Pair<AudioTrack, Int>>,
    val recent: List<PlaybackHistoryEntry>
)

data class WrappedSummary(
    val label: String,
    val listeningMs: Long,
    val totalPlays: Int,
    val uniqueTracks: Int,
    val topTracks: List<Pair<AudioTrack, Int>>
)

data class StorageUsage(
    val totalBytes: Long,
    val audioBytes: Long,
    val coverBytes: Long,
    val cacheBytes: Long,
    val songCount: Int,
    val playlistCount: Int
)

data class TrashTrack(val track: AudioTrack, val deletedAt: Long)

data class TrashPlaylist(
    val name: String,
    val coverPath: String?,
    val memberTrackIds: List<String>,
    val deletedAt: Long
)

data class DuplicateCandidate(
    val token: String,
    val track: AudioTrack,
    val existing: AudioTrack
)

data class LocalTransferOffer(
    val title: String,
    val qrPayload: String
)

enum class KochifyThemeMode {
    BLACK,
    LIGHT,
    RGB,
    CYBERPUNK,
    GERMANY
}

enum class LibrarySort {
    ADDED_NEWEST,
    ADDED_OLDEST,
    TITLE_AZ,
    TITLE_ZA
}

private data class YoutubeDownloadItem(
    val id: String,
    val title: String,
    val url: String,
    val thumbnailUrl: String?
)

private data class YoutubeDownloadPlan(
    val title: String,
    val items: List<YoutubeDownloadItem>,
    val isPlaylist: Boolean,
    val unavailableItems: Int,
    val thumbnailUrl: String?
)

private data class PlaybackNavigationEntry(
    val trackId: String,
    val queueIds: List<String>,
    val sourceKey: String
)

private const val SPOTIFY_AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
private const val SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token"
private const val SPOTIFY_API_URL = "https://api.spotify.com/v1"
private const val SPOTIFY_REDIRECT_URI = "kochify://spotify-callback"
private const val YTDLP_UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L
private const val LOCAL_TRANSFER_MAGIC = "KOCHIFY_LOCAL_1"
private const val LOCAL_TRANSFER_MAX_BYTES = 8L * 1024L * 1024L * 1024L
private const val PIN_BACKGROUND_LOCK_DELAY_MS = 15_000L

private class SpotifyHttpException(
    val statusCode: Int,
    message: String
) : Exception(message)

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val downloaderInitMutex = Mutex()
    private val downloaderUpdateMutex = Mutex()
    @Volatile
    private var downloaderReady = false
    @Volatile
    private var downloaderUpdateChecked = false
    @Volatile
    private var playlistShareBusy = false
    private val prefs = app.getSharedPreferences("kochify_music", 0)
    private val musicDir = File(app.filesDir, "music").apply { mkdirs() }
    private val coversDir = File(app.filesDir, "covers").apply { mkdirs() }
    private val downloadDir =
        File(app.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Kochify").apply { mkdirs() }

    val tracks = mutableStateListOf<AudioTrack>()
    val playlists = mutableStateListOf<String>()
    val playlistCovers = mutableStateMapOf<String, String>()
    private val playlistOrders = mutableStateMapOf<String, List<String>>()
    private val pendingSpotifyTracks = mutableStateListOf<PendingSpotifyTrack>()
    private val spotifyPlaylistLinks = mutableStateMapOf<String, String>()
    val playbackHistory = mutableStateListOf<PlaybackHistoryEntry>()
    private val playCounts = mutableStateMapOf<String, Int>()
    private val monthlyListeningMs = mutableStateMapOf<String, Long>()
    private val yearlyListeningMs = mutableStateMapOf<String, Long>()
    val trashTracks = mutableStateListOf<TrashTrack>()
    val trashPlaylists = mutableStateListOf<TrashPlaylist>()
    val pendingDuplicates = mutableStateListOf<DuplicateCandidate>()
    val player = ExoPlayer.Builder(app).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )
        setWakeMode(C.WAKE_MODE_LOCAL)
    }
    private var playbackQueue: List<AudioTrack> = emptyList()
    private var playbackQueueKey: String = "library"
    private var shuffleSessionSignature: String? = null
    private val shuffleRemainingIds = mutableListOf<String>()
    private val playbackBackStack = mutableListOf<PlaybackNavigationEntry>()
    private var activeListeningStartedAt = 0L
    private var transferServer: ServerSocket? = null
    private var backgroundedAtElapsedMs = 0L
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && player.isPlaying) {
                player.pause()
            }
        }
    }

    var search by mutableStateOf("")
    var selectedPlaylist by mutableStateOf<String?>(null)
    var currentTrack by mutableStateOf<AudioTrack?>(null)
    var isPlaying by mutableStateOf(false)
    var playbackPositionMs by mutableLongStateOf(0L)
        private set
    var playbackDurationMs by mutableLongStateOf(0L)
        private set
    var shuffleEnabled by mutableStateOf(prefs.getBoolean("shuffle_enabled", false))
    var repeatOneEnabled by mutableStateOf(prefs.getBoolean("repeat_one_enabled", false))
    var playbackSpeed by mutableFloatStateOf(
        prefs.getFloat("playback_speed", 1f).coerceIn(0.25f, 2f)
    )
        private set
    var totalListeningMs by mutableLongStateOf(prefs.getLong("total_listening_ms", 0L))
        private set
    var themeMode by mutableStateOf(
        runCatching {
            KochifyThemeMode.valueOf(
                prefs.getString("theme_mode", KochifyThemeMode.BLACK.name).orEmpty()
            )
        }.getOrDefault(KochifyThemeMode.BLACK)
    )
        private set
    var librarySort by mutableStateOf(
        runCatching {
            LibrarySort.valueOf(
                prefs.getString("library_sort", LibrarySort.ADDED_NEWEST.name).orEmpty()
            )
        }.getOrDefault(LibrarySort.ADDED_NEWEST)
    )
        private set
    var pinEnabled by mutableStateOf(prefs.getBoolean("pin_enabled", false))
        private set
    var guestModeEnabled by mutableStateOf(prefs.getBoolean("guest_mode_enabled", false))
        private set
    var guestMode by mutableStateOf(false)
        private set
    var appLocked by mutableStateOf(pinEnabled)
        private set
    var downloadProgress by mutableFloatStateOf(0f)
    var downloadStatus by mutableStateOf<String?>(null)
    var isDownloading by mutableStateOf(false)
    var spotifyStatus by mutableStateOf<String?>(null)
    var isSpotifyImporting by mutableStateOf(false)
    var backupStatus by mutableStateOf<String?>(null)
    var isBackupBusy by mutableStateOf(false)
    var localTransferOffer by mutableStateOf<LocalTransferOffer?>(null)
        private set
    var localTransferStatus by mutableStateOf<String?>(null)
        private set
    var isLocalTransferBusy by mutableStateOf(false)
        private set
    val spotifyClientId: String
        get() = prefs.getString("spotify_client_id", "").orEmpty()

    init {
        load()
        scanDownloadedFiles()
        ContextCompat.registerReceiver(
            app,
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_EXPORTED
        )
        player.repeatMode =
            if (repeatOneEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.setPlaybackSpeed(playbackSpeed)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
                updateListeningSession(value)
                currentTrack?.let { track ->
                    PlaybackKeepAliveService.start(
                        app,
                        track.title,
                        track.artist,
                        isPlaying = value
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                playbackPositionMs = player.currentPosition.coerceAtLeast(0L)
                playbackDurationMs = player.duration.takeIf { it > 0L } ?: 0L
                if (playbackState == Player.STATE_ENDED && !repeatOneEnabled) {
                    next()
                }
            }
        })
        PlaybackCommandBridge.register(
            onPlay = {
                if (!player.isPlaying && currentTrack != null) player.play()
            },
            onPause = {
                if (player.isPlaying) player.pause()
            },
            onNext = { next() },
            onPrevious = { previous() }
        )
        viewModelScope.launch {
            while (isActive) {
                playbackPositionMs = player.currentPosition.coerceAtLeast(0L)
                playbackDurationMs = player.duration.takeIf { it > 0L } ?: 0L
                delay(500L)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureDownloaderReady()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    downloadStatus = "Download-Modul konnte nicht gestartet werden: ${e.message}"
                }
            }
        }
    }

    fun exportKochifyBackup(uri: Uri, includeMusic: Boolean) {
        if (isBackupBusy || guestMode) return
        val trackSnapshot = tracks.toList()
        val playlistSnapshot = playlists.toList()
        val playlistCoverSnapshot = playlistCovers.toMap()
        val themeSnapshot = themeMode
        val shuffleSnapshot = shuffleEnabled
        val repeatSnapshot = repeatOneEnabled
        val librarySortSnapshot = librarySort
        val speedSnapshot = playbackSpeed
        val totalListeningSnapshot = currentListeningTotal()
        val playCountSnapshot = playCounts.toMap()
        val historySnapshot = playbackHistory.toList()
        val monthlySnapshot = monthlyListeningMs.toMap()
        val yearlySnapshot = yearlyListeningMs.toMap()
        isBackupBusy = true
        backupStatus = if (includeMusic) {
            "Komplettsicherung wird erstellt …"
        } else {
            "Schnellsicherung wird erstellt …"
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val audioFiles = KochifyBackupManager.export(
                    context = app,
                    uri = uri,
                    tracks = trackSnapshot,
                    playlists = playlistSnapshot,
                    playlistCovers = playlistCoverSnapshot,
                    themeMode = themeSnapshot,
                    shuffleEnabled = shuffleSnapshot,
                    repeatOneEnabled = repeatSnapshot,
                    librarySort = librarySortSnapshot,
                    playbackSpeed = speedSnapshot,
                    includeMusic = includeMusic,
                    totalListeningMs = totalListeningSnapshot,
                    playCounts = playCountSnapshot,
                    playbackHistory = historySnapshot,
                    monthlyListeningMs = monthlySnapshot,
                    yearlyListeningMs = yearlySnapshot
                )
                withContext(Dispatchers.Main) {
                    backupStatus = if (includeMusic) {
                        "Sicherung gespeichert: ${playlistSnapshot.size} Playlists und " +
                            "$audioFiles Musikdateien."
                    } else {
                        "Sicherung gespeichert: ${playlistSnapshot.size} Playlists. " +
                            "Musikdateien wurden nicht mitgesichert."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    backupStatus = "Export fehlgeschlagen: " +
                        (e.message ?: "Unbekannter Fehler").take(240)
                }
            } finally {
                withContext(Dispatchers.Main) { isBackupBusy = false }
            }
        }
    }

    fun shareTrack(track: AudioTrack) {
        runCatching {
            val file = File(track.path)
            require(file.isFile) { "Die Audiodatei wurde nicht gefunden." }
            val uri = FileProvider.getUriForFile(
                app,
                "${app.packageName}.fileprovider",
                file
            )
            val mimeType = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase())
                ?: "audio/*"
            launchShareChooser(
                uri = uri,
                mimeType = mimeType,
                title = "${track.title} – ${track.artist}",
                chooserTitle = "Song teilen"
            )
        }.onFailure { error ->
            Toast.makeText(
                app,
                "Song konnte nicht geteilt werden: " +
                    (error.message ?: "Unbekannter Fehler"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun shareTracks(trackIds: Set<String>) {
        val selectedTracks = tracks.filter { it.id in trackIds }
        val uris = arrayListOf<Uri>()
        selectedTracks.forEach { track ->
            val file = File(track.path)
            if (file.isFile) {
                uris += FileProvider.getUriForFile(
                    app,
                    "${app.packageName}.fileprovider",
                    file
                )
            }
        }
        if (uris.isEmpty()) {
            Toast.makeText(app, "Keine Audiodateien zum Teilen gefunden.", Toast.LENGTH_LONG)
                .show()
            return
        }
        val title = "${uris.size} Kochify-Songs"
        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra(Intent.EXTRA_TEXT, title)
            clipData = ClipData.newUri(app.contentResolver, title, uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        app.startActivity(
            Intent.createChooser(shareIntent, "Songs teilen").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    fun sharePlaylist(name: String) {
        if (playlistShareBusy) {
            Toast.makeText(
                app,
                "Eine Playlist wird bereits vorbereitet.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val playlistTracks = orderedPlaylistTracks(name)
            .map { it.copy(favorite = false, playlists = setOf(name)) }
        val themeSnapshot = themeMode
        val shuffleSnapshot = shuffleEnabled
        val repeatSnapshot = repeatOneEnabled
        playlistShareBusy = true
        Toast.makeText(
            app,
            "Playlist wird zum Teilen vorbereitet …",
            Toast.LENGTH_SHORT
        ).show()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val shareFile = createTransferPackage(
                    fileName = name,
                    packageTracks = playlistTracks,
                    packagePlaylists = listOf(name),
                    packagePlaylistCovers = playlistCovers[name]
                        ?.let { mapOf(name to it) }
                        .orEmpty(),
                    theme = themeSnapshot,
                    shuffle = shuffleSnapshot,
                    repeat = repeatSnapshot
                )
                val uri = fileProviderUri(shareFile)
                withContext(Dispatchers.Main) {
                    launchShareChooser(
                        uri = uri,
                        mimeType = "application/zip",
                        title = "Kochify-Playlist: $name",
                        chooserTitle = "Playlist teilen"
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        app,
                        "Playlist konnte nicht geteilt werden: " +
                            (e.message ?: "Unbekannter Fehler"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                playlistShareBusy = false
            }
        }
    }

    fun startPlaylistLocalTransfer(name: String) {
        if (guestMode) return
        val packageTracks = orderedPlaylistTracks(name)
            .map { it.copy(favorite = false, playlists = setOf(name)) }
        startLocalTransfer(
            title = "Playlist: $name",
            fileName = name,
            packageTracks = packageTracks,
            packagePlaylists = listOf(name),
            packagePlaylistCovers = playlistCovers[name]
                ?.let { mapOf(name to it) }
                .orEmpty()
        )
    }

    fun startTrackLocalTransfer(track: AudioTrack) {
        if (guestMode) return
        startLocalTransfer(
            title = "Song: ${track.title}",
            fileName = track.title,
            packageTracks = listOf(track.copy(favorite = false, playlists = emptySet())),
            packagePlaylists = emptyList(),
            packagePlaylistCovers = emptyMap()
        )
    }

    fun startTracksLocalTransfer(trackIds: Set<String>) {
        if (guestMode) return
        val selectedTracks = tracks
            .filter { it.id in trackIds }
            .map { it.copy(favorite = false, playlists = emptySet()) }
        startLocalTransfer(
            title = "${selectedTracks.size} ausgewählte Songs",
            fileName = "Kochify-${selectedTracks.size}-Songs",
            packageTracks = selectedTracks,
            packagePlaylists = emptyList(),
            packagePlaylistCovers = emptyMap()
        )
    }

    private fun startLocalTransfer(
        title: String,
        fileName: String,
        packageTracks: List<AudioTrack>,
        packagePlaylists: List<String>,
        packagePlaylistCovers: Map<String, String>
    ) {
        if (isLocalTransferBusy) return
        closeLocalTransfer(clearStatus = false)
        isLocalTransferBusy = true
        localTransferStatus = "Übertragung wird vorbereitet …"
        val themeSnapshot = themeMode
        val shuffleSnapshot = shuffleEnabled
        val repeatSnapshot = repeatOneEnabled
        viewModelScope.launch(Dispatchers.IO) {
            var server: ServerSocket? = null
            try {
                require(packageTracks.isNotEmpty()) { "Es sind keine Songs zum Übertragen vorhanden." }
                val transferFile = createTransferPackage(
                    fileName = fileName,
                    packageTracks = packageTracks,
                    packagePlaylists = packagePlaylists,
                    packagePlaylistCovers = packagePlaylistCovers,
                    theme = themeSnapshot,
                    shuffle = shuffleSnapshot,
                    repeat = repeatSnapshot
                )
                val host = localIpv4Address()
                    ?: error("Kein lokales WLAN gefunden. Beide Handys müssen im selben WLAN sein.")
                val token = randomUrlSafe(24)
                val activeServer = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                    soTimeout = 5 * 60 * 1000
                }
                server = activeServer
                transferServer = activeServer
                val payload = JSONObject().apply {
                    put("version", 1)
                    put("host", host)
                    put("port", activeServer.localPort)
                    put("token", token)
                }.toString()
                val encoded = Base64.encodeToString(
                    payload.toByteArray(StandardCharsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )
                withContext(Dispatchers.Main) {
                    localTransferOffer = LocalTransferOffer(
                        title = title,
                        qrPayload = "kochify://transfer/$encoded"
                    )
                    localTransferStatus =
                        "QR-Code am anderen Handy in Kochify scannen. Beide Geräte müssen " +
                            "im selben WLAN sein."
                    isLocalTransferBusy = false
                }

                val client = activeServer.accept()
                client.use { socket -> sendTransferFile(socket, token, transferFile) }
                withContext(Dispatchers.Main) {
                    localTransferOffer = null
                    localTransferStatus = "Übertragung erfolgreich abgeschlossen."
                }
                transferFile.delete()
            } catch (_: SocketTimeoutException) {
                withContext(Dispatchers.Main) {
                    localTransferOffer = null
                    localTransferStatus = "QR-Code abgelaufen. Bitte neu starten."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    localTransferOffer = null
                    localTransferStatus = "Übertragung beendet: " +
                        (e.message ?: "Verbindung geschlossen").take(220)
                }
            } finally {
                runCatching { server?.close() }
                if (transferServer === server) transferServer = null
                withContext(Dispatchers.Main) { isLocalTransferBusy = false }
            }
        }
    }

    fun receiveLocalTransfer(qrPayload: String) {
        if (guestMode) return
        if (isLocalTransferBusy) return
        isLocalTransferBusy = true
        localTransferStatus = "Verbindung wird hergestellt …"
        viewModelScope.launch(Dispatchers.IO) {
            var incoming: File? = null
            try {
                val uri = Uri.parse(qrPayload.trim())
                require(uri.scheme == "kochify" && uri.host == "transfer") {
                    "Dieser QR-Code gehört nicht zu Kochify."
                }
                val encoded = uri.lastPathSegment.orEmpty()
                val details = JSONObject(
                    Base64.decode(
                        encoded,
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                    ).toString(StandardCharsets.UTF_8)
                )
                require(details.optInt("version") == 1) { "Unbekannte QR-Version." }
                val host = details.getString("host")
                val port = details.getInt("port")
                val token = details.getString("token")
                val address = InetAddress.getByName(host)
                require(address.isSiteLocalAddress || address.isLinkLocalAddress) {
                    "Der QR-Code verweist nicht auf das lokale WLAN."
                }
                require(port in 1024..65535 && token.length >= 20) {
                    "Der QR-Code ist unvollständig."
                }
                val incomingFile = File(
                    File(app.cacheDir, "incoming").apply { mkdirs() },
                    "Kochify-${System.currentTimeMillis()}.kochify"
                )
                incoming = incomingFile
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), 15_000)
                    socket.soTimeout = 60_000
                    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    output.writeUTF(token)
                    output.flush()
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    require(input.readUTF() == LOCAL_TRANSFER_MAGIC) {
                        "Die Gegenstelle ist keine Kochify-App."
                    }
                    val size = input.readLong()
                    require(size in 1L..LOCAL_TRANSFER_MAX_BYTES) {
                        "Die Übertragungsdatei ist ungültig oder zu groß."
                    }
                    require(size < app.cacheDir.usableSpace) {
                        "Auf dem Handy ist nicht genug freier Speicher."
                    }
                    incomingFile.outputStream().use { fileOutput ->
                        val buffer = ByteArray(64 * 1024)
                        var remaining = size
                        while (remaining > 0L) {
                            val count = input.read(
                                buffer,
                                0,
                                minOf(buffer.size.toLong(), remaining).toInt()
                            )
                            require(count > 0) { "Die Verbindung wurde zu früh getrennt." }
                            fileOutput.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                }
                val imported = KochifyBackupManager.import(app, fileProviderUri(incomingFile))
                withContext(Dispatchers.Main) {
                    localTransferOffer = null
                    localTransferStatus = mergeImportedBackup(imported)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    localTransferStatus = "Empfang fehlgeschlagen: " +
                        (e.message ?: "Unbekannter Fehler").take(220)
                }
            } finally {
                incoming?.delete()
                withContext(Dispatchers.Main) { isLocalTransferBusy = false }
            }
        }
    }

    fun closeLocalTransfer(clearStatus: Boolean = true) {
        val server = transferServer
        transferServer = null
        runCatching { server?.close() }
        localTransferOffer = null
        if (clearStatus) localTransferStatus = null
        isLocalTransferBusy = false
    }

    fun importKochifyBackup(uri: Uri) {
        if (isBackupBusy || guestMode) return
        isBackupBusy = true
        backupStatus = "Kochify-Sicherung wird importiert …"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val imported = KochifyBackupManager.import(app, uri)
                withContext(Dispatchers.Main) {
                    backupStatus = mergeImportedBackup(imported)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    backupStatus = "Import fehlgeschlagen: " +
                        (e.message ?: "Unbekannter Fehler").take(240)
                }
            } finally {
                withContext(Dispatchers.Main) { isBackupBusy = false }
            }
        }
    }

    fun importAudio(uris: List<Uri>) {
        if (uris.isEmpty() || guestMode) return
        viewModelScope.launch(Dispatchers.IO) {
            var imported = 0
            uris.forEach { uri ->
                runCatching {
                    val name = app.contentResolver.query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "audio-${UUID.randomUUID()}.mp3"
                    val extension = name.substringAfterLast('.', "mp3").take(5)
                    val target = File(musicDir, "${UUID.randomUUID()}.$extension")
                    app.contentResolver.openInputStream(uri)!!.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    addFile(target)
                    imported++
                }
            }
            withContext(Dispatchers.Main) {
                val message = "$imported MP3-Datei(en) vom Handy importiert."
                downloadStatus = message
                Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun downloadFromYoutube(
        url: String,
        useYoutubeCovers: Boolean = true,
        customCoverUri: Uri? = null
    ) {
        if (url.isBlank() || isDownloading || guestMode) return
        val cleanUrl = url.trim()
        if (!isYoutubeUrl(cleanUrl)) {
            downloadStatus = "Bitte einen gültigen YouTube- oder YouTube-Music-Link eingeben."
            return
        }
        isDownloading = true
        downloadProgress = 0f
        downloadStatus = "Download wird vorbereitet …"
        runCatching {
            DownloadKeepAliveService.start(app, requireNotNull(downloadStatus))
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    downloadStatus = "Download-Modul wird gestartet …"
                    DownloadKeepAliveService.update(
                        app,
                        requireNotNull(downloadStatus),
                        downloadProgress
                    )
                }
                ensureDownloaderReady()
                updateDownloaderIfNeeded()
                withContext(Dispatchers.Main) {
                    downloadStatus = "YouTube-Link wird analysiert …"
                    DownloadKeepAliveService.update(
                        app,
                        requireNotNull(downloadStatus),
                        downloadProgress
                    )
                }
                val plan = createYoutubeDownloadPlan(cleanUrl)
                val playlistName = plan.title.takeIf { plan.isPlaylist }
                val targetDir = File(
                    downloadDir,
                    safeFileName(playlistName ?: "Einzelne Downloads")
                ).apply { mkdirs() }

                if (playlistName != null) {
                    if (customCoverUri != null) {
                        copyLocalCover(
                            customCoverUri,
                            "playlist-custom-${stableKey(playlistName)}"
                        )?.let { cover ->
                            withContext(Dispatchers.Main) {
                                playlistCovers[playlistName] = cover.absolutePath
                                save()
                            }
                        }
                    } else if (useYoutubeCovers) {
                        val playlistThumbnail = plan.thumbnailUrl
                            ?: plan.items.firstNotNullOfOrNull { it.thumbnailUrl }
                        playlistThumbnail?.let { thumbnail ->
                            downloadRemoteCover(
                                thumbnail,
                                "playlist-${stableKey(playlistName)}"
                            )?.let { cover ->
                                withContext(Dispatchers.Main) {
                                    playlistCovers[playlistName] = cover.absolutePath
                                    save()
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        if (playlistName !in playlists) {
                            playlists.add(playlistName)
                            playlistOrders[playlistName] = emptyList()
                            save()
                        }
                        downloadStatus = buildString {
                            append("„$playlistName“: ${plan.items.size} Titel gefunden.")
                            if (plan.unavailableItems > 0) {
                                append(" ${plan.unavailableItems} nicht verfügbar.")
                            }
                        }
                        DownloadKeepAliveService.update(
                            app,
                            requireNotNull(downloadStatus),
                            downloadProgress
                        )
                    }
                }

                var completed = 0
                var reused = 0
                val failures = mutableListOf<String>()
                plan.items.forEachIndexed { index, item ->
                    val itemNumber = index + 1
                    val before = mp3Files(targetDir).associateBy { it.absolutePath }
                    withContext(Dispatchers.Main) {
                        downloadProgress = index.toFloat() / plan.items.size
                        downloadStatus = if (plan.isPlaylist) {
                            "Titel $itemNumber von ${plan.items.size}: ${item.title}"
                        } else {
                            "Wird heruntergeladen: ${item.title}"
                        }
                        DownloadKeepAliveService.update(
                            app,
                            requireNotNull(downloadStatus),
                            downloadProgress
                        )
                    }

                    try {
                        val request = YoutubeDLRequest(item.url).apply {
                            addOption("--extract-audio")
                            addOption("--audio-format", "mp3")
                            addOption("--audio-quality", "0")
                            addOption(
                                "--postprocessor-args",
                                "ffmpeg:-af loudnorm=I=-14:LRA=11:TP=-1.5"
                            )
                            addOption("--embed-metadata")
                            addOption("--embed-thumbnail")
                            addOption("--no-playlist")
                            addOption("--no-overwrites")
                            addOption("--remote-components", "ejs:github")
                            addOption(
                                "-o",
                                File(targetDir, "%(title)s [%(id)s].%(ext)s").absolutePath
                            )
                        }
                        YoutubeDL.getInstance().execute(request) { progress, eta, _ ->
                            viewModelScope.launch(Dispatchers.Main) {
                                val itemProgress = (progress / 100f).coerceIn(0f, 1f)
                                downloadProgress =
                                    ((index + itemProgress) / plan.items.size).coerceIn(0f, 1f)
                                val etaText = if (eta > 0) " · noch etwa ${eta}s" else ""
                                downloadStatus = if (plan.isPlaylist) {
                                    "Titel $itemNumber von ${plan.items.size}: " +
                                        "${progress.toInt()} %$etaText\n${item.title}"
                                } else {
                                    "Wird heruntergeladen: ${progress.toInt()} %$etaText"
                                }
                                DownloadKeepAliveService.update(
                                    app,
                                    requireNotNull(downloadStatus),
                                    downloadProgress
                                )
                            }
                        }

                        val after = mp3Files(targetDir)
                        val newFiles = after.filter { it.absolutePath !in before }
                        val itemFiles = if (newFiles.isNotEmpty()) {
                            newFiles
                        } else {
                            findExistingDownload(after, item)
                        }
                        if (itemFiles.isEmpty()) {
                            failures += item.title
                        } else {
                            val itemKey = stableKey(item.id.ifBlank { item.url })
                            val coverPath = when {
                                customCoverUri != null -> copyLocalCover(
                                    customCoverUri,
                                    "youtube-custom-$itemKey"
                                )?.absolutePath
                                useYoutubeCovers -> item.thumbnailUrl?.let { thumbnail ->
                                    downloadRemoteCover(
                                        thumbnail,
                                        "youtube-$itemKey"
                                    )?.absolutePath
                                }
                                else -> null
                            }
                            itemFiles.forEach { addFile(it, playlistName, coverPath) }
                            if (newFiles.isEmpty()) reused++ else completed++
                        }
                    } catch (_: Exception) {
                        failures += item.title
                    }
                }

                withContext(Dispatchers.Main) {
                    downloadProgress = 1f
                    downloadStatus = buildDownloadSummary(
                        plan = plan,
                        completed = completed,
                        reused = reused,
                        failures = failures
                    )
                    DownloadKeepAliveService.update(
                        app,
                        requireNotNull(downloadStatus),
                        downloadProgress
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    downloadStatus = "Download fehlgeschlagen: ${compactDownloadError(e)}"
                    DownloadKeepAliveService.update(
                        app,
                        requireNotNull(downloadStatus),
                        downloadProgress
                    )
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    DownloadKeepAliveService.stop(app)
                }
            }
        }
    }

    private fun createYoutubeDownloadPlan(url: String): YoutubeDownloadPlan {
        if (isYoutubePlaylistUrl(url)) {
            return createYoutubePlaylistPlan(url)
        }

        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--remote-components", "ejs:github")
        }
        val response = YoutubeDL.getInstance().execute(request)
        val jsonText = response.out.trim()
        val jsonStart = jsonText.indexOf('{')
        val jsonEnd = jsonText.lastIndexOf('}')
        if (jsonStart < 0 || jsonEnd <= jsonStart) {
            throw IllegalStateException("Die YouTube-Informationen konnten nicht gelesen werden.")
        }
        val root = JSONObject(jsonText.substring(jsonStart, jsonEnd + 1))
        return YoutubeDownloadPlan(
            title = cleanDisplayName(root.optString("title"), "YouTube-Download"),
            items = listOf(
                YoutubeDownloadItem(
                    id = root.optString("id"),
                    title = cleanDisplayName(root.optString("title"), "YouTube-Titel"),
                    url = url,
                    thumbnailUrl = youtubeThumbnailUrl(root)
                )
            ),
            isPlaylist = false,
            unavailableItems = 0,
            thumbnailUrl = youtubeThumbnailUrl(root)
        )
    }

    private fun createYoutubePlaylistPlan(url: String): YoutubeDownloadPlan {
        val request = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("--dump-json")
            addOption("--ignore-errors")
            addOption("--no-warnings")
            addOption("--remote-components", "ejs:github")
        }
        val response = YoutubeDL.getInstance().execute(request)
        val entries = response.out.lineSequence()
            .mapNotNull { line ->
                val start = line.indexOf('{')
                val end = line.lastIndexOf('}')
                if (start < 0 || end <= start) {
                    null
                } else {
                    runCatching { JSONObject(line.substring(start, end + 1)) }.getOrNull()
                }
            }
            .toList()

        val items = mutableListOf<YoutubeDownloadItem>()
        var unavailable = 0
        entries.forEachIndexed { index, entry ->
            val itemUrl = youtubeEntryUrl(entry)
            if (itemUrl == null) {
                unavailable++
                return@forEachIndexed
            }
            items += YoutubeDownloadItem(
                id = entry.optString("id"),
                title = cleanDisplayName(
                    entry.optString("title").ifBlank { entry.optString("fulltitle") },
                    "Titel ${index + 1}"
                ),
                url = itemUrl,
                thumbnailUrl = youtubeThumbnailUrl(entry)
            )
        }
        if (items.isEmpty()) {
            throw IllegalStateException(
                "Die Playlist enthält keine zugänglichen Videos. Gesperrte Einträge wurden " +
                    "übersprungen."
            )
        }
        val playlistTitle = entries.firstNotNullOfOrNull { entry ->
            entry.optString("playlist_title").takeIf { it.isNotBlank() }
                ?: entry.optString("playlist").takeIf { it.isNotBlank() }
        }
        return YoutubeDownloadPlan(
            title = cleanDisplayName(
                playlistTitle.orEmpty(),
                "YouTube-Playlist"
            ),
            items = items,
            isPlaylist = true,
            unavailableItems = unavailable,
            thumbnailUrl = entries.firstNotNullOfOrNull { entry ->
                entry.optString("playlist_thumbnail").takeIf { it.startsWith("https://") }
                    ?: entry.optJSONArray("playlist_thumbnails")
                        ?.let(::bestThumbnailUrl)
            }
        )
    }

    private fun youtubeThumbnailUrl(item: JSONObject): String? =
        item.optString("thumbnail").takeIf { it.startsWith("https://") }
            ?: item.optJSONArray("thumbnails")?.let(::bestThumbnailUrl)

    private fun bestThumbnailUrl(items: JSONArray): String? {
        var bestUrl: String? = null
        var bestWidth = -1
        repeat(items.length()) { index ->
            val thumbnail = items.optJSONObject(index) ?: return@repeat
            val url = thumbnail.optString("url")
            if (!url.startsWith("https://")) return@repeat
            val width = thumbnail.optInt("width", index)
            if (width >= bestWidth) {
                bestWidth = width
                bestUrl = url
            }
        }
        return bestUrl
    }

    private fun isYoutubePlaylistUrl(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        return !uri.getQueryParameter("list").isNullOrBlank() ||
            uri.path.orEmpty().contains("/playlist", ignoreCase = true)
    }

    private fun youtubeEntryUrl(entry: JSONObject): String? {
        val candidates = listOf(
            entry.optString("webpage_url"),
            entry.optString("original_url"),
            entry.optString("url")
        )
        candidates.firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
            ?.let { return it }
        val id = entry.optString("id").ifBlank {
            candidates.firstOrNull { it.isNotBlank() }.orEmpty()
        }
        return id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
            ?.let { "https://www.youtube.com/watch?v=$it" }
    }

    private fun isYoutubeUrl(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("http", "https")) return false
        val host = uri.host?.lowercase().orEmpty()
        return host == "youtu.be" ||
            host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" ||
            host.endsWith(".youtube-nocookie.com")
    }

    private fun cleanDisplayName(value: String, fallback: String): String = value
        .replace(Regex("[\\r\\n]+"), " ")
        .trim()
        .take(100)
        .ifBlank { fallback }

    private fun safeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.')
        .take(80)
        .ifBlank { "YouTube-Playlist" }

    private fun stableKey(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun downloadRemoteCover(url: String, fileStem: String): File? = runCatching {
        val parsed = URL(url)
        require(parsed.protocol == "https")
        val target = File(coversDir, "$fileStem-${System.currentTimeMillis()}.jpg")
        val connection = (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Kochify/${BuildConfig.VERSION_NAME}")
        }
        try {
            require(connection.responseCode in 200..299)
            val contentType = connection.contentType.orEmpty().lowercase()
            require(contentType.isBlank() || contentType.startsWith("image/"))
            val announcedSize = connection.contentLengthLong
            require(announcedSize <= 12L * 1024L * 1024L)
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var written = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        require(written <= 12L * 1024L * 1024L)
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(target.length() > 0L)
            target
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun copyLocalCover(uri: Uri, fileStem: String): File? = runCatching {
        val target = File(coversDir, "$fileStem-${System.currentTimeMillis()}.jpg")
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var written = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        require(written <= 12L * 1024L * 1024L) {
                            "Das Cover darf höchstens 12 MB groß sein."
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Das ausgewählte Cover konnte nicht geöffnet werden.")
            require(target.length() > 0L) { "Das ausgewählte Cover ist leer." }
            target
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }.getOrNull()

    private fun createTransferPackage(
        fileName: String,
        packageTracks: List<AudioTrack>,
        packagePlaylists: List<String>,
        packagePlaylistCovers: Map<String, String>,
        theme: KochifyThemeMode,
        shuffle: Boolean,
        repeat: Boolean
    ): File {
        val shareDir = File(app.cacheDir, "shared").apply { mkdirs() }
        val shareFile = File(
            shareDir,
            "${safeFileName(fileName)}-${System.currentTimeMillis()}.kochify"
        ).apply { createNewFile() }
        KochifyBackupManager.export(
            context = app,
            uri = fileProviderUri(shareFile),
            tracks = packageTracks,
            playlists = packagePlaylists,
            playlistCovers = packagePlaylistCovers,
            themeMode = theme,
            shuffleEnabled = shuffle,
            repeatOneEnabled = repeat,
            librarySort = librarySort,
            playbackSpeed = playbackSpeed,
            includeMusic = true,
            includeSettings = false,
            includeStats = false
        )
        return shareFile
    }

    private fun fileProviderUri(file: File): Uri = FileProvider.getUriForFile(
        app,
        "${app.packageName}.fileprovider",
        file
    )

    private fun sendTransferFile(socket: Socket, token: String, file: File) {
        socket.soTimeout = 60_000
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        require(input.readUTF() == token) { "Ungültiger Übertragungscode." }
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        output.writeUTF(LOCAL_TRANSFER_MAGIC)
        output.writeLong(file.length())
        file.inputStream().use { it.copyTo(output, 64 * 1024) }
        output.flush()
    }

    private fun localIpv4Address(): String? {
        val candidates = mutableListOf<Pair<String, String>>()
        val networkInterfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (networkInterfaces.hasMoreElements()) {
            val network = networkInterfaces.nextElement()
            if (!network.isUp || network.isLoopback || network.isVirtual) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && address.isSiteLocalAddress) {
                    candidates += network.name to address.hostAddress.orEmpty()
                }
            }
        }
        return candidates.sortedBy { (name, _) ->
            if (name.contains("wlan", true) || name.contains("wifi", true)) 0 else 1
        }.firstOrNull()?.second
    }

    private fun launchShareChooser(
        uri: Uri,
        mimeType: String,
        title: String,
        chooserTitle: String
    ) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra(Intent.EXTRA_TEXT, title)
            clipData = ClipData.newUri(app.contentResolver, title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        app.startActivity(
            Intent.createChooser(shareIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    private fun mp3Files(directory: File): List<File> = directory.walkTopDown()
        .filter { it.isFile && it.extension.equals("mp3", true) }
        .toList()

    private fun findExistingDownload(
        files: List<File>,
        item: YoutubeDownloadItem
    ): List<File> {
        if (item.id.isBlank()) return emptyList()
        val suffix = "[${item.id}]"
        return files.filter { it.nameWithoutExtension.endsWith(suffix) }
    }

    private fun buildDownloadSummary(
        plan: YoutubeDownloadPlan,
        completed: Int,
        reused: Int,
        failures: List<String>
    ): String = buildString {
        if (plan.isPlaylist) {
            append("Playlist „${plan.title}“ fertig: ")
        }
        append("$completed neu")
        if (reused > 0) append(", $reused bereits vorhanden")
        if (plan.unavailableItems > 0) {
            append(", ${plan.unavailableItems} nicht verfügbar")
        }
        if (failures.isNotEmpty()) {
            append(", ${failures.size} fehlgeschlagen")
            append(". Erneut versuchen: ${failures.take(3).joinToString()}")
            if (failures.size > 3) append(" …")
        } else {
            append(".")
        }
    }

    private suspend fun ensureDownloaderReady() {
        if (downloaderReady) return
        downloaderInitMutex.withLock {
            if (downloaderReady) return@withLock
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            downloaderReady = true
        }
    }

    private suspend fun updateDownloaderIfNeeded() {
        if (downloaderUpdateChecked) return
        downloaderUpdateMutex.withLock {
            if (downloaderUpdateChecked) return@withLock
            val now = System.currentTimeMillis()
            val lastCheck = prefs.getLong("ytdlp_last_update_check", 0L)
            if (now - lastCheck < YTDLP_UPDATE_INTERVAL_MS) {
                downloaderUpdateChecked = true
                return@withLock
            }

            withContext(Dispatchers.Main) {
                downloadStatus = "yt-dlp wird aktualisiert …"
            }
            try {
                YoutubeDL.getInstance().updateYoutubeDL(
                    app,
                    YoutubeDL.UpdateChannel.STABLE
                )
                prefs.edit().putLong("ytdlp_last_update_check", now).apply()
                downloaderUpdateChecked = true
                withContext(Dispatchers.Main) {
                    val version = YoutubeDL.getInstance().version(app)
                    downloadStatus = if (version.isNullOrBlank()) {
                        "Download wird vorbereitet …"
                    } else {
                        "Downloader $version bereit …"
                    }
                }
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Downloader-Aktualisierung fehlgeschlagen: " +
                        (e.message ?: "Netzwerkfehler"),
                    e
                )
            }
        }
    }

    private fun compactDownloadError(error: Exception): String {
        val message = error.message.orEmpty()
        val lines = message.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        val relevant = lines.lastOrNull { it.startsWith("ERROR:", ignoreCase = true) }
            ?: lines.lastOrNull { it.contains("challenge", ignoreCase = true) }
            ?: lines.lastOrNull()
            ?: "Unbekannter Fehler"
        return relevant.take(420)
    }

    fun startSpotifyImport(clientId: String) {
        if (guestMode) return
        val cleanClientId = clientId.trim()
        if (cleanClientId.isEmpty()) {
            spotifyStatus = "Bitte zuerst deine Spotify Client-ID eingeben."
            return
        }

        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(24)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        prefs.edit()
            .putString("spotify_client_id", cleanClientId)
            .putString("spotify_code_verifier", verifier)
            .putString("spotify_auth_state", state)
            .apply()

        isSpotifyImporting = true
        spotifyStatus = "Spotify-Anmeldung wird geöffnet …"
        val authorizationUri = Uri.parse(SPOTIFY_AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", cleanClientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", SPOTIFY_REDIRECT_URI)
            .appendQueryParameter(
                "scope",
                "playlist-read-private playlist-read-collaborative"
            )
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .build()

        runCatching {
            app.startActivity(
                Intent(Intent.ACTION_VIEW, authorizationUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            isSpotifyImporting = false
            spotifyStatus = "Spotify-Anmeldung konnte nicht geöffnet werden."
        }
    }

    fun handleSpotifyCallback(uri: Uri?) {
        if (uri?.scheme != "kochify" || uri.host != "spotify-callback") return

        val error = uri.getQueryParameter("error")
        if (error != null) {
            isSpotifyImporting = false
            spotifyStatus = "Spotify-Anmeldung abgebrochen: $error"
            return
        }

        val expectedState = prefs.getString("spotify_auth_state", null)
        val receivedState = uri.getQueryParameter("state")
        val code = uri.getQueryParameter("code")
        val clientId = spotifyClientId
        val verifier = prefs.getString("spotify_code_verifier", null)
        if (receivedState != expectedState || code.isNullOrBlank() ||
            clientId.isBlank() || verifier.isNullOrBlank()
        ) {
            isSpotifyImporting = false
            spotifyStatus = "Spotify-Anmeldung konnte nicht sicher bestätigt werden."
            return
        }

        isSpotifyImporting = true
        spotifyStatus = "Spotify-Playlists werden geladen …"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tokenResponse = postForm(
                    SPOTIFY_TOKEN_URL,
                    mapOf(
                        "client_id" to clientId,
                        "grant_type" to "authorization_code",
                        "code" to code,
                        "redirect_uri" to SPOTIFY_REDIRECT_URI,
                        "code_verifier" to verifier
                    )
                )
                val accessToken = tokenResponse.getString("access_token")
                val importedPlaylists = loadSpotifyPlaylists(accessToken)
                applySpotifyPlaylists(importedPlaylists)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    spotifyStatus =
                        "Spotify-Import fehlgeschlagen: ${e.message ?: "Unbekannter Fehler"}"
                }
            } finally {
                prefs.edit()
                    .remove("spotify_code_verifier")
                    .remove("spotify_auth_state")
                    .apply()
                withContext(Dispatchers.Main) { isSpotifyImporting = false }
            }
        }
    }

    private fun loadSpotifyPlaylists(
        accessToken: String
    ): List<SpotifyPlaylistImport> {
        val imported = mutableListOf<SpotifyPlaylistImport>()
        var nextPlaylistsUrl: String? = "$SPOTIFY_API_URL/me/playlists?limit=50"

        while (!nextPlaylistsUrl.isNullOrBlank()) {
            val page = getJson(nextPlaylistsUrl, accessToken)
            val playlistItems = page.optJSONArray("items") ?: JSONArray()
            repeat(playlistItems.length()) playlistLoop@ { playlistIndex ->
                val playlist =
                    playlistItems.optJSONObject(playlistIndex) ?: return@playlistLoop
                val name = playlist.optString("name").trim()
                val id = playlist.optString("id").trim()
                if (name.isEmpty() || id.isEmpty()) return@playlistLoop
                val spotifyUrl = playlist.optJSONObject("external_urls")
                    ?.optString("spotify")
                    .orEmpty()

                val playlistTracks = mutableListOf<PendingSpotifyTrack>()
                var nextItemsUrl: String? =
                    "$SPOTIFY_API_URL/playlists/$id/items?limit=50"
                try {
                    while (!nextItemsUrl.isNullOrBlank()) {
                        val itemPage = getJson(nextItemsUrl, accessToken)
                        val items = itemPage.optJSONArray("items") ?: JSONArray()
                        repeat(items.length()) itemLoop@ { itemIndex ->
                            val wrapper =
                                items.optJSONObject(itemIndex) ?: return@itemLoop
                            val item = wrapper.optJSONObject("item")
                                ?: wrapper.optJSONObject("track")
                                ?: return@itemLoop
                            if (item.optString("type", "track") != "track") {
                                return@itemLoop
                            }
                            val title = item.optString("name").trim()
                            val artists = item.optJSONArray("artists") ?: JSONArray()
                            val artist = buildList {
                                repeat(artists.length()) { artistIndex ->
                                    artists.optJSONObject(artistIndex)
                                        ?.optString("name")
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let(::add)
                                }
                            }.joinToString(", ")
                            if (title.isNotEmpty()) {
                                playlistTracks += PendingSpotifyTrack(
                                    playlist = name,
                                    title = title,
                                    artist = artist.ifBlank { "Unbekannter Interpret" }
                                )
                            }
                        }
                        nextItemsUrl = itemPage.optString("next").takeIf { it.isNotBlank() }
                    }
                    imported += SpotifyPlaylistImport(
                        name = name,
                        spotifyUrl = spotifyUrl,
                        tracks = playlistTracks
                    )
                } catch (e: SpotifyHttpException) {
                    // Spotify erlaubt den neuen Items-Endpunkt nur für eigene
                    // oder gemeinsam bearbeitete Playlists. Andere werden übersprungen.
                    if (e.statusCode != 403) throw e
                }
            }
            nextPlaylistsUrl = page.optString("next").takeIf { it.isNotBlank() }
        }
        return imported
    }

    private suspend fun applySpotifyPlaylists(
        imported: List<SpotifyPlaylistImport>
    ) = withContext(Dispatchers.Main) {
        var matched = 0
        var missing = 0
        imported.forEach { spotifyPlaylist ->
            val playlistName = spotifyPlaylist.name
            val importedTracks = spotifyPlaylist.tracks
            if (playlistName !in playlists) {
                playlists.add(playlistName)
                playlistOrders[playlistName] = emptyList()
            }
            if (spotifyPlaylist.spotifyUrl.isNotBlank()) {
                spotifyPlaylistLinks[playlistName] = spotifyPlaylist.spotifyUrl
            }
            importedTracks.forEach { spotifyTrack ->
                val trackIndex = tracks.indexOfFirst {
                    spotifyMatches(it.title, it.artist, spotifyTrack.title, spotifyTrack.artist)
                }
                if (trackIndex >= 0) {
                    val localTrack = tracks[trackIndex]
                    tracks[trackIndex] = localTrack.copy(
                        playlists = localTrack.playlists + playlistName
                    )
                    ensurePlaylistOrder(playlistName, localTrack.id)
                    matched++
                } else {
                    val exists = pendingSpotifyTracks.any {
                        it.playlist == playlistName &&
                            spotifyMatches(
                                it.title,
                                it.artist,
                                spotifyTrack.title,
                                spotifyTrack.artist
                            )
                    }
                    if (!exists) pendingSpotifyTracks.add(spotifyTrack)
                    missing++
                }
            }
        }
        save()
        spotifyStatus =
            "${imported.size} Spotify-Playlist(s) übertragen: " +
                "$matched vorhandene Titel zugeordnet, $missing für später vorgemerkt."
    }

    private fun getJson(url: String, accessToken: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        return readJsonResponse(connection)
    }

    private fun postForm(url: String, fields: Map<String, String>): JSONObject {
        val body = fields.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        connection.outputStream.use {
            it.write(body.toByteArray(StandardCharsets.UTF_8))
        }
        return readJsonResponse(connection)
    }

    private fun readJsonResponse(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        connection.disconnect()
        if (status !in 200..299) {
            val message = runCatching {
                JSONObject(response).optJSONObject("error")?.optString("message")
                    ?: JSONObject(response).optString("error_description")
            }.getOrNull().orEmpty()
            throw SpotifyHttpException(
                status,
                if (message.isBlank()) "Spotify-Fehler $status" else message
            )
        }
        return JSONObject(response)
    }

    private fun randomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun spotifyMatches(
        localTitle: String,
        localArtist: String,
        spotifyTitle: String,
        spotifyArtist: String
    ): Boolean {
        val titleMatches = normalized(localTitle) == normalized(spotifyTitle)
        val localArtistKey = normalized(localArtist)
        val spotifyArtistKey = normalized(spotifyArtist)
        val artistMatches = localArtistKey.isBlank() || spotifyArtistKey.isBlank() ||
            localArtistKey.contains(spotifyArtistKey) ||
            spotifyArtistKey.contains(localArtistKey)
        return titleMatches && artistMatches
    }

    private fun normalized(value: String): String = Normalizer
        .normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("\\s*\\([^)]*\\)"), "")
        .replace(Regex("\\s*\\[[^]]*]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    fun play(
        track: AudioTrack,
        queue: List<AudioTrack> = tracks.toList(),
        sourceKey: String? = null
    ) = playInternal(
        track = track,
        queue = queue,
        sourceKey = sourceKey,
        rememberCurrentTrack = true
    )

    private fun playInternal(
        track: AudioTrack,
        queue: List<AudioTrack>,
        sourceKey: String?,
        rememberCurrentTrack: Boolean
    ) {
        val preparedQueue = queue.ifEmpty { tracks.toList() }
        val preparedKey = sourceKey ?: "queue:${preparedQueue.joinToString(",") { it.id }}"
        if (rememberCurrentTrack && currentTrack?.id != track.id) {
            currentTrack?.let { previousTrack ->
                playbackBackStack += PlaybackNavigationEntry(
                    trackId = previousTrack.id,
                    queueIds = playbackQueue.map { it.id },
                    sourceKey = playbackQueueKey
                )
                if (playbackBackStack.size > 500) playbackBackStack.removeAt(0)
            }
        }
        val signature = shuffleSignature(preparedQueue, preparedKey)
        if (signature != shuffleSessionSignature) {
            resetShuffleCycle(preparedQueue, track.id, signature)
        } else if (shuffleEnabled) {
            shuffleRemainingIds.remove(track.id)
        }
        recordPlay(track)
        playbackQueue = preparedQueue
        playbackQueueKey = preparedKey
        currentTrack = track
        playbackPositionMs = 0L
        playbackDurationMs = 0L
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(track.path))))
        player.prepare()
        player.seekTo(0L)
        player.play()
        PlaybackKeepAliveService.start(app, track.title, track.artist)
    }

    fun togglePlayback() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
            currentTrack?.let {
                PlaybackKeepAliveService.start(app, it.title, it.artist)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val target = if (playbackDurationMs > 0L) {
            positionMs.coerceIn(0L, playbackDurationMs)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        player.seekTo(target)
        playbackPositionMs = target
    }

    fun toggleShuffle() {
        shuffleEnabled = !shuffleEnabled
        currentTrack?.let { current ->
            val queue = activePlaybackQueue()
            resetShuffleCycle(
                queue,
                current.id,
                shuffleSignature(queue, playbackQueueKey)
            )
        }
        prefs.edit().putBoolean("shuffle_enabled", shuffleEnabled).apply()
    }

    fun toggleRepeatOne() {
        repeatOneEnabled = !repeatOneEnabled
        player.repeatMode =
            if (repeatOneEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        prefs.edit().putBoolean("repeat_one_enabled", repeatOneEnabled).apply()
    }

    fun selectThemeMode(mode: KochifyThemeMode) {
        themeMode = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun selectLibrarySort(sort: LibrarySort) {
        librarySort = sort
        prefs.edit().putString("library_sort", sort.name).apply()
    }

    fun selectPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.25f, 2f)
        player.setPlaybackSpeed(playbackSpeed)
        prefs.edit().putFloat("playback_speed", playbackSpeed).apply()
    }

    fun toggleBookmark(id: String) {
        if (guestMode) return
        update(id) { it.copy(bookmarked = !it.bookmarked) }
    }

    fun setGuestModeEnabled(enabled: Boolean) {
        if (enabled && !pinEnabled) return
        guestModeEnabled = enabled
        prefs.edit().putBoolean("guest_mode_enabled", enabled).apply()
        if (!enabled) guestMode = false
    }

    fun enterGuestMode() {
        if (!guestModeEnabled) return
        updateListeningSession(false)
        guestMode = true
        appLocked = false
        backgroundedAtElapsedMs = 0L
    }

    fun exitGuestMode() {
        updateListeningSession(false)
        guestMode = false
        player.pause()
        appLocked = pinEnabled
    }

    fun enablePin(pin: String, confirmation: String): String? {
        val validation = validateNewPin(pin, confirmation)
        if (validation != null) return validation
        savePin(pin)
        pinEnabled = true
        appLocked = false
        return null
    }

    fun changePin(currentPin: String, newPin: String, confirmation: String): String? {
        if (!verifyPin(currentPin)) return "Die bisherige PIN ist falsch."
        val validation = validateNewPin(newPin, confirmation)
        if (validation != null) return validation
        savePin(newPin)
        appLocked = false
        return null
    }

    fun disablePin(currentPin: String): Boolean {
        if (!verifyPin(currentPin)) return false
        prefs.edit()
            .remove("pin_hash")
            .remove("pin_salt")
            .putBoolean("pin_enabled", false)
            .apply()
        pinEnabled = false
        guestModeEnabled = false
        guestMode = false
        prefs.edit().putBoolean("guest_mode_enabled", false).apply()
        appLocked = false
        backgroundedAtElapsedMs = 0L
        return true
    }

    fun unlockWithPin(pin: String): Boolean {
        if (!pinEnabled || verifyPin(pin)) {
            guestMode = false
            appLocked = false
            backgroundedAtElapsedMs = 0L
            return true
        }
        return false
    }

    fun lockNow() {
        if (pinEnabled) appLocked = true
    }

    fun onAppBackgrounded() {
        if (pinEnabled && !appLocked) {
            backgroundedAtElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    fun onAppForegrounded() {
        if (!pinEnabled || appLocked || backgroundedAtElapsedMs == 0L) return
        if (SystemClock.elapsedRealtime() - backgroundedAtElapsedMs >=
            PIN_BACKGROUND_LOCK_DELAY_MS
        ) {
            appLocked = true
        }
        backgroundedAtElapsedMs = 0L
    }

    private fun validateNewPin(pin: String, confirmation: String): String? = when {
        !pin.matches(Regex("\\d{4,8}")) -> "Die PIN muss aus 4 bis 8 Ziffern bestehen."
        pin != confirmation -> "Die beiden PIN-Eingaben stimmen nicht überein."
        else -> null
    }

    private fun savePin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pinHash(pin, salt)
        prefs.edit()
            .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .putBoolean("pin_enabled", true)
            .apply()
    }

    private fun verifyPin(pin: String): Boolean = runCatching {
        val salt = Base64.decode(prefs.getString("pin_salt", ""), Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString("pin_hash", ""), Base64.NO_WRAP)
        salt.isNotEmpty() && expected.isNotEmpty() &&
            MessageDigest.isEqual(expected, pinHash(pin, salt))
    }.getOrDefault(false)

    private fun pinHash(pin: String, salt: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply {
            update(salt)
            update(pin.toByteArray(StandardCharsets.UTF_8))
        }.digest()

    fun playbackStats(): PlaybackStats {
        val top = playCounts.entries
            .sortedByDescending { it.value }
            .mapNotNull { (trackId, count) ->
                tracks.firstOrNull { it.id == trackId }?.let { it to count }
            }
            .take(5)
        return PlaybackStats(
            totalListeningMs = currentListeningTotal(),
            totalPlays = playCounts.values.sum(),
            uniqueTracks = playCounts.count { it.value > 0 },
            mostPlayed = top,
            recent = playbackHistory.take(30)
        )
    }

    fun wrappedSummary(monthly: Boolean): WrappedSummary {
        val calendar = Calendar.getInstance()
        val periodKey = if (monthly) {
            SimpleDateFormat("yyyy-MM", Locale.GERMANY).format(calendar.time)
        } else {
            SimpleDateFormat("yyyy", Locale.GERMANY).format(calendar.time)
        }
        if (monthly) {
            calendar.set(Calendar.DAY_OF_MONTH, 1)
        } else {
            calendar.set(Calendar.DAY_OF_YEAR, 1)
        }
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val periodEntries = playbackHistory.filter { it.playedAt >= calendar.timeInMillis }
        val counts = periodEntries.groupingBy { it.trackId }.eachCount()
        val top = counts.entries
            .sortedByDescending { it.value }
            .mapNotNull { (id, count) -> tracks.firstOrNull { it.id == id }?.let { it to count } }
            .take(5)
        val currentDelta = if (activeListeningStartedAt > 0L && !guestMode) {
            (SystemClock.elapsedRealtime() - activeListeningStartedAt).coerceAtLeast(0L)
        } else 0L
        val listening = if (monthly) monthlyListeningMs[periodKey] else yearlyListeningMs[periodKey]
        return WrappedSummary(
            label = if (monthly) "Dieser Monat" else "Dieses Jahr",
            listeningMs = (listening ?: 0L) + currentDelta,
            totalPlays = periodEntries.size,
            uniqueTracks = counts.size,
            topTracks = top
        )
    }

    fun recommendTrack(): AudioTrack? {
        if (tracks.isEmpty()) return null
        val recentIds = playbackHistory.take(10).mapTo(hashSetOf()) { it.trackId }
        val candidates = tracks.filter { it.id !in recentIds }.ifEmpty { tracks.toList() }
        val leastPlayed = candidates.sortedBy { playCounts[it.id] ?: 0 }
        return leastPlayed.take((leastPlayed.size / 2).coerceAtLeast(1)).randomOrNull()
    }

    fun storageUsage(): StorageUsage {
        fun size(root: File): Long = if (!root.exists()) 0L else root.walkTopDown()
            .filter(File::isFile)
            .sumOf(File::length)
        val audio = listOf(musicDir, downloadDir).distinctBy { it.absolutePath }.sumOf(::size)
        val covers = size(coversDir)
        val cache = size(app.cacheDir)
        return StorageUsage(
            totalBytes = audio + covers + cache,
            audioBytes = audio,
            coverBytes = covers,
            cacheBytes = cache,
            songCount = tracks.size,
            playlistCount = playlists.size
        )
    }

    fun clearCache() {
        if (guestMode) return
        app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        Toast.makeText(app, "Zwischenspeicher geleert.", Toast.LENGTH_SHORT).show()
    }

    fun next() {
        val current = currentTrack ?: return
        val queue = activePlaybackQueue()
        if (queue.isEmpty()) return
        if (shuffleEnabled && queue.size > 1) {
            ensureShuffleCycle(queue, current.id)
            if (shuffleRemainingIds.isEmpty()) {
                resetShuffleCycle(
                    queue,
                    current.id,
                    shuffleSignature(queue, playbackQueueKey)
                )
            }
            val nextId = shuffleRemainingIds.randomOrNull() ?: return
            val nextTrack = queue.firstOrNull { it.id == nextId } ?: return
            play(nextTrack, queue, playbackQueueKey)
            return
        }
        val index = queue.indexOfFirst { it.id == current.id }
            .takeIf { it >= 0 }
            ?: 0
        play(queue[(index + 1).mod(queue.size)], queue, playbackQueueKey)
    }

    fun previous() {
        currentTrack ?: return
        while (playbackBackStack.isNotEmpty()) {
            val entry = playbackBackStack.removeAt(playbackBackStack.lastIndex)
            val previousTrack = tracks.firstOrNull { it.id == entry.trackId } ?: continue
            val restoredQueue = entry.queueIds.mapNotNull { trackId ->
                tracks.firstOrNull { it.id == trackId }
            }.ifEmpty { tracks.toList() }
            playInternal(
                track = previousTrack,
                queue = restoredQueue,
                sourceKey = entry.sourceKey,
                rememberCurrentTrack = false
            )
            return
        }

        val queue = activePlaybackQueue()
        if (queue.isEmpty()) return
        val currentIndex = queue.indexOfFirst { it.id == currentTrack?.id }
            .takeIf { it >= 0 }
            ?: 0
        playInternal(
            track = queue[(currentIndex - 1).mod(queue.size)],
            queue = queue,
            sourceKey = playbackQueueKey,
            rememberCurrentTrack = false
        )
    }

    private fun shuffleSignature(queue: List<AudioTrack>, sourceKey: String): String =
        "$sourceKey|${queue.joinToString(",") { it.id }}"

    private fun resetShuffleCycle(
        queue: List<AudioTrack>,
        currentTrackId: String,
        signature: String
    ) {
        shuffleSessionSignature = signature
        shuffleRemainingIds.clear()
        shuffleRemainingIds += queue.map { it.id }.distinct().filter { it != currentTrackId }
    }

    private fun ensureShuffleCycle(queue: List<AudioTrack>, currentTrackId: String) {
        val signature = shuffleSignature(queue, playbackQueueKey)
        if (signature != shuffleSessionSignature) {
            resetShuffleCycle(queue, currentTrackId, signature)
            return
        }
        val availableIds = queue.mapTo(hashSetOf()) { it.id }
        shuffleRemainingIds.retainAll(availableIds)
        shuffleRemainingIds.remove(currentTrackId)
    }

    private fun activePlaybackQueue(): List<AudioTrack> {
        val availableIds = tracks.mapTo(hashSetOf()) { it.id }
        return playbackQueue
            .filter { it.id in availableIds }
            .ifEmpty { tracks.toList() }
    }

    fun toggleFavorite(id: String) = update(id) { it.copy(favorite = !it.favorite) }

    fun createPlaylist(name: String) {
        if (guestMode) return
        val clean = name.trim()
        if (clean.isNotEmpty() && clean !in playlists) {
            playlists.add(clean)
            playlistOrders[clean] = emptyList()
            save()
        }
    }

    fun renamePlaylist(oldName: String, newName: String) {
        if (guestMode) return
        val clean = newName.trim()
        val playlistIndex = playlists.indexOf(oldName)
        if (clean.isEmpty() || playlistIndex < 0 ||
            (clean != oldName && clean in playlists)
        ) {
            return
        }
        if (clean == oldName) return

        playlists[playlistIndex] = clean
        tracks.indices.forEach { index ->
            val track = tracks[index]
            if (oldName in track.playlists) {
                val updated = track.copy(
                    playlists = (track.playlists - oldName) + clean
                )
                tracks[index] = updated
                if (currentTrack?.id == updated.id) currentTrack = updated
            }
        }
        pendingSpotifyTracks.indices.forEach { index ->
            val pending = pendingSpotifyTracks[index]
            if (pending.playlist == oldName) {
                pendingSpotifyTracks[index] = pending.copy(playlist = clean)
            }
        }
        spotifyPlaylistLinks.remove(oldName)?.let { url ->
            spotifyPlaylistLinks[clean] = url
        }
        playlistCovers.remove(oldName)?.let { playlistCovers[clean] = it }
        playlistOrders.remove(oldName)?.let { playlistOrders[clean] = it }
        if (selectedPlaylist == oldName) selectedPlaylist = clean
        save()
    }

    fun deletePlaylist(name: String) {
        if (guestMode) return
        val deletedCover = playlistCovers[name]
        val memberIds = orderedPlaylistTracks(name).map { it.id }
        if (!playlists.remove(name)) return
        trashPlaylists.removeAll { it.name == name }
        trashPlaylists.add(
            0,
            TrashPlaylist(name, deletedCover, memberIds, System.currentTimeMillis())
        )
        tracks.indices.forEach { index ->
            val track = tracks[index]
            if (name in track.playlists) {
                val updated = track.copy(playlists = track.playlists - name)
                tracks[index] = updated
                if (currentTrack?.id == updated.id) currentTrack = updated
            }
        }
        pendingSpotifyTracks.removeAll { it.playlist == name }
        spotifyPlaylistLinks.remove(name)
        playlistCovers.remove(name)
        playlistOrders.remove(name)
        if (selectedPlaylist == name) selectedPlaylist = null
        save()
    }

    fun spotifyPlaylistUrl(name: String): String? = spotifyPlaylistLinks[name]

    fun openSpotifyPlaylist(name: String) {
        val url = spotifyPlaylistLinks[name] ?: return
        runCatching {
            app.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun togglePlaylist(id: String, playlist: String) = update(id) { track ->
        val next = track.playlists.toMutableSet()
        if (next.add(playlist)) {
            ensurePlaylistOrder(playlist, id)
        } else {
            next.remove(playlist)
            playlistOrders[playlist] = playlistOrders[playlist].orEmpty() - id
        }
        track.copy(playlists = next)
    }

    fun addTracksToPlaylists(trackIds: Set<String>, targetPlaylists: Set<String>) {
        if (guestMode) return
        val validPlaylists = targetPlaylists.filterTo(linkedSetOf()) { it in playlists }
        if (trackIds.isEmpty() || validPlaylists.isEmpty()) return
        var changed = 0
        tracks.indices.forEach { index ->
            val track = tracks[index]
            if (track.id !in trackIds) return@forEach
            val updatedPlaylists = track.playlists + validPlaylists
            if (updatedPlaylists != track.playlists) {
                val updated = track.copy(playlists = updatedPlaylists)
                tracks[index] = updated
                if (currentTrack?.id == updated.id) currentTrack = updated
                validPlaylists.forEach { ensurePlaylistOrder(it, updated.id) }
                changed++
            }
        }
        if (changed > 0) save()
        Toast.makeText(
            app,
            "$changed Song(s) zu ${validPlaylists.size} Playlist(s) hinzugefügt.",
            Toast.LENGTH_LONG
        ).show()
    }

    fun playlistCover(name: String): String? = playlistCovers[name]
        ?.takeIf { File(it).isFile }
        ?: orderedPlaylistTracks(name).firstNotNullOfOrNull { track ->
            track.coverPath?.takeIf { File(it).isFile }
        }

    fun setPlaylistCover(name: String, uri: Uri) {
        if (guestMode) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val target = File(
                    coversDir,
                    "playlist-${stableKey(name)}-${System.currentTimeMillis()}.jpg"
                )
                app.contentResolver.openInputStream(uri)!!.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                withContext(Dispatchers.Main) {
                    playlistCovers.put(name, target.absolutePath)?.let { oldPath ->
                        if (oldPath != target.absolutePath && oldPath.startsWith(coversDir.path)) {
                            File(oldPath).delete()
                        }
                    }
                    save()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    downloadStatus = "Playlist-Cover konnte nicht übernommen werden."
                }
            }
        }
    }

    fun moveTrackInPlaylist(playlist: String, trackId: String, direction: Int) {
        if (guestMode) return
        val order = orderedPlaylistTracks(playlist).map { it.id }.toMutableList()
        val from = order.indexOf(trackId)
        if (from < 0) return
        val to = (from + direction).coerceIn(0, order.lastIndex)
        if (from == to) return
        val moved = order.removeAt(from)
        order.add(to, moved)
        playlistOrders[playlist] = order
        save()
    }

    fun setPlaylistOrder(playlist: String, orderedTrackIds: List<String>) {
        if (guestMode) return
        val memberIds = tracks
            .filter { playlist in it.playlists }
            .map { it.id }
        val validIds = orderedTrackIds
            .filter { it in memberIds }
            .distinct()
        playlistOrders[playlist] = validIds + memberIds.filter { it !in validIds }
        save()
        Toast.makeText(app, "Playlist-Reihenfolge gespeichert.", Toast.LENGTH_SHORT).show()
    }

    fun playlistTracks(name: String): List<AudioTrack> = orderedPlaylistTracks(name)

    fun updateMetadata(id: String, title: String, artist: String) = update(id) {
        it.copy(
            title = title.trim().ifEmpty { it.title },
            artist = artist.trim().ifEmpty { "Unbekannter Interpret" }
        )
    }

    fun setCover(id: String, uri: Uri) {
        if (guestMode) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val target = copyLocalCover(uri, "song-${stableKey(id)}")
                    ?: error("Cover konnte nicht kopiert werden.")
                withContext(Dispatchers.Main) {
                    update(id) { it.copy(coverPath = target.absolutePath) }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    downloadStatus = "Cover konnte nicht übernommen werden."
                }
            }
        }
    }

    fun setCovers(ids: Set<String>, uri: Uri) {
        if (ids.isEmpty() || guestMode) return
        viewModelScope.launch(Dispatchers.IO) {
            val copied = ids.associateWith { id ->
                copyLocalCover(uri, "song-${stableKey(id)}")?.absolutePath
            }.filterValues { it != null }
            withContext(Dispatchers.Main) {
                var changed = 0
                tracks.indices.forEach { index ->
                    val path = copied[tracks[index].id] ?: return@forEach
                    val updated = tracks[index].copy(coverPath = path)
                    tracks[index] = updated
                    if (currentTrack?.id == updated.id) currentTrack = updated
                    changed++
                }
                if (changed > 0) save()
                val message = if (changed == ids.size) {
                    "Cover für $changed Song(s) geändert."
                } else {
                    "Cover für $changed von ${ids.size} Song(s) geändert."
                }
                downloadStatus = message
                Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun deleteTrack(track: AudioTrack) {
        if (guestMode) return
        if (currentTrack?.id == track.id) {
            player.stop()
            PlaybackKeepAliveService.stop(app)
            currentTrack = null
        }
        tracks.removeAll { it.id == track.id }
        trashTracks.removeAll { it.track.id == track.id }
        trashTracks.add(0, TrashTrack(track, System.currentTimeMillis()))
        playlistOrders.keys.toList().forEach { playlist ->
            playlistOrders[playlist] = playlistOrders[playlist].orEmpty() - track.id
        }
        save()
    }

    fun deleteTracks(trackIds: Set<String>) {
        if (guestMode) return
        val selectedTracks = tracks.filter { it.id in trackIds }
        if (selectedTracks.isEmpty()) return
        if (currentTrack?.id?.let { it in trackIds } == true) {
            player.stop()
            PlaybackKeepAliveService.stop(app)
            currentTrack = null
        }
        tracks.removeAll { it.id in trackIds }
        trashTracks.removeAll { it.track.id in trackIds }
        trashTracks.addAll(0, selectedTracks.map { TrashTrack(it, System.currentTimeMillis()) })
        playlistOrders.keys.toList().forEach { playlist ->
            playlistOrders[playlist] = playlistOrders[playlist].orEmpty()
                .filterNot { it in trackIds }
        }
        save()
        Toast.makeText(
            app,
            "${selectedTracks.size} Song(s) in den Papierkorb verschoben.",
            Toast.LENGTH_LONG
        ).show()
    }

    fun restoreTrashTrack(trackId: String) {
        if (guestMode) return
        val entry = trashTracks.firstOrNull { it.track.id == trackId } ?: return
        if (!File(entry.track.path).isFile) {
            Toast.makeText(app, "Die Audiodatei ist nicht mehr vorhanden.", Toast.LENGTH_LONG).show()
            return
        }
        val restored = entry.track.copy(
            playlists = entry.track.playlists.filterTo(linkedSetOf()) { it in playlists }
        )
        tracks.add(restored)
        restored.playlists.forEach { ensurePlaylistOrder(it, restored.id) }
        trashTracks.remove(entry)
        save()
    }

    fun permanentlyDeleteTrashTrack(trackId: String) {
        if (guestMode) return
        val entry = trashTracks.firstOrNull { it.track.id == trackId } ?: return
        trashTracks.remove(entry)
        val track = entry.track
        if (tracks.none { it.path == track.path } &&
            trashTracks.none { it.track.path == track.path } &&
            (track.path.startsWith(app.filesDir.absolutePath) ||
                track.path.startsWith(downloadDir.absolutePath))
        ) File(track.path).delete()
        track.coverPath?.let { coverPath ->
            if (tracks.none { it.coverPath == coverPath } &&
                trashTracks.none { it.track.coverPath == coverPath } &&
                coverPath !in playlistCovers.values &&
                trashPlaylists.none { it.coverPath == coverPath }
            ) File(coverPath).delete()
        }
        save()
    }

    fun restoreTrashPlaylist(name: String) {
        if (guestMode) return
        val entry = trashPlaylists.firstOrNull { it.name == name } ?: return
        val restoredName = generateSequence(name) { previous -> "$previous (wiederhergestellt)" }
            .first { it !in playlists }
        playlists.add(restoredName)
        entry.coverPath?.takeIf { File(it).isFile }?.let { playlistCovers[restoredName] = it }
        val validIds = entry.memberTrackIds.filter { id -> tracks.any { it.id == id } }
        playlistOrders[restoredName] = validIds
        tracks.indices.forEach { index ->
            if (tracks[index].id in validIds) {
                tracks[index] = tracks[index].copy(playlists = tracks[index].playlists + restoredName)
            }
        }
        trashPlaylists.remove(entry)
        save()
    }

    fun permanentlyDeleteTrashPlaylist(name: String) {
        if (guestMode) return
        val entry = trashPlaylists.firstOrNull { it.name == name } ?: return
        trashPlaylists.remove(entry)
        entry.coverPath?.let { path ->
            if (path !in playlistCovers.values && tracks.none { it.coverPath == path }) {
                File(path).delete()
            }
        }
        save()
    }

    fun emptyTrash() {
        if (guestMode) return
        trashTracks.map { it.track.id }.toList().forEach(::permanentlyDeleteTrashTrack)
        trashPlaylists.map { it.name }.toList().forEach(::permanentlyDeleteTrashPlaylist)
    }

    fun visibleTracks(mode: LibraryMode): List<AudioTrack> {
        val query = search.trim()
        val filtered = tracks.filter { track ->
            val sectionMatch = when (mode) {
                LibraryMode.ALL -> true
                LibraryMode.FAVORITES -> track.favorite
                LibraryMode.BOOKMARKS -> track.bookmarked
                LibraryMode.PLAYLIST -> selectedPlaylist in track.playlists
            }
            val searchMatch = query.isBlank() ||
                track.title.contains(query, ignoreCase = true) ||
                track.artist.contains(query, ignoreCase = true)
            sectionMatch && searchMatch
        }
        return if (mode == LibraryMode.PLAYLIST && selectedPlaylist != null) {
            val order = playlistOrders[selectedPlaylist].orEmpty()
            val positions = order.withIndex().associate { it.value to it.index }
            filtered.sortedBy { positions[it.id] ?: Int.MAX_VALUE }
        } else {
            when (librarySort) {
                LibrarySort.ADDED_NEWEST -> filtered.sortedByDescending { tracks.indexOf(it) }
                LibrarySort.ADDED_OLDEST -> filtered.sortedBy { tracks.indexOf(it) }
                LibrarySort.TITLE_AZ -> filtered.sortedBy { it.title.lowercase() }
                LibrarySort.TITLE_ZA -> filtered.sortedByDescending { it.title.lowercase() }
            }
        }
    }

    private suspend fun addFile(
        file: File,
        playlistName: String? = null,
        coverPath: String? = null
    ): AudioTrack? {
        if (!file.exists()) return null
        val metadata = metadata(file)
        val track = AudioTrack(
            id = UUID.randomUUID().toString(),
            title = metadata.first.ifBlank { file.nameWithoutExtension },
            artist = metadata.second.ifBlank { "Unbekannter Interpret" },
            path = file.absolutePath,
            coverPath = coverPath,
            playlists = playlistName?.let(::setOf).orEmpty()
        )
        return withContext(Dispatchers.Main) {
            val existingIndex = tracks.indexOfFirst { it.path == file.absolutePath }
            if (existingIndex < 0) {
                val assignments = pendingSpotifyTracks.filter {
                    spotifyMatches(track.title, track.artist, it.title, it.artist)
                }
                val assignedTrack = track.copy(
                    playlists = track.playlists + assignments.map { it.playlist }
                )
                val duplicate = tracks.firstOrNull {
                    normalized(it.title) == normalized(assignedTrack.title) &&
                        normalized(it.artist) == normalized(assignedTrack.artist)
                }
                if (duplicate != null) {
                    pendingDuplicates.add(
                        DuplicateCandidate(UUID.randomUUID().toString(), assignedTrack, duplicate)
                    )
                    null
                } else {
                    pendingSpotifyTracks.removeAll(assignments.toSet())
                    commitNewTrack(assignedTrack)
                }
            } else if (playlistName != null &&
                playlistName !in tracks[existingIndex].playlists
            ) {
                tracks[existingIndex] = tracks[existingIndex].copy(
                    coverPath = tracks[existingIndex].coverPath ?: coverPath,
                    playlists = tracks[existingIndex].playlists + playlistName
                )
                ensurePlaylistOrder(playlistName, tracks[existingIndex].id)
                save()
                tracks[existingIndex]
            } else {
                if (coverPath != null && tracks[existingIndex].coverPath == null) {
                    tracks[existingIndex] = tracks[existingIndex].copy(coverPath = coverPath)
                    save()
                }
                tracks[existingIndex]
            }
        }
    }

    private fun commitNewTrack(track: AudioTrack): AudioTrack {
        tracks.add(track)
        track.playlists.forEach { ensurePlaylistOrder(it, track.id) }
        save()
        return track
    }

    fun resolveDuplicate(token: String, addAnyway: Boolean) {
        if (guestMode) return
        val candidate = pendingDuplicates.firstOrNull { it.token == token } ?: return
        pendingDuplicates.remove(candidate)
        if (addAnyway) {
            commitNewTrack(candidate.track)
        } else {
            val track = candidate.track
            if (tracks.none { it.path == track.path } &&
                (track.path.startsWith(app.filesDir.absolutePath) ||
                    track.path.startsWith(downloadDir.absolutePath))
            ) File(track.path).delete()
            track.coverPath?.let { cover ->
                if (tracks.none { it.coverPath == cover } && cover !in playlistCovers.values) {
                    File(cover).delete()
                }
            }
        }
    }

    private fun metadata(file: File): Pair<String, String> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: file.nameWithoutExtension
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: "Unbekannter Interpret"
            title to artist
        } catch (_: Exception) {
            file.nameWithoutExtension to "Unbekannter Interpret"
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scanDownloadedFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            downloadDir.walkTopDown()
                .filter { file ->
                    file.isFile && file.extension.equals("mp3", true) &&
                        trashTracks.none { it.track.path == file.absolutePath }
                }
                .forEach { addFile(it) }
        }
    }

    private fun orderedPlaylistTracks(name: String): List<AudioTrack> {
        val members = tracks.filter { name in it.playlists }
        val storedOrder = playlistOrders[name].orEmpty()
        val positions = storedOrder.withIndex().associate { it.value to it.index }
        return members.sortedBy { positions[it.id] ?: Int.MAX_VALUE }
    }

    private fun ensurePlaylistOrder(name: String, trackId: String) {
        val current = playlistOrders[name].orEmpty()
        if (trackId !in current) playlistOrders[name] = current + trackId
    }

    private fun recordPlay(track: AudioTrack) {
        if (guestMode) return
        playCounts[track.id] = (playCounts[track.id] ?: 0) + 1
        playbackHistory.add(
            0,
            PlaybackHistoryEntry(
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                playedAt = System.currentTimeMillis()
            )
        )
        while (playbackHistory.size > 10_000) {
            playbackHistory.removeAt(playbackHistory.lastIndex)
        }
        saveStats()
    }

    private fun updateListeningSession(nowPlaying: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (nowPlaying) {
            if (activeListeningStartedAt == 0L && !guestMode) activeListeningStartedAt = now
        } else if (activeListeningStartedAt > 0L) {
            val delta = (now - activeListeningStartedAt).coerceAtLeast(0L)
            totalListeningMs += delta
            val wallClock = System.currentTimeMillis()
            val monthKey = SimpleDateFormat("yyyy-MM", Locale.GERMANY).format(wallClock)
            val yearKey = SimpleDateFormat("yyyy", Locale.GERMANY).format(wallClock)
            monthlyListeningMs[monthKey] = (monthlyListeningMs[monthKey] ?: 0L) + delta
            yearlyListeningMs[yearKey] = (yearlyListeningMs[yearKey] ?: 0L) + delta
            activeListeningStartedAt = 0L
            saveStats()
        }
    }

    private fun currentListeningTotal(): Long = totalListeningMs +
        if (activeListeningStartedAt > 0L) {
            (SystemClock.elapsedRealtime() - activeListeningStartedAt).coerceAtLeast(0L)
        } else {
            0L
        }

    private fun saveStats() {
        prefs.edit()
            .putLong("total_listening_ms", totalListeningMs)
            .putString(
                "play_counts",
                JSONObject().apply {
                    playCounts.forEach { (trackId, count) -> put(trackId, count) }
                }.toString()
            )
            .putString(
                "playback_history",
                JSONArray().apply {
                    playbackHistory.forEach { entry ->
                        put(JSONObject().apply {
                            put("trackId", entry.trackId)
                            put("title", entry.title)
                            put("artist", entry.artist)
                            put("playedAt", entry.playedAt)
                        })
                    }
                }.toString()
            )
            .putString(
                "monthly_listening_ms",
                JSONObject().apply {
                    monthlyListeningMs.forEach { (period, value) -> put(period, value) }
                }.toString()
            )
            .putString(
                "yearly_listening_ms",
                JSONObject().apply {
                    yearlyListeningMs.forEach { (period, value) -> put(period, value) }
                }.toString()
            )
            .apply()
    }

    private fun mergeImportedBackup(imported: KochifyBackupImport): String {
        imported.playlists.forEach { playlist ->
            if (playlist !in playlists) playlists.add(playlist)
            if (playlist !in playlistOrders) playlistOrders[playlist] = emptyList()
        }
        imported.playlistCovers.forEach { (name, path) ->
            playlistCovers[name] = path
        }
        var restoredTracks = 0
        var matchedTracks = 0
        var missingTracks = 0
        val restoredIdMap = mutableMapOf<String, String>()
        imported.tracks.forEach { backupTrack ->
            val existingIndex = tracks.indexOfFirst {
                spotifyMatches(
                    it.title,
                    it.artist,
                    backupTrack.title,
                    backupTrack.artist
                )
            }
            val resultingTrack = if (existingIndex >= 0) {
                val existing = tracks[existingIndex]
                val updated = existing.copy(
                    favorite = existing.favorite || backupTrack.favorite,
                    bookmarked = existing.bookmarked || backupTrack.bookmarked,
                    playlists = existing.playlists + backupTrack.playlists,
                    coverPath = backupTrack.coverPath ?: existing.coverPath
                )
                tracks[existingIndex] = updated
                if (currentTrack?.id == updated.id) currentTrack = updated
                backupTrack.audioPath?.let { File(it).delete() }
                matchedTracks++
                updated
            } else if (backupTrack.audioPath != null) {
                val restored = AudioTrack(
                    id = UUID.randomUUID().toString(),
                    title = backupTrack.title,
                    artist = backupTrack.artist,
                    path = backupTrack.audioPath,
                    coverPath = backupTrack.coverPath,
                    favorite = backupTrack.favorite,
                    bookmarked = backupTrack.bookmarked,
                    playlists = backupTrack.playlists
                )
                tracks.add(restored)
                restoredTracks++
                restored
            } else {
                backupTrack.coverPath?.let { File(it).delete() }
                missingTracks++
                null
            }
            resultingTrack?.playlists?.forEach { playlist ->
                ensurePlaylistOrder(playlist, resultingTrack.id)
            }
            resultingTrack?.let { restoredIdMap[backupTrack.sourceId] = it.id }
        }

        if (imported.includeSettings) {
            themeMode = imported.themeMode
            shuffleEnabled = imported.shuffleEnabled
            repeatOneEnabled = imported.repeatOneEnabled
            librarySort = imported.librarySort
            playbackSpeed = imported.playbackSpeed.coerceIn(0.25f, 2f)
            player.setPlaybackSpeed(playbackSpeed)
            player.repeatMode = if (repeatOneEnabled) {
                Player.REPEAT_MODE_ONE
            } else {
                Player.REPEAT_MODE_OFF
            }
            prefs.edit()
                .putString("theme_mode", themeMode.name)
                .putBoolean("shuffle_enabled", shuffleEnabled)
                .putBoolean("repeat_one_enabled", repeatOneEnabled)
                .putString("library_sort", librarySort.name)
                .putFloat("playback_speed", playbackSpeed)
                .apply()
        }
        if (imported.includeStats) {
            totalListeningMs = maxOf(totalListeningMs, imported.totalListeningMs)
            imported.playCounts.forEach { (sourceId, count) ->
                val localId = restoredIdMap[sourceId] ?: sourceId
                playCounts[localId] = maxOf(playCounts[localId] ?: 0, count)
            }
            val knownHistory = playbackHistory.mapTo(hashSetOf()) {
                "${it.trackId}:${it.playedAt}"
            }
            imported.playbackHistory.forEach { entry ->
                val mapped = entry.copy(trackId = restoredIdMap[entry.trackId] ?: entry.trackId)
                if (knownHistory.add("${mapped.trackId}:${mapped.playedAt}")) {
                    playbackHistory.add(mapped)
                }
            }
            playbackHistory.sortByDescending { it.playedAt }
            while (playbackHistory.size > 10_000) {
                playbackHistory.removeAt(playbackHistory.lastIndex)
            }
            imported.monthlyListeningMs.forEach { (period, value) ->
                monthlyListeningMs[period] = maxOf(monthlyListeningMs[period] ?: 0L, value)
            }
            imported.yearlyListeningMs.forEach { (period, value) ->
                yearlyListeningMs[period] = maxOf(yearlyListeningMs[period] ?: 0L, value)
            }
            saveStats()
        }
        save()
        return buildString {
            append("Import abgeschlossen: ${imported.playlists.size} Playlists")
            if (restoredTracks > 0) append(", $restoredTracks Songs wiederhergestellt")
            if (matchedTracks > 0) append(", $matchedTracks vorhandene Songs zugeordnet")
            if (missingTracks > 0) append(", $missingTracks Songs ohne Datei übersprungen")
            if (imported.restoredSongCovers > 0) {
                append(", ${imported.restoredSongCovers} Songbilder")
            }
            if (imported.restoredPlaylistCovers > 0) {
                append(", ${imported.restoredPlaylistCovers} Playlistbilder")
            }
            if (imported.includeStats) append(", Statistiken übernommen")
            append(".")
        }
    }

    private fun update(id: String, transform: (AudioTrack) -> AudioTrack) {
        if (guestMode) return
        val index = tracks.indexOfFirst { it.id == id }
        if (index >= 0) {
            val updated = transform(tracks[index])
            tracks[index] = updated
            if (currentTrack?.id == id) {
                currentTrack = updated
                if (player.isPlaying) {
                    PlaybackKeepAliveService.start(app, updated.title, updated.artist)
                }
            }
            save()
        }
    }

    private fun load() {
        runCatching {
            val array = JSONArray(prefs.getString("tracks", "[]"))
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val playlistArray = item.optJSONArray("playlists") ?: JSONArray()
                val trackPlaylists = buildSet {
                    repeat(playlistArray.length()) { add(playlistArray.getString(it)) }
                }
                val track = AudioTrack(
                    id = item.getString("id"),
                    title = item.getString("title"),
                    artist = item.optString("artist", "Unbekannter Interpret"),
                    path = item.getString("path"),
                    coverPath = item.optString("coverPath").takeIf { it.isNotBlank() },
                    favorite = item.optBoolean("favorite"),
                    bookmarked = item.optBoolean("bookmarked"),
                    playlists = trackPlaylists
                )
                if (File(track.path).exists()) tracks.add(track)
            }
            val storedPlaylists = JSONArray(prefs.getString("playlists", "[]"))
            repeat(storedPlaylists.length()) { playlists.add(storedPlaylists.getString(it)) }

            val storedPlaylistCovers = JSONObject(
                prefs.getString("playlist_covers", "{}").orEmpty().ifBlank { "{}" }
            )
            val coverNames = storedPlaylistCovers.keys()
            while (coverNames.hasNext()) {
                val name = coverNames.next()
                storedPlaylistCovers.optString(name)
                    .takeIf { it.isNotBlank() && File(it).isFile }
                    ?.let { playlistCovers[name] = it }
            }

            val storedPlaylistOrders = JSONObject(
                prefs.getString("playlist_orders", "{}").orEmpty().ifBlank { "{}" }
            )
            val orderNames = storedPlaylistOrders.keys()
            while (orderNames.hasNext()) {
                val name = orderNames.next()
                val order = storedPlaylistOrders.optJSONArray(name) ?: JSONArray()
                playlistOrders[name] = buildList {
                    repeat(order.length()) { index ->
                        order.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }

            val pending = JSONArray(prefs.getString("spotify_pending_tracks", "[]"))
            repeat(pending.length()) { index ->
                val item = pending.getJSONObject(index)
                pendingSpotifyTracks.add(
                    PendingSpotifyTrack(
                        playlist = item.getString("playlist"),
                        title = item.getString("title"),
                        artist = item.optString("artist", "Unbekannter Interpret")
                    )
                )
            }

            val spotifyLinks = JSONObject(
                prefs.getString("spotify_playlist_links", "{}").orEmpty().ifBlank { "{}" }
            )
            val linkNames = spotifyLinks.keys()
            while (linkNames.hasNext()) {
                val name = linkNames.next()
                spotifyLinks.optString(name)
                    .takeIf { it.isNotBlank() }
                    ?.let { spotifyPlaylistLinks[name] = it }
            }

            val storedPlayCounts = JSONObject(
                prefs.getString("play_counts", "{}").orEmpty().ifBlank { "{}" }
            )
            val countedTrackIds = storedPlayCounts.keys()
            while (countedTrackIds.hasNext()) {
                val trackId = countedTrackIds.next()
                val count = storedPlayCounts.optInt(trackId)
                if (count > 0) playCounts[trackId] = count
            }

            val storedHistory = JSONArray(prefs.getString("playback_history", "[]"))
            repeat(minOf(storedHistory.length(), 10_000)) { index ->
                val item = storedHistory.optJSONObject(index) ?: return@repeat
                playbackHistory.add(
                    PlaybackHistoryEntry(
                        trackId = item.optString("trackId"),
                        title = item.optString("title").ifBlank { "Unbekannter Titel" },
                        artist = item.optString("artist").ifBlank {
                            "Unbekannter Interpret"
                        },
                        playedAt = item.optLong("playedAt")
                    )
                )
            }

            fun loadLongMap(prefName: String, target: MutableMap<String, Long>) {
                val stored = JSONObject(
                    prefs.getString(prefName, "{}").orEmpty().ifBlank { "{}" }
                )
                val keys = stored.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    stored.optLong(key).takeIf { it > 0L }?.let { target[key] = it }
                }
            }
            loadLongMap("monthly_listening_ms", monthlyListeningMs)
            loadLongMap("yearly_listening_ms", yearlyListeningMs)

            val storedTrashTracks = JSONArray(prefs.getString("trash_tracks", "[]"))
            repeat(storedTrashTracks.length()) { index ->
                val entry = storedTrashTracks.optJSONObject(index) ?: return@repeat
                val item = entry.optJSONObject("track") ?: return@repeat
                val playlistArray = item.optJSONArray("playlists") ?: JSONArray()
                val deletedTrack = AudioTrack(
                    id = item.optString("id"),
                    title = item.optString("title").ifBlank { "Unbekannter Titel" },
                    artist = item.optString("artist").ifBlank { "Unbekannter Interpret" },
                    path = item.optString("path"),
                    coverPath = item.optString("coverPath").takeIf { it.isNotBlank() },
                    favorite = item.optBoolean("favorite"),
                    bookmarked = item.optBoolean("bookmarked"),
                    playlists = buildSet {
                        repeat(playlistArray.length()) { playlistIndex ->
                            playlistArray.optString(playlistIndex)
                                .takeIf { it.isNotBlank() }
                                ?.let(::add)
                        }
                    }
                )
                if (deletedTrack.path.isNotBlank()) {
                    trashTracks.add(TrashTrack(deletedTrack, entry.optLong("deletedAt")))
                }
            }
            val storedTrashPlaylists = JSONArray(prefs.getString("trash_playlists", "[]"))
            repeat(storedTrashPlaylists.length()) { index ->
                val item = storedTrashPlaylists.optJSONObject(index) ?: return@repeat
                val members = item.optJSONArray("memberTrackIds") ?: JSONArray()
                trashPlaylists.add(
                    TrashPlaylist(
                        name = item.optString("name"),
                        coverPath = item.optString("coverPath").takeIf { it.isNotBlank() },
                        memberTrackIds = buildList {
                            repeat(members.length()) { memberIndex ->
                                members.optString(memberIndex)
                                    .takeIf { it.isNotBlank() }
                                    ?.let(::add)
                            }
                        },
                        deletedAt = item.optLong("deletedAt")
                    )
                )
            }

            playlists.forEach { playlist ->
                val existingIds = tracks.filter { playlist in it.playlists }.map { it.id }
                val stored = playlistOrders[playlist].orEmpty().filter { it in existingIds }
                playlistOrders[playlist] = stored + existingIds.filter { it !in stored }
            }
        }
    }

    private fun save() {
        val array = JSONArray()
        tracks.forEach { track ->
            array.put(JSONObject().apply {
                put("id", track.id)
                put("title", track.title)
                put("artist", track.artist)
                put("path", track.path)
                put("coverPath", track.coverPath ?: "")
                put("favorite", track.favorite)
                put("bookmarked", track.bookmarked)
                put("playlists", JSONArray(track.playlists.toList()))
            })
        }
        prefs.edit()
            .putString("tracks", array.toString())
            .putString("playlists", JSONArray(playlists.toList()).toString())
            .putString(
                "playlist_covers",
                JSONObject().apply {
                    playlistCovers.forEach { (name, path) -> put(name, path) }
                }.toString()
            )
            .putString(
                "playlist_orders",
                JSONObject().apply {
                    playlistOrders.forEach { (name, order) ->
                        put(name, JSONArray(order))
                    }
                }.toString()
            )
            .putString(
                "spotify_pending_tracks",
                JSONArray().apply {
                    pendingSpotifyTracks.forEach { pending ->
                        put(JSONObject().apply {
                            put("playlist", pending.playlist)
                            put("title", pending.title)
                            put("artist", pending.artist)
                        })
                    }
                }.toString()
            )
            .putString(
                "spotify_playlist_links",
                JSONObject().apply {
                    spotifyPlaylistLinks.forEach { (name, url) -> put(name, url) }
                }.toString()
            )
            .putString(
                "trash_tracks",
                JSONArray().apply {
                    trashTracks.forEach { entry ->
                        put(JSONObject().apply {
                            put("deletedAt", entry.deletedAt)
                            put("track", JSONObject().apply {
                                put("id", entry.track.id)
                                put("title", entry.track.title)
                                put("artist", entry.track.artist)
                                put("path", entry.track.path)
                                put("coverPath", entry.track.coverPath ?: "")
                                put("favorite", entry.track.favorite)
                                put("bookmarked", entry.track.bookmarked)
                                put("playlists", JSONArray(entry.track.playlists.toList()))
                            })
                        })
                    }
                }.toString()
            )
            .putString(
                "trash_playlists",
                JSONArray().apply {
                    trashPlaylists.forEach { entry ->
                        put(JSONObject().apply {
                            put("name", entry.name)
                            put("coverPath", entry.coverPath ?: "")
                            put("memberTrackIds", JSONArray(entry.memberTrackIds))
                            put("deletedAt", entry.deletedAt)
                        })
                    }
                }.toString()
            )
            .apply()
    }

    override fun onCleared() {
        updateListeningSession(false)
        closeLocalTransfer()
        PlaybackCommandBridge.unregister()
        runCatching { app.unregisterReceiver(becomingNoisyReceiver) }
        DownloadKeepAliveService.stop(app)
        player.release()
        PlaybackKeepAliveService.stop(app)
        super.onCleared()
    }
}

enum class LibraryMode { ALL, FAVORITES, BOOKMARKS, PLAYLIST }
