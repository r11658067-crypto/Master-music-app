package com.mastermusic.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale

// ---------------------------------------------------------------- helpers

private const val TAB_HOME = 0
private const val TAB_SEARCH = 1
private const val TAB_LIB = 2
private const val TAB_PROFILE = 3

private const val SEC_ALL = 0
private const val SEC_FAV = 1
private const val SEC_OFFLINE = 2

private val LANGS = listOf("en" to "English", "hi" to "हिन्दी", "bn" to "বাংলা", "mr" to "मराठी", "ta" to "தமிழ்")

private fun mediaPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun videoPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun has(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun currentLang(): String =
    AppCompatDelegate.getApplicationLocales().get(0)?.language ?: Locale.getDefault().language

private fun setLang(tag: String) {
    // Applies immediately: the whole UI is recreated in the chosen language.
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
}

@Composable
private fun NeonText(text: String, size: TextUnit) {
    Text(text, style = TextStyle(brush = NeonBrush, fontSize = size, fontWeight = FontWeight.ExtraBold))
}

private fun Modifier.neonBorder(radius: Dp = 16.dp) = this.border(1.dp, NeonBrush, RoundedCornerShape(radius))

// ---------------------------------------------------------------- root

@Composable
fun MasterMusicApp(vm: PlayerViewModel) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(TAB_HOME) }
    var section by rememberSaveable { mutableIntStateOf(SEC_ALL) }
    var showVideos by rememberSaveable { mutableStateOf(false) }
    var showPlayer by remember { mutableStateOf(false) }
    var showLang by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(has(ctx, mediaPermission())) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = has(ctx, mediaPermission())
    }
    LaunchedEffect(granted) { if (granted) vm.loadLocal() }

    Box(
        Modifier.fillMaxSize()
            .background(AppBackground)
            .background(Brush.radialGradient(listOf(NeonBlue.copy(alpha = 0.22f), Color.Transparent), center = Offset(0f, 0f), radius = 900f))
            .background(Brush.radialGradient(listOf(NeonPink.copy(alpha = 0.18f), Color.Transparent), center = Offset(1000f, 2200f), radius = 1100f))
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                Column {
                    if (vm.current != null) MiniPlayer(vm) { showPlayer = true }
                    NavigationBar(containerColor = Color(0xFF080D26)) {
                        val items = listOf(
                            Triple(TAB_HOME, Icons.Filled.Home, stringResource(R.string.tab_home)),
                            Triple(TAB_SEARCH, Icons.Filled.Search, stringResource(R.string.tab_search)),
                            Triple(TAB_LIB, Icons.Filled.LibraryMusic, stringResource(R.string.tab_library)),
                            Triple(TAB_PROFILE, Icons.Filled.Person, stringResource(R.string.tab_profile)),
                        )
                        items.forEach { (i, icon, label) ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { Icon(icon, label) },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = NeonPink,
                                    selectedTextColor = NeonPink,
                                    indicatorColor = NeonPink.copy(alpha = 0.16f),
                                    unselectedIconColor = Color.White.copy(alpha = 0.6f),
                                    unselectedTextColor = Color.White.copy(alpha = 0.6f),
                                ),
                            )
                        }
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    TAB_HOME -> HomeScreen(
                        vm, granted,
                        askPermission = {
                            launcher.launch(
                                if (Build.VERSION.SDK_INT >= 33)
                                    arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                                else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                            )
                        },
                        onSearch = { tab = TAB_SEARCH },
                        onLibrary = { sec -> section = sec; tab = TAB_LIB },
                        onVideos = { showVideos = true },
                        onLang = { showLang = true },
                    )
                    TAB_SEARCH -> SearchScreen(vm)
                    TAB_LIB -> LibraryScreen(vm, section) { section = it }
                    else -> ProfileScreen(vm)
                }
            }
        }

        AnimatedVisibility(visible = showVideos, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            VideosScreen(vm) { showVideos = false }
        }
        AnimatedVisibility(visible = showPlayer, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            FullPlayer(vm) { showPlayer = false }
        }
    }
    if (showLang) LanguageDialog { showLang = false }
    BackHandler(showPlayer) { showPlayer = false }
    BackHandler(showVideos && !showPlayer) { showVideos = false }
}

// ---------------------------------------------------------------- home

