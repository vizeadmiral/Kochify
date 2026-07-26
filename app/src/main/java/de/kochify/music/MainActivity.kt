package de.kochify.music

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SpotifyGreen = Color(0xFF1ED760)
private val AppBackground = Color(0xFF0A0A0A)
private val CardBackground = Color(0xFF181818)

class MainActivity : ComponentActivity() {
    private val musicViewModel by viewModels<MusicViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        musicViewModel.handleSpotifyCallback(intent?.data)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = SpotifyGreen,
                    background = AppBackground,
                    surface = CardBackground
                )
            ) {
                MusicApp(musicViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        musicViewModel.handleSpotifyCallback(intent.data)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicApp(vm: MusicViewModel) {
    var mode by remember { mutableStateOf(LibraryMode.ALL) }
    var showDownload by remember { mutableStateOf(false) }
    var showSpotifyImport by remember { mutableStateOf(false) }
    var showNewPlaylist by remember { mutableStateOf(false) }
    var editingTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var playlistTrack by remember { mutableStateOf<AudioTrack?>(null) }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> vm.importAudio(uris) }

    Scaffold(
        containerColor = AppBackground,
        bottomBar = {
            Column {
                vm.currentTrack?.let { MiniPlayer(vm, it) }
                NavigationBar(containerColor = Color(0xFF111111)) {
                    NavigationBarItem(
                        selected = mode == LibraryMode.ALL,
                        onClick = { mode = LibraryMode.ALL },
                        icon = { Icon(Icons.Default.LibraryMusic, null) },
                        label = { Text("Bibliothek") }
                    )
                    NavigationBarItem(
                        selected = mode == LibraryMode.FAVORITES,
                        onClick = { mode = LibraryMode.FAVORITES },
                        icon = { Icon(Icons.Default.Favorite, null) },
                        label = { Text("Favoriten") }
                    )
                    NavigationBarItem(
                        selected = mode == LibraryMode.PLAYLIST,
                        onClick = {
                            mode = LibraryMode.PLAYLIST
                            vm.selectedPlaylist = null
                        },
                        icon = { Icon(Icons.Default.QueueMusic, null) },
                        label = { Text("Playlists") }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(AppBackground)
        ) {
            Header(
                mode = mode,
                playlistName = vm.selectedPlaylist,
                onBack = { vm.selectedPlaylist = null },
                onImport = { audioPicker.launch("audio/*") },
                onDownload = { showDownload = true },
                onSpotifyImport = { showSpotifyImport = true },
                onNewPlaylist = { showNewPlaylist = true }
            )
            SearchField(vm)

            if (mode == LibraryMode.PLAYLIST && vm.selectedPlaylist == null) {
                PlaylistList(
                    playlists = vm.playlists,
                    onSelect = { vm.selectedPlaylist = it },
                    onCreate = { showNewPlaylist = true },
                    spotifyUrlFor = vm::spotifyPlaylistUrl,
                    onOpenSpotify = vm::openSpotifyPlaylist
                )
            } else {
                TrackList(
                    tracks = vm.visibleTracks(mode),
                    vm = vm,
                    onEdit = { editingTrack = it },
                    onPlaylist = { playlistTrack = it }
                )
            }
        }
    }

    if (showDownload) {
        DownloadDialog(
            downloading = vm.isDownloading,
            progress = vm.downloadProgress,
            status = vm.downloadStatus,
            onDismiss = { if (!vm.isDownloading) showDownload = false },
            onDownload = vm::downloadFromYoutube
        )
    }
    if (showSpotifyImport) {
        SpotifyImportDialog(
            initialClientId = vm.spotifyClientId,
            importing = vm.isSpotifyImporting,
            status = vm.spotifyStatus,
            onDismiss = { if (!vm.isSpotifyImporting) showSpotifyImport = false },
            onConnect = vm::startSpotifyImport
        )
    }
    if (showNewPlaylist) {
        TextInputDialog(
            title = "Neue Playlist",
            label = "Name",
            onDismiss = { showNewPlaylist = false },
            onConfirm = {
                vm.createPlaylist(it)
                showNewPlaylist = false
            }
        )
    }
    editingTrack?.let { selected ->
        val track = vm.tracks.firstOrNull { it.id == selected.id } ?: selected
        EditTrackDialog(
            track = track,
            vm = vm,
            onDismiss = { editingTrack = null }
        )
    }
    playlistTrack?.let { selected ->
        val track = vm.tracks.firstOrNull { it.id == selected.id } ?: selected
        PlaylistAssignmentDialog(
            track = track,
            playlists = vm.playlists,
            onToggle = { vm.togglePlaylist(track.id, it) },
            onDismiss = { playlistTrack = null }
        )
    }
}

@Composable
private fun Header(
    mode: LibraryMode,
    playlistName: String?,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onDownload: () -> Unit,
    onSpotifyImport: () -> Unit,
    onNewPlaylist: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 18.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (playlistName != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Zurück")
            }
        }
        Text(
            text = playlistName ?: when (mode) {
                LibraryMode.ALL -> "Kochify"
                LibraryMode.FAVORITES -> "Deine Favoriten"
                LibraryMode.PLAYLIST -> "Deine Playlists"
            },
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onImport) {
            Icon(Icons.Default.Add, "MP3 importieren")
        }
        IconButton(onClick = onDownload) {
            Icon(Icons.Default.Download, "Link herunterladen", tint = SpotifyGreen)
        }
        IconButton(onClick = onSpotifyImport) {
            Icon(Icons.Default.Sync, "Spotify-Playlists importieren", tint = SpotifyGreen)
        }
        IconButton(onClick = onNewPlaylist) {
            Icon(Icons.Default.PlaylistAdd, "Playlist erstellen")
        }
    }
}

@Composable
private fun SearchField(vm: MusicViewModel) {
    OutlinedTextField(
        value = vm.search,
        onValueChange = { vm.search = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        leadingIcon = { Icon(Icons.Default.Search, null) },
        placeholder = { Text("Titel oder Interpret suchen") },
        singleLine = true,
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun TrackList(
    tracks: List<AudioTrack>,
    vm: MusicViewModel,
    onEdit: (AudioTrack) -> Unit,
    onPlaylist: (AudioTrack) -> Unit
) {
    if (tracks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(52.dp), tint = Color.Gray)
                Spacer(Modifier.height(12.dp))
                Text("Noch keine passenden Titel", color = Color.Gray)
            }
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
    ) {
        items(tracks, key = { it.id }) { track ->
            TrackRow(
                track = track,
                isCurrent = vm.currentTrack?.id == track.id,
                onPlay = { vm.play(track) },
                onFavorite = { vm.toggleFavorite(track.id) },
                onEdit = { onEdit(track) },
                onPlaylist = { onPlaylist(track) },
                onDelete = { vm.deleteTrack(track) }
            )
        }
    }
}

@Composable
private fun TrackRow(
    track: AudioTrack,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onPlaylist: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(track.coverPath, 56)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) SpotifyGreen else Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                track.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.Gray,
                fontSize = 13.sp
            )
        }
        IconButton(onClick = onFavorite) {
            Icon(
                if (track.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                "Favorit",
                tint = if (track.favorite) SpotifyGreen else Color.LightGray
            )
        }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Default.MoreVert, "Mehr")
            }
            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Titel und Cover bearbeiten") },
                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                    onClick = { expanded = false; onEdit() }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Zu Playlist hinzufügen") },
                    leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) },
                    onClick = { expanded = false; onPlaylist() }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Aus der App löschen") },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    onClick = { expanded = false; onDelete() }
                )
            }
        }
    }
}

