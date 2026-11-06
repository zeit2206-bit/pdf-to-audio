package com.zeit2206.audiobook

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private class Thumb(val bitmap: Bitmap?, val durationMs: Long)

// Bounded: with hundreds of videos an unbounded map of bitmaps (~0.9 MB each) got the app killed,
// stopping playback in the background. Evicted thumbnails are simply decoded again.
private val thumbCache = object : LruCache<Uri, Thumb>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
    override fun sizeOf(key: Uri, value: Thumb) = (value.bitmap?.allocationByteCount ?: 0) / 1024 + 1 // KB
}

private fun loadThumb(context: Context, uri: Uri): Thumb = synchronized(thumbCache) { thumbCache[uri] } ?: run {
    val r = MediaMetadataRetriever()
    val t = try {
        r.setDataSource(context, uri)
        // RGB_565 halves the memory of each thumbnail.
        Thumb(r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 640)
            ?.let { b -> b.copy(Bitmap.Config.RGB_565, false).also { b.recycle() } },
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0)
    } catch (e: Exception) {
        Thumb(null, 0)
    } finally {
        r.release()
    }
    synchronized(thumbCache) { thumbCache.put(uri, t) }
    t
}

@Composable
private fun rememberThumb(uri: Uri): Thumb? {
    val context = LocalContext.current
    val t by produceState<Thumb?>(synchronized(thumbCache) { thumbCache[uri] }, uri) {
        if (value == null) value = withContext(Dispatchers.IO) { loadThumb(context, uri) }
    }
    return t
}

@Composable
fun VideosScreen(videos: List<Video>, loading: Boolean, trimOn: Boolean, onReport: () -> Unit, onToggleTrim: () -> Unit, onAddFolder: () -> Unit, onOpen: (Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Videos", onAddFolder)
        if (videos.isEmpty() && loading) { LoadingState("Loading videos…"); return }
        if (videos.isEmpty()) {
            EmptyState("Pick a folder with video clips (mp4, webm…).\nThey play in a loop; swipe up for the next one.",
                "Choose folder", onAddFolder)
            return
        }
        Text("Loop mode · audiobook pauses while a video plays", fontSize = 13.sp, color = Palette.muted,
            modifier = Modifier.padding(horizontal = 20.dp))
        val trimmed = videos.count { it.endMs > 0 }
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(RoundedCornerShape(15.dp))
            .background(if (trimOn) Palette.hl else Palette.surface2).clickable(onClick = onToggleTrim)
            .padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text((if (trimOn) "Cut silent endings: On" else "Cut silent endings: Off") +
                    if (trimmed > 0) " · $trimmed video${if (trimmed == 1) "" else "s"}" else "",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (trimOn) Palette.accent else Palette.muted)
        }
        Spacer(Modifier.weight(1f))
        // Diagnostics log (Diag.kt): share it in a chat if videos vanish or playback stops again.
        Text("Report", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.muted,
            modifier = Modifier.clip(RoundedCornerShape(15.dp)).background(Palette.surface2).clickable(onClick = onReport)
                .padding(horizontal = 12.dp, vertical = 6.dp))
        }
        LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(videos, key = { _, v -> v.uri.toString() }) { i, v ->
                val thumb = rememberThumb(v.uri)
                Box(Modifier.aspectRatio(154f / 184f).clip(RoundedCornerShape(16.dp)).background(Palette.surface2)
                    .clickable { onOpen(i) }) {
                    thumb?.bitmap?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    Icon(Icons.Play, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(28.dp))
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.35f))
                        .padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(v.name.substringBeforeLast('.'), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        val dur = if (trimOn && v.endMs > 0) v.endMs else thumb?.durationMs ?: 0
                        if (dur > 0) Text(formatTime(dur), fontSize = 12.sp, color = if (trimOn && v.endMs > 0) Palette.accent else Color(0xFFDDDDDD))
                    }
                }
            }
        }
    }
}