@Composable
private fun HomeScreen(
    vm: PlayerViewModel,
    granted: Boolean,
    askPermission: () -> Unit,
    onSearch: () -> Unit,
    onLibrary: (Int) -> Unit,
    onVideos: () -> Unit,
    onLang: () -> Unit,
) {
    val added = vm.recentlyAdded
    val played = vm.recentSongs.take(6)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo), stringResource(R.string.app_name), Modifier.size(52.dp))
                Spacer(Modifier.width(10.dp))
                NeonText(stringResource(R.string.app_name), 24.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onLang) { Icon(Icons.Filled.Language, null, tint = NeonCyan, modifier = Modifier.size(28.dp)) }
            }
            Spacer(Modifier.height(14.dp))
            if (!granted) {
                Button(onClick = askPermission, colors = ButtonDefaults.buttonColors(containerColor = NeonPink)) {
                    Text(stringResource(R.string.grant_permission))
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.07f))
                    .neonBorder(28.dp).clickable(onClick = onSearch).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.search_hint), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(listOf(Color(0xFF7C3AED), Color(0xFF4F46E5)), Icons.Filled.MusicNote, R.string.tile_music, R.string.tile_music_sub, Modifier.weight(1f)) { onLibrary(SEC_ALL) }
                Tile(listOf(Color(0xFFEF4444), Color(0xFFB91C1C)), Icons.Filled.PlayCircle, R.string.tile_videos, R.string.tile_videos_sub, Modifier.weight(1f), onVideos)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(listOf(Color(0xFF10B981), Color(0xFF047857)), Icons.AutoMirrored.Filled.PlaylistPlay, R.string.tile_playlists, R.string.tile_playlists_sub, Modifier.weight(1f)) { onLibrary(SEC_FAV) }
                Tile(listOf(Color(0xFF2563EB), Color(0xFF1D4ED8)), Icons.Filled.Download, R.string.tile_downloads, R.string.tile_downloads_sub, Modifier.weight(1f)) { onLibrary(SEC_OFFLINE) }
            }
        }
        if (added.isNotEmpty()) {
            item {
                SectionHeader(R.string.recently_added) { onLibrary(SEC_ALL) }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(added, key = { _, s -> s.id }) { index, s ->
                        Column(Modifier.width(112.dp).clickable { vm.play(added, index) }) {
                            Art(s.artUri, 112.dp, 14.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            if (s.artist.isNotBlank())
                                Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
        if (played.isNotEmpty()) {
            item { SectionHeader(R.string.recently_played) { onLibrary(SEC_ALL) } }
            itemsIndexed(played, key = { _, s -> "r_" + s.id }) { index, s ->
                SongRow(vm, s) { vm.play(played, index) }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SectionHeader(title: Int, onSeeAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.see_all), color = NeonCyan, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onSeeAll))
    }
}

@Composable
private fun Tile(colors: List<Color>, icon: ImageVector, title: Int, sub: Int, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(colors))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(stringResource(title), fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            Text(stringResource(sub), fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- search / library / profile

@Composable
private fun SearchScreen(vm: PlayerViewModel) {
    var q by rememberSaveable { mutableStateOf("") }
    val results = remember(q, vm.songs) {
        if (q.isBlank()) emptyList()
        else vm.songs.filter { it.title.contains(q, true) || it.artist.contains(q, true) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = q, onValueChange = { q = it },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonPink, unfocusedBorderColor = NeonBlue),
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
        )
        LaunchedEffect(q) {
            if (q.isBlank()) vm.searchOnline("") else { delay(500); vm.searchOnline(q) }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(results, key = { _, s -> s.id }) { i, s -> SongRow(vm, s) { vm.play(results, i) } }
            if (q.isNotBlank()) {
                item {
                    Text(
                        stringResource(R.string.online_results),
                        color = NeonCyan, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                    )
                }
                when {
                    !OnlineRepository.enabled() -> item {
                        Text(stringResource(R.string.online_need_key), fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                    }
                    vm.onlineLoading -> item { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = NeonCyan) }
                    vm.onlineError -> item { Text(stringResource(R.string.online_error), color = Color.White.copy(alpha = 0.7f)) }
                    vm.online.isEmpty() -> item { Text(stringResource(R.string.no_songs), color = Color.White.copy(alpha = 0.6f)) }
                    else -> itemsIndexed(vm.online, key = { _, s -> "o_" + s.id }) { i, s ->
                        SongRow(vm, s) { vm.play(vm.online, i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(vm: PlayerViewModel, section: Int, onSection: (Int) -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    val list = when (section) {
        SEC_FAV -> vm.songs.filter { it.id in vm.favorites }
        SEC_OFFLINE -> vm.songs.filter { it.isLocal || it.offline }
        else -> vm.songs
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val chipColors = FilterChipDefaults.filterChipColors(selectedContainerColor = NeonPink.copy(alpha = 0.3f))
            FilterChip(section == SEC_ALL, { onSection(SEC_ALL) }, { Text(stringResource(R.string.all)) }, colors = chipColors)
            FilterChip(section == SEC_FAV, { onSection(SEC_FAV) }, { Text(stringResource(R.string.favorites)) }, colors = chipColors)
            FilterChip(section == SEC_OFFLINE, { onSection(SEC_OFFLINE) }, { Text(stringResource(R.string.offline)) }, colors = chipColors)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, stringResource(R.string.add_stream), tint = NeonCyan) }
        }
        SongList(vm, list)
    }
    if (showAdd) AddLinkDialog(onDismiss = { showAdd = false }) { t, u -> vm.addStream(t, u); showAdd = false }
}

@Composable
private fun ProfileScreen(vm: PlayerViewModel) {
    val lang = currentLang()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(110.dp))
                NeonText(stringResource(R.string.app_name), 26.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.language), fontWeight = FontWeight.SemiBold, color = NeonCyan)
        }
        items(LANGS) { (tag, name) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (lang == tag) NeonPink.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
                    .clickable { setLang(tag) }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (lang == tag) Icon(Icons.Filled.Check, null, tint = NeonPink)
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.sleep_timer), fontWeight = FontWeight.SemiBold, color = NeonCyan)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 15, 30, 60).forEach { m ->
                    FilterChip(
                        selected = vm.sleepMinutes == m,
                        onClick = { vm.setSleepTimer(m) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NeonPink.copy(alpha = 0.3f)),
                        label = { Text(if (m == 0) stringResource(R.string.off) else stringResource(R.string.minutes_fmt, m)) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.legal_note), fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun LanguageDialog(onDismiss: () -> Unit) {
    val lang = currentLang()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink,
        title = { Text(stringResource(R.string.choose_language)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LANGS.forEach { (tag, name) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (lang == tag) NeonPink.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable { setLang(tag); onDismiss() }.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (lang == tag) Icon(Icons.Filled.Check, null, tint = NeonPink)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---------------------------------------------------------------- videos (files on this phone)

@Composable
private fun VideosScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(has(ctx, videoPermission())) }
    var playing by remember { mutableStateOf<VideoItem?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(granted) { if (granted) vm.loadVideos() }

    Box(Modifier.fillMaxSize().background(AppBackground)) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                NeonText(stringResource(R.string.tile_videos), 24.sp)
            }
            if (!granted) {
                Button(onClick = { launcher.launch(videoPermission()) }, colors = ButtonDefaults.buttonColors(containerColor = NeonPink)) {
                    Text(stringResource(R.string.grant_video))
                }
            } else if (vm.videos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_videos), color = Color.White.copy(alpha = 0.6f))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.videos, key = { it.id }) { v ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.pauseMusic(); playing = v }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(64.dp, 44.dp).clip(RoundedCornerShape(10.dp))
                                    .background(Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFF7C3AED)))),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(v.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text(fmt(v.durationMs), fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }
        }
        playing?.let { v -> VideoPlayer(v) { playing = null } }
    }
    BackHandler(playing != null) { playing = null }
}

@Composable
private fun VideoPlayer(video: VideoItem, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember(video.id) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(video.uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = true } },
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(onClick = onClose, modifier = Modifier.systemBarsPadding().padding(4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
        }
    }
}

// ---------------------------------------------------------------- shared pieces

@Composable
private fun SongList(vm: PlayerViewModel, list: List<Song>) {
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_songs), color = Color.White.copy(alpha = 0.6f))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(list, key = { _, s -> s.id }) { index, song ->
            SongRow(vm, song) { vm.play(list, index) }
        }
    }
}

