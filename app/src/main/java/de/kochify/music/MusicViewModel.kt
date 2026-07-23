package de.kochify.music

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val prefs = app.getSharedPreferences("kochify_music", 0)
    private val musicDir = File(app.filesDir, "music").apply { mkdirs() }
    private val coversDir = File(app.filesDir, "covers").apply { mkdirs() }
    private val downloadDir =
        File(app.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Kochify").apply { mkdirs() }

    val tracks = mutableStateListOf<AudioTrack>()
    val playlists = mutableStateListOf<String>()
    val player = ExoPlayer.Builder(app).build()

    var search by mutableStateOf("")
    var selectedPlaylist by mutableStateOf<String?>(null)
    var currentTrack by mutableStateOf<AudioTrack?>(null)
    var isPlaying by mutableStateOf(false)
    var downloadProgress by mutableFloatStateOf(0f)
    var downloadStatus by mutableStateOf<String?>(null)
    var isDownloading by mutableStateOf(false)

    init {
        load()
        scanDownloadedFiles()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }
        })
        viewModelScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
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
        isDownloading = true
        downloadProgress = 0f
        downloadStatus = "Download wird vorbereitet …"

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val before = downloadDir.listFiles()?.map { it.absolutePath }?.toSet().orEmpty()
                val request = YoutubeDLRequest(url.trim()).apply {
                    addOption("--extract-audio")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                    addOption("--embed-metadata")
                    addOption("--embed-thumbnail")
                    addOption("--yes-playlist")
                    addOption("--no-overwrites")
                    addOption(
                        "-o",
                        File(downloadDir, "%(playlist_title,channel)s/%(title)s [%(id)s].%(ext)s").absolutePath
                    )
                }
                YoutubeDL.getInstance().execute(request) { progress, eta, _ ->
                    viewModelScope.launch(Dispatchers.Main) {
                        downloadProgress = (progress / 100f).coerceIn(0f, 1f)
                        downloadStatus =
                            "Wird heruntergeladen: ${progress.toInt()} % · noch etwa ${eta}s"
                    }
                }
                val files = downloadDir.walkTopDown()
                    .filter { it.isFile && it.extension.equals("mp3", true) }
                    .filter { it.absolutePath !in before }
                    .toList()
                files.forEach(::addFile)
                withContext(Dispatchers.Main) {
                    downloadProgress = 1f
                    downloadStatus =
                        if (files.isEmpty()) "Keine neue MP3 gefunden oder bereits vorhanden."
                        else "${files.size} MP3-Datei(en) fertig."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    downloadStatus = "Download fehlgeschlagen: ${e.message ?: "Unbekannter Fehler"}"
                }
            } finally {
                withContext(Dispatchers.Main) { isDownloading = false }
            }
        }
    }

    fun play(track: AudioTrack) {
        currentTrack = track
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(track.path))))
        player.prepare()
        player.play()
    }

    fun togglePlayback() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() {
        val current = currentTrack ?: return
        val index = tracks.indexOfFirst { it.id == current.id }
        if (tracks.isNotEmpty()) play(tracks[(index + 1).mod(tracks.size)])
    }

    fun previous() {
        val current = currentTrack ?: return
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

    private fun addFile(file: File) {
        if (!file.exists()) return
        val metadata = metadata(file)
        val track = AudioTrack(
            id = UUID.randomUUID().toString(),
            title = metadata.first.ifBlank { file.nameWithoutExtension },
            artist = metadata.second.ifBlank { "Unbekannter Interpret" },
            path = file.absolutePath
        )
        viewModelScope.launch(Dispatchers.Main) {
            if (tracks.none { it.path == file.absolutePath }) {
                tracks.add(track)
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
                .forEach(::addFile)
        }
    }

    private fun update(id: String, transform: (AudioTrack) -> AudioTrack) {
        val index = tracks.indexOfFirst { it.id == id }
        if (index >= 0) {
            val updated = transform(tracks[index])
            tracks[index] = updated
            if (currentTrack?.id == id) currentTrack = updated
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
            .apply()
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}

enum class LibraryMode { ALL, FAVORITES, PLAYLIST }