/**
 * TikTok-style feed: swipe up = next, down = previous, wraps around.
 * Each clip loops (with sound) until swiped away. Tap toggles pause.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoPager(videos: List<Video>, start: Int, player: Player, trim: Boolean, onClose: () -> Unit) {
    if (videos.isEmpty()) { onClose(); return }
    val n = videos.size
    val ids = remember(videos) { videos.map { it.uri.toString() } }
    var loops by remember { mutableIntStateOf(1) }
    var paused by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var scrub by remember { mutableStateOf<Float?>(null) } // 0..1 while dragging the playhead
    var fast by remember { mutableStateOf(false) } // long press held = 2×
    // False until the current clip's first frame is on screen; the thumbnail covers the player meanwhile.
    var rendered by remember { mutableStateOf(false) }
    // Clip the player has switched to. The video view attaches only after the switch, otherwise
    // the new surface first shows the previous clip's last frame.
    var playingId by remember { mutableStateOf(player.currentMediaItem?.mediaId) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) loops++
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                playingId = item?.mediaId
            }
            override fun onRenderedFirstFrame() { rendered = true }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player.playbackState == Player.STATE_READY) paused = !isPlaying // also reflects lock-screen controls
            }
        }
        player.addListener(listener)
        // Closing the feed stops the clip; locking the screen or switching apps does not (the service keeps playing).
        onDispose { player.removeListener(listener); player.pause(); player.repeatMode = Player.REPEAT_MODE_OFF }
    }
    LaunchedEffect(player) {
        while (true) {
            progress = if (player.duration > 0) player.currentPosition.toFloat() / player.duration else 0f
            delay(100)
        }
    }

    // A large virtual page count makes the feed wrap around in both directions.
    val pages = if (n > 1) n * 1000 else 1
    val pagerState = rememberPagerState(initialPage = if (n > 1) n * 500 + start else 0) { pages }
    LaunchedEffect(pagerState.settledPage) {
        val i = pagerState.settledPage % n
        // Videos play through the same background service as audiobooks, replacing its queue.
        if (player.currentMediaItem?.mediaId !in ids) {
            player.setMediaItems(videos.map {
                MediaItem.Builder().setMediaId(it.uri.toString()).setUri(it.uri)
                    // Skip a silent ending (TikTok outro); the file itself is untouched.
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                        .apply { if (trim && it.endMs > 0) setEndPositionMs(it.endMs) }.build())
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(it.name.substringBeforeLast('.')).setArtist("Videos").build())
                    .build()
            }, i, 0)
            player.prepare()
        } else if (player.currentMediaItemIndex != i) player.seekTo(i, 0)
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.setPlaybackSpeed(1f)
        loops = 1; paused = false
        player.play()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pagerState, beyondBoundsPageCount = 1, modifier = Modifier.fillMaxSize(),
            // A quarter-screen drag is enough to change clip, like TikTok (default needs half).
            flingBehavior = PagerDefaults.flingBehavior(pagerState, snapPositionalThreshold = 0.25f),
        ) { page ->
            val video = videos[page % n]
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { paused = !paused; if (paused) player.pause() else player.play() },
                    onLongPress = { fast = true; player.setPlaybackSpeed(2f); player.play() },
                    onPress = {
                        tryAwaitRelease() // also ends when the pager takes over the drag
                        if (fast) { fast = false; player.setPlaybackSpeed(1f); if (paused) player.pause() }
                    },
                )
            }) {
                val thumb = rememberThumb(video.uri)?.bitmap
                // Always underneath: whatever the video view hasn't drawn yet shows the clip's first frame, not black.
                thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                if (page == pagerState.settledPage && playingId == video.uri.toString()) {
                    AndroidView(
                        factory = { ctx ->
                            rendered = false
                            (LayoutInflater.from(ctx).inflate(R.layout.video_player, null) as PlayerView).apply {
                                // Box already has the clip's shape: fill it, unless the shape is unknown (no thumbnail).
                                if (thumb != null) resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                                keepScreenOn = true
                                isClickable = false
                                isLongClickable = false
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            }
                        },
                        update = { it.player = player },
                        onRelease = { it.player = null },
                        // Shape comes from the clip itself (thumbnail), not from the size the player reports:
                        // that arrives after the first frame, which would briefly draw a landscape clip full-screen.
                        modifier = thumb?.let { Modifier.align(Alignment.Center).aspectRatio(it.width.toFloat() / it.height) }
                            ?: Modifier.fillMaxSize(),
                    )
                    // No black flash between attaching and the first decoded frame.
                    if (!rendered) thumb?.let {
                        Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                    // Topmost sibling wins the hit test: keeps every touch away from the native view,
                    // so the pager (swipe) and the parent (tap = pause) always get them.
                    Box(Modifier.fillMaxSize().pointerInput(Unit) {})
                }
                // Bottom info
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 28.dp)) {
                    Text(if (page == pagerState.settledPage) "Playing ${ordinal(loops)} time · tap to pause" else "",
                        fontSize = 12.sp, color = Color(0xFFCFCFCF))
                    Spacer(Modifier.height(4.dp))
                    if (page == pagerState.settledPage) Scrubber(scrub ?: progress, player.duration,
                        onScrub = { f -> scrub = f; if (player.duration > 0) player.seekTo((f * player.duration).toLong()) },
                        onEnd = { scrub = null })
                    else ProgressLine(0f, Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        track = Color.White.copy(alpha = 0.3f), fill = Color.White)
                    if (n > 1) {
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Up, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Swipe up", fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (page == pagerState.settledPage && fast) {
                    Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp).clip(RoundedCornerShape(15.dp))
                        .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text("2× speed", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
                if (page == pagerState.settledPage && paused && !fast) {
                    Box(Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Play, "Paused", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
        // Top bar
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Close, "Close", tint = Color.White,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose).padding(8.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(15.dp)).background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Loop, null, tint = Palette.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Loop", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.accent)
            }
        }
    }
}

/** Progress bar you can tap or drag horizontally (tall invisible hit area, thicker while dragging). */
@Composable
private fun Scrubber(progress: Float, durationMs: Long, onScrub: (Float) -> Unit, onEnd: () -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    Column {
        Box(Modifier.fillMaxWidth().height(28.dp)
            .pointerInput(Unit) { detectTapGestures { onScrub((it.x / size.width).coerceIn(0f, 1f)); onEnd() } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; onScrub((it.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { dragging = false; onEnd() },
                    onDragCancel = { dragging = false; onEnd() },
                ) { change, _ -> change.consume(); onScrub((change.position.x / size.width).coerceIn(0f, 1f)) }
            }, contentAlignment = Alignment.CenterStart) {
            ProgressLine(progress, Modifier.fillMaxWidth(), height = if (dragging) 6 else 3,
                track = Color.White.copy(alpha = 0.3f), fill = Color.White)
            if (dragging) Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White))
            }
        }
        if (dragging && durationMs > 0) Text("${formatTime((progress * durationMs).toLong())} / ${formatTime(durationMs)}",
            fontSize = 12.sp, color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

private fun ordinal(n: Int) = n.toString() + when {
    n % 100 in 11..13 -> "th"
    n % 10 == 1 -> "st"
    n % 10 == 2 -> "nd"
    n % 10 == 3 -> "rd"
    else -> "th"
}