@Composable
private fun Art(uri: String?, size: Dp, radius: Dp = 10.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(radius))
            .background(Brush.linearGradient(listOf(NeonBlue, NeonPurple, NeonPink))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.MusicNote, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(size / 2))
        if (uri != null) AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

@Composable
private fun SongRow(vm: PlayerViewModel, song: Song, onClick: () -> Unit) {
    val playing = vm.currentId == song.id
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Art(song.artUri, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) NeonPink else Color.White, fontWeight = FontWeight.SemiBold)
            if (song.artist.isNotBlank())
                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
        }
        if (!song.isLocal && (song.remoteUrl != null || song.offline)) {
            when {
                song.id in vm.downloading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = NeonCyan)
                song.offline -> Icon(Icons.Filled.DownloadDone, stringResource(R.string.downloaded), tint = NeonCyan, modifier = Modifier.padding(horizontal = 8.dp))
                else -> IconButton(onClick = { vm.download(song) }) { Icon(Icons.Filled.Download, stringResource(R.string.download)) }
            }
        }
        IconButton(onClick = { vm.toggleFavorite(song.id) }) {
            val fav = song.id in vm.favorites
            Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, tint = if (fav) NeonPink else Color.White)
        }
    }
}

@Composable
private fun MiniPlayer(vm: PlayerViewModel, onOpen: () -> Unit) {
    val s = vm.current ?: return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp)).background(Color(0xFF111A40)).neonBorder(16.dp)
            .clickable(onClick = onOpen).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Art(s.artUri, 44.dp, 8.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            if (s.artist.isNotBlank()) Text(s.artist, maxLines = 1, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
        }
        IconButton(onClick = vm::previous) { Icon(Icons.Filled.SkipPrevious, null) }
        IconButton(onClick = vm::togglePlay) { Icon(if (vm.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = NeonPink) }
        IconButton(onClick = vm::next) { Icon(Icons.Filled.SkipNext, null) }
    }
}