@Composable
private fun PlaylistList(
    playlists: List<String>,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    spotifyUrlFor: (String) -> String?,
    onOpenSpotify: (String) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardBackground)
                    .clickable(onClick = onCreate)
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, null, tint = SpotifyGreen)
                Spacer(Modifier.width(14.dp))
                Text("Neue Playlist erstellen", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
        }
        items(playlists) { playlist ->
            val spotifyUrl = spotifyUrlFor(playlist)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(playlist) }
                    .padding(vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(58.dp)
                        .background(Color(0xFF242424), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.QueueMusic, null, tint = SpotifyGreen)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(playlist, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    if (spotifyUrl != null) {
                        Text(
                            "Von Spotify importiert",
                            color = SpotifyGreen,
                            fontSize = 12.sp
                        )
                    }
                }
                if (spotifyUrl != null) {
                    IconButton(onClick = { onOpenSpotify(playlist) }) {
                        Icon(
                            Icons.Default.OpenInNew,
                            "Original in Spotify öffnen",
                            tint = SpotifyGreen
                        )
                    }
                }
            }
            HorizontalDivider(color = Color(0xFF222222))
        }
    }
}

@Composable
private fun MiniPlayer(vm: MusicViewModel, track: AudioTrack) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF252525))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(track.coverPath, 42)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    track.artist,
                    maxLines = 1,
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = vm::toggleShuffle) {
                Icon(
                    Icons.Default.Shuffle,
                    if (vm.shuffleEnabled) "Shuffle ausschalten" else "Shuffle einschalten",
                    tint = if (vm.shuffleEnabled) SpotifyGreen else Color.LightGray
                )
            }
            IconButton(onClick = vm::previous) {
                Icon(Icons.Default.NavigateBefore, "Zurück")
            }
            FilledIconButton(
                onClick = vm::togglePlayback,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = SpotifyGreen)
            ) {
                Icon(
                    if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (vm.isPlaying) "Pause" else "Abspielen",
                    tint = Color.Black
                )
            }
            IconButton(onClick = vm::next) {
                Icon(Icons.Default.NavigateNext, "Weiter")
            }
            IconButton(onClick = vm::toggleRepeatOne) {
                Icon(
                    Icons.Default.RepeatOne,
                    if (vm.repeatOneEnabled) {
                        "Endloswiederholung ausschalten"
                    } else {
                        "Titel endlos wiederholen"
                    },
                    tint = if (vm.repeatOneEnabled) SpotifyGreen else Color.LightGray
                )
            }
        }
    }
}

