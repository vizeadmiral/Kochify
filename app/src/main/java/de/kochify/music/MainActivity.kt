package de.kochify.music

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val KochifyGreen = Color(0xFF1ED760)
private val DarkBackground = Color(0xFF0A0A0A)
private val DarkSurface = Color(0xFF181818)

class MainActivity : ComponentActivity() {
    private val musicViewModel by viewModels<MusicViewModel>()
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPlaybackNotificationPermission()
        musicViewModel.handleSpotifyCallback(intent?.data)
        setContent {
            KochifyTheme(musicViewModel.themeMode) {
                MusicApp(musicViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        musicViewModel.handleSpotifyCallback(intent.data)
    }

    private fun requestPlaybackNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val prefs = getSharedPreferences("kochify_permissions", MODE_PRIVATE)
        if (!prefs.getBoolean("notification_permission_asked", false)) {
            prefs.edit().putBoolean("notification_permission_asked", true).apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun KochifyTheme(
    mode: KochifyThemeMode,
    content: @Composable () -> Unit
) {
    val rgbAccent = if (mode == KochifyThemeMode.RGB) {
        val rgbTransition = rememberInfiniteTransition(label = "RGB-Farben")
        val hue by rgbTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 10_000,
                    easing = LinearEasing
                )
            ),
            label = "RGB-Akzent"
        )
        Color.hsv(hue, saturation = 0.78f, value = 1f)
    } else {
        KochifyGreen
    }
    val colorScheme = when (mode) {
        KochifyThemeMode.BLACK -> darkColorScheme(
            primary = KochifyGreen,
            onPrimary = Color.Black,
            background = DarkBackground,
            onBackground = Color.White,
            surface = DarkSurface,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF252525),
            onSurfaceVariant = Color(0xFFCCCCCC)
        )
        KochifyThemeMode.LIGHT -> lightColorScheme(
            primary = Color(0xFF087D3E),
            onPrimary = Color.White,
            background = Color(0xFFF4F5F7),
            onBackground = Color(0xFF151515),
            surface = Color.White,
            onSurface = Color(0xFF151515),
            surfaceVariant = Color(0xFFE5E8EB),
            onSurfaceVariant = Color(0xFF50545A)
        )
        KochifyThemeMode.RGB -> darkColorScheme(
            primary = rgbAccent,
            onPrimary = if (rgbAccent.luminance() > 0.42f) Color.Black else Color.White,
            secondary = Color(0xFFE040FB),
            tertiary = Color(0xFFFFEA00),
            background = Color(0xFF090912),
            onBackground = Color.White,
            surface = Color(0xFF151525),
            onSurface = Color.White,
            surfaceVariant = Color(0xFF24243B),
            onSurfaceVariant = Color(0xFFD6D2E4)
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicApp(vm: MusicViewModel) {
    var mode by remember { mutableStateOf(LibraryMode.ALL) }
    var showDownload by remember { mutableStateOf(false) }
    var showSpotifyImport by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var backupIncludesMusic by remember { mutableStateOf(true) }
    var showNewPlaylist by remember { mutableStateOf(false) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var editingTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var playlistTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var renamingPlaylist by remember { mutableStateOf<String?>(null) }
    var deletingPlaylist by remember { mutableStateOf<String?>(null) }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> vm.importAudio(uris) }
    val backupExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { vm.exportKochifyBackup(it, backupIncludesMusic) }
    }
    val backupImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(vm::importKochifyBackup)
    }

    val activeTrack = vm.currentTrack
    val hasOpenDialog = showDownload || showSpotifyImport || showThemePicker ||
        showBackup || showNewPlaylist || editingTrack != null || playlistTrack != null ||
        renamingPlaylist != null || deletingPlaylist != null
    BackHandler(
        enabled = !hasOpenDialog &&
            (showNowPlaying || vm.selectedPlaylist != null || mode != LibraryMode.ALL)
    ) {
        when {
            showNowPlaying -> showNowPlaying = false
            vm.selectedPlaylist != null -> vm.selectedPlaylist = null
            mode != LibraryMode.ALL -> {
                mode = LibraryMode.ALL
                vm.selectedPlaylist = null
            }
        }
    }

    if (showNowPlaying && activeTrack != null) {
        NowPlayingScreen(
            vm = vm,
            track = activeTrack,
            sourceName = vm.selectedPlaylist
                ?: activeTrack.playlists.firstOrNull()
                ?: "Kochify",
            onClose = { showNowPlaying = false },
            onEdit = { editingTrack = activeTrack },
            onPlaylist = { playlistTrack = activeTrack },
            onDelete = {
                vm.deleteTrack(activeTrack)
                showNowPlaying = false
            }
        )
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    vm.currentTrack?.let {
                        MiniPlayer(
                            vm = vm,
                            track = it,
                            onOpen = { showNowPlaying = true }
                        )
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NavigationBarItem(
                            selected = mode == LibraryMode.ALL,
                            onClick = {
                                mode = LibraryMode.ALL
                                vm.selectedPlaylist = null
                            },
                            icon = { Icon(Icons.Default.LibraryMusic, null) },
                            label = { Text("Bibliothek") }
                        )
                        NavigationBarItem(
                            selected = mode == LibraryMode.FAVORITES,
                            onClick = {
                                mode = LibraryMode.FAVORITES
                                vm.selectedPlaylist = null
                            },
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
                        NavigationBarItem(
                            selected = false,
                            onClick = { showThemePicker = true },
                            icon = { Icon(Icons.Default.Palette, null) },
                            label = { Text("Design") }
                        )
                        NavigationBarItem(
                            selected = false,
                            onClick = { showBackup = true },
                            icon = { Icon(Icons.Default.SettingsBackupRestore, null) },
                            label = { Text("Sicherung") }
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
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
                        onOpenSpotify = vm::openSpotifyPlaylist,
                        onRename = { renamingPlaylist = it },
                        onDelete = { deletingPlaylist = it }
                    )
                } else {
                    TrackList(
                        tracks = vm.visibleTracks(mode),
                        vm = vm,
                        onOpenPlayer = { showNowPlaying = true },
                        onEdit = { editingTrack = it },
                        onPlaylist = { playlistTrack = it }
                    )
                }
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
    if (showThemePicker) {
        ThemePickerDialog(
            selected = vm.themeMode,
            onSelect = vm::selectThemeMode,
            onDismiss = { showThemePicker = false }
        )
    }
    if (showBackup) {
        BackupDialog(
            busy = vm.isBackupBusy,
            status = vm.backupStatus,
            onExportComplete = {
                backupIncludesMusic = true
                backupExporter.launch("Kochify-Komplettsicherung.kochify")
            },
            onExportQuick = {
                backupIncludesMusic = false
                backupExporter.launch("Kochify-Sicherung.kochify")
            },
            onImport = {
                backupImporter.launch(
                    arrayOf(
                        "application/zip",
                        "application/octet-stream",
                        "application/x-zip-compressed",
                        "*/*"
                    )
                )
            },
            onDismiss = { if (!vm.isBackupBusy) showBackup = false }
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
    renamingPlaylist?.let { playlist ->
        TextInputDialog(
            title = "Playlist umbenennen",
            label = "Neuer Name",
            initialText = playlist,
            confirmText = "Umbenennen",
            onDismiss = { renamingPlaylist = null },
            onConfirm = { newName ->
                vm.renamePlaylist(playlist, newName)
                renamingPlaylist = null
            }
        )
    }
    deletingPlaylist?.let { playlist ->
        DeletePlaylistDialog(
            playlist = playlist,
            onDismiss = { deletingPlaylist = null },
            onConfirm = {
                vm.deletePlaylist(playlist)
                deletingPlaylist = null
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlistName ?: when (mode) {
                    LibraryMode.ALL -> "Kochify"
                    LibraryMode.FAVORITES -> "Deine Favoriten"
                    LibraryMode.PLAYLIST -> "Deine Playlists"
                },
                fontSize = 24.sp,
                fontWeight = FontWeight.Black
            )
            if (playlistName == null && mode == LibraryMode.ALL) {
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onImport) {
            Icon(Icons.Default.Add, "MP3 importieren")
        }
        IconButton(onClick = onDownload) {
            Icon(
                Icons.Default.Download,
                "Link herunterladen",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onSpotifyImport) {
            Icon(
                Icons.Default.Sync,
                "Spotify-Playlists importieren",
                tint = MaterialTheme.colorScheme.primary
            )
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
    onOpenPlayer: () -> Unit,
    onEdit: (AudioTrack) -> Unit,
    onPlaylist: (AudioTrack) -> Unit
) {
    if (tracks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.MusicNote,
                    null,
                    modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Noch keine passenden Titel",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                onPlay = {
                    vm.play(track, tracks)
                    onOpenPlayer()
                },
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
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
                fontWeight = FontWeight.SemiBold
            )
            Text(
                track.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
        IconButton(onClick = onFavorite) {
            Icon(
                if (track.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                "Favorit",
                tint = if (track.favorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
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
    onOpenSpotify: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onCreate)
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Text("Neue Playlist erstellen", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
        }
        items(playlists) { playlist ->
            val spotifyUrl = spotifyUrlFor(playlist)
            var expanded by remember(playlist) { mutableStateOf(false) }
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
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.QueueMusic,
                        null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(playlist, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    if (spotifyUrl != null) {
                        Text(
                            "Von Spotify importiert",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp
                        )
                    }
                }
                if (spotifyUrl != null) {
                    IconButton(onClick = { onOpenSpotify(playlist) }) {
                        Icon(
                            Icons.Default.OpenInNew,
                            "Original in Spotify öffnen",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.MoreVert, "Playlist-Optionen")
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Playlist umbenennen") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = {
                                expanded = false
                                onRename(playlist)
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Playlist löschen") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = {
                                expanded = false
                                onDelete(playlist)
                            }
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun MiniPlayer(
    vm: MusicViewModel,
    track: AudioTrack,
    onOpen: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Cover(track.coverPath, 44)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = vm::togglePlayback) {
                Icon(
                    if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (vm.isPlaying) "Pause" else "Abspielen"
                )
            }
            IconButton(onClick = vm::next) {
                Icon(Icons.Default.SkipNext, "Weiter")
            }
        }
        val progress = if (vm.playbackDurationMs > 0L) {
            (vm.playbackPositionMs.toFloat() / vm.playbackDurationMs)
                .coerceIn(0f, 1f)
        } else {
            0f
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun NowPlayingScreen(
    vm: MusicViewModel,
    track: AudioTrack,
    sourceName: String,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onPlaylist: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember(track.id) { mutableStateOf(false) }
    var draggedPosition by remember(track.id) { mutableStateOf<Float?>(null) }
    val duration = vm.playbackDurationMs.coerceAtLeast(1L).toFloat()
    val shownPosition = (draggedPosition ?: vm.playbackPositionMs.toFloat())
        .coerceIn(0f, duration)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.KeyboardArrowDown, "Player schließen")
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "WIEDERGABE AUS",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            sourceName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                    Box {
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Default.MoreVert, "Songoptionen")
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(
                                        if (track.favorite) {
                                            "Aus Favoriten entfernen"
                                        } else {
                                            "Zu Favoriten hinzufügen"
                                        }
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (track.favorite) {
                                            Icons.Default.Favorite
                                        } else {
                                            Icons.Default.FavoriteBorder
                                        },
                                        null
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    vm.toggleFavorite(track.id)
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Zu Playlist hinzufügen") },
                                leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) },
                                onClick = {
                                    expanded = false
                                    onPlaylist()
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Titel und Cover bearbeiten") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = {
                                    expanded = false
                                    onEdit()
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Aus der App löschen") },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                onClick = {
                                    expanded = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
                NowPlayingCover(track)
                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            track.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            track.artist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp
                        )
                    }
                    IconButton(onClick = { vm.toggleFavorite(track.id) }) {
                        Icon(
                            if (track.favorite) {
                                Icons.Default.Favorite
                            } else {
                                Icons.Default.FavoriteBorder
                            },
                            if (track.favorite) {
                                "Aus Favoriten entfernen"
                            } else {
                                "Zu Favoriten hinzufügen"
                            },
                            tint = if (track.favorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Slider(
                    value = shownPosition,
                    onValueChange = { draggedPosition = it },
                    onValueChangeFinished = {
                        draggedPosition?.let { vm.seekTo(it.toLong()) }
                        draggedPosition = null
                    },
                    valueRange = 0f..duration,
                    enabled = vm.playbackDurationMs > 0L
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        formatPlaybackTime(shownPosition.toLong()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatPlaybackTime(vm.playbackDurationMs),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = vm::toggleShuffle) {
                        Icon(
                            Icons.Default.Shuffle,
                            if (vm.shuffleEnabled) {
                                "Shuffle ausschalten"
                            } else {
                                "Shuffle einschalten"
                            },
                            tint = if (vm.shuffleEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    IconButton(
                        onClick = vm::previous,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            "Vorheriger Titel",
                            modifier = Modifier.size(38.dp)
                        )
                    }
                    FilledIconButton(
                        onClick = vm::togglePlayback,
                        modifier = Modifier.size(76.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Icon(
                            if (vm.isPlaying) {
                                Icons.Default.Pause
                            } else {
                                Icons.Default.PlayArrow
                            },
                            if (vm.isPlaying) "Pause" else "Abspielen",
                            modifier = Modifier.size(42.dp)
                        )
                    }
                    IconButton(
                        onClick = vm::next,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            "Nächster Titel",
                            modifier = Modifier.size(38.dp)
                        )
                    }
                    IconButton(onClick = vm::toggleRepeatOne) {
                        Icon(
                            Icons.Default.RepeatOne,
                            if (vm.repeatOneEnabled) {
                                "Endloswiederholung ausschalten"
                            } else {
                                "Titel endlos wiederholen"
                            },
                            tint = if (vm.repeatOneEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(onClick = onPlaylist) {
                        Icon(Icons.Default.PlaylistAdd, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Playlist")
                    }
                    TextButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Bearbeiten")
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun NowPlayingCover(track: AudioTrack) {
    val bitmap = remember(track.coverPath) {
        track.coverPath?.let {
            runCatching { BitmapFactory.decodeFile(it) }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = "Cover von ${track.title}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Default.MusicNote,
                null,
                modifier = Modifier.size(128.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun BackupDialog(
    busy: Boolean,
    status: String?,
    onExportComplete: () -> Unit,
    onExportQuick: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sicherung & Wiederherstellung") },
        text = {
            Column {
                Text(
                    "Speichere deine Playlists, Favoriten, Cover und Einstellungen direkt " +
                        "auf dem Handy. Die Komplettsicherung enthält zusätzlich alle " +
                        "Musikdateien."
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onExportComplete,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Komplettsicherung exportieren")
                }
                TextButton(
                    onClick = onExportQuick,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Schnellsicherung ohne Musik")
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Button(
                    onClick = onImport,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Kochify-Sicherung importieren")
                }
                if (busy) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                    )
                }
                status?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Schließen")
            }
        }
    )
}

@Composable
private fun ThemePickerDialog(
    selected: KochifyThemeMode,
    onSelect: (KochifyThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Design auswählen") },
        text = {
            Column {
                ThemeOption(
                    mode = KochifyThemeMode.BLACK,
                    selected = selected == KochifyThemeMode.BLACK,
                    title = "Schwarz",
                    description = "Klassischer Kochify Dark Mode",
                    swatch = KochifyGreen,
                    onSelect = onSelect
                )
                ThemeOption(
                    mode = KochifyThemeMode.LIGHT,
                    selected = selected == KochifyThemeMode.LIGHT,
                    title = "Hell",
                    description = "Helle Flächen und dunkle Schrift",
                    swatch = Color(0xFF087D3E),
                    onSelect = onSelect
                )
                ThemeOption(
                    mode = KochifyThemeMode.RGB,
                    selected = selected == KochifyThemeMode.RGB,
                    title = "RGB",
                    description = "Langsam wechselnde Farbakzente",
                    swatch = Color(0xFFE040FB),
                    onSelect = onSelect
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Fertig")
            }
        }
    )
}

@Composable
private fun ThemeOption(
    mode: KochifyThemeMode,
    selected: Boolean,
    title: String,
    description: String,
    swatch: Color,
    onSelect: (KochifyThemeMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelect(mode) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = { onSelect(mode) }
        )
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(swatch, CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Nur für eigene Inhalte oder Medien verwenden, für deren Download und " +
                        "Umwandlung du die ausdrückliche Erlaubnis besitzt.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                status?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Spotify-Audiodateien werden nicht kopiert oder heruntergeladen.",
                    color = MaterialTheme.colorScheme.primary,
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
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
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
    initialText: String = "",
    confirmText: String = "Erstellen",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
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
                Text(confirmText)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun DeletePlaylistDialog(
    playlist: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playlist löschen?") },
        text = {
            Text(
                "„$playlist“ wird gelöscht. Die enthaltenen Songs bleiben in " +
                    "deiner Kochify-Bibliothek erhalten."
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Playlist löschen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
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
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
