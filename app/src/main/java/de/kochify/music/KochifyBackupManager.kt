package de.kochify.music

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class BackupTrack(
    val title: String,
    val artist: String,
    val favorite: Boolean,
    val playlists: Set<String>,
    val audioPath: String?,
    val coverPath: String?
)

internal data class KochifyBackupImport(
    val playlists: List<String>,
    val tracks: List<BackupTrack>,
    val themeMode: KochifyThemeMode,
    val shuffleEnabled: Boolean,
    val repeatOneEnabled: Boolean,
    val restoredAudioFiles: Int,
    val includeSettings: Boolean
)

internal object KochifyBackupManager {
    private const val FORMAT = "kochify-backup"
    private const val BACKUP_VERSION = 1
    private const val MANIFEST_ENTRY = "backup.json"

    fun export(
        context: Context,
        uri: Uri,
        tracks: List<AudioTrack>,
        playlists: List<String>,
        themeMode: KochifyThemeMode,
        shuffleEnabled: Boolean,
        repeatOneEnabled: Boolean,
        includeMusic: Boolean,
        includeSettings: Boolean = true
    ): Int {
        val preparedTracks = tracks.map { track ->
            val audioFile = File(track.path).takeIf { includeMusic && it.isFile }
            val coverFile = track.coverPath
                ?.let(::File)
                ?.takeIf { it.isFile }
            val audioEntry = audioFile?.let {
                "music/${track.id}.${safeExtension(it, "mp3")}"
            }
            val coverEntry = coverFile?.let {
                "covers/${track.id}.${safeExtension(it, "jpg")}"
            }
            Triple(track, audioEntry to audioFile, coverEntry to coverFile)
        }

        val manifest = JSONObject().apply {
            put("format", FORMAT)
            put("version", BACKUP_VERSION)
            put("createdAt", System.currentTimeMillis())
            put("includeMusic", includeMusic)
            put("includeSettings", includeSettings)
            put("themeMode", themeMode.name)
            put("shuffleEnabled", shuffleEnabled)
            put("repeatOneEnabled", repeatOneEnabled)
            put("playlists", JSONArray(playlists))
            put("tracks", JSONArray().apply {
                preparedTracks.forEach { (track, audio, cover) ->
                    put(JSONObject().apply {
                        put("title", track.title)
                        put("artist", track.artist)
                        put("favorite", track.favorite)
                        put("playlists", JSONArray(track.playlists.toList()))
                        put("audioEntry", audio.first ?: "")
                        put("coverEntry", cover.first ?: "")
                    })
                }
            })
        }

        val output = context.contentResolver.openOutputStream(uri, "w")
            ?: error("Die Sicherungsdatei konnte nicht geöffnet werden.")
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            preparedTracks.forEach { (_, audio, cover) ->
                audio.first?.let { name ->
                    audio.second?.let { file -> addFile(zip, name, file) }
                }
                cover.first?.let { name ->
                    cover.second?.let { file -> addFile(zip, name, file) }
                }
            }
        }
        return preparedTracks.count { it.second.second != null }
    }

    fun import(context: Context, uri: Uri): KochifyBackupImport {
        val tempRoot = File(context.cacheDir, "kochify-import-${UUID.randomUUID()}")
            .apply { mkdirs() }
        try {
            var manifestText: String? = null
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Die Sicherungsdatei konnte nicht geöffnet werden.")
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val cleanName = safeEntryName(entry.name)
                    if (!entry.isDirectory && cleanName != null) {
                        if (cleanName == MANIFEST_ENTRY) {
                            val bytes = zip.readBytes()
                            if (bytes.size > 5 * 1024 * 1024) {
                                error("Die Sicherungsinformationen sind ungültig.")
                            }
                            manifestText = bytes.toString(Charsets.UTF_8)
                        } else if (cleanName.startsWith("music/") ||
                            cleanName.startsWith("covers/")
                        ) {
                            val target = safeTarget(tempRoot, cleanName)
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                    }
                    zip.closeEntry()
                }
            }

            val root = JSONObject(
                manifestText ?: error("Keine Kochify-Sicherungsinformationen gefunden.")
            )
            if (root.optString("format") != FORMAT ||
                root.optInt("version", -1) !in 1..BACKUP_VERSION
            ) {
                error("Diese Datei ist keine unterstützte Kochify-Sicherung.")
            }

            val restoredMusicDir = File(context.filesDir, "music").apply { mkdirs() }
            val restoredCoversDir = File(context.filesDir, "covers").apply { mkdirs() }
            var restoredAudioFiles = 0
            val restoredTracks = mutableListOf<BackupTrack>()
            val trackArray = root.optJSONArray("tracks") ?: JSONArray()
            repeat(trackArray.length()) { index ->
                val item = trackArray.optJSONObject(index) ?: return@repeat
                val audioEntry = safeEntryName(item.optString("audioEntry"))
                val coverEntry = safeEntryName(item.optString("coverEntry"))
                val audioSource = audioEntry
                    ?.takeIf { it.startsWith("music/") }
                    ?.let { safeTarget(tempRoot, it) }
                    ?.takeIf { it.isFile }
                val coverSource = coverEntry
                    ?.takeIf { it.startsWith("covers/") }
                    ?.let { safeTarget(tempRoot, it) }
                    ?.takeIf { it.isFile }

                val restoredId = UUID.randomUUID().toString()
                val audioTarget = audioSource?.let { source ->
                    val extension = safeExtension(source, "mp3")
                    File(restoredMusicDir, "$restoredId.$extension").also { target ->
                        source.copyTo(target, overwrite = true)
                        restoredAudioFiles++
                    }
                }
                val coverTarget = coverSource?.let { source ->
                    val extension = safeExtension(source, "jpg")
                    File(restoredCoversDir, "$restoredId.$extension").also { target ->
                        source.copyTo(target, overwrite = true)
                    }
                }
                val itemPlaylists = buildSet {
                    val array = item.optJSONArray("playlists") ?: JSONArray()
                    repeat(array.length()) { playlistIndex ->
                        array.optString(playlistIndex)
                            .trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let(::add)
                    }
                }
                restoredTracks += BackupTrack(
                    title = item.optString("title").ifBlank { "Unbekannter Titel" },
                    artist = item.optString("artist").ifBlank {
                        "Unbekannter Interpret"
                    },
                    favorite = item.optBoolean("favorite"),
                    playlists = itemPlaylists,
                    audioPath = audioTarget?.absolutePath,
                    coverPath = coverTarget?.absolutePath
                )
            }

            val restoredPlaylists = buildList {
                val array = root.optJSONArray("playlists") ?: JSONArray()
                repeat(array.length()) { index ->
                    array.optString(index)
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let(::add)
                }
            }
            val restoredTheme = runCatching {
                KochifyThemeMode.valueOf(root.optString("themeMode"))
            }.getOrDefault(KochifyThemeMode.BLACK)
            return KochifyBackupImport(
                playlists = restoredPlaylists,
                tracks = restoredTracks,
                themeMode = restoredTheme,
                shuffleEnabled = root.optBoolean("shuffleEnabled"),
                repeatOneEnabled = root.optBoolean("repeatOneEnabled"),
                restoredAudioFiles = restoredAudioFiles,
                includeSettings = root.optBoolean("includeSettings", true)
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun addFile(zip: ZipOutputStream, entryName: String, file: File) {
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun safeExtension(file: File, fallback: String): String = file.extension
        .lowercase()
        .takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
        ?: fallback

    private fun safeEntryName(value: String): String? {
        val clean = value.replace('\\', '/').trimStart('/')
        if (clean.isBlank() || clean.split('/').any { it == ".." }) return null
        return clean
    }

    private fun safeTarget(root: File, entryName: String): File {
        val target = File(root, entryName).canonicalFile
        val rootPath = root.canonicalFile.path + File.separator
        require(target.path.startsWith(rootPath)) { "Ungültiger Sicherungspfad." }
        return target
    }
}
