package de.kochify.music

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.text.DateFormat
import java.util.Date

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
    val rgbHue = if (mode == KochifyThemeMode.RGB) {
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
        hue
    } else {
        0f
    }
    val rgbAccent = Color.hsv(rgbHue, saturation = 0.82f, value = 1f)
    val rgbSecondary = Color.hsv((rgbHue + 120f) % 360f, 0.82f, 1f)
    val rgbTertiary = Color.hsv((rgbHue + 240f) % 360f, 0.82f, 1f)
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
            primaryContainer = rgbSecondary,
            onPrimaryContainer = if (rgbSecondary.luminance() > 0.42f) {
                Color.Black
            } else {
                Color.White
            },
            secondary = rgbSecondary,
            onSecondary = if (rgbSecondary.luminance() > 0.42f) Color.Black else Color.White,
            secondaryContainer = rgbTertiary,
            onSecondaryContainer = if (rgbTertiary.luminance() > 0.42f) {
                Color.Black
            } else {
                Color.White
            },
            tertiary = rgbTertiary,
            background = Color(0xFF090912),
            onBackground = Color.White,
            surface = Color(0xFF151525),
            onSurface = Color.White,
            surfaceVariant = Color.hsv((rgbHue + 210f) % 360f, 0.38f, 0.28f),
            onSurfaceVariant = Color.White,
            outline = rgbSecondary
        )
        KochifyThemeMode.CYBERPUNK -> darkColorScheme(
            primary = Color(0xFFFCEE09),
            onPrimary = Color(0xFF101014),
            primaryContainer = Color(0xFFFCEE09),
            onPrimaryContainer = Color.Black,
            secondary = Color(0xFF00F0FF),
            onSecondary = Color.Black,
            secondaryContainer = Color(0xFF003B46),
            onSecondaryContainer = Color(0xFF8EFAFF),
            tertiary = Color(0xFFFF2A6D),
            background = Color(0xFF0B0B10),
            onBackground = Color(0xFFF7F7F7),
            surface = Color(0xFF202127),
            onSurface = Color.White,
            surfaceVariant = Color(0xFF30313A),
            onSurfaceVariant = Color(0xFFD8D9DF),
            outline = Color(0xFFFCEE09)
        )
        KochifyThemeMode.GERMANY -> darkColorScheme(
            primary = Color(0xFFDD0000),
            onPrimary = Color.White,
            primaryContainer = Color(0xFF7A0000),
            onPrimaryContainer = Color.White,
            secondary = Color(0xFFFFCE00),
            onSecondary = Color.Black,
            secondaryContainer = Color(0xFFFFCE00),
            onSecondaryContainer = Color.Black,
            tertiary = Color.White,
            background = Color(0xFF070707),
            onBackground = Color.White,
            surface = Color(0xFF171717),
            onSurface = Color.White,
            surfaceVariant = Color(0xFF2A1A1A),
            onSurfaceVariant = Color(0xFFFFE7A6),
            outline = Color(0xFFFFCE00)
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
    var showStats by remember { mutableStateOf(false) }
    var showLocalTransfer by remember { mutableStateOf(false) }
    var showBulkCover by remember { mutableStateOf(false) }
    var showBulkPlaylistAssignment by remember { mutableStateOf(false) }
    var backupIncludesMusic by remember { mutableStateOf(true) }
    var showNewPlaylist by remember { mutableStateOf(false) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var editingTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var playlistTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var renamingPlaylist by remember { mutableStateOf<String?>(null) }
    var deletingPlaylist by remember { mutableStateOf<String?>(null) }
    var coveringPlaylist by remember { mutableStateOf<String?>(null) }
    var editingPlaylistOrder by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val qrScanner = remember {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options)
    }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
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
    val playlistCoverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val playlist = coveringPlaylist
        if (uri != null && playlist != null) vm.setPlaylistCover(playlist, uri)
        coveringPlaylist = null
    }
    val scanTransferQr: () -> Unit = {
        showLocalTransfer = true
        qrScanner.startScan()
            .addOnSuccessListener { barcode ->
                barcode.rawValue?.let(vm::receiveLocalTransfer)
            }
        Unit
    }

    val activeTrack = vm.currentTrack
    val hasOpenDialog = showDownload || showSpotifyImport || showThemePicker ||
        showBackup || showStats || showLocalTransfer || showBulkCover || showNewPlaylist ||
        showBulkPlaylistAssignment ||
        editingTrack != null || playlistTrack != null ||
        renamingPlaylist != null || deletingPlaylist != null ||
        editingPlaylistOrder != null
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
                            label = { BottomNavigationLabel("Bibliothek") }
                        )
                        NavigationBarItem(
                            selected = mode == LibraryMode.FAVORITES,
                            onClick = {
                                mode = LibraryMode.FAVORITES
                                vm.selectedPlaylist = null
                            },
                            icon = { Icon(Icons.Default.Favorite, null) },
                            label = { BottomNavigationLabel("Favoriten") }
                        )
                        NavigationBarItem(
                            selected = mode == LibraryMode.PLAYLIST,
                            onClick = {
                                mode = LibraryMode.PLAYLIST
                                vm.selectedPlaylist = null
                            },
                            icon = { Icon(Icons.Default.QueueMusic, null) },
                            label = { BottomNavigationLabel("Playlists") }
                        )
                        NavigationBarItem(
                            selected = false,
                            onClick = { showThemePicker = true },
                            icon = { Icon(Icons.Default.Palette, null) },
                            label = { BottomNavigationLabel("Design") }
                        )
                        NavigationBarItem(
                            selected = false,
                            onClick = { showBackup = true },
                            icon = { Icon(Icons.Default.SettingsBackupRestore, null) },
                            label = { BottomNavigationLabel("Sicherung") }
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
                    onImport = {
                        audioPicker.launch(arrayOf("audio/mpeg", "audio/*"))
                    },
                    onDownload = { showDownload = true },
                    onSpotifyImport = { showSpotifyImport = true },
                    onStats = { showStats = true },
                    onLocalTransfer = {
                        showLocalTransfer = true
                    },
                    onSharePlaylist = vm::sharePlaylist,
                    onEditPlaylist = { editingPlaylistOrder = it },
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
                        onShare = vm::sharePlaylist,
                        coverFor = vm::playlistCover,
                        onCover = { playlist ->
                            coveringPlaylist = playlist
                            playlistCoverPicker.launch("image/*")
                        },
                        onLocalTransfer = { playlist ->
                            showLocalTransfer = true
                            vm.startPlaylistLocalTransfer(playlist)
                        },
                        onRename = { renamingPlaylist = it },
                        onDelete = { deletingPlaylist = it }
                    )
                } else {
                    TrackList(
                        tracks = vm.visibleTracks(mode),
                        vm = vm,
                        onOpenPlayer = { showNowPlaying = true },
                        onEdit = { editingTrack = it },
                        onPlaylist = { playlistTrack = it },
                        playlistName = vm.selectedPlaylist,
                        queueKey = vm.selectedPlaylist?.let { "playlist:$it" }
                            ?: when (mode) {
                                LibraryMode.ALL -> "library"
                                LibraryMode.FAVORITES -> "favorites"
                                LibraryMode.PLAYLIST -> "playlists"
                            },
                        onBulkCover = { showBulkCover = true },
                        onBulkPlaylist = { showBulkPlaylistAssignment = true },
                        onLocalTransfer = { track ->
                            showLocalTransfer = true
                            vm.startTrackLocalTransfer(track)
                        }
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
    if (showStats) {
        StatsDialog(
            stats = vm.playbackStats(),
            onDismiss = { showStats = false }
        )
    }
    if (showLocalTransfer) {
        LocalTransferDialog(
            vm = vm,
            onScan = scanTransferQr,
            onDismiss = {
                vm.closeLocalTransfer()
                showLocalTransfer = false
            }
        )
    }
    if (showBulkCover) {
        BulkCoverDialog(
            tracks = vm.visibleTracks(mode),
            onApply = { trackIds, coverUri ->
                vm.setCovers(trackIds, coverUri)
                showBulkCover = false
            },
            onDismiss = { showBulkCover = false }
        )
    }
    if (showBulkPlaylistAssignment) {
        BulkPlaylistAssignmentDialog(
            tracks = vm.visibleTracks(mode),
            playlists = vm.playlists,
            onApply = { trackIds, playlistNames ->
                vm.addTracksToPlaylists(trackIds, playlistNames)
                showBulkPlaylistAssignment = false
            },
            onDismiss = { showBulkPlaylistAssignment = false }
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
    editingPlaylistOrder?.let { playlist ->
        PlaylistOrderDialog(
            playlistName = playlist,
            tracks = vm.playlistTracks(playlist),
            onSave = { order ->
                vm.setPlaylistOrder(playlist, order)
                editingPlaylistOrder = null
            },
            onDismiss = { editingPlaylistOrder = null }
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
    onStats: () -> Unit,
    onLocalTransfer: () -> Unit,
    onSharePlaylist: (String) -> Unit,
    onEditPlaylist: (String) -> Unit,
    onNewPlaylist: () -> Unit
) {
    var toolsExpanded by remember { mutableStateOf(false) }
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
        if (playlistName != null) {
            IconButton(onClick = { onEditPlaylist(playlistName) }) {
                Icon(
                    Icons.Default.Edit,
                    "Playlist bearbeiten",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { onSharePlaylist(playlistName) }) {
                Icon(
                    Icons.Default.Share,
                    "Playlist teilen",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (playlistName == null) {
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
            IconButton(onClick = onNewPlaylist) {
                Icon(Icons.Default.PlaylistAdd, "Playlist erstellen")
            }
            Box {
                IconButton(onClick = { toolsExpanded = true }) {
                    Icon(Icons.Default.MoreVert, "Weitere Funktionen")
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = toolsExpanded,
                    onDismissRequest = { toolsExpanded = false }
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Mehrere MP3s vom Handy auswählen") },
                        leadingIcon = { Icon(Icons.Default.LibraryMusic, null) },
                        onClick = {
                            toolsExpanded = false
                            onImport()
                        }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Spotify-Playlists importieren") },
                        leadingIcon = { Icon(Icons.Default.Sync, null) },
                        onClick = {
                            toolsExpanded = false
                            onSpotifyImport()
                        }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Verlauf und Statistiken") },
                        leadingIcon = { Icon(Icons.Default.BarChart, null) },
                        onClick = {
                            toolsExpanded = false
                            onStats()
                        }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Lokale QR-Übertragung") },
                        leadingIcon = { Icon(Icons.Default.QrCodeScanner, null) },
                        onClick = {
                            toolsExpanded = false
                            onLocalTransfer()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomNavigationLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        softWrap = false,
        fontSize = 9.sp,
        overflow = TextOverflow.Clip
    )
}

@Composable
private fun PlaylistOrderDialog(
    playlistName: String,
    tracks: List<AudioTrack>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val orderedTracks = remember(playlistName, tracks.map { it.id }) {
        tracks.toMutableStateList()
    }
    var draggingTrackId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playlist bearbeiten") },
        text = {
            Column {
                Text(
                    playlistName,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Halte die drei Striche gedrückt und ziehe den Song an die gewünschte Stelle.",
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                if (orderedTracks.isEmpty()) {
                    Text("Diese Playlist enthält noch keine Songs.")
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                    ) {
                        itemsIndexed(orderedTracks, key = { _, track -> track.id }) { _, track ->
                            val isDragging = draggingTrackId == track.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(72.dp)
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .graphicsLayer {
                                        translationY = if (isDragging) dragOffset else 0f
                                        alpha = if (isDragging) 0.88f else 1f
                                        shadowElevation = if (isDragging) 10f else 0f
                                    }
                                    .background(
                                        if (isDragging) {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        } else {
                                            Color.Transparent
                                        },
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .pointerInput(track.id) {
                                            val rowHeightPx = 72.dp.toPx()
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    draggingTrackId = track.id
                                                    dragOffset = 0f
                                                },
                                                onDragCancel = {
                                                    draggingTrackId = null
                                                    dragOffset = 0f
                                                },
                                                onDragEnd = {
                                                    draggingTrackId = null
                                                    dragOffset = 0f
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragOffset += dragAmount.y
                                                    while (dragOffset > rowHeightPx / 2f) {
                                                        val currentIndex = orderedTracks.indexOfFirst {
                                                            it.id == track.id
                                                        }
                                                        if (currentIndex < 0 ||
                                                            currentIndex >= orderedTracks.lastIndex
                                                        ) {
                                                            dragOffset = rowHeightPx / 2f
                                                            break
                                                        }
                                                        val moved = orderedTracks.removeAt(currentIndex)
                                                        orderedTracks.add(currentIndex + 1, moved)
                                                        dragOffset -= rowHeightPx
                                                    }
                                                    while (dragOffset < -rowHeightPx / 2f) {
                                                        val currentIndex = orderedTracks.indexOfFirst {
                                                            it.id == track.id
                                                        }
                                                        if (currentIndex <= 0) {
                                                            dragOffset = -rowHeightPx / 2f
                                                            break
                                                        }
                                                        val moved = orderedTracks.removeAt(currentIndex)
                                                        orderedTracks.add(currentIndex - 1, moved)
                                                        dragOffset += rowHeightPx
                                                    }
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.DragHandle,
                                        "Song verschieben",
                                        tint = if (isDragging) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                                Cover(track.coverPath, 48)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        track.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        track.artist,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(orderedTracks.map { it.id }) },
                enabled = orderedTracks.isNotEmpty()
            ) {
                Text("Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
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
    onPlaylist: (AudioTrack) -> Unit,
    playlistName: String?,
    queueKey: String,
    onBulkCover: () -> Unit,
    onBulkPlaylist: () -> Unit,
    onLocalTransfer: (AudioTrack) -> Unit
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
        item(key = "bulk-actions") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${tracks.size} Song${if (tracks.size == 1) "" else "s"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBulkPlaylist) {
                    Icon(Icons.Default.PlaylistAdd, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Playlists")
                }
                TextButton(onClick = onBulkCover) {
                    Icon(Icons.Default.Photo, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Cover")
                }
            }
        }
        items(tracks, key = { it.id }) { track ->
            TrackRow(
                track = track,
                isCurrent = vm.currentTrack?.id == track.id,
                onPlay = {
                    vm.play(track, tracks, queueKey)
                    onOpenPlayer()
                },
                onFavorite = { vm.toggleFavorite(track.id) },
                onEdit = { onEdit(track) },
                onPlaylist = { onPlaylist(track) },
                onShare = { vm.shareTrack(track) },
                onLocalTransfer = { onLocalTransfer(track) },
                onMoveUp = playlistName?.let {
                    { vm.moveTrackInPlaylist(it, track.id, -1) }
                },
                onMoveDown = playlistName?.let {
                    { vm.moveTrackInPlaylist(it, track.id, 1) }
                },
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
    onShare: () -> Unit,
    onLocalTransfer: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
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
                    text = { Text("Song teilen") },
                    leadingIcon = { Icon(Icons.Default.Share, null) },
                    onClick = { expanded = false; onShare() }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Per QR direkt übertragen") },
                    leadingIcon = { Icon(Icons.Default.QrCode2, null) },
                    onClick = { expanded = false; onLocalTransfer() }
                )
                if (onMoveUp != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("In Playlist nach oben") },
                        leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                        onClick = { expanded = false; onMoveUp() }
                    )
                }
                if (onMoveDown != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("In Playlist nach unten") },
                        leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                        onClick = { expanded = false; onMoveDown() }
                    )
                }
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
    onShare: (String) -> Unit,
    coverFor: (String) -> String?,
    onCover: (String) -> Unit,
    onLocalTransfer: (String) -> Unit,
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
                Cover(coverFor(playlist), 58)
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
                            text = { Text("Playlist teilen") },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = {
                                expanded = false
                                onShare(playlist)
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Per QR direkt übertragen") },
                            leadingIcon = { Icon(Icons.Default.QrCode2, null) },
                            onClick = {
                                expanded = false
                                onLocalTransfer(playlist)
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Playlist-Cover ändern") },
                            leadingIcon = { Icon(Icons.Default.Photo, null) },
                            onClick = {
                                expanded = false
                                onCover(playlist)
                            }
                        )
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
                                text = { Text("Song teilen") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = {
                                    expanded = false
                                    vm.shareTrack(track)
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
private fun StatsDialog(
    stats: PlaybackStats,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verlauf & Statistiken") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "${formatListeningTime(stats.totalListeningMs)} gehört",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "${stats.totalPlays} Wiedergaben · ${stats.uniqueTracks} verschiedene Songs",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (stats.mostPlayed.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text("Am häufigsten gehört", fontWeight = FontWeight.Bold)
                    stats.mostPlayed.forEachIndexed { index, (track, count) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${index + 1}.", modifier = Modifier.width(28.dp))
                            Cover(track.coverPath, 42)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    track.artist,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("${count}×", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("Zuletzt gehört", fontWeight = FontWeight.Bold)
                if (stats.recent.isEmpty()) {
                    Text(
                        "Noch kein Wiedergabeverlauf vorhanden.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    stats.recent.take(15).forEach { entry ->
                        Column(Modifier.padding(vertical = 7.dp)) {
                            Text(
                                entry.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${entry.artist} · ${formatHistoryDate(entry.playedAt)}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Schließen") }
        }
    )
}

@Composable
private fun LocalTransferDialog(
    vm: MusicViewModel,
    onScan: () -> Unit,
    onDismiss: () -> Unit
) {
    val offer = vm.localTransferOffer
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lokale QR-Übertragung") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (offer != null) {
                    Text(
                        offer.title,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    val qr = remember(offer.qrPayload) { createQrBitmap(offer.qrPayload) }
                    Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = "Kochify Übertragungs-QR-Code",
                            modifier = Modifier
                                .size(250.dp)
                                .padding(8.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                } else {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        null,
                        modifier = Modifier.size(54.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Scanne den Code des anderen Kochify-Handys. Beide Geräte müssen " +
                            "im selben WLAN sein.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (vm.isLocalTransferBusy) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                    )
                }
                vm.localTransferStatus?.let { status ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        status,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
                if (offer == null) {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onScan,
                        enabled = !vm.isLocalTransferBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.QrCodeScanner, null)
                        Spacer(Modifier.width(8.dp))
                        Text("QR-Code scannen")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schließen") }
        }
    )
}

private fun createQrBitmap(value: String): Bitmap {
    val size = 700
    val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    repeat(size) { y ->
        repeat(size) { x ->
            pixels[y * size + x] = if (matrix[x, y]) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        }
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

private fun formatListeningTime(milliseconds: Long): String {
    val totalMinutes = (milliseconds / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "$hours Std. $minutes Min." else "$minutes Min."
}

private fun formatHistoryDate(timestamp: Long): String = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
}.getOrDefault("–")

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
                    description = "Kräftige wechselnde Farben auf Tasten und Flächen",
                    swatch = Color(0xFFE040FB),
                    onSelect = onSelect
                )
                ThemeOption(
                    mode = KochifyThemeMode.CYBERPUNK,
                    selected = selected == KochifyThemeMode.CYBERPUNK,
                    title = "Cyberpunk",
                    description = "Neongelb, Cyan und Pink auf dunklem Hintergrund",
                    swatch = Color(0xFFFCEE09),
                    onSelect = onSelect
                )
                ThemeOption(
                    mode = KochifyThemeMode.GERMANY,
                    selected = selected == KochifyThemeMode.GERMANY,
                    title = "Deutschland",
                    description = "Schwarz, Rot und Gold",
                    swatch = Color(0xFFDD0000),
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
    onDownload: (String, Boolean, Uri?) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var coverMode by remember { mutableStateOf(DownloadCoverMode.YOUTUBE) }
    var customCoverUri by remember { mutableStateOf<Uri?>(null) }
    val customCoverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            customCoverUri = uri
            coverMode = DownloadCoverMode.CUSTOM
        }
    }
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
                Text(
                    "Song-Cover",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                DownloadCoverOption(
                    selected = coverMode == DownloadCoverMode.YOUTUBE,
                    title = "YouTube-Thumbnails übernehmen",
                    enabled = !downloading,
                    onSelect = { coverMode = DownloadCoverMode.YOUTUBE }
                )
                DownloadCoverOption(
                    selected = coverMode == DownloadCoverMode.CUSTOM,
                    title = if (customCoverUri == null) {
                        "Eigenes Cover für alle Downloads"
                    } else {
                        "Eigenes Cover ausgewählt"
                    },
                    enabled = !downloading,
                    onSelect = {
                        coverMode = DownloadCoverMode.CUSTOM
                        if (customCoverUri == null) customCoverPicker.launch("image/*")
                    }
                )
                if (coverMode == DownloadCoverMode.CUSTOM) {
                    TextButton(
                        onClick = { customCoverPicker.launch("image/*") },
                        enabled = !downloading
                    ) {
                        Icon(Icons.Default.Photo, null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (customCoverUri == null) "Cover auswählen" else "Cover wechseln"
                        )
                    }
                }
                DownloadCoverOption(
                    selected = coverMode == DownloadCoverMode.NONE,
                    title = "Ohne Cover herunterladen",
                    enabled = !downloading,
                    onSelect = { coverMode = DownloadCoverMode.NONE }
                )
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
                onClick = {
                    onDownload(
                        url,
                        coverMode == DownloadCoverMode.YOUTUBE,
                        customCoverUri.takeIf { coverMode == DownloadCoverMode.CUSTOM }
                    )
                },
                enabled = confirmed && url.isNotBlank() && !downloading &&
                    (coverMode != DownloadCoverMode.CUSTOM || customCoverUri != null)
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

private enum class DownloadCoverMode {
    YOUTUBE,
    CUSTOM,
    NONE
}

@Composable
private fun DownloadCoverOption(
    selected: Boolean,
    title: String,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = enabled
        )
        Text(title)
    }
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
private fun BulkPlaylistAssignmentDialog(
    tracks: List<AudioTrack>,
    playlists: List<String>,
    onApply: (Set<String>, Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTrackIds by remember(tracks.map { it.id }) {
        mutableStateOf(emptySet<String>())
    }
    var selectedPlaylists by remember(playlists) {
        mutableStateOf(emptySet<String>())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mehrere Songs zu Playlists") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(440.dp)
            ) {
                item {
                    Text(
                        "Wähle zuerst mehrere Songs und danach eine oder mehrere Ziel-Playlists.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = {
                                selectedTrackIds = tracks.map { it.id }.toSet()
                            }
                        ) {
                            Text("Alle Songs")
                        }
                        TextButton(onClick = { selectedTrackIds = emptySet() }) {
                            Text("Auswahl löschen")
                        }
                    }
                    Text(
                        "Songs (${selectedTrackIds.size} ausgewählt)",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(tracks, key = { "bulk-playlist-track-${it.id}" }) { track ->
                    val selected = track.id in selectedTrackIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedTrackIds = if (selected) {
                                    selectedTrackIds - track.id
                                } else {
                                    selectedTrackIds + track.id
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = {
                                selectedTrackIds = if (selected) {
                                    selectedTrackIds - track.id
                                } else {
                                    selectedTrackIds + track.id
                                }
                            }
                        )
                        Cover(track.coverPath, 40)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                track.artist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text(
                        "Ziel-Playlists (${selectedPlaylists.size} ausgewählt)",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    if (playlists.isEmpty()) {
                        Text(
                            "Erstelle zuerst eine Playlist.",
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(playlists, key = { "bulk-playlist-target-$it" }) { playlist ->
                    val selected = playlist in selectedPlaylists
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedPlaylists = if (selected) {
                                    selectedPlaylists - playlist
                                } else {
                                    selectedPlaylists + playlist
                                }
                            }
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = {
                                selectedPlaylists = if (selected) {
                                    selectedPlaylists - playlist
                                } else {
                                    selectedPlaylists + playlist
                                }
                            }
                        )
                        Icon(Icons.Default.QueueMusic, null)
                        Spacer(Modifier.width(8.dp))
                        Text(playlist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(selectedTrackIds, selectedPlaylists) },
                enabled = selectedTrackIds.isNotEmpty() && selectedPlaylists.isNotEmpty()
            ) {
                Text("Hinzufügen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

@Composable
private fun BulkCoverDialog(
    tracks: List<AudioTrack>,
    onApply: (Set<String>, Uri) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedIds by remember(tracks.map { it.id }) {
        mutableStateOf(emptySet<String>())
    }
    var coverUri by remember { mutableStateOf<Uri?>(null) }
    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) coverUri = uri
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cover für mehrere Songs") },
        text = {
            Column {
                Text(
                    "Wähle alle Songs aus, die dasselbe neue Cover erhalten sollen.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { selectedIds = tracks.map { it.id }.toSet() }
                    ) {
                        Text("Alle auswählen")
                    }
                    TextButton(onClick = { selectedIds = emptySet() }) {
                        Text("Auswahl löschen")
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                ) {
                    items(tracks, key = { "cover-${it.id}" }) { track ->
                        val selected = track.id in selectedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIds = if (selected) {
                                        selectedIds - track.id
                                    } else {
                                        selectedIds + track.id
                                    }
                                }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = {
                                    selectedIds = if (selected) {
                                        selectedIds - track.id
                                    } else {
                                        selectedIds + track.id
                                    }
                                }
                            )
                            Cover(track.coverPath, 42)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    track.artist,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { coverPicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Photo, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (coverUri == null) "Neues Cover auswählen" else "Cover wechseln")
                }
                if (coverUri != null) {
                    Text(
                        "Cover ausgewählt · ${selectedIds.size} Song(s) markiert",
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { coverUri?.let { onApply(selectedIds, it) } },
                enabled = selectedIds.isNotEmpty() && coverUri != null
            ) {
                Text("Auf ${selectedIds.size} anwenden")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
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
