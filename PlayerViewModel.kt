package com.mastermusic.app

import android.app.Application
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val uri: String,
    val artUri: String? = null,
    val durationMs: Long = 0L,
    val isLocal: Boolean = true,
    val remoteUrl: String? = null,
    val offline: Boolean = false,
    val addedAt: Long = 0L,
)

data class VideoItem(val id: Long, val title: String, val uri: String, val durationMs: Long)

class PlayerViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("mm", Context.MODE_PRIVATE)

    // ---- library state ----
    var local by mutableStateOf<List<Song>>(emptyList()); private set
    var streams by mutableStateOf(loadStreams()); private set
    val songs: List<Song> get() = local + streams

    var favorites by mutableStateOf(prefs.getStringSet("fav", emptySet())!!.toSet()); private set
    var downloading by mutableStateOf<Set<String>>(emptySet()); private set

    var recent by mutableStateOf(prefs.getString("recent", "")!!.split(",").filter { it.isNotBlank() }); private set
    val recentSongs: List<Song> get() = recent.mapNotNull { id -> songs.find { it.id == id } ?: known[id] }
    val recentlyAdded: List<Song> get() = local.sortedByDescending { it.addedAt }.take(12)

    var videos by mutableStateOf<List<VideoItem>>(emptyList()); private set

    // ---- player state ----
    var currentId by mutableStateOf<String?>(null); private set
    var isPlaying by mutableStateOf(false); private set
    var position by mutableLongStateOf(0L); private set
    var duration by mutableLongStateOf(0L); private set
    var shuffle by mutableStateOf(false); private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF); private set
    var sleepMinutes by mutableIntStateOf(0); private set

    var online by mutableStateOf<List<Song>>(emptyList()); private set
    var onlineLoading by mutableStateOf(false); private set
    var onlineError by mutableStateOf(false); private set
    private var searchJob: Job? = null
    private val known = HashMap<String, Song>()

    val current: Song? get() = songs.find { it.id == currentId } ?: known[currentId]

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var sleepJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { this@PlayerViewModel.isPlaying = isPlaying }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentId = mediaItem?.mediaId
            mediaItem?.mediaId?.let { pushRecent(it) }
        }
        override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffle = enabled }
        override fun onRepeatModeChanged(mode: Int) { repeatMode = mode }
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(listener)
            isPlaying = c.isPlaying
            currentId = c.currentMediaItem?.mediaId
            shuffle = c.shuffleModeEnabled
            repeatMode = c.repeatMode
        }, ContextCompat.getMainExecutor(app))

        viewModelScope.launch {
            while (true) {
                delay(500)
                controller?.let {
                    position = it.currentPosition.coerceAtLeast(0L)
                    duration = it.duration.coerceAtLeast(0L)
                }
            }
        }
    }

    // ---- playback ----
    fun play(list: List<Song>, index: Int) {
        val c = controller ?: return
        if (list.isEmpty()) return
        list.forEach { known[it.id] = it }
        c.setMediaItems(list.map { it.toMediaItem() }, index.coerceIn(0, list.lastIndex), 0L)
        c.prepare()
        c.play()
    }

    fun playShuffled(list: List<Song>) {
        val c = controller ?: return
        if (list.isEmpty()) return
        c.shuffleModeEnabled = true
        play(list, list.indices.random())
    }

    private fun pushRecent(id: String) {
        recent = (listOf(id) + recent.filter { it != id }).take(15)
        prefs.edit().putString("recent", recent.joinToString(",")).apply()
    }

    fun pauseMusic() { controller?.pause() }

    fun searchOnline(q: String) {
        searchJob?.cancel()
        if (q.isBlank() || !OnlineRepository.enabled()) {
            online = emptyList(); onlineLoading = false; onlineError = false
            return
        }
        onlineLoading = true
        onlineError = false
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val r = OnlineRepository.search(q.trim()).map { s ->
                    val f = offlineFile(s.id)
                    if (f.exists()) s.copy(uri = Uri.fromFile(f).toString(), offline = true) else s
                }
                withContext(Dispatchers.Main) { online = r; onlineLoading = false }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onlineError = true; onlineLoading = false }
            }
        }
    }

    fun loadVideos() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = mutableListOf<VideoItem>()
            val proj = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DURATION,
            )
            app.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    list += VideoItem(
                        id = id,
                        title = c.getString(iTitle) ?: "Video",
                        uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString(),
                        durationMs = c.getLong(iDur),
                    )
                }
            }
            withContext(Dispatchers.Main) { videos = list }
        }
    }

    fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { controller?.seekToNext() }
    fun previous() { controller?.seekToPrevious() }
    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepMinutes = minutes
        if (minutes > 0) {
            sleepJob = viewModelScope.launch {
                delay(minutes * 60_000L)
                controller?.pause()
                sleepMinutes = 0
            }
        }
    }

    // ---- favorites ----
    fun toggleFavorite(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        prefs.edit().putStringSet("fav", favorites).apply()
    }

    // ---- local files (phone storage) ----
    fun loadLocal() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = mutableListOf<Song>()
            val proj = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
            )
            val sel = "${MediaStore.Audio.Media.IS_MUSIC}!=0 AND ${MediaStore.Audio.Media.DURATION}>30000"
            app.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    list += Song(
                        addedAt = c.getLong(iAdded),
                        id = "l_$id",
                        title = c.getString(iTitle) ?: "Unknown",
                        artist = c.getString(iArtist)?.takeIf { it != "<unknown>" } ?: "",
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
                        artUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), c.getLong(iAlbum)).toString(),
                        durationMs = c.getLong(iDur),
                    )
                }
            }
            withContext(Dispatchers.Main) { local = list }
        }
    }

    // ---- links the user adds + offline download ----
    fun addStream(title: String, url: String) {
        val arr = JSONArray(prefs.getString("streams", "[]"))
        arr.put(JSONObject().put("id", "s_${url.hashCode().toUInt()}").put("title", title).put("url", url))
        prefs.edit().putString("streams", arr.toString()).apply()
        streams = loadStreams()
    }

    fun removeStream(id: String) {
        val old = JSONArray(prefs.getString("streams", "[]"))
        val arr = JSONArray()
        for (i in 0 until old.length()) if (old.getJSONObject(i).getString("id") != id) arr.put(old.getJSONObject(i))
        prefs.edit().putString("streams", arr.toString()).apply()
        offlineFile(id).delete()
        streams = loadStreams()
    }

    fun download(song: Song) {
        val url = song.remoteUrl ?: return
        if (song.id in downloading || song.offline) return
        downloading = downloading + song.id
        viewModelScope.launch(Dispatchers.IO) {
            val tmp = File(offlineDir(), "${song.id}.part")
            try {
                URL(url).openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(offlineFile(song.id))
            } catch (e: Exception) {
                tmp.delete()
            }
            withContext(Dispatchers.Main) {
                downloading = downloading - song.id
                streams = loadStreams()
                if (offlineFile(song.id).exists()) {
                    online = online.map {
                        if (it.id == song.id) it.copy(offline = true, uri = Uri.fromFile(offlineFile(song.id)).toString()) else it
                    }
                }
            }
        }
    }

    private fun offlineDir() = File(app.filesDir, "offline").apply { mkdirs() }
    private fun offlineFile(id: String) = File(offlineDir(), "$id.mp3")

    private fun loadStreams(): List<Song> {
        val arr = JSONArray(prefs.getString("streams", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val id = o.getString("id")
            val f = offlineFile(id)
            Song(
                id = id,
                title = o.getString("title"),
                artist = "",
                uri = if (f.exists()) Uri.fromFile(f).toString() else o.getString("url"),
                isLocal = false,
                remoteUrl = o.getString("url"),
                offline = f.exists(),
            )
        }
    }

    private fun Song.toMediaItem() = MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(artUri?.let(Uri::parse))
                .build()
        )
        .build()

    override fun onCleared() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