@Composable
private fun FullPlayer(vm: PlayerViewModel, onClose: () -> Unit) {
    val s = vm.current
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B2F8F), Color(0xFF2A0B55), Navy)))
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, null, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.weight(1f))
            Image(painterResource(R.drawable.logo), null, Modifier.size(40.dp))
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.neonBorder(26.dp).padding(3.dp)) { Art(s?.artUri, 290.dp, 24.dp) }
        Spacer(Modifier.height(28.dp))
        Text(s?.title ?: "", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!s?.artist.isNullOrBlank()) Text(s!!.artist, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(20.dp))

        val dur = vm.duration.coerceAtLeast(1L)
        Slider(
            value = dragging ?: (vm.position.toFloat() / dur),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { vm.seekTo((it * dur).toLong()) }; dragging = null },
            colors = SliderDefaults.colors(thumbColor = NeonPink, activeTrackColor = NeonPink, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt(vm.position), fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
            Text(fmt(vm.duration), fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::toggleShuffle) { Icon(Icons.Filled.Shuffle, null, tint = if (vm.shuffle) NeonCyan else Color.White) }
            IconButton(onClick = vm::previous) { Icon(Icons.Filled.SkipPrevious, null, modifier = Modifier.size(40.dp)) }
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(Brush.linearGradient(listOf(NeonBlue, NeonPurple, NeonPink)))
                    .clickable(onClick = vm::togglePlay),
                contentAlignment = Alignment.Center,
            ) { Icon(if (vm.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(40.dp)) }
            IconButton(onClick = vm::next) { Icon(Icons.Filled.SkipNext, null, modifier = Modifier.size(40.dp)) }
            IconButton(onClick = vm::cycleRepeat) {
                Icon(
                    if (vm.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, null,
                    tint = if (vm.repeatMode == Player.REPEAT_MODE_OFF) Color.White else NeonCyan,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        s?.let { song ->
            val fav = song.id in vm.favorites
            IconButton(onClick = { vm.toggleFavorite(song.id) }) {
                Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, tint = if (fav) NeonPink else Color.White, modifier = Modifier.size(30.dp))
            }
        }
    }
}

@Composable
private fun AddLinkDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink,
        title = { Text(stringResource(R.string.add_stream)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.song_title)) }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.song_url)) }, singleLine = true)
                Text(stringResource(R.string.legal_note), fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && url.startsWith("http"),
                onClick = { onAdd(title.trim(), url.trim()) },
            ) { Text(stringResource(R.string.add), color = NeonPink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