@Composable
private fun DownloadDialog(
    downloading: Boolean,
    progress: Float,
    status: String?,
    onDismiss: () -> Unit,
    onDownload: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    val looksLikePlaylist = url.contains("list=", ignoreCase = true) ||
        url.contains("/playlist", ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("YouTube als MP3 herunterladen") },
        text = {
            Column {
                Text(
                    "Einzelne Videos oder vollständige Playlists. Kochify erstellt für eine " +
                        "YouTube-Playlist automatisch eine gleichnamige Playlist.",
                    color = Color.LightGray
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Nur für eigene Inhalte oder Medien verwenden, für deren Download und " +
                        "Umwandlung du die ausdrückliche Erlaubnis besitzt.",
                    color = Color.LightGray
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("YouTube-Link") },
                    enabled = !downloading,
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = confirmed,
                        onCheckedChange = { confirmed = it },
                        enabled = !downloading
                    )
                    Text("Ich besitze die nötigen Rechte.")
                }
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                    Text(
                        "Gesamtfortschritt: ${(progress * 100).toInt()} %",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                }
                status?.let {
                    Text(it, color = Color.LightGray, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDownload(url) },
                enabled = confirmed && url.isNotBlank() && !downloading
            ) {
                if (downloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        if (looksLikePlaylist) {
                            "Playlist vollständig laden"
                        } else {
                            "Als MP3 laden"
                        }
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !downloading) { Text("Schließen") }
        }
    )
}

@Composable
private fun SpotifyImportDialog(
    initialClientId: String,
    importing: Boolean,
    status: String?,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit
) {
    var clientId by remember(initialClientId) { mutableStateOf(initialClientId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Spotify-Playlists übertragen") },
        text = {
            Column {
                Text(
                    "Kochify übernimmt Playlistnamen, Titel und Interpreten. " +
                        "Vorhandene MP3s werden zugeordnet; fehlende Titel werden für später vorgemerkt.",
                    color = Color.LightGray
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Spotify-Audiodateien werden nicht kopiert oder heruntergeladen.",
                    color = SpotifyGreen,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text("Spotify Client-ID") },
                    supportingText = {
                        Text("Redirect-URI im Spotify Dashboard: kochify://spotify-callback")
                    },
                    enabled = !importing,
                    singleLine = true
                )
                if (importing) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                    )
                }
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color.LightGray, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(clientId) },
                enabled = clientId.isNotBlank() && !importing
            ) {
                if (importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Spotify verbinden")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !importing) {
                Text("Schließen")
            }
        }
    )
}

@Composable
private fun EditTrackDialog(
    track: AudioTrack,
    vm: MusicViewModel,
    onDismiss: () -> Unit
) {
    var title by remember(track.id) { mutableStateOf(track.title) }
    var artist by remember(track.id) { mutableStateOf(track.artist) }
    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.setCover(track.id, it) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Titel bearbeiten") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Cover(track.coverPath, 110)
                TextButton(onClick = { coverPicker.launch("image/*") }) {
                    Text("Anderes Cover auswählen")
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titel") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Interpret") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                vm.updateMetadata(track.id, title, artist)
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun PlaylistAssignmentDialog(
    track: AudioTrack,
    playlists: List<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playlists") },
        text = {
            if (playlists.isEmpty()) {
                Text("Erstelle zuerst eine Playlist.")
            } else {
                Column {
                    playlists.forEach { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(playlist) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = playlist in track.playlists,
                                onCheckedChange = { onToggle(playlist) }
                            )
                            Text(playlist)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Fertig") } }
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text("Erstellen")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun Cover(path: String?, size: Int) {
    val bitmap = remember(path) {
        path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF303030)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = "Cover",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Default.MusicNote,
                null,
                modifier = Modifier.size((size / 2).dp),
                tint = Color.LightGray
            )
        }
    }
}
