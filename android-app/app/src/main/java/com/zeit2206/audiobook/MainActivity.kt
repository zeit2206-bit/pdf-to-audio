package com.zeit2206.audiobook

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { App(controller) } }
    }

    // Connected for the activity's whole life (not just while visible), so locking/unlocking the
    // screen doesn't tear down the read-along view and reload the text.
    override fun onStart() {
        super.onStart()
        if (controllerFuture != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({ controller = future.get() }, MoreExecutors.directExecutor())
    }

    override fun onDestroy() {
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onDestroy()
    }
}

/** Snapshot of the audiobook player, refreshed a few times per second while visible. */
data class PlayerUi(
    val mediaId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
)

sealed interface Screen {
    data object Library : Screen
    data object Player : Screen
    data object Videos : Screen
    data class VideoLoop(val index: Int) : Screen
}

@Composable
fun App(controller: MediaController?) {
    val context = LocalContext.current
    val store = remember { Store(context) }
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    var refresh by remember { mutableIntStateOf(0) }
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var ui by remember { mutableStateOf(PlayerUi()) }
    var scanned by remember { mutableStateOf(false) } // first folder scan finished
    var trimOn by remember { mutableStateOf(store.trimSilentEndings) }
    val scope = rememberCoroutineScope()

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            books = store.folders("books").mapNotNull { scanBook(context, it) }.filter { it.chapters.isNotEmpty() }
            videos = store.folders("videos").flatMap { scanVideos(context, it) }
                .map { v -> v.copy(endMs = store.trimEnd(v.uri) ?: 0) }
            scanned = true
            val perms = context.contentResolver.persistedUriPermissions.map { it.uri.toString() }
            val folders = store.folders("books") + store.folders("videos")
            Diag.log("scan", "books=${books.size} videos=${videos.size} in ${System.currentTimeMillis() - t0} ms · " +
                    "folders=${folders.size} without permission=${folders.count { it.toString() !in perms }} · ${Diag.memory()}")
            // Analyse new videos one at a time (single core) and remember the result.
            for (v in videos.filter { store.trimEnd(it.uri) == null }) {
                val end = detectSilentTail(context, v.uri)
                store.setTrimEnd(v.uri, end)
                if (end > 0) videos = videos.map { if (it.uri == v.uri) it.copy(endMs = end) else it }
            }
        }
    }

    // Poll the controller; also persist the position so each chapter resumes where it stopped.
    // Fast while visible (drives the highlight); every 5 s in the background, just to save the position.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller) {
        var tick = 0
        while (controller != null) {
            val visible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val id = controller.currentMediaItem?.mediaId
            ui = PlayerUi(id, controller.currentPosition, controller.duration.coerceAtLeast(0),
                controller.isPlaying, controller.playbackParameters.speed)
            if (id != null && (!visible || tick++ % 10 == 0 || !controller.isPlaying) && controller.playbackState == Player.STATE_READY)
                store.savePosition(android.net.Uri.parse(id), controller.currentPosition, controller.duration)
            if (visible) delay(200)
            else for (i in 0 until 25) { // wake up promptly when the screen comes back
                delay(200)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) break
            }
        }
    }

    val pickBooks = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { store.addFolder("books", uri); refresh++ }
    }
    val pickVideos = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { store.addFolder("videos", uri); refresh++ }
    }

    // The chapter just tapped, shown until the controller reports it (avoids bouncing back to the library).
    var opening by remember { mutableStateOf<Pair<Book, Chapter>?>(null) }
    val current = books.firstNotNullOfOrNull { b -> b.chapters.firstOrNull { it.audio.toString() == ui.mediaId }?.let { b to it } }
        ?: opening

    fun play(book: Book, chapter: Chapter) {
        opening = book to chapter
        screen = Screen.Player
        val c = controller ?: return // played by the effect below once the service connects
        if (ui.mediaId != chapter.audio.toString()) {
            val items = book.chapters.map {
                MediaItem.Builder().setMediaId(it.audio.toString()).setUri(it.audio)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).setArtist(book.name)
                        .setTrackNumber(it.number).build()).build()
            }
            val idx = book.chapters.indexOf(chapter)
            c.setMediaItems(items, idx, store.position(chapter.audio))
            c.prepare()
        }
        // The service may have been playing videos (looping, 1×) before.
        c.repeatMode = Player.REPEAT_MODE_OFF
        c.setPlaybackSpeed(store.speed)
        c.play()
    }
    LaunchedEffect(ui.mediaId) { if (ui.mediaId != null && opening?.second?.audio.toString() == ui.mediaId) opening = null }
    LaunchedEffect(controller) {
        val pending = opening
        if (controller != null && pending != null && ui.mediaId != pending.second.audio.toString()) play(pending.first, pending.second)
    }

    BackHandler(enabled = screen != Screen.Library) {
        screen = when (screen) {
            is Screen.VideoLoop -> Screen.Videos
            Screen.Videos -> Screen.Library
            else -> Screen.Library
        }
    }

    // Android 15+ always draws edge-to-edge, so keep content clear of the status/navigation bars
    // (the video feed is full-bleed and pads its own overlays).
    val insets = if (screen is Screen.VideoLoop) Modifier else Modifier.systemBarsPadding()
    Box(Modifier.fillMaxSize().background(if (screen is Screen.VideoLoop) Color.Black else Palette.bg).then(insets)) {
        when (val s = screen) {
            Screen.Library, Screen.Videos -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    if (s == Screen.Library) LibraryScreen(
                        books = books, loading = !scanned && store.folders("books").isNotEmpty(), store = store, ui = ui, current = current?.second,
                        onAddFolder = { pickBooks.launch(null) },
                        onOpen = { b, ch -> play(b, ch) },
                        onOpenPlayer = { screen = Screen.Player },
                        onToggle = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                    ) else VideosScreen(
                        videos = videos, loading = !scanned && store.folders("videos").isNotEmpty(), trimOn = trimOn,
                        onReport = { Diag.share(context) },
                        onToggleTrim = { trimOn = !trimOn; store.trimSilentEndings = trimOn },
                        onAddFolder = { pickVideos.launch(null) },
                        onOpen = { screen = Screen.VideoLoop(it) },
                    )
                }
                NavBar(isVideos = s == Screen.Videos) { screen = if (it) Screen.Videos else Screen.Library }
            }
            Screen.Player -> {
                val pair = current
                if (pair == null) LaunchedEffect(Unit) { screen = Screen.Library }
                else if (controller != null) PlayerScreen(pair.first, pair.second, ui, controller, store, onClose = { screen = Screen.Library })
            }
            is Screen.VideoLoop -> if (controller != null) VideoPager(
                videos = videos, start = s.index, player = controller, trim = trimOn,
                onClose = { screen = Screen.Videos },
            )
        }
    }
}
