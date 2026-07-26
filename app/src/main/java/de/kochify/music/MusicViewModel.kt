package de.kochify.music

import android.app.Application
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.UUID

data class AudioTrack(
    val id: String,
    val title: String,
    val artist: String,
    val path: String,
    val coverPath: String? = null,
    val favorite: Boolean = false,
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

enum class KochifyThemeMode {
    BLACK,
    LIGHT,
    RGB
}

private data class YoutubeDownloadItem(
    val id: String,
    val title: String,
    val url: String
)

private data class YoutubeDownloadPlan(
    val title: String,
    val items: List<YoutubeDownloadItem>,
    val isPlaylist: Boolean,
    val unavailableItems: Int
)

private const val SPOTIFY_AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
private const val SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token"
private const val SPOTIFY_API_URL = "https://api.spotify.com/v1"
private const val SPOTIFY_REDIRECT_URI = "kochify://spotify-callback"
private const val YTDLP_UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L

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
    private val prefs = app.getSharedPreferences("kochify_music", 0)
    private val musicDir = File(app.filesDir, "music").apply { mkdirs() }
    private val coversDir = File(app.filesDir, "covers").apply { mkdirs() }
    private val downloadDir =
        File(app.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Kochify").apply { mkdirs() }

    val tracks = mutableStateListOf<AudioTrack>()
    val playlists = mutableStateListOf<String>()
    private val pendingSpotifyTracks = mutableStateListOf<PendingSpotifyTrack>()
    private val spotifyPlaylistLinks = mutableStateMapOf<String, String>()
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
    var themeMode by mutableStateOf(
        runCatching {
            KochifyThemeMode.valueOf(
                prefs.getString("theme_mode", KochifyThemeMode.BLACK.name).orEmpty()
            )
        }.getOrDefault(KochifyThemeMode.BLACK)
    )
        private set
    var downloadProgress by mutableFloatStateOf(0f)
    var downloadStatus by mutableStateOf<String?>(null)
    var isDownloading by mutableStateOf(false)
    var spotifyStatus by mutableStateOf<String?>(null)
    var isSpotifyImporting by mutableStateOf(false)
    val spotifyClientId: String
        get() = prefs.getString("spotify_client_id", "").orEmpty()

    init {
        load()
        scanDownloadedFiles()
        player.repeatMode =
            if (repeatOneEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                playbackPositionMs = player.currentPosition.coerceAtLeast(0L)
                playbackDurationMs = player.duration.takeIf { it > 0L } ?: 0L
                if (playbackState == Player.STATE_ENDED && !repeatOneEnabled) {
                    next()
                }
            }
        })
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

    fun importAudio(uris: List<Uri>) {
        if (uris.isEmpty()) return
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
                downloadStatus = "$imported Audiodatei(en) importiert."
            }
        }
    }

    fun downloadFromYoutube(url: String) {
        if (url.isBlank() || isDownloading) return
        val cleanUrl = url.trim()
        if (!isYoutubeUrl(cleanUrl)) {
            downloadStatus = "Bitte einen gültigen YouTube- oder YouTube-Music-Link eingeben."
            return
        }
        isDownloading = true
        downloadProgress = 0f
        downloadStatus = "Download wird vorbereitet …"

        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    downloadStatus = "Download-Modul wird gestartet …"
                }
                ensureDownloaderReady()
                updateDownloaderIfNeeded()
                withContext(Dispatchers.Main) {
                    downloadStatus = "YouTube-Link wird analysiert …"
                }
                val plan = createYoutubeDownloadPlan(cleanUrl)
                val playlistName = plan.title.takeIf { plan.isPlaylist }
                val targetDir = File(
                    downloadDir,
                    safeFileName(playlistName ?: "Einzelne Downloads")
                ).apply { mkdirs() }

                if (playlistName != null) {
                    withContext(Dispatchers.Main) {
                        if (playlistName !in playlists) {
                            playlists.add(playlistName)
                            save()
                        }
                        downloadStatus = buildString {
                            append("„$playlistName“: ${plan.items.size} Titel gefunden.")
                            if (plan.unavailableItems > 0) {
                                append(" ${plan.unavailableItems} nicht verfügbar.")
                            }
                        }
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
                    }

                    try {
                        val request = YoutubeDLRequest(item.url).apply {
                            addOption("--extract-audio")
                            addOption("--audio-format", "mp3")
                            addOption("--audio-quality", "0")
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
                            itemFiles.forEach { addFile(it, playlistName) }
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
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    downloadStatus = "Download fehlgeschlagen: ${compactDownloadError(e)}"
                }
            } finally {
                withContext(Dispatchers.Main) { isDownloading = false }
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
                    url = url
                )
            ),
            isPlaylist = false,
            unavailableItems = 0
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
                url = itemUrl
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
            unavailableItems = unavailable
        )
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
            if (playlistName !in playlists) playlists.add(playlistName)
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

    fun play(track: AudioTrack) {
        currentTrack = track
        playbackPositionMs = 0L
        playbackDurationMs = 0L
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(track.path))))
        player.prepare()
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

    fun next() {
        val current = currentTrack ?: return
        if (tracks.isEmpty()) return
        if (shuffleEnabled && tracks.size > 1) {
            val candidates = tracks.filter { it.id != current.id }
            play(candidates.random())
            return
        }
        val index = tracks.indexOfFirst { it.id == current.id }
        play(tracks[(index + 1).mod(tracks.size)])
    }

    fun previous() {
        val current = currentTrack ?: return
        if (player.currentPosition > 5_000L) {
            seekTo(0L)
            return
        }
        val index = tracks.indexOfFirst { it.id == current.id }
        if (tracks.isNotEmpty()) play(tracks[(index - 1).mod(tracks.size)])
    }

    fun toggleFavorite(id: String) = update(id) { it.copy(favorite = !it.favorite) }

    fun createPlaylist(name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty() && clean !in playlists) {
            playlists.add(clean)
            save()
        }
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
        if (!next.add(playlist)) next.remove(playlist)
        track.copy(playlists = next)
    }

    fun updateMetadata(id: String, title: String, artist: String) = update(id) {
        it.copy(
            title = title.trim().ifEmpty { it.title },
            artist = artist.trim().ifEmpty { "Unbekannter Interpret" }
        )
    }

    fun setCover(id: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val target = File(coversDir, "$id.jpg")
                app.contentResolver.openInputStream(uri)!!.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
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

    fun deleteTrack(track: AudioTrack) {
        if (currentTrack?.id == track.id) {
            player.stop()
            PlaybackKeepAliveService.stop(app)
            currentTrack = null
        }
        tracks.removeAll { it.id == track.id }
        if (track.path.startsWith(app.filesDir.absolutePath) ||
            track.path.startsWith(downloadDir.absolutePath)
        ) {
            File(track.path).delete()
        }
        track.coverPath?.let { File(it).delete() }
        save()
    }

    fun visibleTracks(mode: LibraryMode): List<AudioTrack> {
        val query = search.trim()
        return tracks.filter { track ->
            val sectionMatch = when (mode) {
                LibraryMode.ALL -> true
                LibraryMode.FAVORITES -> track.favorite
                LibraryMode.PLAYLIST -> selectedPlaylist in track.playlists
            }
            val searchMatch = query.isBlank() ||
                track.title.contains(query, ignoreCase = true) ||
                track.artist.contains(query, ignoreCase = true)
            sectionMatch && searchMatch
        }
    }

    private suspend fun addFile(file: File, playlistName: String? = null) {
        if (!file.exists()) return
        val metadata = metadata(file)
        val track = AudioTrack(
            id = UUID.randomUUID().toString(),
            title = metadata.first.ifBlank { file.nameWithoutExtension },
            artist = metadata.second.ifBlank { "Unbekannter Interpret" },
            path = file.absolutePath,
            playlists = playlistName?.let(::setOf).orEmpty()
        )
        withContext(Dispatchers.Main) {
            val existingIndex = tracks.indexOfFirst { it.path == file.absolutePath }
            if (existingIndex < 0) {
                val assignments = pendingSpotifyTracks.filter {
                    spotifyMatches(track.title, track.artist, it.title, it.artist)
                }
                val assignedTrack = track.copy(
                    playlists = track.playlists + assignments.map { it.playlist }
                )
                pendingSpotifyTracks.removeAll(assignments.toSet())
                tracks.add(assignedTrack)
                save()
            } else if (playlistName != null &&
                playlistName !in tracks[existingIndex].playlists
            ) {
                tracks[existingIndex] = tracks[existingIndex].copy(
                    playlists = tracks[existingIndex].playlists + playlistName
                )
                save()
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
                .filter { it.isFile && it.extension.equals("mp3", true) }
                .forEach { addFile(it) }
        }
    }

    private fun update(id: String, transform: (AudioTrack) -> AudioTrack) {
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
                    playlists = trackPlaylists
                )
                if (File(track.path).exists()) tracks.add(track)
            }
            val storedPlaylists = JSONArray(prefs.getString("playlists", "[]"))
            repeat(storedPlaylists.length()) { playlists.add(storedPlaylists.getString(it)) }

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
                put("playlists", JSONArray(track.playlists.toList()))
            })
        }
        prefs.edit()
            .putString("tracks", array.toString())
            .putString("playlists", JSONArray(playlists.toList()).toString())
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
            .apply()
    }

    override fun onCleared() {
        player.release()
        PlaybackKeepAliveService.stop(app)
        super.onCleared()
    }
}

enum class LibraryMode { ALL, FAVORITES, PLAYLIST }
